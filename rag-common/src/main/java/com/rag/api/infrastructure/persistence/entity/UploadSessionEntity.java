package com.rag.api.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 分片上传会话：大文件分片上传的状态与进度，支持断点续传。 */
@Data
@TableName("upload_session")
public class UploadSessionEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String tenantId;
    private Long userId;
    /** 会话用途：LIBRARY=文件库直接上传 / KB_DOCUMENT=知识库文档上传。 */
    private String biz;
    /** 业务关联知识库ID（biz=KB_DOCUMENT 时必填）。 */
    private Long kbId;
    private String fileName;
    private Long fileSize;
    private String contentType;
    /** 文件内容 SHA-256（hex，init 时前端上送，complete 时转写 library_file）。 */
    private String sha256;
    private Long chunkSize;
    private Integer totalChunks;
    private Integer uploadedChunks;
    /** 已完成分片 JSON：[{"partNumber":1,"etag":"..."}]。 */
    private String uploadedParts;
    private String objectKey;
    /** MinIO Multipart UploadId。 */
    private String uploadId;
    /** UPLOADING / COMPLETED / ABORTED。 */
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
