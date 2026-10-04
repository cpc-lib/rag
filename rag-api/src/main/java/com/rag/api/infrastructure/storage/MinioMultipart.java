package com.rag.api.infrastructure.storage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompletedMultipartUpload;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.UploadPartRequest;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;

import java.net.URI;
import java.util.List;

/**
 * MinIO 原生 Multipart（S3 兼容接口）：createMultipartUpload / uploadPart / complete / abort。
 * minio-java 未暴露分片 API，这里用 AWS SDK v2 走同一套 endpoint/凭证；path-style 访问 MinIO。
 */
@Component
public class MinioMultipart {

    private final S3Client s3;
    private final String bucket;

    public MinioMultipart(@Value("${rag.minio.endpoint}") String endpoint,
                          @Value("${rag.minio.access-key}") String accessKey,
                          @Value("${rag.minio.secret-key}") String secretKey,
                          @Value("${rag.minio.bucket}") String bucket) {
        this.bucket = bucket;
        this.s3 = S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKey, secretKey)))
                .region(Region.US_EAST_1)
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .httpClient(UrlConnectionHttpClient.builder().build())
                .build();
    }

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

    /** 中止分片上传，清理 MinIO 侧已传分片。 */
    public void abort(String objectKey, String uploadId) {
        s3.abortMultipartUpload(AbortMultipartUploadRequest.builder()
                .bucket(bucket).key(objectKey).uploadId(uploadId).build());
    }
}
