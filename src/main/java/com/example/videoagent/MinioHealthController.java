package com.example.videoagent;

import io.minio.BucketExistsArgs;
import io.minio.MinioClient;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

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
}