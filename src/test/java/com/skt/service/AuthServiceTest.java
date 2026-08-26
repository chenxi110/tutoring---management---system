package com.skt.service;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AuthService单元测试
 * 覆盖：密码加密验证、学生姓名规范化、手机号规范化
 */
class AuthServiceTest {

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    @Test
    void shouldEncodePassword() {
        String rawPassword = "admin123";
        String encoded = encoder.encode(rawPassword);
        assertNotNull(encoded);
        assertNotEquals(rawPassword, encoded);
        assertTrue(encoded.startsWith("$2a$") || encoded.startsWith("$2b$"));
    }

    @Test
    void shouldMatchEncodedPassword() {
        String rawPassword = "testPassword123";
        String encoded = encoder.encode(rawPassword);
        assertTrue(encoder.matches(rawPassword, encoded));
    }

    @Test
    void shouldNotMatchWrongPassword() {
        String encoded = encoder.encode("correctPassword");
        assertFalse(encoder.matches("wrongPassword", encoded));
    }

    @Test
    void shouldGenerateDifferentHashEachTime() {
        String password = "samePassword";
        String hash1 = encoder.encode(password);
        String hash2 = encoder.encode(password);
        assertNotEquals(hash1, hash2);
    }

    @Test
    void shouldNormalizeStudentNameWithInvisibleChars() {
        String raw = "张\u200B\u200C\u2060\uFEFF三";
        String result = AuthService.normalizeStudentName(raw);
        assertEquals("张三", result);
    }

    @Test
    void shouldNormalizeStudentNameWithSpaces() {
        String raw = "  张  三  ";
        String result = AuthService.normalizeStudentName(raw);
        assertEquals("张三", result);
    }

    @Test
    void shouldNormalizeNormalName() {
        String raw = "李四";
        String result = AuthService.normalizeStudentName(raw);
        assertEquals("李四", result);
    }

    @Test
    void shouldNormalizeEmptyName() {
        String result = AuthService.normalizeStudentName("");
        assertEquals("", result);
    }

    @Test
    void shouldNormalizeNullName() {
        String result = AuthService.normalizeStudentName(null);
        assertEquals("", result);
    }

    @Test
    void shouldNormalizePhoneWithSeparators() {
        String raw = "138-0000-0000";
        String result = AuthService.normalizePhone(raw);
        assertEquals("13800000000", result);
    }

    @Test
    void shouldNormalizePhoneWithSpaces() {
        String raw = "138 0000 0000";
        String result = AuthService.normalizePhone(raw);
        assertEquals("13800000000", result);
    }

    @Test
    void shouldNormalizePhoneWithCountryCode() {
        String raw = "+86 13800000000";
        String result = AuthService.normalizePhone(raw);
        assertTrue(result.contains("13800000000"));
    }

    @Test
    void shouldNormalizePhoneWithInvisibleChars() {
        String raw = "138\u200B0000\u00A00000";
        String result = AuthService.normalizePhone(raw);
        assertEquals("13800000000", result);
    }

    @Test
    void shouldHandleEmptyPhone() {
        String result = AuthService.normalizePhone("");
        assertEquals("", result);
    }

    @Test
    void shouldHandleNullPhone() {
        String result = AuthService.normalizePhone(null);
        assertEquals("", result);
    }
}
