package com.rag.api.infrastructure.storage;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompletedMultipartUpload;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.UploadPartRequest;

import java.util.List;

/**
 * S3 协议分片上传：createMultipartUpload / uploadPart / complete / abort。
 * 复用 StorageConfig 注册的 S3Client（同一套 endpoint/凭证/path-style 配置）。
 */
@Component
@RequiredArgsConstructor
public class MultipartStorage {

    private final S3Client s3;

    @Value("${rag.storage.bucket}")
    private String bucket;

    /** 发起分片上传，返回 uploadId。 */
    public String createUpload(String objectKey, String contentType) {
        return s3.createMultipartUpload(CreateMultipartUploadRequest.builder()
                        .bucket(bucket).key(objectKey)
                        .contentType(contentType == null ? "application/octet-stream" : contentType)
                        .build())
                .uploadId();
    }

    /** 上传一个分片，返回 ETag（合并时需要回传）。 */
    public String uploadPart(String objectKey, String uploadId, int partNumber, byte[] body) {
        return s3.uploadPart(UploadPartRequest.builder()
                        .bucket(bucket).key(objectKey).uploadId(uploadId)
                        .partNumber(partNumber).contentLength((long) body.length)
                        .build(),
                RequestBody.fromBytes(body)).eTag();
    }

    /** 合并分片为最终对象；parts 需按 partNumber 升序。 */
    public void complete(String objectKey, String uploadId, List<CompletedPart> parts) {
        s3.completeMultipartUpload(CompleteMultipartUploadRequest.builder()
                .bucket(bucket).key(objectKey).uploadId(uploadId)
                .multipartUpload(CompletedMultipartUpload.builder().parts(parts).build())
                .build());
    }

    /** 中止分片上传，清理已传分片。 */
    public void abort(String objectKey, String uploadId) {
        s3.abortMultipartUpload(AbortMultipartUploadRequest.builder()
                .bucket(bucket).key(objectKey).uploadId(uploadId).build());
    }
}
