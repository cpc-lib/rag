package com.rag.worker.infrastructure.storage;

import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.InputStream;

@Component
public class MinioStorage {

    private final MinioClient minioClient;
    private final String bucket;

    public MinioStorage(@Value("${rag.minio.endpoint}") String endpoint,
                        @Value("${rag.minio.access-key}") String accessKey,
                        @Value("${rag.minio.secret-key}") String secretKey,
                        @Value("${rag.minio.bucket}") String bucket) {
        this.bucket = bucket;
        this.minioClient = MinioClient.builder().endpoint(endpoint).credentials(accessKey, secretKey).build();
    }

    public InputStream download(String objectKey) {
        try {
            return minioClient.getObject(GetObjectArgs.builder().bucket(bucket).object(objectKey).build());
        } catch (Exception e) {
            throw new IllegalStateException("MinIO 下载失败: " + objectKey + " - " + e.getMessage(), e);
        }
    }
}
