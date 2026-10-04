package com.rag.worker.infrastructure.storage;

import io.minio.GetObjectArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.ListObjectsArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectsArgs;
import io.minio.messages.DeleteObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
public class MinioStorage {

    private final MinioClient minioClient;
    private final String bucket;

    public MinioStorage(@Value("${rag.minio.endpoint}") String endpoint,
                        @Value("${rag.minio.access-key}") String accessKey,
                        @Value("${rag.minio.secret-key}") String secretKey,
                        @Value("${rag.minio.bucket}") String bucket) {
        this.bucket = bucket;
        // 显式超时：默认客户端无读超时，网络中断会无限挂死（大文件下载场景必须兜底）
        okhttp3.OkHttpClient httpClient = new okhttp3.OkHttpClient.Builder()
                .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(5, java.util.concurrent.TimeUnit.MINUTES)
                .writeTimeout(10, java.util.concurrent.TimeUnit.MINUTES)
                .build();
        this.minioClient = MinioClient.builder()
                .endpoint(endpoint)
                .credentials(accessKey, secretKey)
                .httpClient(httpClient)
                .build();
    }

    /** 生成预签名 GET URL（供 ffprobe/ffmpeg 直读 MinIO，免下载落盘）；有效期 2 小时（大文件转码足够）。 */
    public String presignUrl(String objectKey) {
        try {
            return minioClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(io.minio.http.Method.GET)
                    .bucket(bucket).object(objectKey)
                    .expiry(2, java.util.concurrent.TimeUnit.HOURS)
                    .build());
        } catch (Exception e) {
            log.warn("MinIO 预签名失败: {}", objectKey, e);
            return null;
        }
    }

    public InputStream download(String objectKey) {
        try {
            return minioClient.getObject(GetObjectArgs.builder().bucket(bucket).object(objectKey).build());
        } catch (Exception e) {
            throw new IllegalStateException("MinIO 下载失败: " + objectKey + " - " + e.getMessage(), e);
        }
    }

    /** 范围下载（并行分片下载用）：[offset, offset+length)。 */
    public InputStream downloadRange(String objectKey, long offset, long length) {
        try {
            return minioClient.getObject(GetObjectArgs.builder()
                    .bucket(bucket).object(objectKey).offset(offset).length(length).build());
        } catch (Exception e) {
            throw new IllegalStateException("MinIO 范围下载失败: " + objectKey + " - " + e.getMessage(), e);
        }
    }

    /** 上传对象（HLS 产物：m3u8/ts 分片）。 */
    public void upload(String objectKey, InputStream stream, long size, String contentType) {
        try {
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .stream(stream, size, -1)
                    .contentType(contentType)
                    .build());
        } catch (Exception e) {
            throw new IllegalStateException("MinIO 上传失败: " + objectKey + " - " + e.getMessage(), e);
        }
    }

    /** 按前缀删除（HLS 产物目录整体清理；重转码时先清旧分片）。 */
    public void deletePrefix(String prefix) {
        try {
            List<DeleteObject> objects = new ArrayList<>();
            minioClient.listObjects(ListObjectsArgs.builder()
                            .bucket(bucket).prefix(prefix).recursive(true).build())
                    .forEach(result -> {
                        try {
                            objects.add(new DeleteObject(result.get().objectName()));
                        } catch (Exception ignored) {
                        }
                    });
            if (objects.isEmpty()) {
                return;
            }
            minioClient.removeObjects(RemoveObjectsArgs.builder().bucket(bucket).objects(objects).build())
                    .forEach(r -> {
                        try {
                            r.get();
                        } catch (Exception ignored) {
                        }
                    });
        } catch (Exception e) {
            log.warn("MinIO 前缀删除失败: {}", prefix, e);
        }
    }
}
