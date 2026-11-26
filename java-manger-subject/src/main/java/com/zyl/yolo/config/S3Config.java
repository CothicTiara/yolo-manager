package com.zyl.yolo.config;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.URI;

/**
 * AWS S3 客户端配置（兼容 RustFS / MinIO 等 S3 协议对象存储）
 */
@Configuration
public class S3Config {

    @Value("${yolo.storage.s3.endpoint:http://localhost:9000}")
    private String endpoint;

    @Value("${yolo.storage.s3.access-key:yoloadmin}")
    private String accessKey;

    @Value("${yolo.storage.s3.secret-key:yolosecret}")
    private String secretKey;

    @Value("${yolo.storage.s3.region:us-east-1}")
    private String region;

    @Value("${yolo.storage.s3.path-style-access:true}")
    private boolean pathStyleAccess;

    @Bean
    public S3Client s3Client() {
        return S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKey, secretKey)))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(pathStyleAccess)
                        .build())
                .build();
    }
}