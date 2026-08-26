package com.skt.security;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RoleAccessTest {

    private HttpServletRequest mockRequest(String role) {
        HttpServletRequest req = Mockito.mock(HttpServletRequest.class);
        Mockito.when(req.getAttribute("role")).thenReturn(role);
        return req;
    }

    @Test
    void shouldReturnTrueForTeacherRole() {
        HttpServletRequest req = mockRequest("teacher");
        assertTrue(RoleAccess.isTeacher(req));
    }

    @Test
    void shouldReturnTrueForAdminRoleInIsTeacher() {
        HttpServletRequest req = mockRequest("admin");
        assertTrue(RoleAccess.isTeacher(req));
    }

    @Test
    void shouldReturnFalseForParentRoleInIsTeacher() {
        HttpServletRequest req = mockRequest("parent");
        assertFalse(RoleAccess.isTeacher(req));
    }

    @Test
    void shouldReturnFalseForStudentRoleInIsTeacher() {
        HttpServletRequest req = mockRequest("student");
        assertFalse(RoleAccess.isTeacher(req));
    }

    @Test
    void shouldReturnTrueForAdminRole() {
        HttpServletRequest req = mockRequest("admin");
        assertTrue(RoleAccess.isAdmin(req));
    }

    @Test
    void shouldReturnFalseForTeacherRoleInIsAdmin() {
        HttpServletRequest req = mockRequest("teacher");
        assertFalse(RoleAccess.isAdmin(req));
    }

    @Test
    void shouldReturnTrueForParentRole() {
        HttpServletRequest req = mockRequest("parent");
        assertTrue(RoleAccess.isParent(req));
    }

    @Test
    void shouldReturnFalseForTeacherRoleInIsParent() {
        HttpServletRequest req = mockRequest("teacher");
        assertFalse(RoleAccess.isParent(req));
    }

    @Test
    void shouldReturnFalseForNullRequest() {
        assertFalse(RoleAccess.isTeacher(null));
        assertFalse(RoleAccess.isAdmin(null));
        assertFalse(RoleAccess.isParent(null));
    }

    @Test
    void shouldReturnForbiddenForParentWrite() {
        Map<String, Object> result = RoleAccess.forbidParentWrite("家长无权录入成绩");
        assertEquals(403, result.get("code"));
        assertEquals("家长无权录入成绩", result.get("msg"));
    }

    @Test
    void shouldReturnDefaultMessageForNull() {
        Map<String, Object> result = RoleAccess.forbidParentWrite(null);
        assertEquals(403, result.get("code"));
        assertEquals("无权限执行此操作", result.get("msg"));
    }

    @Test
    void shouldReturnForbiddenForTeacherOnly() {
        Map<String, Object> result = RoleAccess.forbidTeacherOnly("仅限教师使用");
        assertEquals(403, result.get("code"));
        assertEquals("仅限教师使用", result.get("msg"));
    }

    @Test
    void shouldGetUserIdFromRequest() {
        HttpServletRequest req = Mockito.mock(HttpServletRequest.class);
        Mockito.when(req.getAttribute("userId")).thenReturn(123L);
        assertEquals(123L, RoleAccess.getUserId(req));
    }

    @Test
    void shouldGetUserIdFromNumber() {
        HttpServletRequest req = Mockito.mock(HttpServletRequest.class);
        Mockito.when(req.getAttribute("userId")).thenReturn(456);
        assertEquals(456L, RoleAccess.getUserId(req));
    }

    @Test
    void shouldGetUserIdFromString() {
        HttpServletRequest req = Mockito.mock(HttpServletRequest.class);
        Mockito.when(req.getAttribute("userId")).thenReturn("789");
        assertEquals(789L, RoleAccess.getUserId(req));
    }

    @Test
    void shouldReturnNullForInvalidUserId() {
        HttpServletRequest req = Mockito.mock(HttpServletRequest.class);
        Mockito.when(req.getAttribute("userId")).thenReturn("invalid");
        assertNull(RoleAccess.getUserId(req));
    }

    @Test
    void shouldReturnNullForNullUserId() {
        HttpServletRequest req = Mockito.mock(HttpServletRequest.class);
        Mockito.when(req.getAttribute("userId")).thenReturn(null);
        assertNull(RoleAccess.getUserId(req));
    }
}
