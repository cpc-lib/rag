package com.rag.api.application;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.rag.api.common.BizException;
import com.rag.api.infrastructure.mq.IngestPublisher;
import com.rag.api.infrastructure.persistence.entity.ChunkEntity;
import com.rag.api.infrastructure.persistence.entity.DocumentEntity;
import com.rag.api.infrastructure.persistence.entity.PipelineTaskEntity;
import com.rag.api.infrastructure.persistence.mapper.ChunkMapper;
import com.rag.api.infrastructure.persistence.mapper.DocumentMapper;
import com.rag.api.infrastructure.persistence.mapper.PipelineTaskMapper;
import com.rag.api.interfaces.dto.Dtos;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 切片管理：人工增删改后触发 REINDEX 任务（spec 3.2）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChunkAppService {

    private final ChunkMapper chunkMapper;
    private final DocumentMapper documentMapper;
    private final PipelineTaskMapper taskMapper;
    private final KnowledgeBaseService knowledgeBaseService;
    private final IngestPublisher publisher;

    public Page<ChunkEntity> page(long documentId, long page, long size) {
        assertDocOwned(documentId);
        return chunkMapper.selectPage(new Page<>(page, size),
                new QueryWrapper<ChunkEntity>()
                        .eq("document_id", documentId)
                        .ne("status", "DELETED")
                        .orderByAsc("seq"));
    }

    /** 查询切片详情（校验归属），供编辑前回显。 */
    public ChunkEntity get(long chunkId) {
        return getOwnedChunk(chunkId);
    }

    public ChunkEntity create(Dtos.ChunkCreateReq req) {
        DocumentEntity doc = assertDocOwned(req.documentId());
        requireEditable(doc);
        ChunkEntity chunk = new ChunkEntity();
        chunk.setKbId(doc.getKbId());
        chunk.setDocumentId(doc.getId());
        chunk.setTenantId(doc.getTenantId());
        chunk.setSeq(nextSeq(doc.getId()));
        chunk.setContent(req.content());
        chunk.setPage(req.page() == null ? 0 : req.page());
        chunk.setSectionTitle(req.sectionTitle() == null || req.sectionTitle().isBlank()
                ? null : req.sectionTitle());
        chunk.setSectionPath(chunk.getSectionTitle());
        chunk.setStatus("MANUAL");
        chunk.setChunkType("CHILD");
        chunk.setContentType("TEXT");
        chunkMapper.insert(chunk);
        enqueueReindex(doc);
        return chunk;
    }

    public ChunkEntity update(long chunkId, Dtos.ChunkUpdateReq req) {
        ChunkEntity chunk = getOwnedChunk(chunkId);
        requireChild(chunk);
        DocumentEntity doc = documentMapper.selectById(chunk.getDocumentId());
        requireEditable(doc);
        if (chunk.getParentChunkId() != null) {
            dissolveParent(chunk.getParentChunkId(), chunk.getDocumentId());
            chunk.setParentChunkId(null);
        }
        if (req.content() != null && !req.content().isBlank()) {
            chunk.setContent(req.content());
        }
        if (req.page() != null) {
            chunk.setPage(req.page());
        }
        if (req.sectionTitle() != null) {
            chunk.setSectionTitle(req.sectionTitle().isBlank() ? null : req.sectionTitle());
            chunk.setSectionPath(chunk.getSectionTitle());
        }
        chunk.setStatus("MANUAL");
        chunkMapper.updateById(chunk);
        enqueueReindex(doc);
        return chunk;
    }

    public void delete(long chunkId) {
        ChunkEntity chunk = getOwnedChunk(chunkId);
        requireChild(chunk);
        DocumentEntity doc = documentMapper.selectById(chunk.getDocumentId());
        requireEditable(doc);
        if (chunk.getParentChunkId() != null) {
            dissolveParent(chunk.getParentChunkId(), chunk.getDocumentId());
            chunk.setParentChunkId(null);
        }
        chunk.setStatus("DELETED");
        chunkMapper.updateById(chunk);
        enqueueReindex(doc);
    }

    /** 编辑或删除 child 时解散其 parent，防止旧 parent 内容再次进入检索上下文。 */
    private void dissolveParent(long parentId, long documentId) {
        ChunkEntity parent = chunkMapper.selectById(parentId);
        if (parent != null && parent.getDocumentId() == documentId && !"DELETED".equals(parent.getStatus())) {
            parent.setStatus("DELETED");
            chunkMapper.updateById(parent);
        }
        chunkMapper.update(null, new UpdateWrapper<ChunkEntity>()
                .eq("parent_chunk_id", parentId)
                .eq("document_id", documentId)
                .ne("status", "DELETED")
                .set("parent_chunk_id", null));
    }

    private void requireChild(ChunkEntity chunk) {
        if (!"CHILD".equals(chunk.getChunkType())) {
            throw BizException.badRequest("Parent 切片仅用于上下文，请编辑或删除其 Child 切片");
        }
    }

    private void requireEditable(DocumentEntity doc) {
        if (doc == null || !("READY".equals(doc.getStatus()) || "FAILED".equals(doc.getStatus()))) {
            throw BizException.badRequest("文档处理未结束，请稍后修改切片");
        }
    }

    private ChunkEntity getOwnedChunk(long chunkId) {
        ChunkEntity chunk = chunkMapper.selectById(chunkId);
        if (chunk == null || "DELETED".equals(chunk.getStatus())) {
            throw BizException.notFound("切片不存在");
        }
        knowledgeBaseService.getOwned(chunk.getKbId());
        return chunk;
    }

    private DocumentEntity assertDocOwned(long documentId) {
        DocumentEntity doc = documentMapper.selectById(documentId);
        if (doc == null) {
            throw BizException.notFound("文档不存在");
        }
        knowledgeBaseService.getOwned(doc.getKbId());
        return doc;
    }

    private int nextSeq(long documentId) {
        Integer max = chunkMapper.selectList(new QueryWrapper<ChunkEntity>()
                        .eq("document_id", documentId).orderByDesc("seq").last("limit 1"))
                .stream().findFirst().map(ChunkEntity::getSeq).orElse(0);
        return max == null ? 1 : max + 1;
    }

    private void enqueueReindex(DocumentEntity doc) {
        if (doc == null) {
            return;
        }
        doc.setStatus("EMBEDDING");
        doc.setProgress(0);
        documentMapper.updateById(doc);
        PipelineTaskEntity task = new PipelineTaskEntity();
        task.setTenantId(doc.getTenantId());
        task.setKbId(doc.getKbId());
        task.setDocumentId(doc.getId());
        task.setType("REINDEX");
        task.setStatus("PENDING");
        task.setRetryCount(0);
        taskMapper.insert(task);
        publisher.publish(new IngestPublisher.IngestMessage(
                task.getId(), "REINDEX", doc.getTenantId(), doc.getKbId(), doc.getId(), doc.getObjectKey()));
        log.info("REINDEX 任务已投递 doc={} task={}", doc.getId(), task.getId());
    }
}
