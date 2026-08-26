package com.skt.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AuthServiceNormalizationTest {

    @Test
    void shouldStripInvisibleCharactersFromStudentName() {
        String raw = "张\u200B\u200C\u2060\uFEFF三\u00A0\u3000";
        assertEquals("张三", AuthService.normalizeStudentName(raw));
    }

    @Test
    void shouldNormalizePhoneAcrossHiddenCharactersAndSeparators() {
        String raw = "+86 138\u200B-0000\u00A0\u300000\u2060";
        assertEquals("138000000", AuthService.normalizePhone(raw));
    }
}
