package com.sayagent.common.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PasswordEncoderTest {

    private final PasswordEncoder encoder = new BCryptPasswordEncoder();

    @Test
    void testEncode_rawPassword_returnsBCryptHash() {
        String hash = encoder.encode("admin123");
        assertTrue(hash.startsWith("$2a$"), "BCrypt 密文以 $2a$ 开头");
        assertNotEquals("admin123", hash, "明文不得等于密文");
    }

    @Test
    void testMatches_correctPassword_returnsTrue() {
        String hash = encoder.encode("admin123");
        assertTrue(encoder.matches("admin123", hash), "正确明文应匹配密文");
    }

    @Test
    void testMatches_wrongPassword_returnsFalse() {
        String hash = encoder.encode("admin123");
        assertTrue(!encoder.matches("wrong", hash), "错误明文不应匹配密文");
    }
}
