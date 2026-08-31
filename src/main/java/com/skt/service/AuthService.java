package com.skt.service;

import com.skt.security.JwtUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private JwtUtil jwtUtil;
    @Autowired
    private OperationLogService operationLogService;

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    public Map<String, Object> login(String username, String password) {
        // 空值防护：防止 null 传入导致 NPE
        if (username == null || username.trim().isEmpty()) {
            return errorMap("用户名不能为空");
        }
        if (password == null || password.isEmpty()) {
            return errorMap("密码不能为空");
        }
        try {
            List<Map<String, Object>> users = jdbc.queryForList(
                "SELECT * FROM users WHERE username=?", username.trim());
            if (users.isEmpty()) {
                return errorMap("用户不存在");
            }
            Map<String, Object> user = users.get(0);
            String passwordHash = (String) user.get("password_hash");
            if (passwordHash == null || !encoder.matches(password, passwordHash)) {
                return errorMap("密码错误");
            }
            Long id = ((Number) user.get("id")).longValue();
            String token = jwtUtil.generateToken(id,
                (String) user.get("username"),
                (String) user.get("role"),
                (String) user.get("display_name"));
            Map<String, Object> result = new HashMap<>();
            result.put("code", 200);
            result.put("token", token);
            Map<String, Object> u = new HashMap<>();
            u.put("id", id);
            u.put("username", user.get("username"));
            u.put("role", user.get("role"));
            u.put("displayName", user.get("display_name"));
            u.put("phone", user.get("phone"));
            result.put("user", u);
            log.info("用户登录成功: username={}, role={}", user.get("username"), user.get("role"));
            return result;
        } catch (DataAccessException e) {
            log.error("登录时数据库访问异常: username={}, error={}", username, e.getMessage(), e);
            return errorMap("登录失败：数据库访问异常，请稍后重试");
        } catch (Exception e) {
            log.error("登录时系统异常: username={}, error={}", username, e.getMessage(), e);
            return errorMap("登录失败：系统异常，请稍后重试");
        }
    }

    public Map<String, Object> register(String username, String password, String role, String displayName, String phone) {
        try {
            if (username == null || username.trim().isEmpty()) {
                return errorMap("用户名不能为空");
            }
            if (password == null || password.trim().isEmpty()) {
                return errorMap("密码不能为空");
            }
            if (role == null || role.trim().isEmpty()) {
                return errorMap("角色不能为空");
            }
            String normalizedRole = role.trim();
            if (!"teacher".equals(normalizedRole) && !"parent".equals(normalizedRole)) {
                return errorMap("角色参数非法");
            }
            if ("parent".equals(normalizedRole) && (displayName == null || displayName.trim().isEmpty())) {
                return errorMap("家长角色必须填写显示名称");
            }

            String normalizedUsername = username.trim();
            String normalizedPhone = normalizePhone(phone);
            String normalizedDisplayName = displayName == null ? "" : displayName.trim();
            if ("teacher".equals(normalizedRole) && normalizedDisplayName.isEmpty()) {
                normalizedDisplayName = "教师";
            }
            if (normalizedPhone != null && !normalizedPhone.isEmpty() && !normalizedPhone.matches("^1[3-9]\\d{9}$")) {
                return errorMap("手机号格式不正确，请输入正确的11位手机号");
            }
            if (normalizedUsername.length() > 50) {
                return errorMap("用户名长度不能超过50个字符");
            }
            if (password.trim().length() < 6) {
                return errorMap("密码长度不能少于6位");
            }
            if (normalizedDisplayName.length() > 50) {
                return errorMap("姓名长度不能超过50个字符");
            }
            if (password.trim().length() > 100) {
                return errorMap("密码长度不能超过100个字符");
            }

            Integer cnt = jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE username=?", Integer.class, normalizedUsername);
            if (cnt != null && cnt > 0) {
                return errorMap("用户名已存在");
            }

            String hash = encoder.encode(password.trim());
            jdbc.update("INSERT INTO users (username, password_hash, role, display_name, phone) VALUES (?,?,?,?,?)",
                normalizedUsername, hash, normalizedRole, normalizedDisplayName, normalizedPhone);

            List<Map<String, Object>> users = jdbc.queryForList("SELECT * FROM users WHERE username=?", normalizedUsername);
            Map<String, Object> user = users.get(0);
            Long id = ((Number) user.get("id")).longValue();
            String token = jwtUtil.generateToken(id,
                (String) user.get("username"),
                (String) user.get("role"),
                (String) user.get("display_name"));
            Map<String, Object> result = new HashMap<>();
            result.put("code", 200);
            result.put("token", token);
            Map<String, Object> u = new HashMap<>();
            u.put("id", id);
            u.put("username", user.get("username"));
            u.put("role", user.get("role"));
            u.put("displayName", user.get("display_name"));
            u.put("phone", user.get("phone"));
            result.put("user", u);
            log.info("用户注册成功: username={}, role={}", normalizedUsername, normalizedRole);
            return result;
        } catch (DataAccessException ex) {
            String message = ex.getMostSpecificCause() != null ? ex.getMostSpecificCause().getMessage() : ex.getMessage();
            log.warn("注册时数据库异常: username={}, error={}", username, message);
            if (message != null && message.toLowerCase().contains("duplicate")) {
                return errorMap("用户名已存在");
            }
            if (message != null && message.toLowerCase().contains("not null")) {
                return errorMap("注册参数非法：教师显示名称为空或字段不合法");
            }
            return errorMap("注册失败：参数校验或数据库写入异常");
        } catch (Exception ex) {
            log.error("注册时系统异常: username={}, error={}", username, ex.getMessage(), ex);
            return errorMap("注册失败：" + ex.getMessage());
        }
    }

    public List<Map<String, Object>> getParentChildren(Long parentId) {
        if (parentId == null) {
            return Collections.emptyList();
        }
        String sql = "SELECT s.id, s.name, s.class_id, s.parent_phone, s.status, " +
                "c.name AS class_name, c.course AS class_course, c.teacher_id, " +
                "u.display_name AS teacher_name " +
                "FROM students s " +
                "LEFT JOIN classes c ON c.id = s.class_id " +
                "LEFT JOIN users u ON u.id = c.teacher_id " +
                "WHERE s.parent_id=?";
        if (hasStudentSoftDeleteColumn()) {
            sql += " AND (s.is_deleted IS NULL OR s.is_deleted = 0)";
        }
        sql += " ORDER BY s.name";
        return jdbc.queryForList(sql, parentId);
    }

    public Map<String, Object> bindParent(Long parentId, String studentName, String parentPhone) {
        try {
            if (parentId == null) {
                return errorMap("未登录，无法绑定学生");
            }

            String rawStudentName = studentName == null ? "" : studentName;
            String rawParentPhone = parentPhone == null ? "" : parentPhone;
            log.debug("bindParent: studentName={}, parentPhone={}, parentId={}", rawStudentName, rawParentPhone, parentId);

            String normalizedStudentName = normalizeStudentName(rawStudentName);
            String normalizedParentPhone = normalizePhone(rawParentPhone);

            if (normalizedStudentName.isEmpty()) {
                return errorMap("孩子姓名不能为空");
            }
            if (normalizedParentPhone.isEmpty()) {
                return errorMap("家长手机号不能为空");
            }
            if (!normalizedParentPhone.matches("^1[3-9]\\d{9}$")) {
                return errorMap("手机号格式不正确，请输入正确的11位手机号");
            }

            List<Map<String, Object>> students = findValidStudentsByName(normalizedStudentName);
            if (students.isEmpty()) {
                return errorMap("学生不存在，请核对姓名");
            }

            Map<String, Object> student = students.get(0);
            String storedParentPhone = student.get("parent_phone") == null ? "" : normalizePhone(String.valueOf(student.get("parent_phone")));
            Object existingPid = student.get("parent_id");

            if (!storedParentPhone.isEmpty() && !storedParentPhone.equals(normalizedParentPhone)) {
                return errorMap("手机号不匹配，请联系教师确认");
            }

            if (existingPid != null) {
                Long existingParentId = ((Number) existingPid).longValue();
                if (existingParentId.equals(parentId)) {
                    return errorMap("您已绑定该孩子");
                }
                return errorMap("该学生已被其他家长绑定");
            }

            Long sid = ((Number) student.get("id")).longValue();
            int updated = jdbc.update(
                "UPDATE students SET parent_id=?, parent_user_id=?, parent_phone=?, status='active' WHERE id=? AND (is_deleted IS NULL OR is_deleted = 0)",
                parentId,
                parentId,
                normalizedParentPhone,
                sid
            );
            if (updated == 0) {
                return errorMap("学生状态异常，无法绑定，请联系教师检查学生状态");
            }

            Map<String, Object> result = new HashMap<>();
            result.put("code", 200);
            Map<String, Object> s = new HashMap<>();
            s.put("id", sid);
            s.put("name", student.get("name"));
            result.put("student", s);
            result.put("msg", "绑定成功");
            log.info("家长绑定学生成功: parentId={}, studentId={}", parentId, sid);
            return result;
        } catch (Exception ex) {
            log.error("绑定学生失败: parentId={}, error={}", parentId, ex.getMessage(), ex);
            return errorMap("绑定失败：" + ex.getMessage());
        }
    }

    public Map<String, Object> unbindParent(Long parentId, Long studentId) {
        try {
            if (parentId == null) {
                return errorMap("未登录，无法解绑学生");
            }
            if (studentId == null) {
                return errorMap("缺少学生ID");
            }
            List<Map<String, Object>> students = jdbc.queryForList(
                "SELECT id, parent_id FROM students WHERE id=? AND (is_deleted IS NULL OR is_deleted = 0)", studentId);
            if (students.isEmpty()) {
                return errorMap("学生不存在");
            }
            Object existingPid = students.get(0).get("parent_id");
            if (existingPid == null) {
                return errorMap("该学生尚未绑定家长");
            }
            Long boundId = ((Number) existingPid).longValue();
            if (!boundId.equals(parentId)) {
                return errorMap("无权解绑：该学生未绑定到当前家长账号");
            }
            jdbc.update(
                "UPDATE students SET parent_id=NULL, parent_user_id=NULL WHERE id=?", studentId);
            Map<String, Object> result = new HashMap<>();
            result.put("code", 200);
            result.put("msg", "解绑成功");
            log.info("家长解绑学生成功: parentId={}, studentId={}", parentId, studentId);
            return result;
        } catch (Exception ex) {
            log.error("解绑学生失败: parentId={}, studentId={}, error={}", parentId, studentId, ex.getMessage(), ex);
            return errorMap("解绑失败：" + ex.getMessage());
        }
    }

    private List<Map<String, Object>> findValidStudentsByName(String studentName) {
        String normalized = normalizeStudentName(studentName);
        if (normalized.isEmpty()) {
            return Collections.emptyList();
        }

        List<Map<String, Object>> exact = jdbc.queryForList(
            "SELECT * FROM students WHERE name = ? AND (status IS NULL OR status = 'active') AND (is_deleted IS NULL OR is_deleted = 0) ORDER BY id DESC LIMIT 10",
            normalized);
        if (!exact.isEmpty()) {
            return exact;
        }

        List<Map<String, Object>> allActive = jdbc.queryForList(
            "SELECT * FROM students WHERE (status IS NULL OR status = 'active') AND (is_deleted IS NULL OR is_deleted = 0) ORDER BY id DESC");
        List<Map<String, Object>> matched = new ArrayList<>();
        for (Map<String, Object> s : allActive) {
            String dbName = s.get("name") == null ? "" : normalizeStudentName(String.valueOf(s.get("name")));
            if (dbName.equals(normalized)) {
                matched.add(s);
                if (matched.size() >= 10) break;
            }
        }
        return matched;
    }

    private boolean hasStudentSoftDeleteColumn() {
        try {
            Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'students' AND column_name = 'is_deleted'",
                Integer.class);
            return count != null && count > 0;
        } catch (Exception e) {
            log.debug("检查 students.is_deleted 列失败（可能表不存在或无权限）: {}", e.getMessage());
            return false;
        }
    }

    public static String normalizeStudentName(String value) {
        if (value == null) {
            return "";
        }
        String cleaned = value
            .replace("\u200B", "")
            .replace("\u200C", "")
            .replace("\u200D", "")
            .replace("\u2060", "")
            .replace("\uFEFF", "")
            .replace("\u00A0", "")
            .replace("\u3000", "")
            .replace("\r", "")
            .replace("\n", "")
            .replace("\t", "")
            .replaceAll("[\\s\\p{Zs}]+", "")
            .replaceAll("[\\p{Cntrl}&&[^\\n\\r\\t]]+", "")
            .trim();
        return cleaned;
    }

    public static String normalizePhone(String value) {
        if (value == null) {
            return "";
        }
        String cleaned = value
            .replace("\u200B", "")
            .replace("\u200C", "")
            .replace("\u200D", "")
            .replace("\u2060", "")
            .replace("\uFEFF", "")
            .replace("\u00A0", "")
            .replace("\u3000", "")
            .replaceAll("[\\s\\r\\n\\t\\-()]+", "")
            .replaceAll("[^0-9+]", "")
            .trim();
        if (cleaned.startsWith("+86")) {
            cleaned = cleaned.substring(3);
        }
        return cleaned.replaceAll("\\+", "");
    }

    private Map<String, Object> errorMap(String msg) {
        Map<String, Object> m = new HashMap<>();
        m.put("code", 400);
        m.put("msg", msg);
        m.put("error", msg);
        return m;
    }

    // 获取所有用户列表（仅教师可调用，由Controller校验权限）
    public List<Map<String, Object>> getAllUsers() {
        return jdbc.queryForList("SELECT id, username, display_name, role, phone, created_at FROM users ORDER BY id ASC");
    }

    // 重置用户密码为初始密码（仅教师管理员可调用）
    public Map<String, Object> resetPassword(Long targetUserId, Long operatorId, String operatorRole) {
        try {
            if (operatorId == null) {
                return errorMap("未登录，无法操作");
            }
            if (!"teacher".equals(operatorRole) && !"admin".equals(operatorRole)) {
                Map<String, Object> r = new HashMap<>();
                r.put("code", 403);
                r.put("error", "无权限：仅教师管理员可重置密码");
                r.put("msg", "无权限：仅教师管理员可重置密码");
                return r;
            }
            if (targetUserId == null) {
                return errorMap("缺少用户ID");
            }
            List<Map<String, Object>> users = jdbc.queryForList("SELECT id, username, role FROM users WHERE id=?", targetUserId);
            if (users.isEmpty()) {
                return errorMap("用户不存在");
            }
            String initialPassword = "123456";
            String hash = encoder.encode(initialPassword);
            jdbc.update("UPDATE users SET password_hash=? WHERE id=?", hash, targetUserId);
            operationLogService.log(operatorId, "operator_"+operatorId, operatorRole, "重置密码",
                "重置用户ID="+targetUserId+"的密码为初始密码", null);
            Map<String, Object> result = new HashMap<>();
            result.put("code", 200);
            result.put("msg", "密码已重置为初始密码");
            result.put("data", Map.of("userId", targetUserId, "username", users.get(0).get("username")));
            log.info("密码重置成功: operatorId={}, targetUserId={}", operatorId, targetUserId);
            return result;
        } catch (Exception ex) {
            log.error("重置密码失败: operatorId={}, targetUserId={}, error={}", operatorId, targetUserId, ex.getMessage(), ex);
            return errorMap("重置密码失败：" + ex.getMessage());
        }
    }

    // 修改本人密码（所有角色可用）
    public Map<String, Object> updateMyPassword(Long userId, String oldPassword, String newPassword, String confirmPassword) {
        try {
            if (userId == null) {
                return errorMap("未登录，无法修改密码");
            }
            if (oldPassword == null || oldPassword.trim().isEmpty()) {
                return errorMap("旧密码不能为空");
            }
            if (newPassword == null || newPassword.trim().isEmpty()) {
                return errorMap("新密码不能为空");
            }
            if (newPassword.trim().length() < 6) {
                return errorMap("新密码长度不能少于6位");
            }
            if (newPassword.trim().length() > 100) {
                return errorMap("新密码长度不能超过100位");
            }
            if (confirmPassword == null || !newPassword.trim().equals(confirmPassword.trim())) {
                return errorMap("两次输入的新密码不一致");
            }
            if (oldPassword.trim().equals(newPassword.trim())) {
                return errorMap("新密码不能与旧密码相同");
            }
            List<Map<String, Object>> users = jdbc.queryForList("SELECT * FROM users WHERE id=?", userId);
            if (users.isEmpty()) {
                return errorMap("用户不存在");
            }
            Map<String, Object> user = users.get(0);
            String passwordHash = (String) user.get("password_hash");
            if (passwordHash == null || !encoder.matches(oldPassword.trim(), passwordHash)) {
                return errorMap("旧密码错误");
            }
            String hash = encoder.encode(newPassword.trim());
            jdbc.update("UPDATE users SET password_hash=? WHERE id=?", hash, userId);
            Map<String, Object> result = new HashMap<>();
            result.put("code", 200);
            result.put("msg", "密码修改成功，请使用新密码重新登录");
            log.info("用户修改密码成功: userId={}", userId);
            return result;
        } catch (Exception ex) {
            log.error("修改密码失败: userId={}, error={}", userId, ex.getMessage(), ex);
            return errorMap("修改密码失败：" + ex.getMessage());
        }
    }
}
