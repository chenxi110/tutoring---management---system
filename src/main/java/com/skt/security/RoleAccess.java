package com.skt.security;

import jakarta.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.Map;

public final class RoleAccess {
    private RoleAccess() {
    }

    public static boolean isTeacher(HttpServletRequest req) {
        Object role = req == null ? null : req.getAttribute("role");
        String r = role == null ? null : role.toString();
        return "teacher".equals(r) || "admin".equals(r);
    }

    public static boolean isAdmin(HttpServletRequest req) {
        Object role = req == null ? null : req.getAttribute("role");
        return "admin".equals(role == null ? null : role.toString());
    }

    public static boolean isParent(HttpServletRequest req) {
        Object role = req == null ? null : req.getAttribute("role");
        return "parent".equals(role == null ? null : role.toString());
    }

    public static Map<String, Object> forbidParentWrite(String message) {
        Map<String, Object> result = new HashMap<>();
        result.put("code", 403);
        result.put("msg", message == null || message.trim().isEmpty() ? "无权限执行此操作" : message);
        return result;
    }

    public static Map<String, Object> forbidTeacherOnly(String message) {
        Map<String, Object> result = new HashMap<>();
        result.put("code", 403);
        result.put("msg", message == null || message.trim().isEmpty() ? "此功能仅限教师账号使用" : message);
        return result;
    }

    public static Long getUserId(HttpServletRequest req) {
        Object id = req == null ? null : req.getAttribute("userId");
        if (id == null) return null;
        if (id instanceof Long) return (Long) id;
        if (id instanceof Number) return ((Number) id).longValue();
        try { return Long.parseLong(id.toString()); } catch (NumberFormatException e) { return null; }
    }
}
