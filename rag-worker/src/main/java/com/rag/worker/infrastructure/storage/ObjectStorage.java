package com.rag.worker.infrastructure.storage;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.Delete;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Worker 侧对象存储（S3 协议，RustFS / MinIO 通用）。
 * Worker 非 Web 工程无独立配置类，客户端在此直接初始化并显式设置大文件传输超时。
 */
@Slf4j
@Component
public class ObjectStorage {

    private final S3Client s3Client;
    private final S3Presigner s3Presigner;
    private final String bucket;

    public ObjectStorage(@Value("${rag.storage.endpoint}") String endpoint,
                         @Value("${rag.storage.access-key}") String accessKey,
                         @Value("${rag.storage.secret-key}") String secretKey,
                         @Value("${rag.storage.region}") String region,
                         @Value("${rag.storage.bucket}") String bucket) {
        this.bucket = bucket;
        StaticCredentialsProvider credentials = StaticCredentialsProvider.create(
                AwsBasicCredentials.create(accessKey, secretKey));
        S3Configuration serviceConfig = S3Configuration.builder().pathStyleAccessEnabled(true).build();
        // 显式超时：默认客户端无读超时，网络中断会无限挂死（大文件下载场景必须兜底）
        this.s3Client = S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                .credentialsProvider(credentials)
                .region(Region.of(region))
                .serviceConfiguration(serviceConfig)
                .httpClient(UrlConnectionHttpClient.builder()
                        .connectionTimeout(Duration.ofSeconds(10))
                        .socketTimeout(Duration.ofMinutes(5))
                        .build())
                .build();
        this.s3Presigner = S3Presigner.builder()
                .endpointOverride(URI.create(endpoint))
                .credentialsProvider(credentials)
                .region(Region.of(region))
                .serviceConfiguration(serviceConfig)
                .build();
    }

    /** 生成预签名 GET URL（供 ffprobe/ffmpeg 直读对象存储，免下载落盘）；有效期 2 小时（大文件转码足够）。 */
    public String presignUrl(String objectKey) {
        try {
            return s3Presigner.presignGetObject(GetObjectPresignRequest.builder()
                            .signatureDuration(Duration.ofHours(2))
                            .getObjectRequest(g -> g.bucket(bucket).key(objectKey))
                            .build())
                    .url().toString();
        } catch (Exception e) {
            log.warn("对象存储预签名失败: {}", objectKey, e);
            return null;
        }
    }

    public InputStream download(String objectKey) {
        try {
            return s3Client.getObject(GetObjectRequest.builder().bucket(bucket).key(objectKey).build());
        } catch (Exception e) {
            throw new IllegalStateException("对象存储下载失败: " + objectKey + " - " + e.getMessage(), e);
        }
    }

    /** 范围下载（并行分片下载用）：[offset, offset+length)。 */
    public InputStream downloadRange(String objectKey, long offset, long length) {
        try {
            return s3Client.getObject(GetObjectRequest.builder()
                    .bucket(bucket).key(objectKey)
                    .range("bytes=" + offset + "-" + (offset + length - 1))
                    .build());
        } catch (Exception e) {
            throw new IllegalStateException("对象存储范围下载失败: " + objectKey + " - " + e.getMessage(), e);
        }
    }

    /** 上传对象（HLS 产物：m3u8/ts 分片）。 */
    public void upload(String objectKey, InputStream stream, long size, String contentType) {
        try {
            s3Client.putObject(PutObjectRequest.builder()
                            .bucket(bucket)
                            .key(objectKey)
                            .contentType(contentType)
                            .build(),
                    RequestBody.fromInputStream(stream, size));
        } catch (Exception e) {
            throw new IllegalStateException("对象存储上传失败: " + objectKey + " - " + e.getMessage(), e);
        }
    }

    /** 按前缀删除（HLS 产物目录整体清理；重转码时先清旧分片）。 */
    public void deletePrefix(String prefix) {
        try {
            List<ObjectIdentifier> objects = new ArrayList<>();
            for (ListObjectsV2Response page : s3Client.listObjectsV2Paginator(
                    b -> b.bucket(bucket).prefix(prefix))) {
                for (S3Object o : page.contents()) {
                    objects.add(ObjectIdentifier.builder().key(o.key()).build());
                }
            }
            // S3 单次批量删除上限 1000
            for (int i = 0; i < objects.size(); i += 1000) {
                List<ObjectIdentifier> batch = objects.subList(i, Math.min(i + 1000, objects.size()));
                s3Client.deleteObjects(DeleteObjectsRequest.builder()
                        .bucket(bucket)
                        .delete(Delete.builder().objects(batch).build())
                        .build());
            }
        } catch (Exception e) {
            log.warn("对象存储前缀删除失败: {}", prefix, e);
        }
    }
}
