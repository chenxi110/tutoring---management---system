package com.skt.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class SigninService {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MessageService messageService;

    /**
     * Teacher creates a signin task. Pushes messages to all parents of students in that class.
     */
    public Map<String, Object> createSignin(Long teacherId, Long classId, String className,
                                             String signType, String password, int durationMinutes) {
        Map<String, Object> result = new HashMap<>();

        if (classId == null) {
            result.put("code", 400);
            result.put("error", "请选择班级");
            return result;
        }
        if (password == null || password.trim().isEmpty()) {
            password = generatePassword();
        }

        // Calculate deadline
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.MINUTE, durationMinutes > 0 ? durationMinutes : 10);
        java.sql.Timestamp deadline = new java.sql.Timestamp(cal.getTimeInMillis());

        // Insert signin task
        jdbc.update("INSERT INTO signins (class_id, class_name, teacher_id, sign_type, password, status, deadline) " +
                        "VALUES (?,?,?,?,?,?,?,?)",
                classId, className, teacherId, signType != null ? signType : "password",
                password, "running", deadline);

        Long signinId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);

        // Find all students in this class and push messages to their parents
        List<Map<String, Object>> students = jdbc.queryForList(
                "SELECT id, name, parent_user_id FROM students WHERE class_id = ? AND is_deleted = 0 AND parent_user_id IS NOT NULL",
                classId);

        int pushedCount = 0;
        for (Map<String, Object> student : students) {
            Long parentUserId = student.get("parent_user_id") != null ?
                    ((Number) student.get("parent_user_id")).longValue() : null;
            if (parentUserId == null) continue;

            String studentName = student.get("name") != null ? String.valueOf(student.get("name")) : "";
            String content = String.format("【签到通知】%s 同学，老师发布了一次%s签到，口令：%s，请在%d分钟内完成签到。",
                    studentName, "qrcode".equals(signType) ? "二维码" : "口令", password, durationMinutes);

            jdbc.update("INSERT INTO messages (sender_id, sender_name, sender_role, receiver_id, student_id, class_id, class_name, title, content, msg_type, status) " +
                            "VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                    teacherId, "教师", "teacher", parentUserId,
                    ((Number) student.get("id")).longValue(), classId, className,
                    "签到通知", content, "signin", "unread");
            messageService.pushSigninNotification(parentUserId, "签到通知", content);
            pushedCount++;
        }

        result.put("code", 200);
        result.put("success", true);
        Map<String, Object> data = new HashMap<>();
        data.put("id", signinId);
        data.put("password", password);
        data.put("deadline", deadline.toString());
        data.put("pushedCount", pushedCount);
        result.put("data", data);
        return result;
    }

    /**
     * Get active signin tasks for a parent (based on their children's classes).
     */
    public List<Map<String, Object>> getActiveSigninsForParent(Long parentUserId) {
        // Get students bound to this parent
        List<Map<String, Object>> students = jdbc.queryForList(
                "SELECT id, name, class_id FROM students WHERE parent_user_id = ? AND is_deleted = 0",
                parentUserId);

        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> student : students) {
            Long studentId = ((Number) student.get("id")).longValue();
            String studentName = String.valueOf(student.get("name"));
            Long classId = student.get("class_id") != null ? ((Number) student.get("class_id")).longValue() : null;
            if (classId == null) continue;

            // Find active signins for this class
            List<Map<String, Object>> signins = jdbc.queryForList(
                    "SELECT * FROM signins WHERE class_id = ? AND status = 'running' AND deadline > NOW() ORDER BY created_at DESC",
                    classId);

            for (Map<String, Object> signin : signins) {
                Map<String, Object> item = new HashMap<>(signin);
                item.put("studentId", studentId);
                item.put("studentName", studentName);

                // Check if already signed
                Long signinId = ((Number) signin.get("id")).longValue();
                List<Map<String, Object>> records = jdbc.queryForList(
                        "SELECT * FROM signin_records WHERE signin_id = ? AND student_id = ?",
                        signinId, studentId);
                item.put("signed", !records.isEmpty());
                if (!records.isEmpty()) {
                    item.put("signStatus", records.get(0).get("status"));
                }
                result.add(item);
            }
        }
        return result;
    }

    /**
     * Parent submits signin for their child. Auth check: parent must be bound to the student.
     */
    public Map<String, Object> submitSignin(Long parentUserId, Long signinId, Long studentId, String password) {
        Map<String, Object> result = new HashMap<>();

        // Validate signin task exists and is active
        List<Map<String, Object>> signins = jdbc.queryForList(
                "SELECT * FROM signins WHERE id = ?", signinId);
        if (signins.isEmpty()) {
            result.put("code", 404);
            result.put("error", "签到任务不存在");
            return result;
        }
        Map<String, Object> signin = signins.get(0);
        String status = String.valueOf(signin.get("status"));
        if (!"running".equals(status)) {
            result.put("code", 400);
            result.put("error", "签到已结束");
            return result;
        }

        // Check deadline
        Object deadlineObj = signin.get("deadline");
        if (deadlineObj != null) {
            java.sql.Timestamp deadline = (java.sql.Timestamp) deadlineObj;
            if (deadline.before(new java.util.Date())) {
                // Auto-expire
                jdbc.update("UPDATE signins SET status = 'expired' WHERE id = ?", signinId);
                result.put("code", 400);
                result.put("error", "签到已过期");
                return result;
            }
        }

        // Validate password
        String signinPassword = String.valueOf(signin.get("password"));
        if (password == null || !password.trim().equalsIgnoreCase(signinPassword.trim())) {
            result.put("code", 400);
            result.put("error", "口令错误");
            return result;
        }

        // Auth check: parent must be bound to this student
        List<Map<String, Object>> students = jdbc.queryForList(
                "SELECT id, name FROM students WHERE id = ? AND parent_user_id = ? AND is_deleted = 0",
                studentId, parentUserId);
        if (students.isEmpty()) {
            result.put("code", 403);
            result.put("error", "无签到权限，该学生未绑定到当前家长账号");
            return result;
        }
        String studentName = String.valueOf(students.get(0).get("name"));

        // Check if already signed
        List<Map<String, Object>> existing = jdbc.queryForList(
                "SELECT * FROM signin_records WHERE signin_id = ? AND student_id = ?",
                signinId, studentId);
        if (!existing.isEmpty()) {
            result.put("code", 400);
            result.put("error", "该学生已签到，请勿重复签到");
            return result;
        }

        // Determine sign status (on time or late)
        java.sql.Timestamp deadline = (java.sql.Timestamp) signin.get("deadline");
        long totalDuration = deadline.getTime() - ((java.sql.Timestamp) signin.get("created_at")).getTime();
        long elapsed = System.currentTimeMillis() - ((java.sql.Timestamp) signin.get("created_at")).getTime();
        String signStatus = elapsed > totalDuration * 0.7 ? "late" : "signed";

        // Insert signin record
        jdbc.update("INSERT INTO signin_records (signin_id, student_id, student_name, parent_id, status, signed_at) " +
                        "VALUES (?,?,?,?,?,?)",
                signinId, studentId, studentName, parentUserId, signStatus, new java.sql.Timestamp(System.currentTimeMillis()));

        result.put("code", 200);
        result.put("success", true);
        result.put("signStatus", signStatus);
        result.put("studentName", studentName);
        return result;
    }

    /**
     * Get signin records for a teacher (all records for their signins).
     */
    public List<Map<String, Object>> getSigninRecords(Long teacherId, Long signinId) {
        if (signinId != null) {
            // Get records for a specific signin task
            return jdbc.queryForList(
                    "SELECT sr.*, s.class_name, s.sign_type, s.password, s.deadline, s.status as task_status " +
                            "FROM signin_records sr JOIN signins s ON sr.signin_id = s.id " +
                            "WHERE s.id = ? ORDER BY sr.signed_at DESC",
                    signinId);
        }
        // Get all signin tasks for this teacher
        return jdbc.queryForList(
                "SELECT * FROM signins WHERE teacher_id = ? ORDER BY created_at DESC",
                teacherId);
    }

    /**
     * Get signin history for a parent (all past signins for their children).
     */
    public List<Map<String, Object>> getParentSigninHistory(Long parentUserId) {
        return jdbc.queryForList(
                "SELECT sr.*, s.class_name, s.sign_type, s.deadline, s.status as task_status " +
                        "FROM signin_records sr JOIN signins s ON sr.signin_id = s.id " +
                        "WHERE sr.parent_id = ? ORDER BY sr.signed_at DESC",
                parentUserId);
    }

    /**
     * Teacher stops a signin task.
     */
    public Map<String, Object> stopSignin(Long teacherId, Long signinId) {
        Map<String, Object> result = new HashMap<>();
        int updated = jdbc.update("UPDATE signins SET status = 'stopped' WHERE id = ? AND teacher_id = ?",
                signinId, teacherId);
        if (updated == 0) {
            result.put("code", 404);
            result.put("error", "签到任务不存在或无权限");
            return result;
        }
        result.put("code", 200);
        result.put("success", true);
        return result;
    }

    /**
     * Auto-expire overdue signins (called on query).
     */
    public void expireOverdueSignins() {
        try {
            jdbc.update("UPDATE signins SET status = 'expired' WHERE status = 'running' AND deadline < NOW()");
        } catch (Exception e) {
            // ignore
        }
    }

    private String generatePassword() {
        String chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        Random rnd = new Random();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 4; i++) {
            sb.append(chars.charAt(rnd.nextInt(chars.length())));
        }
        return sb.toString();
    }
}
