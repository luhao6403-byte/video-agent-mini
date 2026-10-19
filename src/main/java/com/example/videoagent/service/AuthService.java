package com.example.videoagent.service;

import com.example.videoagent.dto.LoginResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class AuthService {

    private final JdbcTemplate jdbcTemplate;
    private final StringRedisTemplate redisTemplate;
    private static final Duration SESSION_TTL = Duration.ofHours(24);
    private final SecureRandom secureRandom = new SecureRandom();

    public AuthService(JdbcTemplate jdbcTemplate, StringRedisTemplate redisTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.redisTemplate = redisTemplate;
    }

    public void register(String username, String password, String nickname) {
        String passwordHash = hashPassword(password);

        jdbcTemplate.update(
                """
                INSERT INTO users (username, password_hash, nickname)
                VALUES (?, ?, ?)
                """,
                username, passwordHash, nickname
        );
    }

    public Optional<LoginResponse> login(String username, String password) {
        List<UserCredentials> users = jdbcTemplate.query(
                "SELECT id, password_hash FROM users WHERE username = ?",
                (rs, rowNum) -> new UserCredentials(
                        rs.getLong("id"), rs.getString("password_hash")
                ),
                username
        );

        if (users.isEmpty() || !verifyPassword(password, users.getFirst().passwordHash())) {
            return Optional.empty();
        }

        long userId = users.getFirst().id();
        byte[] randomBytes = new byte[32];
        secureRandom.nextBytes(randomBytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);

        // Redis 只保存 Token 的摘要；原始 Token 仅返回给客户端。
        redisTemplate.opsForValue().set(
                "auth:session:" + tokenDigest(token),
                Long.toString(userId),
                SESSION_TTL
        );

        return Optional.of(new LoginResponse(token, "Bearer", SESSION_TTL.toSeconds(), userId));
    }

    private boolean verifyPassword(String password, String storedHash) {
        if (storedHash == null) {
            return false;
        }
        String[] parts = storedHash.split("\\$", -1);
        if (parts.length != 4 || !"pbkdf2_sha256".equals(parts[0])) {
            return false;
        }
        try {
            int iterations = Integer.parseInt(parts[1]);
            if (iterations < 1 || iterations > 1000000) {
                return false;
            }
            byte[] salt = Base64.getDecoder().decode(parts[2]);
            byte[] expected = Base64.getDecoder().decode(parts[3]);
            if (salt.length != 16 || expected.length != 32) {
                return false;
            }
            byte[] actual = derivePassword(password, salt, iterations);
            return MessageDigest.isEqual(expected, actual);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private String tokenDigest(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Token 摘要生成失败", e);
        }
    }

    private String hashPassword(String password) {
        byte[] salt = new byte[16];
        secureRandom.nextBytes(salt);

        int iterations = 210000;
        byte[] hash = derivePassword(password, salt, iterations);
        return "pbkdf2_sha256$" + iterations
                + "$" + Base64.getEncoder().encodeToString(salt)
                + "$" + Base64.getEncoder().encodeToString(hash);
    }

    private byte[] derivePassword(String password, byte[] salt, int iterations) {
        PBEKeySpec spec = new PBEKeySpec(
                password.toCharArray(),
                salt,
                iterations,
                256
        );

        try {
            return SecretKeyFactory
                    .getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(spec)
                    .getEncoded();

        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("密码哈希生成失败", e);
        } finally {
            spec.clearPassword();
        }
    }

    private record UserCredentials(long id, String passwordHash) {
    }
}
