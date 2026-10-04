package com.rag.api.infrastructure.storage;

import com.rag.api.common.BizException;
import com.rag.api.common.ErrorCode;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.RemoveObjectsArgs;
import io.minio.ListObjectsArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.http.Method;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class MinioStorage {

    private final MinioClient minioClient;

    @Value("${rag.minio.bucket}")
    private String bucket;

    @Value("${rag.minio.presign-minutes}")
    private int presignMinutes;

    @PostConstruct
    public void init() {
        try {
            if (!minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
                minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
                log.info("MinIO bucket 已创建: {}", bucket);
            }
        } catch (Exception e) {
            log.warn("MinIO 初始化失败（允许启动，运行期再重试）: {}", e.getMessage());
        }
    }

    public void upload(String objectKey, InputStream stream, long size, String contentType) {
        try {
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .stream(stream, size, -1)
                    .contentType(normalizeTextCharset(objectKey, contentType))
                    .build());
        } catch (Exception e) {
            log.error("MinIO 上传失败: {}", objectKey, e);
            throw new BizException(ErrorCode.UPSTREAM, "文件存储上传失败: " + e.getMessage());
        }
    }

    public InputStream download(String objectKey) {
        try {
            return minioClient.getObject(GetObjectArgs.builder().bucket(bucket).object(objectKey).build());
        } catch (Exception e) {
            throw new BizException(ErrorCode.UPSTREAM, "文件存储下载失败: " + e.getMessage());
        }
    }

    /** 范围下载：offset 起始字节，length 字节数（null 读到末尾），用于媒体流 Range 请求。 */
    public InputStream downloadRange(String objectKey, long offset, Long length) {
        try {
            GetObjectArgs.Builder b = GetObjectArgs.builder().bucket(bucket).object(objectKey).offset(offset);
            if (length != null) {
                b.length(length);
            }
            return minioClient.getObject(b.build());
        } catch (Exception e) {
            throw new BizException(ErrorCode.UPSTREAM, "文件存储读取失败: " + e.getMessage());
        }
    }

    public String presignUrl(String objectKey) {
        try {
            return minioClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Method.GET)
                    .bucket(bucket)
                    .object(objectKey)
                    .expiry(presignMinutes, TimeUnit.MINUTES)
                    .build());
        } catch (Exception e) {
            log.warn("MinIO 预签名失败: {}", objectKey);
            return null;
        }
    }

    /** 查询对象字节大小（用于历史文件大小懒回填）。 */
    public long statSize(String objectKey) {
        try {
            return minioClient.statObject(
                    io.minio.StatObjectArgs.builder().bucket(bucket).object(objectKey).build()).size();
        } catch (Exception e) {
            throw new BizException(ErrorCode.UPSTREAM, "文件信息查询失败: " + e.getMessage());
        }
    }

    public void deletePrefix(String prefix) {
        try {
            List<String> keys = new java.util.ArrayList<>();
            minioClient.listObjects(ListObjectsArgs.builder()
                            .bucket(bucket).prefix(prefix).recursive(true).build())
                    .forEach(result -> {
                        try {
                            keys.add(result.get().objectName());
                        } catch (Exception ignored) {
                        }
                    });
            if (keys.isEmpty()) {
                return;
            }
            List<io.minio.messages.DeleteObject> objects = keys.stream()
                    .map(io.minio.messages.DeleteObject::new)
                    .toList();
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

    /** md/txt 是 UTF-8 文本：显式声明 charset，避免浏览器在无 charset + nosniff 时按系统编码回退导致乱码。 */
    private String normalizeTextCharset(String objectKey, String contentType) {
        String lower = objectKey.toLowerCase();
        if (lower.endsWith(".md")) {
            return "text/markdown; charset=utf-8";
        }
        if (lower.endsWith(".txt")) {
            return "text/plain; charset=utf-8";
        }
        return contentType == null ? "application/octet-stream" : contentType;
    }

    public void deleteObject(String objectKey) {
        try {
            minioClient.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(objectKey).build());
        } catch (Exception e) {
            log.warn("MinIO 删除对象失败: {}", objectKey, e);
        }
    }

    /** 按前缀递归统计真实占用字节数（配额用量展示），异常抛给调用方回退。 */
    public long sumSizeByPrefix(String prefix) {
        long[] total = {0};
        minioClient.listObjects(ListObjectsArgs.builder()
                        .bucket(bucket).prefix(prefix).recursive(true).build())
                .forEach(result -> {
                    try {
                        total[0] += result.get().size();
                    } catch (Exception ignored) {
                    }
                });
        return total[0];
    }
}
