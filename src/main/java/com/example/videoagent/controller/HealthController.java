package com.example.videoagent.controller;

import java.util.List;
import java.util.Map;
import java.time.Duration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.data.redis.core.StringRedisTemplate;

@RestController
public class HealthController {

    private final JdbcTemplate jdbcTemplate;
    private final StringRedisTemplate redisTemplate;

    public HealthController(
            JdbcTemplate jdbcTemplate,
            StringRedisTemplate redisTemplate
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.redisTemplate = redisTemplate;
    }

    @GetMapping("/health/mysql")
    public Map<String, Object> mysql() {
        String result = jdbcTemplate.queryForObject("SELECT 1", String.class);
        return Map.of(
                "component", "mysql",
                "status", "UP",
                "result", result
        );
    }

    @PostMapping("/health/mysql/media")
    public Map<String, Object> insertMedia() {
        int rows = jdbcTemplate.update(
                """
                INSERT INTO media_files (filename, status, file_path)
                VALUES (?, ?, ?)
                """,
                "test.mp4", "UPLOADED", "test/test.mp4"
        );
        return Map.of("insertedRows", rows);
    }

    @GetMapping("/health/mysql/media")
    public List<Map<String, Object>> listMedia() {
        return jdbcTemplate.queryForList(
                """
                SELECT id, filename, status, file_path, upload_time
                FROM media_files
                ORDER BY id DESC
                LIMIT 10
                """
        );
    }

    @GetMapping("/health/redis")
    public Map<String, Object> redis() {
        String result = redisTemplate.execute(
                (org.springframework.data.redis.core.RedisCallback<String>)
                        connection -> connection.ping()
        );
        return Map.of(
                "component", "redis",
                "status", "PONG".equals(result) ? "UP" : "DOWN",
                "result", result == null ? "无响应" : result
        );
    }

    @PostMapping("/health/redis/value")
    public Map<String, Object> writeRedis() {
        redisTemplate.opsForValue().set(
                "health:test",
                "hello-redis",
                Duration.ofSeconds(60)
        );
        return Map.of("status", "OK");
    }

    @GetMapping("/health/redis/value")
    public Map<String, Object> readRedis() {
        String value = redisTemplate.opsForValue().get("health:test");
        Long ttl = redisTemplate.getExpire("health:test");

        return Map.of(
                "value", value == null ? "键不存在或已过期" : value,
                "ttlSeconds", ttl == null ? -2L : ttl
        );
    }
}