package com.rag.worker.pipeline;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.rag.worker.infrastructure.llm.EmbeddingClient;
import com.rag.worker.infrastructure.persistence.entity.ChunkEntity;
import com.rag.worker.infrastructure.persistence.entity.DocumentEntity;
import com.rag.worker.infrastructure.persistence.entity.KnowledgeBaseEntity;
import com.rag.worker.infrastructure.persistence.entity.ModelEntity;
import com.rag.worker.infrastructure.persistence.entity.PipelineTaskEntity;
import com.rag.worker.infrastructure.persistence.mapper.ChunkMapper;
import com.rag.worker.infrastructure.persistence.mapper.DocumentMapper;
import com.rag.worker.infrastructure.persistence.mapper.KnowledgeBaseMapper;
import com.rag.worker.infrastructure.persistence.mapper.ModelMapper;
import com.rag.worker.infrastructure.search.EsIndexer;
import com.rag.worker.infrastructure.search.MilvusIndexer;
import com.rag.worker.mq.RetryPublisher;
import com.rag.worker.pipeline.chunk.ChunkContext;
import com.rag.worker.pipeline.chunk.ChunkParams;
import com.rag.worker.pipeline.chunk.ChunkPlan;
import com.rag.worker.pipeline.chunk.ChunkStrategy;
import com.rag.worker.pipeline.chunk.ChunkStrategyRouter;
import com.rag.worker.pipeline.chunk.PlannedChild;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 流水线状态机（设计文档 §7）：
 * PARSE: PARSING→CHUNKING→EMBEDDING→INDEXING→READY
 * REINDEX: 保留既有切片（含人工），重新向量化并重建索引。
 * CHUNKING 阶段由 ChunkStrategyRouter 选择具体切片策略（指南 §42）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PipelineProcessor {

    private static final List<String> DEFAULT_SEPARATORS =
            List.of("\n\n", "\n", "。", "？", "！", "；", "，");

    private static final String VISION = "VISION";
    private static final String EMBEDDING = "EMBEDDING";

    private final ParseService parseService;
    private final ChunkStrategyRouter strategyRouter;
    private final EmbeddingClient embeddingClient;
    private final MilvusIndexer milvusIndexer;
    private final EsIndexer esIndexer;
    private final DocumentMapper documentMapper;
    private final ChunkMapper chunkMapper;
    private final KnowledgeBaseMapper kbMapper;
    private final ModelMapper modelMapper;
    private final ObjectMapper objectMapper;

    public void process(RetryPublisher.IngestMessage msg, PipelineTaskEntity task) {
        DocumentEntity doc = documentMapper.selectById(msg.documentId());
        if (doc == null) {
            throw new IllegalStateException("文档不存在: " + msg.documentId());
        }
        KnowledgeBaseEntity kb = kbMapper.selectById(msg.kbId());
        if (kb == null) {
            throw new IllegalStateException("知识库不存在: " + msg.kbId());
        }
        ModelEntity vision = findEnabled(msg.tenantId(), VISION);
        ModelEntity embedding = findEnabled(msg.tenantId(), EMBEDDING);
        if (embedding == null) {
            throw new IllegalStateException("租户未启用向量(EMBEDDING)模型，无法处理文档");
        }
        if ("PARSE".equalsIgnoreCase(msg.type())) {
            parseFlow(doc, kb, vision, embedding);
        } else {
            reindexFlow(doc, kb, embedding);
        }
        task.setStatus("SUCCESS");
    }

    private ModelEntity findEnabled(String tenantId, String type) {
        return modelMapper.selectOne(new QueryWrapper<ModelEntity>()
                .eq("tenant_id", tenantId).eq("type", type).eq("enabled", 1));
    }

    private void parseFlow(DocumentEntity doc, KnowledgeBaseEntity kb,
                           ModelEntity vision, ModelEntity embedding) {
        setStage(doc, "PARSING", 5);
        ParseService.ParseOutcome outcome = parseService.parse(doc, vision);
        doc.setPageCount(outcome.pages().size());
        doc.setWarning(outcome.warnings().isEmpty() ? null : String.join("；", outcome.warnings()));
        setStage(doc, "PARSING", 20);

        setStage(doc, "CHUNKING", 25);
        ChunkContext chunkCtx = new ChunkContext(doc.getTenantId(), doc.getId(), doc.getFileName(),
                doc.getObjectKey(), outcome.pages(), chunkParams(kb));
        ChunkStrategy strategy = strategyRouter.route(kb.getChunkStrategy(), doc.getFileName());
        List<ChunkPlan> plans = strategy.plan(chunkCtx);
        if (plans.stream().noneMatch(p -> !p.children().isEmpty())) {
            throw new IllegalStateException("文档未提取到可检索内容，请检查文件或 OCR/Vision 配置");
        }
        persistPlans(doc, plans);
        setStage(doc, "CHUNKING", 40);

        indexAll(doc, kb, embedding, 45, 78, 85);

        setStage(doc, "READY", 100);
        int children = plans.stream().mapToInt(p -> p.children().size()).sum();
        int parents = (int) plans.stream().filter(ChunkPlan::parentChild).count();
        log.info("文档解析完成 doc={} strategy={} pages={} parents={} children={} warnings={}",
                doc.getId(), strategy.mode(), outcome.pages().size(), parents, children,
                outcome.warnings());
    }

    private void reindexFlow(DocumentEntity doc, KnowledgeBaseEntity kb, ModelEntity embedding) {
        indexAll(doc, kb, embedding, 10, 70, 80);
        setStage(doc, "READY", 100);
        log.info("文档重建索引完成 doc={}", doc.getId());
    }

    /**
     * 全量重嵌 + 双写索引（先删旧后写新）。
     *
     * @param embStart 向量化阶段起始进度
     * @param embEnd   向量化阶段结束进度
     * @param idxStart 索引阶段起始进度
     */
    private void indexAll(DocumentEntity doc, KnowledgeBaseEntity kb, ModelEntity embedding,
                          int embStart, int embEnd, int idxStart) {
        if (embedding.getModel() == null) {
            throw new IllegalStateException("启用的向量(EMBEDDING)模型缺少模型名称");
        }
        List<ChunkEntity> chunks = chunkMapper.selectList(new QueryWrapper<ChunkEntity>()
                .eq("document_id", doc.getId())
                .eq("chunk_type", "CHILD")
                .ne("status", "DELETED")
                .orderByAsc("seq"));
        if (chunks.isEmpty()) {
            // 切片被清空（如一键清空）：清理该文档在 ES/Milvus 的旧索引
            log.warn("文档无有效切片，清理旧索引 doc={}", doc.getId());
            esIndexer.ensureIndex(kb.getEsIndex());
            esIndexer.deleteByDocument(kb.getEsIndex(), doc.getId());
            try {
                milvusIndexer.deleteByDocument(kb.getMilvusCollection(), doc.getId());
            } catch (Exception e) {
                log.warn("Milvus 旧向量清理失败（集合可能不存在）doc={} err={}",
                        doc.getId(), e.getMessage());
            }
            return;
        }
        setStage(doc, "EMBEDDING", embStart);
        List<String> contents = chunks.stream().map(c -> embeddingText(doc, c)).toList();
        int totalBatches = (contents.size() + 9) / 10;
        List<float[]> vectors = embeddingClient.embed(
                embedding.getBaseUrl(), embedding.getApiKey(), embedding.getModel(), contents,
                done -> setStage(doc, "EMBEDDING",
                        embStart + (int) Math.round((double) (embEnd - embStart) * done / totalBatches)));
        setStage(doc, "EMBEDDING", embEnd);

        setStage(doc, "INDEXING", idxStart);
        int dim = embedding.getEmbeddingDim() == null ? vectors.get(0).length : embedding.getEmbeddingDim();
        milvusIndexer.ensureCollection(kb.getMilvusCollection(), dim);
        milvusIndexer.deleteByDocument(kb.getMilvusCollection(), doc.getId());
        List<long[]> ids = chunks.stream().map(c -> new long[]{c.getId(), doc.getId()}).toList();
        milvusIndexer.insert(kb.getMilvusCollection(), ids, vectors);
        setStage(doc, "INDEXING", idxStart + (100 - idxStart) / 2);

        esIndexer.ensureIndex(kb.getEsIndex());
        esIndexer.deleteByDocument(kb.getEsIndex(), doc.getId());
        esIndexer.indexDocs(kb.getEsIndex(), chunks.stream()
                .map(c -> new EsIndexer.EsDoc(c.getId(), doc.getId(), kb.getId(),
                        doc.getFileName(), c.getPage() == null ? 0 : c.getPage(),
                        embeddingText(doc, c)))
                .toList());
    }

    /**
     * 持久化策略输出：删除旧 AUTO 行（含旧 parent），保留 MANUAL；
     * parentChild 计划先插 PARENT 再插关联 CHILDREN；独立计划直接插 CHILD。
     */
    private void persistPlans(DocumentEntity doc, List<ChunkPlan> plans) {
        chunkMapper.delete(new QueryWrapper<ChunkEntity>()
                .eq("document_id", doc.getId())
                .eq("status", "AUTO"));
        Integer maxSeq = chunkMapper.selectList(new QueryWrapper<ChunkEntity>()
                        .eq("document_id", doc.getId()).orderByDesc("seq").last("limit 1"))
                .stream().findFirst().map(ChunkEntity::getSeq).orElse(0);
        int seq = maxSeq == null ? 1 : maxSeq + 1;

        for (ChunkPlan plan : plans) {
            Long parentId = null;
            if (plan.parentChild()) {
                ChunkEntity parent = newChunk(doc, seq++, 0);
                parent.setChunkType("PARENT");
                parent.setContentType("TEXT");
                parent.setContent(plan.parentContent());
                parent.setSectionTitle(plan.sectionTitle());
                parent.setSectionPath(plan.sectionPath());
                parent.setContentHash(plan.contentHash());
                chunkMapper.insert(parent);
                parentId = parent.getId();
            }
            for (PlannedChild child : plan.children()) {
                if (child.content() == null || child.content().isBlank()) {
                    continue;
                }
                ChunkEntity c = newChunk(doc, seq++, child.page());
                c.setChunkType("CHILD");
                c.setParentChunkId(parentId);
                c.setContent(child.content());
                c.setContentType(contentType(doc.getFileName(), child.content()));
                c.setSectionTitle(plan.sectionTitle());
                c.setSectionPath(plan.sectionPath());
                chunkMapper.insert(c);
            }
        }
    }

    private ChunkParams chunkParams(KnowledgeBaseEntity kb) {
        return new ChunkParams(
                kb.getParentChunkSize() == null ? 2000 : kb.getParentChunkSize(),
                kb.getChildChunkSize() == null ? 500 : kb.getChildChunkSize(),
                kb.getChildOverlap() == null ? 80 : kb.getChildOverlap(),
                parseSeparators(kb.getSeparators()));
    }

    private List<String> parseSeparators(String json) {
        try {
            if (json != null && !json.isBlank()) {
                List<String> list = objectMapper.readValue(json, new TypeReference<List<String>>() {
                });
                if (list != null && !list.isEmpty()) {
                    return list;
                }
            }
        } catch (Exception ignored) {
        }
        return DEFAULT_SEPARATORS;
    }

    private ChunkEntity newChunk(DocumentEntity doc, int seq, int page) {
        ChunkEntity c = new ChunkEntity();
        c.setKbId(doc.getKbId());
        c.setDocumentId(doc.getId());
        c.setTenantId(doc.getTenantId());
        c.setSeq(seq);
        c.setPage(page);
        c.setStatus("AUTO");
        return c;
    }

    private String contentType(String fileName, String content) {
        String lower = fileName == null ? "" : fileName.toLowerCase(java.util.Locale.ROOT);
        if (lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return "IMAGE";
        }
        String text = content.stripLeading();
        if (text.startsWith("|") && text.lines().skip(1).findFirst().orElse("")
                .matches("^\\|\\s*:?-{2,}.*")) {
            return "TABLE";
        }
        if (text.startsWith("```") || text.startsWith("~~~")) {
            return "CODE";
        }
        return "TEXT";
    }

    /** Embedding 内容增强（指南 §9/28）：文档标题 + 章节路径 + 正文。 */
    private String embeddingText(DocumentEntity doc, ChunkEntity c) {
        StringBuilder sb = new StringBuilder("文档：").append(doc.getFileName()).append('\n');
        if (c.getSectionPath() != null && !c.getSectionPath().isBlank()) {
            sb.append("章节：").append(c.getSectionPath()).append('\n');
        }
        sb.append("类型：").append(c.getContentType() == null ? "TEXT" : c.getContentType())
                .append("\n\n");
        return sb.append(c.getContent()).toString();
    }

    private void setStage(DocumentEntity doc, String status, int progress) {
        doc.setStatus(status);
        doc.setProgress(progress);
        documentMapper.updateById(doc);
    }
}
