package com.rag.api.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.api.common.BizException;
import com.rag.api.common.TenantContext;
import com.rag.api.infrastructure.persistence.entity.UploadSessionEntity;
import com.rag.api.infrastructure.persistence.mapper.UploadSessionMapper;
import com.rag.api.infrastructure.storage.MinioMultipart;
import com.rag.api.interfaces.dto.Dtos;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.s3.model.CompletedPart;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 分片上传：前端分片 → 后端转发 MinIO 原生 Multipart → 合并；会话状态落库，支持断点续传。
 * 断点续传语义：前端以 sessionId 恢复会话，按 GET 会话返回的已传分片号跳过，只补传缺失分片。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ChunkUploadService {

    /** S3 限制：除最后一片外每片最小 5MB；分片数上限 10000。 */
    public static final long MIN_CHUNK = 5L * 1024 * 1024;
    public static final long DEFAULT_CHUNK = 8L * 1024 * 1024;
    public static final int MAX_CHUNKS = 10000;

    private static final String STATUS_UPLOADING = "UPLOADING";
    private static final String STATUS_COMPLETED = "COMPLETED";
    private static final String STATUS_ABORTED = "ABORTED";
    /** 会话用途：文件库直接上传。 */
    private static final String BIZ_LIBRARY = "LIBRARY";

    private final UploadSessionMapper sessionMapper;
    private final MinioMultipart multipart;
    private final FileLibraryService fileLibraryService;
    private final UserManageService userManageService;
    private final ObjectMapper objectMapper;

    /** 已传分片记录（持久化在 upload_session.uploaded_parts JSON）。 */
    private record Part(int partNumber, String etag) {
    }

    /** 初始化分片会话：创建 MinIO Multipart Upload，落库会话。 */
    public Dtos.UploadInitResp init(Dtos.UploadInitReq req) {
        userManageService.requireMenu("library");
        TenantContext.Session s = TenantContext.require();
        long chunkSize = DEFAULT_CHUNK;
        long total = (req.fileSize() + chunkSize - 1) / chunkSize;
        if (total > MAX_CHUNKS) {
            throw BizException.badRequest("文件过大，超出分片数上限");
        }
        int dot = req.fileName().lastIndexOf('.');
        String ext = dot >= 0 ? req.fileName().substring(dot) : "";
        String objectKey = s.tenantId() + "/library/direct/" + System.currentTimeMillis() + ext;
        String uploadId = multipart.createUpload(objectKey, req.contentType());

        UploadSessionEntity e = new UploadSessionEntity();
        e.setTenantId(s.tenantId());
        e.setUserId(s.userId());
        e.setBiz(BIZ_LIBRARY);
        e.setFileName(req.fileName());
        e.setFileSize(req.fileSize());
        e.setContentType(req.contentType());
        e.setChunkSize(chunkSize);
        e.setTotalChunks((int) total);
        e.setUploadedChunks(0);
        e.setUploadedParts("[]");
        e.setObjectKey(objectKey);
        e.setUploadId(uploadId);
        e.setStatus(STATUS_UPLOADING);
        sessionMapper.insert(e);
        log.info("分片会话创建 session={} file={} size={} chunks={}", e.getId(), req.fileName(), req.fileSize(), total);
        return new Dtos.UploadInitResp(e.getId(), chunkSize);
    }

    /** 上传一个分片：幂等（同 partNumber 重复传会覆盖 ETag），失败可安全重试。 */
    public Dtos.UploadPartResp uploadPart(long sessionId, int partNumber, byte[] body) {
        userManageService.requireMenu("library");
        UploadSessionEntity e = requireOwned(sessionId);
        if (!STATUS_UPLOADING.equals(e.getStatus())) {
            throw BizException.badRequest("会话已结束，无法继续上传");
        }
        if (partNumber < 1 || partNumber > e.getTotalChunks()) {
            throw BizException.badRequest("分片序号超出范围");
        }
        long expect = partNumber == e.getTotalChunks()
                ? e.getFileSize() - (long) (partNumber - 1) * e.getChunkSize()
                : e.getChunkSize();
        if (body.length != expect) {
            throw BizException.badRequest("分片大小不符：期望 " + expect + " 字节，实际 " + body.length);
        }
        String etag = multipart.uploadPart(e.getObjectKey(), e.getUploadId(), partNumber, body);

        List<Part> parts = readParts(e);
        parts.removeIf(p -> p.partNumber() == partNumber);
        parts.add(new Part(partNumber, etag));
        e.setUploadedParts(writeParts(parts));
        e.setUploadedChunks(parts.size());
        sessionMapper.updateById(e);
        return new Dtos.UploadPartResp(partNumber, etag);
    }

    /** 查询会话：断点续传时前端据此跳过已传分片。 */
    public Dtos.UploadSessionView session(long sessionId) {
        userManageService.requireMenu("library");
        UploadSessionEntity e = requireOwned(sessionId);
        return new Dtos.UploadSessionView(e.getId(), e.getStatus(), e.getChunkSize(), e.getTotalChunks(),
                readParts(e).stream().map(Part::partNumber).sorted().toList());
    }

    /** 合并分片：全部传完后调用，登记文件库条目并返回视图。 */
    public Dtos.LibraryFileView complete(long sessionId) {
        userManageService.requireMenu("library");
        UploadSessionEntity e = requireOwned(sessionId);
        if (!STATUS_UPLOADING.equals(e.getStatus())) {
            throw BizException.badRequest("会话已结束");
        }
        List<Part> parts = readParts(e);
        if (parts.size() != e.getTotalChunks()) {
            throw BizException.badRequest("分片未传完：" + parts.size() + "/" + e.getTotalChunks());
        }
        List<CompletedPart> completed = parts.stream()
                .sorted(Comparator.comparingInt(Part::partNumber))
                .map(p -> CompletedPart.builder().partNumber(p.partNumber()).eTag(p.etag()).build())
                .toList();
        multipart.complete(e.getObjectKey(), e.getUploadId(), completed);

        e.setStatus(STATUS_COMPLETED);
        sessionMapper.updateById(e);
        log.info("分片合并完成 session={} file={}", e.getId(), e.getFileName());
        return fileLibraryService.completeDirectUpload(e.getTenantId(), e.getUserId(), e.getFileName(),
                e.getObjectKey(), e.getContentType(), e.getFileSize());
    }

    /** 中止会话：清理 MinIO 已传分片。 */
    public void abort(long sessionId) {
        userManageService.requireMenu("library");
        UploadSessionEntity e = requireOwned(sessionId);
        if (STATUS_UPLOADING.equals(e.getStatus())) {
            try {
                multipart.abort(e.getObjectKey(), e.getUploadId());
            } catch (Exception ex) {
                log.warn("分片会话中止失败 session={} err={}", e.getId(), ex.getMessage());
            }
            e.setStatus(STATUS_ABORTED);
            sessionMapper.updateById(e);
        }
    }

    private UploadSessionEntity requireOwned(long sessionId) {
        UploadSessionEntity e = sessionMapper.selectById(sessionId);
        TenantContext.Session s = TenantContext.require();
        if (e == null || !s.tenantId().equals(e.getTenantId()) || s.userId() != e.getUserId()) {
            throw BizException.notFound("上传会话不存在");
        }
        return e;
    }

    private List<Part> readParts(UploadSessionEntity e) {
        try {
            String json = e.getUploadedParts();
            if (json == null || json.isBlank()) {
                return new ArrayList<>();
            }
            return new ArrayList<>(objectMapper.readValue(json, new TypeReference<List<Part>>() {
            }));
        } catch (Exception ex) {
            throw new BizException(com.rag.api.common.ErrorCode.INTERNAL, "解析分片进度失败: " + ex.getMessage());
        }
    }

    private String writeParts(List<Part> parts) {
        try {
            return objectMapper.writeValueAsString(parts);
        } catch (Exception ex) {
            throw new BizException(com.rag.api.common.ErrorCode.INTERNAL, "保存分片进度失败: " + ex.getMessage());
        }
    }
}
