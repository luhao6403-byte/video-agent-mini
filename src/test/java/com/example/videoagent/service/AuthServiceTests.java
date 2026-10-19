package com.example.videoagent.service;

import com.example.videoagent.dto.LoginResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AuthServiceTests {

    // 使用 Python hashlib 生成的独立 PBKDF2 测试数据，密码为 Test123456。
    private static final String STORED_HASH = "pbkdf2_sha256$210000$"
            + "AAECAwQFBgcICQoLDA0ODw==$U7uEtTZ239IVGh9UkEvoWvZYf6Y7vUzj24yif9g01pY=";

    private JdbcTemplate jdbc;
    private StringRedisTemplate redis;
    private AuthService service;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        redis = mock(StringRedisTemplate.class);
        service = new AuthService(jdbc, redis);
    }

    @Test
    void correctPasswordStoresOnlyTokenDigestWith24HourExpiry() throws Exception {
        stubUser(STORED_HASH);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);

        LoginResponse login = service.login("testuser", "Test123456").orElseThrow();

        assertEquals(7L, login.userId());
        assertEquals("Bearer", login.tokenType());
        assertEquals(86400L, login.expiresIn());
        assertTrue(login.token().matches("[A-Za-z0-9_-]{43}"));
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(login.token().getBytes(StandardCharsets.UTF_8)));
        verify(values).set("auth:session:" + digest, "7", Duration.ofHours(24));
        verifyNoMoreInteractions(values);
    }

    @Test
    void wrongPasswordDoesNotCreateSession() throws Exception {
        stubUser(STORED_HASH);
        assertTrue(service.login("testuser", "Wrong123456").isEmpty());
        verifyNoInteractions(redis);
    }

    @Test
    void missingUserDoesNotCreateSession() {
        when(jdbc.query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<Object>>any(), eq("missing")))
                .thenReturn(List.of());
        assertTrue(service.login("missing", "Test123456").isEmpty());
        verifyNoInteractions(redis);
    }

    @Test
    void malformedStoredHashDoesNotCreateSession() throws Exception {
        stubUser("pbkdf2_sha256$invalid$salt$hash");
        assertTrue(service.login("testuser", "Test123456").isEmpty());
        verifyNoInteractions(redis);
    }

    private void stubUser(String hash) throws Exception {
        ResultSet row = mock(ResultSet.class);
        when(row.getLong("id")).thenReturn(7L);
        when(row.getString("password_hash")).thenReturn(hash);
        when(jdbc.query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<Object>>any(), eq("testuser")))
                .thenAnswer(invocation -> {
                    RowMapper<?> mapper = invocation.getArgument(1);
                    return List.of(mapper.mapRow(row, 0));
                });
    }
}
