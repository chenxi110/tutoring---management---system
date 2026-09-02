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
            // 账号禁用检查：status=0 禁止登录
            Object statusObj = user.get("status");
            if (statusObj != null) {
                int st;
                try { st = ((Number) statusObj).intValue(); } catch (Exception ignore) { st = 1; }
                if (st == 0) {
                    log.warn("登录被拒绝：账号已禁用 username={}", user.get("username"));
                    return errorMap("账号已被禁用，请联系管理员");
                }
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
            if (!"teacher".equals(normalizedRole) && !"parent".equals(normalizedRole) && !"student".equals(normalizedRole)) {
                return errorMap("角色参数非法");
            }
            if (("parent".equals(normalizedRole) || "student".equals(normalizedRole)) && (displayName == null || displayName.trim().isEmpty())) {
                return errorMap("家长/学生角色必须填写显示名称");
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

    /** 学生角色按 students.user_id 取本人记录（形状与家长 children 一致，便于前端复用家长页面） */
    public List<Map<String, Object>> getSelfStudentChild(Long userId) {
        if (userId == null) {
            return Collections.emptyList();
        }
        String sql = "SELECT s.id, s.name, s.class_id, s.parent_phone, s.status, " +
                "c.name AS class_name, c.course AS class_course, c.teacher_id, " +
                "u.display_name AS teacher_name " +
                "FROM students s " +
                "LEFT JOIN classes c ON c.id = s.class_id " +
                "LEFT JOIN users u ON u.id = c.teacher_id " +
                "WHERE s.user_id=?";
        if (hasStudentSoftDeleteColumn()) {
            sql += " AND (s.is_deleted IS NULL OR s.is_deleted = 0)";
        }
        return jdbc.queryForList(sql, userId);
    }

    /** 家长查看自己孩子的学生账号（仅 parent；按 parent_user_id 隔离） */
    public Map<String, Object> getParentChildrenAccounts(Long parentId) {
        Map<String, Object> result = new HashMap<>();
        try {
            if (parentId == null) return errorMap("未登录，无法操作");
            List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT s.id AS student_id, s.name, s.parent_phone, s.phone, u.username, u.id AS user_id, u.status " +
                "FROM students s LEFT JOIN users u ON u.id = s.user_id " +
                "WHERE s.parent_user_id=? AND (s.is_deleted IS NULL OR s.is_deleted=0) ORDER BY s.name", parentId);
            List<Map<String, Object>> data = new ArrayList<>();
            for (Map<String, Object> r : rows) {
                Map<String, Object> item = new HashMap<>();
                item.put("studentId", r.get("student_id"));
                item.put("name", r.get("name"));
                item.put("phone", r.get("phone"));
                item.put("parentPhone", r.get("parent_phone"));
                item.put("accountExists", r.get("username") != null);
                item.put("username", r.get("username"));
                item.put("userId", r.get("user_id"));
                data.add(item);
            }
            result.put("code", 200);
            result.put("data", data);
            return result;
        } catch (Exception ex) {
            log.error("获取孩子账号失败: parentId={}, error={}", parentId, ex.getMessage(), ex);
            return errorMap("获取孩子账号失败：" + ex.getMessage());
        }
    }

    /** 家长修改自己孩子的学生账号密码（仅 parent；校验 parent_user_id 关联，越权拦截） */
    public Map<String, Object> changeChildStudentPassword(Long parentId, Long studentId, String newPassword) {
        try {
            if (parentId == null) return errorMap("未登录，无法操作");
            if (studentId == null) return errorMap("缺少学生ID");
            if (newPassword == null || newPassword.trim().length() < 6) {
                return errorMap("新密码至少6位");
            }
            List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, name, user_id FROM students WHERE id=? AND parent_user_id=? AND (is_deleted IS NULL OR is_deleted=0)",
                studentId, parentId);
            if (rows.isEmpty()) {
                Map<String, Object> r = new HashMap<>();
                r.put("code", 403);
                r.put("error", "无权限：只能修改自己孩子的密码");
                r.put("msg", "无权限：只能修改自己孩子的密码");
                return r;
            }
            Object userIdObj = rows.get(0).get("user_id");
            if (userIdObj == null) {
                return errorMap("该孩子暂无学生登录账号，请先重新绑定生成账号");
            }
            Long userId = ((Number) userIdObj).longValue();
            String hash = encoder.encode(newPassword.trim());
            jdbc.update("UPDATE users SET password_hash=? WHERE id=? AND role='student'", hash, userId);
            operationLogService.log(parentId, "operator_" + parentId, "parent", "家长修改孩子密码",
                "家长修改孩子学生账号密码 studentId=" + studentId, null);
            Map<String, Object> result = new HashMap<>();
            result.put("code", 200);
            result.put("msg", "密码修改成功");
            result.put("data", Map.of("studentId", studentId, "name", rows.get(0).get("name")));
            log.info("家长修改孩子密码成功: parentId={}, studentId={}", parentId, studentId);
            return result;
        } catch (Exception ex) {
            log.error("家长修改孩子密码失败: parentId={}, studentId={}, error={}", parentId, studentId, ex.getMessage(), ex);
            return errorMap("修改密码失败：" + ex.getMessage());
        }
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
            // 自动为该学生创建 student 角色登录账号并写入 students.user_id（家长绑定孩子后孩子即可登录学生端）
            Map<String, Object> stuAcc = ensureStudentAccountForStudent(sid, String.valueOf(student.get("name")));
            if (stuAcc != null) {
                result.put("studentAccount", stuAcc);
            }
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

    // ==================== 学生账号自动创建 / 补建 / 删除 ====================

    /** 为该学生确保存在一个 student 角色登录账号，并写入 students.user_id。
     *  账号命名：孩子姓名 + 孩子绑定的手机号（如 张三13800138000）；孩子 phone 为空则回退 parent_phone，
     *  再为空则用 姓名_学生ID 兜底；重名/重号自动加序号后缀保证唯一。初始密码 123456。
     *  返回 {username, password, accountCreated, userId}；若已存在则返回既有账号且 accountCreated=false。 */
    public Map<String, Object> ensureStudentAccountForStudent(Long studentId, String studentName) {
        if (studentId == null) return null;
        try {
            List<Map<String, Object>> st = jdbc.queryForList(
                "SELECT id, user_id, phone, parent_phone FROM students WHERE id=? AND (is_deleted IS NULL OR is_deleted=0)", studentId);
            if (st.isEmpty()) return null;
            Object existingUserId = st.get(0).get("user_id");
            if (existingUserId != null) {
                Long uid = ((Number) existingUserId).longValue();
                List<Map<String, Object>> u = jdbc.queryForList("SELECT username FROM users WHERE id=?", uid);
                if (!u.isEmpty()) {
                    Map<String, Object> r = new HashMap<>();
                    r.put("username", u.get(0).get("username"));
                    r.put("password", null);
                    r.put("accountCreated", false);
                    r.put("userId", uid);
                    return r;
                }
            }
            String base = buildStudentUsername(studentName, st.get(0));
            String username = base;
            Long uid = null;
            List<Map<String, Object>> existing = jdbc.queryForList("SELECT id FROM users WHERE username=?", base);
            if (!existing.isEmpty()) {
                // 优先复用未被任何学生绑定的 student 角色遗留账号（解绑后遗留，且同名）
                List<Map<String, Object>> reusable = jdbc.queryForList(
                    "SELECT id FROM users WHERE username=? AND role='student' AND " +
                    "id NOT IN (SELECT user_id FROM students WHERE user_id IS NOT NULL AND (is_deleted IS NULL OR is_deleted=0))",
                    base);
                if (!reusable.isEmpty()) {
                    uid = ((Number) reusable.get(0).get("id")).longValue();
                } else {
                    for (int i = 1; i < 100; i++) {
                        String cand = base + "_" + i;
                        List<Map<String, Object>> dup = jdbc.queryForList("SELECT id FROM users WHERE username=?", cand);
                        if (dup.isEmpty()) { username = cand; break; }
                    }
                }
            }
            if (uid == null) {
                String phone = st.get(0).get("phone") == null ? null : String.valueOf(st.get(0).get("phone"));
                String hash = encoder.encode("123456");
                jdbc.update("INSERT INTO users (username, password_hash, role, display_name, phone, status) VALUES (?,?,?,?,?,1)",
                    username, hash, "student", studentName == null ? "" : studentName, phone);
                uid = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
                log.info("自动创建学生账号: studentId={}, username={}", studentId, username);
            }
            jdbc.update("UPDATE students SET user_id=? WHERE id=?", uid, studentId);
            Map<String, Object> r = new HashMap<>();
            r.put("username", username);
            r.put("password", "123456");
            r.put("accountCreated", true);
            r.put("userId", uid);
            return r;
        } catch (Exception e) {
            log.error("自动创建学生账号失败: studentId={}, error={}", studentId, e.getMessage(), e);
            return null;
        }
    }

    /** 生成学生账号名：孩子姓名 + 孩子绑定的手机号；phone 空则回退 parent_phone；再空则 姓名_学生ID 兜底 */
    private String buildStudentUsername(String studentName, Map<String, Object> row) {
        String name = (studentName == null ? "" : String.valueOf(studentName)).trim();
        String phone = row.get("phone") == null ? "" : String.valueOf(row.get("phone")).trim();
        if (phone.isEmpty() && row.get("parent_phone") != null) {
            phone = String.valueOf(row.get("parent_phone")).trim();
        }
        phone = phone.replaceAll("\\D", "");
        if (!phone.isEmpty()) {
            return name + phone;
        }
        Object sid = row.get("id");
        return name + "_" + (sid == null ? "x" : sid);
    }

    /** 为「已绑定家长但尚无学生账号」的存量学生补建 student 账号（仅管理员） */
    public Map<String, Object> backfillStudentAccounts(Long operatorId, String operatorRole) {
        Map<String, Object> result = new HashMap<>();
        try {
            if (operatorId == null) return errorMap("未登录，无法操作");
            if (!"admin".equals(operatorRole)) {
                result.put("code", 403);
                result.put("error", "无权限：仅管理员可执行学生账号补建");
                result.put("msg", "无权限：仅管理员可执行学生账号补建");
                return result;
            }
            List<Map<String, Object>> targets = jdbc.queryForList(
                "SELECT id, name FROM students WHERE parent_user_id IS NOT NULL AND user_id IS NULL " +
                "AND (is_deleted IS NULL OR is_deleted=0) ORDER BY id");
            List<Map<String, Object>> created = new ArrayList<>();
            int skipped = 0;
            for (Map<String, Object> t : targets) {
                Long sid = ((Number) t.get("id")).longValue();
                Map<String, Object> acc = ensureStudentAccountForStudent(sid, String.valueOf(t.get("name")));
                if (acc == null) { skipped++; continue; }
                Map<String, Object> item = new HashMap<>();
                item.put("studentId", sid);
                item.put("studentName", t.get("name"));
                item.put("username", acc.get("username"));
                item.put("password", acc.get("password"));
                item.put("accountCreated", acc.get("accountCreated"));
                created.add(item);
            }
            result.put("code", 200);
            result.put("data", created);
            result.put("count", created.size());
            result.put("skipped", skipped);
            result.put("msg", "学生账号补建完成，共补建 " + created.size() + " 个账号");
            operationLogService.log(operatorId, "operator_" + operatorId, operatorRole, "学生账号补建",
                "为已绑定家长但无账号的存量学生补建账号 " + created.size() + " 个", null);
            return result;
        } catch (Exception e) {
            log.error("学生账号补建失败: operatorId={}, error={}", operatorId, e.getMessage(), e);
            return errorMap("学生账号补建失败：" + e.getMessage());
        }
    }

    // 删除用户（仅 admin，不可删除 admin 与自身；同步清理 students.user_id 引用）
    public Map<String, Object> deleteUser(Long targetUserId, Long operatorId, String operatorRole) {
        try {
            if (operatorId == null) return errorMap("未登录，无法操作");
            if (!"admin".equals(operatorRole)) {
                Map<String, Object> r = new HashMap<>();
                r.put("code", 403);
                r.put("error", "无权限：仅管理员可删除用户");
                r.put("msg", "无权限：仅管理员可删除用户");
                return r;
            }
            if (targetUserId == null) return errorMap("缺少用户ID");
            if (targetUserId.equals(operatorId)) return errorMap("不能删除管理员自己");
            List<Map<String, Object>> users = jdbc.queryForList("SELECT id, username, role FROM users WHERE id=?", targetUserId);
            if (users.isEmpty()) return errorMap("用户不存在");
            String curRole = users.get(0).get("role") == null ? "" : String.valueOf(users.get(0).get("role"));
            if ("admin".equals(curRole)) return errorMap("管理员账号不可删除");
            // 清理学生绑定引用
            jdbc.update("UPDATE students SET user_id=NULL WHERE user_id=? AND (is_deleted IS NULL OR is_deleted=0)", targetUserId);
            jdbc.update("DELETE FROM users WHERE id=?", targetUserId);
            operationLogService.log(operatorId, "operator_" + operatorId, operatorRole, "删除用户",
                "删除用户 ID=" + targetUserId + " username=" + users.get(0).get("username") + " role=" + curRole, null);
            Map<String, Object> result = new HashMap<>();
            result.put("code", 200);
            result.put("msg", "用户已删除");
            log.info("管理员删除用户: operatorId={}, targetUserId={}", operatorId, targetUserId);
            return result;
        } catch (Exception ex) {
            log.error("删除用户失败: operatorId={}, targetUserId={}, error={}", operatorId, targetUserId, ex.getMessage(), ex);
            return errorMap("删除用户失败：" + ex.getMessage());
        }
    }

    /** 根据学生账号(user_id)解析对应 students.id */
    public Long getStudentIdByUserId(Long userId) {
        if (userId == null) return null;
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id FROM students WHERE user_id=? AND (is_deleted IS NULL OR is_deleted=0) LIMIT 1", userId);
            return rows.isEmpty() ? null : ((Number) rows.get(0).get("id")).longValue();
        } catch (Exception e) {
            return null;
        }
    }

    /** 根据学生账号(user_id)返回 students 行（id/name/class_id 等），用于学生端数据隔离 */
    public Map<String, Object> getStudentRowByUserId(Long userId) {
        if (userId == null) return null;
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, name, class_id FROM students WHERE user_id=? AND (is_deleted IS NULL OR is_deleted=0) LIMIT 1", userId);
            return rows.isEmpty() ? null : rows.get(0);
        } catch (Exception e) {
            return null;
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
        return jdbc.queryForList("SELECT id, username, display_name, role, phone, created_at, status FROM users ORDER BY id ASC");
    }

    // 重置用户密码为初始密码（仅教师管理员可调用）
    public Map<String, Object> resetPassword(Long targetUserId, Long operatorId, String operatorRole) {
        try {
            if (operatorId == null) {
                return errorMap("未登录，无法操作");
            }
            if (!"admin".equals(operatorRole)) {
                Map<String, Object> r = new HashMap<>();
                r.put("code", 403);
                r.put("error", "无权限：仅管理员可重置密码");
                r.put("msg", "无权限：仅管理员可重置密码");
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

    // 管理员为指定用户设置自定义新密码（仅 admin）
    public Map<String, Object> adminSetPassword(Long targetUserId, String newPassword, Long operatorId, String operatorRole) {
        try {
            if (operatorId == null) {
                return errorMap("未登录，无法操作");
            }
            if (!"admin".equals(operatorRole)) {
                Map<String, Object> r = new HashMap<>();
                r.put("code", 403);
                r.put("error", "无权限：仅管理员可修改用户密码");
                r.put("msg", "无权限：仅管理员可修改用户密码");
                return r;
            }
            if (targetUserId == null) {
                return errorMap("缺少用户ID");
            }
            if (newPassword == null || newPassword.trim().isEmpty()) {
                return errorMap("新密码不能为空");
            }
            if (newPassword.length() < 6) {
                return errorMap("新密码至少6位");
            }
            List<Map<String, Object>> users = jdbc.queryForList("SELECT id, username, role FROM users WHERE id=?", targetUserId);
            if (users.isEmpty()) {
                return errorMap("用户不存在");
            }
            String hash = encoder.encode(newPassword);
            jdbc.update("UPDATE users SET password_hash=? WHERE id=?", hash, targetUserId);
            operationLogService.log(operatorId, "operator_" + operatorId, operatorRole, "修改密码",
                "管理员为用户ID=" + targetUserId + "设置自定义新密码", null);
            Map<String, Object> result = new HashMap<>();
            result.put("code", 200);
            result.put("msg", "密码修改成功");
            result.put("data", Map.of("userId", targetUserId, "username", users.get(0).get("username")));
            log.info("管理员修改用户密码成功: operatorId={}, targetUserId={}", operatorId, targetUserId);
            return result;
        } catch (Exception ex) {
            log.error("管理员修改用户密码失败: operatorId={}, targetUserId={}, error={}", operatorId, targetUserId, ex.getMessage(), ex);
            return errorMap("修改密码失败：" + ex.getMessage());
        }
    }

    // 管理员新增用户（仅 admin）
    public Map<String, Object> createUser(String username, String password, String role, String displayName, String phone, Long operatorId, String operatorRole) {
        try {
            if (operatorId == null) {
                return errorMap("未登录，无法操作");
            }
            if (!"admin".equals(operatorRole)) {
                Map<String, Object> r = new HashMap<>();
                r.put("code", 403);
                r.put("error", "无权限：仅管理员可新增用户");
                r.put("msg", "无权限：仅管理员可新增用户");
                return r;
            }
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
            if (!"teacher".equals(normalizedRole) && !"parent".equals(normalizedRole) && !"student".equals(normalizedRole)) {
                return errorMap("角色参数非法");
            }
            if (("parent".equals(normalizedRole) || "student".equals(normalizedRole)) && (displayName == null || displayName.trim().isEmpty())) {
                return errorMap((normalizedRole.equals("parent") ? "家长" : "学生") + "角色必须填写显示名称");
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
            jdbc.update("INSERT INTO users (username, password_hash, role, display_name, phone, status) VALUES (?,?,?,?,?,1)",
                normalizedUsername, hash, normalizedRole, normalizedDisplayName, normalizedPhone);
            operationLogService.log(operatorId, "operator_" + operatorId, operatorRole, "新增用户",
                "新增用户 username=" + normalizedUsername + " role=" + normalizedRole, null);
            Map<String, Object> result = new HashMap<>();
            result.put("code", 200);
            result.put("msg", "用户创建成功");
            result.put("data", Map.of("username", normalizedUsername, "role", normalizedRole));
            log.info("管理员新增用户: operatorId={}, username={}, role={}", operatorId, normalizedUsername, normalizedRole);
            return result;
        } catch (DataAccessException ex) {
            String message = ex.getMostSpecificCause() != null ? ex.getMostSpecificCause().getMessage() : ex.getMessage();
            log.warn("新增用户数据库异常: username={}, error={}", username, message);
            if (message != null && message.toLowerCase().contains("duplicate")) {
                return errorMap("用户名已存在");
            }
            return errorMap("新增用户失败：数据库写入异常");
        } catch (Exception ex) {
            log.error("新增用户失败: operatorId={}, error={}", operatorId, ex.getMessage(), ex);
            return errorMap("新增用户失败：" + ex.getMessage());
        }
    }

    // 管理员编辑用户（仅 admin，不改 admin 账号自身）
    public Map<String, Object> updateUser(Long targetUserId, String displayName, String phone, String role, Long operatorId, String operatorRole) {
        try {
            if (operatorId == null) {
                return errorMap("未登录，无法操作");
            }
            if (!"admin".equals(operatorRole)) {
                Map<String, Object> r = new HashMap<>();
                r.put("code", 403);
                r.put("error", "无权限：仅管理员可编辑用户");
                r.put("msg", "无权限：仅管理员可编辑用户");
                return r;
            }
            if (targetUserId == null) {
                return errorMap("缺少用户ID");
            }
            List<Map<String, Object>> users = jdbc.queryForList("SELECT id, username, role FROM users WHERE id=?", targetUserId);
            if (users.isEmpty()) {
                return errorMap("用户不存在");
            }
            String curRole = users.get(0).get("role") == null ? "" : String.valueOf(users.get(0).get("role"));
            if ("admin".equals(curRole)) {
                return errorMap("管理员账号不可编辑");
            }
            String normalizedRole = role == null || role.trim().isEmpty() ? curRole : role.trim();
            if (!"teacher".equals(normalizedRole) && !"parent".equals(normalizedRole) && !"student".equals(normalizedRole)) {
                return errorMap("角色参数非法");
            }
            String normalizedDisplayName = displayName == null ? "" : displayName.trim();
            String normalizedPhone = normalizePhone(phone);
            if (normalizedPhone != null && !normalizedPhone.isEmpty() && !normalizedPhone.matches("^1[3-9]\\d{9}$")) {
                return errorMap("手机号格式不正确，请输入正确的11位手机号");
            }
            if (normalizedDisplayName.length() > 50) {
                return errorMap("姓名长度不能超过50个字符");
            }
            jdbc.update("UPDATE users SET display_name=?, phone=?, role=? WHERE id=?", normalizedDisplayName, normalizedPhone, normalizedRole, targetUserId);
            operationLogService.log(operatorId, "operator_" + operatorId, operatorRole, "编辑用户",
                "编辑用户 ID=" + targetUserId + " username=" + users.get(0).get("username"), null);
            Map<String, Object> result = new HashMap<>();
            result.put("code", 200);
            result.put("msg", "用户信息更新成功");
            log.info("管理员编辑用户: operatorId={}, targetUserId={}", operatorId, targetUserId);
            return result;
        } catch (Exception ex) {
            log.error("编辑用户失败: operatorId={}, targetUserId={}, error={}", operatorId, targetUserId, ex.getMessage(), ex);
            return errorMap("编辑用户失败：" + ex.getMessage());
        }
    }

    // 管理员启用/禁用用户（仅 admin，禁止操作 admin 账号自身）
    public Map<String, Object> toggleUserStatus(Long targetUserId, Integer status, Long operatorId, String operatorRole) {
        try {
            if (operatorId == null) {
                return errorMap("未登录，无法操作");
            }
            if (!"admin".equals(operatorRole)) {
                Map<String, Object> r = new HashMap<>();
                r.put("code", 403);
                r.put("error", "无权限：仅管理员可操作账号状态");
                r.put("msg", "无权限：仅管理员可操作账号状态");
                return r;
            }
            if (targetUserId == null) {
                return errorMap("缺少用户ID");
            }
            if (status == null || (status != 0 && status != 1)) {
                return errorMap("状态参数非法");
            }
            if (targetUserId.equals(operatorId)) {
                return errorMap("不能禁用管理员自己");
            }
            List<Map<String, Object>> users = jdbc.queryForList("SELECT id, username, role FROM users WHERE id=?", targetUserId);
            if (users.isEmpty()) {
                return errorMap("用户不存在");
            }
            String curRole = users.get(0).get("role") == null ? "" : String.valueOf(users.get(0).get("role"));
            if ("admin".equals(curRole)) {
                return errorMap("管理员账号不可禁用");
            }
            jdbc.update("UPDATE users SET status=? WHERE id=?", status, targetUserId);
            operationLogService.log(operatorId, "operator_" + operatorId, operatorRole, "账号状态",
                (status == 1 ? "启用" : "禁用") + "用户 ID=" + targetUserId + " username=" + users.get(0).get("username"), null);
            Map<String, Object> result = new HashMap<>();
            result.put("code", 200);
            result.put("msg", status == 1 ? "账号已启用" : "账号已禁用");
            log.info("管理员{}用户: operatorId={}, targetUserId={}", status == 1 ? "启用" : "禁用", operatorId, targetUserId);
            return result;
        } catch (Exception ex) {
            log.error("操作账号状态失败: operatorId={}, targetUserId={}, error={}", operatorId, targetUserId, ex.getMessage(), ex);
            return errorMap("操作失败：" + ex.getMessage());
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
