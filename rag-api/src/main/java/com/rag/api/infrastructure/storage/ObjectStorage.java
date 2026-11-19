package com.rag.api.infrastructure.storage;

import com.rag.api.common.BizException;
import com.rag.api.common.ErrorCode;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.Delete;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

import java.io.InputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 对象存储（S3 协议）统一入口：RustFS / MinIO 等 S3 兼容服务通用。
 * 故障策略：上传/下载/查询失败抛 BizException(UPSTREAM)；预签名与删除失败降级为 warn 日志。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ObjectStorage {

    private final S3Client s3Client;
    private final S3Presigner s3Presigner;

    @Value("${rag.storage.bucket}")
    private String bucket;

    @Value("${rag.storage.presign-minutes}")
    private int presignMinutes;

    @PostConstruct
    public void init() {
        try {
            s3Client.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
            log.info("对象存储 bucket 已存在: {}", bucket);
        } catch (S3Exception e) {
            if (e instanceof NoSuchBucketException || e.statusCode() == 404) {
                try {
                    s3Client.createBucket(b -> b.bucket(bucket));
                    log.info("对象存储 bucket 已创建: {}", bucket);
                } catch (Exception ce) {
                    log.warn("对象存储 bucket 创建失败（允许启动，运行期再重试）: {}", ce.getMessage());
                }
            } else {
                log.warn("对象存储初始化失败（允许启动，运行期再重试）: {}", e.getMessage());
            }
        } catch (Exception e) {
            log.warn("对象存储初始化失败（允许启动，运行期再重试）: {}", e.getMessage());
        }
    }

    public void upload(String objectKey, InputStream stream, long size, String contentType) {
        try {
            s3Client.putObject(PutObjectRequest.builder()
                            .bucket(bucket)
                            .key(objectKey)
                            .contentType(normalizeTextCharset(objectKey, contentType))
                            .build(),
                    RequestBody.fromInputStream(stream, size));
        } catch (Exception e) {
            log.error("对象存储上传失败: {}", objectKey, e);
            throw new BizException(ErrorCode.UPSTREAM, "文件存储上传失败: " + e.getMessage());
        }
    }

    public InputStream download(String objectKey) {
        try {
            return s3Client.getObject(GetObjectRequest.builder().bucket(bucket).key(objectKey).build());
        } catch (Exception e) {
            throw new BizException(ErrorCode.UPSTREAM, "文件存储下载失败: " + e.getMessage());
        }
    }

    /** 范围下载：offset 起始字节，length 字节数（null 读到末尾），用于媒体流 Range 请求。 */
    public InputStream downloadRange(String objectKey, long offset, Long length) {
        String range = length == null
                ? "bytes=" + offset + "-"
                : "bytes=" + offset + "-" + (offset + length - 1);
        try {
            return s3Client.getObject(GetObjectRequest.builder()
                    .bucket(bucket).key(objectKey).range(range).build());
        } catch (Exception e) {
            throw new BizException(ErrorCode.UPSTREAM, "文件存储读取失败: " + e.getMessage());
        }
    }

    public String presignUrl(String objectKey) {
        try {
            return s3Presigner.presignGetObject(GetObjectPresignRequest.builder()
                            .signatureDuration(Duration.ofMinutes(presignMinutes))
                            .getObjectRequest(g -> g.bucket(bucket).key(objectKey))
                            .build())
                    .url().toString();
        } catch (Exception e) {
            log.warn("对象存储预签名失败: {}", objectKey);
            return null;
        }
    }

    /** 查询对象字节大小（用于历史文件大小懒回填）。 */
    public long statSize(String objectKey) {
        try {
            return s3Client.headObject(b -> b.bucket(bucket).key(objectKey)).contentLength();
        } catch (Exception e) {
            throw new BizException(ErrorCode.UPSTREAM, "文件信息查询失败: " + e.getMessage());
        }
    }

    public void deleteObject(String objectKey) {
        try {
            s3Client.deleteObject(b -> b.bucket(bucket).key(objectKey));
        } catch (Exception e) {
            log.warn("对象存储删除对象失败: {}", objectKey, e);
        }
    }

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

    /** 按前缀递归统计真实占用字节数（配额用量展示），异常抛给调用方回退。 */
    public long sumSizeByPrefix(String prefix) {
        long total = 0;
        for (ListObjectsV2Response page : s3Client.listObjectsV2Paginator(
                b -> b.bucket(bucket).prefix(prefix))) {
            for (S3Object o : page.contents()) {
                total += o.size();
            }
        }
        return total;
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
}
