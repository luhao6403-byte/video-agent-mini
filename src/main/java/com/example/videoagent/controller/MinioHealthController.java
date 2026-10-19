package com.example.videoagent.controller;

import io.minio.BucketExistsArgs;
import io.minio.MinioClient;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import io.minio.GetObjectArgs;
import io.minio.GetObjectResponse;
import io.minio.PutObjectArgs;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.springframework.web.bind.annotation.PostMapping;

@RestController
public class MinioHealthController {

    private final MinioClient minioClient;
    private final String bucketName;

    public MinioHealthController(
            MinioClient minioClient,
            @Value("${minio.bucket-name}") String bucketName
    ) {
        this.minioClient = minioClient;
        this.bucketName = bucketName;
    }

    @GetMapping("/health/minio")
    public Map<String, Object> minio() throws Exception {
        boolean exists = minioClient.bucketExists(
                BucketExistsArgs.builder()
                        .bucket(bucketName)
                        .build()
        );

        return Map.of(
                "component", "minio",
                "status", exists ? "UP" : "DOWN",
                "bucket", bucketName,
                "bucketExists", exists
        );
    }

    @PostMapping("/health/minio/object")
    public Map<String, Object> uploadObject() throws Exception {
        byte[] content = "hello-minio".getBytes(StandardCharsets.UTF_8);

        try (ByteArrayInputStream input = new ByteArrayInputStream(content)) {
            minioClient.putObject(
                    PutObjectArgs.builder()
                            .bucket(bucketName)
                            .object("health/test.txt")
                            .stream(input, content.length, -1)
                            .contentType("text/plain; charset=utf-8")
                            .build()
            );
        }

        return Map.of(
                "status", "OK",
                "object", "health/test.txt"
        );
    }

    @GetMapping("/health/minio/object")
    public Map<String, Object> readObject() throws Exception {
        try (GetObjectResponse response = minioClient.getObject(
                GetObjectArgs.builder()
                        .bucket(bucketName)
                        .object("health/test.txt")
                        .build()
        )) {
            String content = new String(
                    response.readAllBytes(),
                    StandardCharsets.UTF_8
            );

            return Map.of(
                    "object", "health/test.txt",
                    "content", content
            );
        }
    }
}