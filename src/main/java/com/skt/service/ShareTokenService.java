package com.skt.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class ShareTokenService {

    @Autowired
    private JdbcTemplate jdbc;

    public Map<String, Object> createToken(Long studentId, boolean isPermanent, Integer validDays, Long createdBy) {
        List<Map<String, Object>> students = jdbc.queryForList(
            "SELECT id, name FROM students WHERE id=? AND (is_deleted IS NULL OR is_deleted=0)", studentId);
        if (students.isEmpty()) {
            return errorMap("学生不存在或已删除");
        }

        String token = UUID.randomUUID().toString().replace("-", "") + System.currentTimeMillis();
        java.sql.Timestamp expiresAt = null;
        if (!isPermanent) {
            int days = validDays != null && validDays > 0 ? validDays : 7;
            Calendar cal = Calendar.getInstance();
            cal.add(Calendar.DAY_OF_MONTH, days);
            expiresAt = new java.sql.Timestamp(cal.getTimeInMillis());
        }

        jdbc.update("INSERT INTO share_tokens (token, student_id, is_permanent, expires_at, created_by) VALUES (?,?,?,?,?)",
            token, studentId, isPermanent ? 1 : 0, expiresAt, createdBy);

        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("token", token);
        result.put("isPermanent", isPermanent);
        result.put("expiresAt", expiresAt);
        return result;
    }

    public Map<String, Object> validateToken(String token) {
        if (token == null || token.trim().isEmpty()) {
            return errorMap("分享链接无效");
        }

        List<Map<String, Object>> tokens = jdbc.queryForList(
            "SELECT * FROM share_tokens WHERE token=?", token.trim());
        if (tokens.isEmpty()) {
            return errorMap("分享链接不存在");
        }

        Map<String, Object> tokenRow = tokens.get(0);
        int isPermanent = tokenRow.get("is_permanent") != null ? ((Number) tokenRow.get("is_permanent")).intValue() : 0;

        if (isPermanent == 0) {
            Object expiresAtObj = tokenRow.get("expires_at");
            if (expiresAtObj == null) {
                return errorMap("分享链接已失效");
            }
            java.sql.Timestamp expiresAt;
            if (expiresAtObj instanceof java.sql.Timestamp) {
                expiresAt = (java.sql.Timestamp) expiresAtObj;
            } else {
                expiresAt = new java.sql.Timestamp(((java.util.Date) expiresAtObj).getTime());
            }
            if (expiresAt.before(new Date())) {
                return errorMap("分享链接已过期");
            }
        }

        Long studentId = ((Number) tokenRow.get("student_id")).longValue();
        List<Map<String, Object>> students = jdbc.queryForList(
            "SELECT s.*, c.name as class_name, c.course as class_course FROM students s LEFT JOIN classes c ON s.class_id=c.id WHERE s.id=?",
            studentId);
        if (students.isEmpty()) {
            return errorMap("学生信息不存在");
        }

        Map<String, Object> student = students.get(0);
        Object isDeleted = student.get("is_deleted");
        if (isDeleted != null && ((Number) isDeleted).intValue() == 1) {
            return errorMap("该学生信息已被删除，分享链接已失效");
        }

        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("student", buildStudentShareInfo(studentId, student));
        return result;
    }

    private Map<String, Object> buildStudentShareInfo(Long studentId, Map<String, Object> student) {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("name", student.get("name"));
        info.put("className", student.get("class_name"));
        info.put("classCourse", student.get("class_course"));
        info.put("enrollmentDate", student.get("enrollment_date"));

        List<Map<String, Object>> grades = jdbc.queryForList(
            "SELECT exam_name, exam_type, score, total_score, remark, created_at FROM grades WHERE student_id=? ORDER BY created_at DESC",
            studentId);
        info.put("grades", grades);

        List<Map<String, Object>> records = jdbc.queryForList(
            "SELECT date, class_name, course, sessions, type, remark FROM records WHERE class_id=? ORDER BY date DESC LIMIT 30",
            student.get("class_id"));
        info.put("records", records);

        List<Map<String, Object>> homework = jdbc.queryForList(
            "SELECT h.title, h.content, h.deadline, hs.submitted_at, hs.score, hs.comment FROM homework h LEFT JOIN homework_submissions hs ON hs.homework_id=h.id AND hs.student_id=? WHERE h.class_id=? ORDER BY h.created_at DESC",
            studentId, student.get("class_id"));
        info.put("homework", homework);

        return info;
    }

    public List<Map<String, Object>> listTokensByStudent(Long studentId) {
        return jdbc.queryForList(
            "SELECT id, token, student_id, is_permanent, expires_at, created_at FROM share_tokens WHERE student_id=? ORDER BY created_at DESC",
            studentId);
    }

    public Map<String, Object> deleteToken(Long tokenId) {
        int updated = jdbc.update("DELETE FROM share_tokens WHERE id=?", tokenId);
        if (updated == 0) {
            return errorMap("分享链接不存在");
        }
        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        return result;
    }

    public void deleteTokensByStudent(Long studentId) {
        jdbc.update("DELETE FROM share_tokens WHERE student_id=?", studentId);
    }

    private Map<String, Object> errorMap(String msg) {
        Map<String, Object> m = new HashMap<>();
        m.put("code", 400);
        m.put("msg", msg);
        m.put("error", msg);
        return m;
    }
}
