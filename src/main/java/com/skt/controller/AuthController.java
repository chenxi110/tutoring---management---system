package com.skt.controller;

import com.skt.security.RoleAccess;
import com.skt.service.AuthService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import java.util.*;

@RestController
@RequestMapping("/api")
public class AuthController {

    @Autowired
    private AuthService authService;

    @PostMapping("/auth/login")
    public Map<String, Object> login(@RequestBody Map<String, Object> body) {
        String username = (String) body.get("username");
        String password = (String) body.get("password");
        return authService.login(username, password);
    }

    @PostMapping("/auth/register")
    public Map<String, Object> register(@RequestBody Map<String, Object> body) {
        if (body == null) {
            return authService.register(null, null, null, null, null);
        }
        String username = body.get("username") == null ? null : String.valueOf(body.get("username")).trim();
        String password = body.get("password") == null ? null : String.valueOf(body.get("password")).trim();
        String role = body.get("role") == null ? null : String.valueOf(body.get("role")).trim();
        String displayName = body.get("displayName") == null ? null : String.valueOf(body.get("displayName")).trim();
        String phone = body.get("phone") == null ? null : String.valueOf(body.get("phone")).trim();
        return authService.register(username, password, role, displayName, phone);
    }

    @GetMapping("/auth/profile")
    public Map<String, Object> profile(HttpServletRequest req) {
        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        Map<String, Object> u = new HashMap<>();
        u.put("id", req.getAttribute("userId"));
        u.put("username", req.getAttribute("username"));
        u.put("role", req.getAttribute("role"));
        u.put("displayName", req.getAttribute("displayName"));
        result.put("user", u);
        return result;
    }

    @GetMapping("/parent/children")
    public Map<String, Object> parentChildren(HttpServletRequest req) {
        Long parentId = (Long) req.getAttribute("userId");
        List<Map<String, Object>> children = authService.getParentChildren(parentId);
        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("data", children);
        return result;
    }

    @PostMapping("/parent/bind")
    public Map<String, Object> bindStudent(@RequestBody Map<String, Object> body, HttpServletRequest req) {
        Long parentId = (Long) req.getAttribute("userId");
        String role = (String) req.getAttribute("role");
        // 权限校验：只有家长角色可绑定学生，防止教师账号误绑/越权占用 parent_id
        if (!"parent".equals(role)) {
            Map<String, Object> r = new HashMap<>();
            r.put("code", 403);
            r.put("error", "仅家长账号可绑定学生");
            return r;
        }
        String rawStudentName = body == null || body.get("studentName") == null ? null : String.valueOf(body.get("studentName"));
        String rawParentPhone = body == null || body.get("parentPhone") == null ? null : String.valueOf(body.get("parentPhone"));
        System.out.println("[AuthController.bindStudent] raw studentName=" + rawStudentName + ", raw parentPhone=" + rawParentPhone + ", parentId=" + parentId);
        return authService.bindParent(parentId, rawStudentName, rawParentPhone);
    }

    @PostMapping("/parent/unbind")
    public Map<String, Object> unbindStudent(@RequestBody Map<String, Object> body, HttpServletRequest req) {
        Long parentId = (Long) req.getAttribute("userId");
        String role = (String) req.getAttribute("role");
        if (!"parent".equals(role)) {
            Map<String, Object> r = new HashMap<>();
            r.put("code", 403);
            r.put("error", "仅家长账号可解绑学生");
            return r;
        }
        Long studentId = null;
        if (body != null && body.get("studentId") != null) {
            try { studentId = Long.valueOf(String.valueOf(body.get("studentId"))); }
            catch (NumberFormatException e) { studentId = null; }
        }
        return authService.unbindParent(parentId, studentId);
    }

    // 重置用户密码（仅教师管理员）
    @PostMapping("/user/resetPwd")
    public Map<String, Object> resetPassword(@RequestBody Map<String, Object> body, HttpServletRequest req) {
        Long operatorId = (Long) req.getAttribute("userId");
        String operatorRole = (String) req.getAttribute("role");
        Long targetUserId = null;
        if (body != null && body.get("userId") != null) {
            try { targetUserId = Long.valueOf(String.valueOf(body.get("userId"))); }
            catch (NumberFormatException e) { targetUserId = null; }
        }
        return authService.resetPassword(targetUserId, operatorId, operatorRole);
    }

    // 修改本人密码（所有角色）
    @PostMapping("/user/updateMyPwd")
    public Map<String, Object> updateMyPassword(@RequestBody Map<String, Object> body, HttpServletRequest req) {
        Long userId = (Long) req.getAttribute("userId");
        String oldPassword = body == null || body.get("oldPassword") == null ? null : String.valueOf(body.get("oldPassword"));
        String newPassword = body == null || body.get("newPassword") == null ? null : String.valueOf(body.get("newPassword"));
        String confirmPassword = body == null || body.get("confirmPassword") == null ? null : String.valueOf(body.get("confirmPassword"));
        return authService.updateMyPassword(userId, oldPassword, newPassword, confirmPassword);
    }

    // 获取用户列表（仅教师/管理员）
    @GetMapping("/user/list")
    public Map<String, Object> getUserList(HttpServletRequest req) {
        Map<String, Object> result = new HashMap<>();
        if (!RoleAccess.isTeacher(req)) {
            result.put("code", 403);
            result.put("error", "无权限：仅教师/管理员可查看用户列表");
            result.put("msg", "无权限：仅教师/管理员可查看用户列表");
            return result;
        }
        try {
            List<Map<String, Object>> users = authService.getAllUsers();
            result.put("code", 200);
            result.put("data", users);
            return result;
        } catch (Exception ex) {
            result.put("code", 500);
            result.put("error", "获取用户列表失败：" + ex.getMessage());
            result.put("msg", "获取用户列表失败");
            return result;
        }
    }
}
