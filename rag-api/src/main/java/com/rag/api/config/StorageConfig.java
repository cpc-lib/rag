package com.rag.api.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;
import java.time.Duration;

/**
 * 对象存储客户端（S3 协议）：RustFS / MinIO 等 S3 兼容服务通用。
 * path-style 访问 + 自定义 endpoint，避免依赖 AWS 默认域名与虚拟主机风格。
 */
@Configuration
public class StorageConfig {

    @Bean
    public S3Client s3Client(@Value("${rag.storage.endpoint}") String endpoint,
                             @Value("${rag.storage.access-key}") String accessKey,
                             @Value("${rag.storage.secret-key}") String secretKey,
                             @Value("${rag.storage.region}") String region) {
        return S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.of(region))
                .credentialsProvider(credentials(accessKey, secretKey))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                // 显式超时：大文件流式读写不能无限挂死
                .httpClient(UrlConnectionHttpClient.builder()
                        .connectionTimeout(Duration.ofSeconds(10))
                        .socketTimeout(Duration.ofMinutes(5))
                        .build())
                .build();
    }

    @Bean
    public S3Presigner s3Presigner(@Value("${rag.storage.endpoint}") String endpoint,
                                   @Value("${rag.storage.access-key}") String accessKey,
                                   @Value("${rag.storage.secret-key}") String secretKey,
                                   @Value("${rag.storage.region}") String region) {
        return S3Presigner.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.of(region))
                .credentialsProvider(credentials(accessKey, secretKey))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .build();
    }

    private StaticCredentialsProvider credentials(String accessKey, String secretKey) {
        return StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey));
    }
}
