package com.hify.hify.common.security;

import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.security.WeakKeyException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JwtUtilTest {

    private JwtUtil jwtUtil;

    @BeforeEach
    void setUp() {
        jwtUtil = new JwtUtil("test-secret-key-must-be-at-least-32-bytes-long!!", 7200000L);
    }

    @Test
    void testSignAndParse_sameUsernameRole() {
        String token = jwtUtil.sign("alice", "ADMIN");
        assertEquals("alice", jwtUtil.parseUsername(token));
        assertEquals("ADMIN", jwtUtil.parseRole(token));
    }

    @Test
    void testParse_tamperedToken_throws() {
        String token = jwtUtil.sign("alice", "ADMIN");
        // 篡改 payload（中间段）任一字符 → 签名必然不匹配 → 抛 JwtException。
        // 注：jjwt 0.12.6 对“末尾追加字符”这种篡改不敏感，故改为改 payload 内容。
        String[] parts = token.split("\\.");
        String payload = parts[1];
        char flip = payload.charAt(0) == 'A' ? 'B' : 'A';
        String tamperedPayload = flip + payload.substring(1);
        String fake = parts[0] + "." + tamperedPayload + "." + parts[2];
        assertThrows(JwtException.class, () -> jwtUtil.parseUsername(fake));
    }

    @Test
    void testConstructor_shortSecret_throwsWeakKeyException() {
        // 密钥过短（<32 字节）会被 jjwt 拒绝，证明密钥长度约束生效（§9）
        assertThrows(WeakKeyException.class, () -> new JwtUtil("short", 7200000L));
    }
}
