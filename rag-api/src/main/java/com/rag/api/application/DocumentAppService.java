package com.rag.api.application;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.api.common.BizException;
import com.rag.api.common.ErrorCode;
import com.rag.api.common.TenantContext;
import com.rag.api.infrastructure.mq.IngestPublisher;
import com.rag.api.infrastructure.persistence.entity.ChunkEntity;
import com.rag.api.infrastructure.persistence.entity.DocumentEntity;
import com.rag.api.infrastructure.persistence.entity.KnowledgeBaseEntity;
import com.rag.api.infrastructure.persistence.entity.PipelineTaskEntity;
import com.rag.api.infrastructure.persistence.mapper.ChunkMapper;
import com.rag.api.infrastructure.persistence.mapper.DocumentMapper;
import com.rag.api.infrastructure.persistence.mapper.PipelineTaskMapper;
import com.rag.api.infrastructure.search.EsSearchClient;
import com.rag.api.infrastructure.search.MilvusClientWrapper;
import com.rag.api.infrastructure.storage.MinioStorage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Set;
import java.util.UUID;

/**
 * 文档上传编排：配额校验 → MinIO 落盘 → 建 task → 发 MQ（spec 4.1）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentAppService {

    private static final Set<String> ALLOWED_EXT = Set.of("txt", "md", "pdf", "docx", "xlsx", "html", "htm", "png", "jpg", "jpeg");

    private final DocumentMapper documentMapper;
    private final ChunkMapper chunkMapper;
    private final PipelineTaskMapper taskMapper;
    private final KnowledgeBaseService knowledgeBaseService;
    private final QuotaService quotaService;
    private final MinioStorage minio;
    private final EsSearchClient es;
    private final MilvusClientWrapper milvus;
    private final IngestPublisher publisher;
    private final ObjectMapper objectMapper;

    public DocumentEntity upload(long kbId, MultipartFile file) {
        TenantContext.Session s = TenantContext.require();
        if (s.userType() != 1) {
            throw BizException.forbidden("仅租户管理员可上传文档");
        }
        if (file == null || file.isEmpty()) {
            throw BizException.badRequest("上传文件为空");
        }
        KnowledgeBaseEntity kb = knowledgeBaseService.getOwned(kbId);
        String fileName = file.getOriginalFilename();
        String ext = extOf(fileName);
        if (!ALLOWED_EXT.contains(ext)) {
            throw BizException.badRequest("不支持的文件类型: " + ext + "（允许: txt/md/html/pdf/docx/xlsx/png/jpg/jpeg）");
        }
        quotaService.checkStorage(s.tenantId(), file.getSize());

        String objectKey = "%s/%d/%s/%s.%s".formatted(s.tenantId(), kbId,
                LocalDate.now().format(DateTimeFormatter.ISO_DATE), UUID.randomUUID(), ext);
        try (var in = file.getInputStream()) {
            minio.upload(objectKey, in, file.getSize(), file.getContentType());
        } catch (Exception e) {
            throw new BizException(ErrorCode.UPSTREAM, "文件读取失败: " + e.getMessage());
        }

        DocumentEntity doc = new DocumentEntity();
        doc.setKbId(kbId);
        doc.setTenantId(s.tenantId());
        doc.setFileName(fileName);
        doc.setObjectKey(objectKey);
        doc.setFileSize(file.getSize());
        doc.setMimeType(file.getContentType());
        doc.setStatus("PARSING");
        doc.setPageCount(0);
        documentMapper.insert(doc);

        PipelineTaskEntity task = new PipelineTaskEntity();
        task.setTenantId(s.tenantId());
        task.setKbId(kbId);
        task.setDocumentId(doc.getId());
        task.setType("PARSE");
        task.setStatus("PENDING");
        task.setRetryCount(0);
        taskMapper.insert(task);

        var msg = new IngestPublisher.IngestMessage(task.getId(), "PARSE", s.tenantId(), kbId, doc.getId(), objectKey);
        try {
            task.setPayload(objectMapper.writeValueAsString(msg));
            taskMapper.updateById(task);
        } catch (Exception ignored) {
        }
        publisher.publish(msg);
        log.info("文档已上传并投递解析任务 doc={} task={} file={}", doc.getId(), task.getId(), fileName);
        return doc;
    }

    public Page<DocumentEntity> list(long kbId, long page, long size) {
        knowledgeBaseService.getOwned(kbId);
        return documentMapper.selectPage(new Page<>(page, size),
                new QueryWrapper<DocumentEntity>().eq("kb_id", kbId).orderByDesc("id"));
    }

    public String downloadUrl(long documentId) {
        DocumentEntity doc = documentMapper.selectById(documentId);
        if (doc == null) {
            throw BizException.notFound("文档不存在");
        }
        knowledgeBaseService.getOwned(doc.getKbId());
        return minio.presignUrl(doc.getObjectKey());
    }

    /**
     * 删除文档（同步）：清理 ES/Milvus 索引与 MinIO 原文件，
     * 物理删除该文档全部切片、流水线任务及文档记录本身。
     */
    @Transactional
    public void delete(long documentId) {
        DocumentEntity doc = documentMapper.selectById(documentId);
        if (doc == null) {
            throw BizException.notFound("文档不存在");
        }
        KnowledgeBaseEntity kb = knowledgeBaseService.getOwned(doc.getKbId());

        es.deleteByDocument(kb.getEsIndex(), doc.getId());
        try {
            milvus.deleteByDocument(kb.getMilvusCollection(), doc.getId());
        } catch (Exception e) {
            log.warn("Milvus 向量清理失败 doc={} err={}", doc.getId(), e.getMessage());
        }
        minio.deleteObject(doc.getObjectKey());

        chunkMapper.delete(new QueryWrapper<ChunkEntity>().eq("document_id", doc.getId()));
        taskMapper.delete(new QueryWrapper<PipelineTaskEntity>().eq("document_id", doc.getId()));
        documentMapper.deleteById(doc.getId());
        log.info("文档已删除 doc={} kb={}", doc.getId(), kb.getId());
    }

    private String extOf(String fileName) {
        if (fileName == null || !fileName.contains(".")) {
            return "";
        }
        return fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase();
    }
}
