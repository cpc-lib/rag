package com.rag.api.application;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.api.common.BizException;
import com.rag.api.common.ErrorCode;
import com.rag.api.common.TenantContext;
import com.rag.api.infrastructure.mq.IngestPublisher;
import com.rag.api.infrastructure.mq.Sha256Publisher;
import com.rag.api.infrastructure.persistence.entity.ChunkEntity;
import com.rag.api.infrastructure.persistence.entity.DocumentEntity;
import com.rag.api.infrastructure.persistence.entity.KnowledgeBaseEntity;
import com.rag.api.infrastructure.persistence.entity.PipelineTaskEntity;
import com.rag.api.infrastructure.persistence.mapper.ChunkMapper;
import com.rag.api.infrastructure.persistence.mapper.DocumentMapper;
import com.rag.api.infrastructure.persistence.mapper.PipelineTaskMapper;
import com.rag.api.infrastructure.search.EsSearchClient;
import com.rag.api.infrastructure.search.MilvusClientWrapper;
import com.rag.api.infrastructure.storage.ObjectStorage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.converter.WordToHtmlConverter;
import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.w3c.dom.Element;

import java.io.InputStream;
import java.io.StringWriter;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Set;
import java.util.UUID;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

/**
 * 文档上传编排：配额校验 → 对象存储落盘 → 落库为 UPLOADED（待处理）。
 * 处理流水线由用户手动触发：开始处理（startProcessing）/ 停止（stopProcessing）/ 断点续跑。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentAppService {

    private static final Set<String> ALLOWED_EXT = Set.of("txt", "md", "pdf", "doc", "docx", "xlsx", "html", "htm", "png", "jpg", "jpeg");

    private final DocumentMapper documentMapper;
    private final ChunkMapper chunkMapper;
    private final PipelineTaskMapper taskMapper;
    private final KnowledgeBaseService knowledgeBaseService;
    private final QuotaService quotaService;
    private final ModelService modelService;
    private final ObjectStorage minio;
    private final EsSearchClient es;
    private final MilvusClientWrapper milvus;
    private final IngestPublisher publisher;
    private final ObjectMapper objectMapper;
    private final FileLibraryService fileLibraryService;
    private final Sha256Publisher sha256Publisher;

    /**
     * 知识库文档上传前置校验：租户管理员权限 + 扩展名白名单 + 存储配额。
     * 返回 kb 归属校验后的实体（供 objectKey 生成）。
     */
    public KnowledgeBaseEntity checkKbUpload(long kbId, String fileName, long fileSize) {
        TenantContext.Session s = TenantContext.require();
        if (s.userType() != 1) {
            throw BizException.forbidden("仅租户管理员可上传文档");
        }
        KnowledgeBaseEntity kb = knowledgeBaseService.getOwned(kbId);
        String ext = extOf(fileName);
        if (!ALLOWED_EXT.contains(ext)) {
            throw BizException.badRequest("不支持的文件类型: " + ext + "（允许: txt/md/html/pdf/docx/xlsx/png/jpg/jpeg）");
        }
        checkDocumentSize(fileSize);
        quotaService.checkStorage(s.tenantId(), fileSize);
        return kb;
    }

    /** 秒传查询：同租户同知识库下相同 SHA-256 的文档（取最新一条），命中则无需重复上传。 */
    public DocumentEntity findKbDocBySha256(String tenantId, long kbId, String sha256) {
        return documentMapper.selectOneBySha256(tenantId, kbId, sha256);
    }

    /**
     * 分片上传完成后登记文档（对象已在 MinIO 合并完成）：创建 document 记录 + 归档到文件库。
     */
    public DocumentEntity completeKbChunkUpload(long kbId, String fileName, String objectKey,
                                                String contentType, long fileSize, String sha256) {
        TenantContext.Session s = TenantContext.require();
        KnowledgeBaseEntity kb = knowledgeBaseService.getOwned(kbId);
        DocumentEntity doc = new DocumentEntity();
        doc.setKbId(kb.getId());
        doc.setTenantId(s.tenantId());
        doc.setFileName(fileName);
        doc.setObjectKey(objectKey);
        doc.setFileSize(fileSize);
        doc.setSha256(sha256);
        doc.setMimeType(contentType);
        doc.setStatus("UPLOADED");
        doc.setProgress(0);
        doc.setPageCount(0);
        documentMapper.insert(doc);
        log.info("文档已分片上传（待处理） doc={} file={}", doc.getId(), fileName);
        try {
            fileLibraryService.archiveDocument(s.tenantId(), s.userId(), doc.getId(), fileName,
                    objectKey, contentType, fileSize, sha256);
        } catch (Exception ex) {
            log.warn("文档归档到文件库失败 doc={} err={}", doc.getId(), ex.getMessage());
        }
        return doc;
    }

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
        checkDocumentSize(file.getSize());
        quotaService.checkStorage(s.tenantId(), file.getSize());

        String objectKey = "%s/%d/%s/%s.%s".formatted(s.tenantId(), kbId,
                LocalDate.now().format(DateTimeFormatter.ISO_DATE), UUID.randomUUID(), ext);
        try (var in = file.getInputStream()) {
            minio.upload(objectKey, in, file.getSize(), file.getContentType());
        } catch (Exception e) {
            throw new BizException(ErrorCode.UPSTREAM, "文件读取失败: " + e.getMessage());
        }

        // 上传后不自动进入流水线，等待用户点击「开始处理」
        DocumentEntity doc = new DocumentEntity();
        doc.setKbId(kbId);
        doc.setTenantId(s.tenantId());
        doc.setFileName(fileName);
        doc.setObjectKey(objectKey);
        doc.setFileSize(file.getSize());
        doc.setMimeType(file.getContentType());
        doc.setStatus("UPLOADED");
        doc.setProgress(0);
        doc.setPageCount(0);
        documentMapper.insert(doc);
        log.info("文档已上传（待处理） doc={} file={}", doc.getId(), fileName);
        // 异步计算 SHA-256 指纹（大文件不阻塞上传响应），Worker 回写后支持秒传
        sha256Publisher.publish(new Sha256Publisher.Sha256Message("DOCUMENT", doc.getId(), objectKey));
        // 文件库登记：与文档共用同一 MinIO 对象，失败不阻断上传；sha256 由 MQ 异步回填
        try {
            fileLibraryService.archiveDocument(s.tenantId(), s.userId(), doc.getId(), fileName,
                    objectKey, file.getContentType(), file.getSize(), null);
        } catch (Exception ex) {
            log.warn("文档归档到文件库失败 doc={} err={}", doc.getId(), ex.getMessage());
        }
        return doc;
    }

    /**
     * 开始/继续处理：UPLOADED 从头解析；STOPPED/FAILED 且已有切片时从向量化断点续跑。
     * 前置校验向量模型：未启用时快速失败，不投递必然失败的任务。
     */
    public DocumentEntity startProcessing(long documentId) {
        TenantContext.Session s = TenantContext.require();
        if (s.userType() != 1) {
            throw BizException.forbidden("仅租户管理员可处理文档");
        }
        DocumentEntity doc = documentMapper.selectById(documentId);
        if (doc == null) {
            throw BizException.notFound("文档不存在");
        }
        knowledgeBaseService.getOwned(doc.getKbId());
        modelService.requireEnabled(doc.getTenantId(), ModelService.EMBEDDING);

        // 断点续跑：切片已落库说明解析/切片已完成，直接从向量化继续
        boolean resume = chunkMapper.countActiveByDocumentId(doc.getId()) > 0;
        String nextStatus = resume ? "EMBEDDING" : "PARSING";
        int nextProgress = resume ? 45 : 0;
        int updated = documentMapper.startProcessing(doc.getId(), nextStatus, nextProgress);
        if (updated != 1) {
            throw BizException.badRequest("文档正在处理中，请勿重复开始");
        }

        PipelineTaskEntity task = new PipelineTaskEntity();
        task.setTenantId(doc.getTenantId());
        task.setKbId(doc.getKbId());
        task.setDocumentId(doc.getId());
        task.setType("PARSE");
        task.setStatus("PENDING");
        task.setRetryCount(0);
        taskMapper.insert(task);

        var msg = new IngestPublisher.IngestMessage(task.getId(), "PARSE", doc.getTenantId(),
                doc.getKbId(), doc.getId(), doc.getObjectKey(), resume);
        try {
            task.setPayload(objectMapper.writeValueAsString(msg));
            taskMapper.updateById(task);
        } catch (Exception ignored) {
        }
        publisher.publish(msg);
        doc.setStatus(nextStatus);
        doc.setProgress(nextProgress);
        doc.setErrorMsg(null);
        log.info("文档处理任务已投递 doc={} task={} resume={}", doc.getId(), task.getId(), resume);
        return doc;
    }

    /**
     * 停止处理：置停止标记，Worker 在阶段边界/向量化批次间检测到后置为 STOPPED；
     * 若任务仍在排队（PENDING）未被执行，直接取消并立即置为 STOPPED。
     */
    public DocumentEntity stopProcessing(long documentId) {
        TenantContext.Session s = TenantContext.require();
        if (s.userType() != 1) {
            throw BizException.forbidden("仅租户管理员可停止处理");
        }
        DocumentEntity doc = documentMapper.selectById(documentId);
        if (doc == null) {
            throw BizException.notFound("文档不存在");
        }
        knowledgeBaseService.getOwned(doc.getKbId());
        if (!Set.of("PARSING", "CHUNKING", "EMBEDDING", "INDEXING").contains(doc.getStatus())) {
            throw BizException.badRequest("文档当前不在处理中");
        }
        documentMapper.updateStopRequestedById(doc.getId(), 1);
        int cancelled = taskMapper.cancelPendingByDocumentId(doc.getId());
        if (cancelled > 0) {
            documentMapper.updateStatusById(doc.getId(), "STOPPED");
            doc.setStatus("STOPPED");
        }
        log.info("文档停止请求已受理 doc={} 排队任务直接取消={}", doc.getId(), cancelled > 0);
        return doc;
    }

    public Page<DocumentEntity> list(long kbId, long page, long size) {
        var kb = knowledgeBaseService.getOwned(kbId);
        return documentMapper.selectPageByKbId(new Page<>(page, size), kbId, kb.getTenantId());
    }

    /** 使用原文件和知识库当前切片策略重新解析；人工切片由 Worker 保留。 */
    public DocumentEntity reparse(long documentId) {
        TenantContext.Session s = TenantContext.require();
        if (s.userType() != 1) {
            throw BizException.forbidden("仅租户管理员可重新解析文档");
        }
        DocumentEntity doc = documentMapper.selectById(documentId);
        if (doc == null) {
            throw BizException.notFound("文档不存在");
        }
        knowledgeBaseService.getOwned(doc.getKbId());
        if (!("READY".equals(doc.getStatus()) || "FAILED".equals(doc.getStatus()))) {
            throw BizException.badRequest("文档正在处理，请完成后再重新解析");
        }
        // 与上传一致：未启用向量模型时快速失败，不投递必然失败的任务
        modelService.requireEnabled(doc.getTenantId(), ModelService.EMBEDDING);

        int updated = documentMapper.reparseById(doc.getId());
        if (updated != 1) {
            throw BizException.badRequest("文档正在处理，请完成后再重新解析");
        }
        doc.setStatus("PARSING");
        doc.setProgress(0);
        doc.setErrorMsg(null);

        PipelineTaskEntity task = new PipelineTaskEntity();
        task.setTenantId(doc.getTenantId());
        task.setKbId(doc.getKbId());
        task.setDocumentId(doc.getId());
        task.setType("PARSE");
        task.setStatus("PENDING");
        task.setRetryCount(0);
        taskMapper.insert(task);

        var msg = new IngestPublisher.IngestMessage(task.getId(), "PARSE", doc.getTenantId(),
                doc.getKbId(), doc.getId(), doc.getObjectKey(), false);
        try {
            task.setPayload(objectMapper.writeValueAsString(msg));
            taskMapper.updateById(task);
        } catch (Exception ignored) {
        }
        publisher.publish(msg);
        log.info("文档重新解析任务已投递 doc={} task={}", doc.getId(), task.getId());
        return doc;
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
     * 在线预览原文件：返回原始字节流与真实 Content-Type，不做任何转换，保留文件原格式。
     */
    public Preview preview(long documentId) {
        DocumentEntity doc = documentMapper.selectById(documentId);
        if (doc == null) {
            throw BizException.notFound("文档不存在");
        }
        knowledgeBaseService.getOwned(doc.getKbId());
        String contentType = MediaTypeFactory.getMediaType(doc.getFileName())
                .map(MediaType::toString)
                .orElse(MediaType.APPLICATION_OCTET_STREAM_VALUE);
        return new Preview(doc.getFileName(), contentType, minio.download(doc.getObjectKey()));
    }

    public record Preview(String fileName, String contentType, InputStream in) {
    }

    /**
     * 老版 .doc（Word 97-2003 二进制）浏览器无法渲染，服务端用 HWPF 转成 HTML 供前端 iframe 展示。
     */
    public String previewHtml(long documentId) {
        DocumentEntity doc = documentMapper.selectById(documentId);
        if (doc == null) {
            throw BizException.notFound("文档不存在");
        }
        knowledgeBaseService.getOwned(doc.getKbId());
        if (doc.getFileName() == null || !doc.getFileName().toLowerCase().endsWith(".doc")) {
            throw BizException.badRequest("仅 .doc 文件需要转换为 HTML 预览");
        }
        try (InputStream in = minio.download(doc.getObjectKey())) {
            HWPFDocument hwpf = new HWPFDocument(in);
            WordToHtmlConverter converter = new WordToHtmlConverter(
                    DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument());
            // 图片以 data URI 内嵌，避免落盘；浏览器不支持的 EMF/WMF 会显示为占位
            converter.setPicturesManager((content, pictureType, suggestedName, widthInches, heightInches) ->
                    "data:" + pictureType.getMime() + ";base64,"
                            + java.util.Base64.getEncoder().encodeToString(content));
            converter.processDocument(hwpf);
            org.w3c.dom.Document html = converter.getDocument();
            fixInvisibleWhiteText(html);
            // 补一层阅读样式：A4 纸张观感（居中、页边距、阴影），表格铺满版心
            org.w3c.dom.NodeList heads = html.getElementsByTagName("head");
            if (heads.getLength() > 0) {
                Element style = html.createElement("style");
                style.setTextContent("html{background:#f2f3f5;}"
                        + "body{font-family:'Microsoft YaHei','PingFang SC',Arial,sans-serif;font-size:15px;"
                        + "line-height:1.8;color:#1f2937;background:#fff;box-sizing:border-box;"
                        + "margin:24px auto !important;max-width:8.5in;min-height:calc(100vh - 48px);"
                        + "padding:0.75in 0.65in !important;box-shadow:0 1px 4px rgba(0,0,0,.08);}"
                        + "table{width:100% !important;table-layout:auto !important;margin:8px 0 16px;}"
                        + "td,th{padding:6px 10px;vertical-align:middle;word-break:break-word;}"
                        + "td p,th p{margin:0;}img{max-width:100%;}");
                heads.item(0).appendChild(style);
            }
            StringWriter writer = new StringWriter();
            TransformerFactory.newInstance().newTransformer()
                    .transform(new DOMSource(html), new StreamResult(writer));
            return writer.toString();
        } catch (Exception e) {
            log.warn("doc 转 HTML 失败 documentId={} fileName={}", documentId, doc.getFileName(), e);
            throw new BizException(ErrorCode.INTERNAL, "文档转换失败，请下载后查看");
        }
    }

    /**
     * HWPF 转 HTML 不输出单元格底纹：整篇没有背景声明时，白字会落在白底上不可见，
     * 统一改成深蓝保证可读（有底纹声明时保持原色）。
     */
    private void fixInvisibleWhiteText(org.w3c.dom.Document html) {
        org.w3c.dom.NodeList styles = html.getElementsByTagName("style");
        StringBuilder css = new StringBuilder();
        for (int i = 0; i < styles.getLength(); i++) {
            css.append(styles.item(i).getTextContent());
        }
        if (css.toString().contains("background")) {
            return;
        }
        for (int i = 0; i < styles.getLength(); i++) {
            org.w3c.dom.Node node = styles.item(i);
            node.setTextContent(node.getTextContent()
                    .replaceAll("(?i)color:\\s*(white|#ffffff\\b|#fff\\b)", "color:#1f4e79"));
        }
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
        // 清理该文档派生图片（PDF 内嵌图片切片）
        minio.deletePrefix(doc.getTenantId() + "/" + doc.getKbId() + "/derived/" + doc.getId() + "/");

        chunkMapper.logicDeleteByDocumentId(doc.getId());
        taskMapper.logicDeleteByDocumentId(doc.getId());
        documentMapper.logicDeleteById(doc.getId());
        fileLibraryService.removeByDocument(doc.getId());
        log.info("文档已删除 doc={} kb={}", doc.getId(), kb.getId());
    }

    /** 文档类文件大小上限：PDF/Office 解析需整棵对象树进内存，500MB 级易触发 Worker OOM。 */
    private static final long MAX_DOCUMENT_BYTES = 100L * 1024 * 1024;

    private void checkDocumentSize(long fileSize) {
        if (fileSize > MAX_DOCUMENT_BYTES) {
            throw BizException.badRequest("文档类文件最大 100MB（当前 %dMB），请拆分后再上传"
                    .formatted(fileSize / 1024 / 1024));
        }
    }

    private String extOf(String fileName) {
        if (fileName == null || !fileName.contains(".")) {
            return "";
        }
        return fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase();
    }
}
