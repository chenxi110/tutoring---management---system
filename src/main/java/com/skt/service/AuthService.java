package com.skt.service;

import com.skt.security.JwtUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataAccessException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class AuthService {

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private JwtUtil jwtUtil;

    private BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    public Map<String, Object> login(String username, String password) {
        List<Map<String, Object>> users = jdbc.queryForList(
            "SELECT * FROM users WHERE username=?", username);
        if (users.isEmpty()) {
            return errorMap("用户不存在");
        }
        Map<String, Object> user = users.get(0);
        if (!encoder.matches(password, (String) user.get("password_hash"))) {
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
        return result;
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
            return result;
        } catch (DataAccessException ex) {
            String message = ex.getMostSpecificCause() != null ? ex.getMostSpecificCause().getMessage() : ex.getMessage();
            if (message != null && message.toLowerCase().contains("duplicate")) {
                return errorMap("用户名已存在");
            }
            if (message != null && message.toLowerCase().contains("not null")) {
                return errorMap("注册参数非法：教师显示名称为空或字段不合法");
            }
            return errorMap("注册失败：参数校验或数据库写入异常");
        } catch (Exception ex) {
            return errorMap("注册失败：" + ex.getMessage());
        }
    }

    public List<Map<String, Object>> getParentChildren(Long parentId) {
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
            System.out.println("[AuthService.bindParent] raw studentName=" + rawStudentName + ", raw parentPhone=" + rawParentPhone + ", parentId=" + parentId);

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
            return result;
        } catch (Exception ex) {
            System.err.println("[AuthService.bindParent] error: " + ex.getMessage());
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
            return result;
        } catch (Exception ex) {
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

    private String studentDeleteFilter() {
        if (hasStudentSoftDeleteColumn()) {
            return "(is_deleted IS NULL OR is_deleted = 0)";
        }
        return "(1=1)";
    }

    private boolean hasStudentSoftDeleteColumn() {
        try {
            Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'students' AND column_name = 'is_deleted'",
                Integer.class);
            return count != null && count > 0;
        } catch (Exception e) {
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
}