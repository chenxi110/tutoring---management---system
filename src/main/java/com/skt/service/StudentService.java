package com.skt.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class StudentService {

    @Autowired
    private JdbcTemplate jdbc;

    // 获取学生个人信息
    public Map<String, Object> getStudentInfo(Long userId) {
        List<Map<String, Object>> students = jdbc.queryForList(
            "SELECT s.*, c.name as class_name, c.course as class_course, u.display_name as teacher_name " +
            "FROM students s LEFT JOIN classes c ON c.id=s.class_id LEFT JOIN users u ON u.id=c.teacher_id " +
            "WHERE s.user_id=? AND (s.is_deleted IS NULL OR s.is_deleted=0)", userId);
        if (students.isEmpty()) return null;
        return students.get(0);
    }

    // 获取学生成绩列表
    public List<Map<String, Object>> getStudentGrades(Long studentId) {
        return jdbc.queryForList(
            "SELECT * FROM grades WHERE student_id=? ORDER BY created_at DESC", studentId);
    }

    // 获取学生作业列表
    public List<Map<String, Object>> getStudentHomework(Long studentId, Long classId) {
        return jdbc.queryForList(
            "SELECT h.*, hs.content as submit_content, hs.score, hs.comment, hs.submitted_at, hs.graded_at " +
            "FROM homework h LEFT JOIN homework_submissions hs ON hs.homework_id=h.id AND hs.student_id=? " +
            "WHERE h.class_id=? ORDER BY h.created_at DESC", studentId, classId);
    }

    // 获取学生出勤记录
    public List<Map<String, Object>> getStudentAttendance(Long studentId) {
        return jdbc.queryForList(
            "SELECT * FROM records WHERE student_id=? ORDER BY date DESC", studentId);
    }

    // 获取学生课堂行为统计
    public Map<String, Object> getStudentBehaviorStats(Long studentId, Long classId) {
        Map<String, Object> stats = new HashMap<>();
        // 签到次数
        Integer signinCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM classroom_behavior WHERE student_id=? AND class_id=? AND behavior_type='signin'",
            Integer.class, studentId, classId);
        stats.put("signinCount", signinCount != null ? signinCount : 0);
        // 举手次数
        Integer raiseHandCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM classroom_behavior WHERE student_id=? AND class_id=? AND behavior_type='raise_hand'",
            Integer.class, studentId, classId);
        stats.put("raiseHandCount", raiseHandCount != null ? raiseHandCount : 0);
        // 答题次数
        Integer answerCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM classroom_behavior WHERE student_id=? AND class_id=? AND behavior_type='answer'",
            Integer.class, studentId, classId);
        stats.put("answerCount", answerCount != null ? answerCount : 0);
        // 总积分
        Integer totalScore = jdbc.queryForObject(
            "SELECT COALESCE(SUM(score_change),0) FROM classroom_behavior WHERE student_id=? AND class_id=?",
            Integer.class, studentId, classId);
        stats.put("totalScore", totalScore != null ? totalScore : 0);
        return stats;
    }

    // 记录课堂行为
    public void recordBehavior(Long classId, String className, Long teacherId, Long studentId,
                                String studentName, Long sessionId, String behaviorType, String detail, int scoreChange) {
        try {
            jdbc.update(
                "INSERT INTO classroom_behavior (class_id, class_name, teacher_id, student_id, student_name, session_id, behavior_type, behavior_detail, score_change) VALUES (?,?,?,?,?,?,?,?,?)",
                classId, className, teacherId, studentId, studentName, sessionId, behaviorType, detail, scoreChange);
        } catch (Exception e) {
            // 记录失败不影响主流程
        }
    }

    // 查询可绑定的学生账号（student角色且未绑定到任何学生）
    public List<Map<String, Object>> getBindableAccounts() {
        return jdbc.queryForList(
            "SELECT u.id, u.username, u.display_name, u.role FROM users u " +
            "WHERE u.role='student' AND u.id NOT IN (SELECT s.user_id FROM students s WHERE s.user_id IS NOT NULL AND (s.is_deleted IS NULL OR s.is_deleted=0)) " +
            "ORDER BY u.username ASC");
    }

    // 绑定学生账号到学生信息
    public Map<String, Object> bindAccount(Long studentId, Long userId) {
        Map<String, Object> result = new HashMap<>();
        try {
            if (studentId == null) {
                result.put("code", 400);
                result.put("msg", "缺少学生ID");
                return result;
            }
            if (userId == null) {
                result.put("code", 400);
                result.put("msg", "缺少账号ID");
                return result;
            }
            // 校验学生存在
            List<Map<String, Object>> students = jdbc.queryForList(
                "SELECT id, name, user_id FROM students WHERE id=? AND (is_deleted IS NULL OR is_deleted=0)", studentId);
            if (students.isEmpty()) {
                result.put("code", 404);
                result.put("msg", "学生不存在");
                return result;
            }
            // 校验账号存在且为student角色
            List<Map<String, Object>> users = jdbc.queryForList(
                "SELECT id, username, role FROM users WHERE id=? AND role='student'", userId);
            if (users.isEmpty()) {
                result.put("code", 404);
                result.put("msg", "学生账号不存在或角色不匹配");
                return result;
            }
            // 校验账号未被其他学生绑定
            List<Map<String, Object>> bound = jdbc.queryForList(
                "SELECT id FROM students WHERE user_id=? AND id<>? AND (is_deleted IS NULL OR is_deleted=0)", userId, studentId);
            if (!bound.isEmpty()) {
                result.put("code", 400);
                result.put("msg", "该账号已被其他学生绑定");
                return result;
            }
            jdbc.update("UPDATE students SET user_id=? WHERE id=?", userId, studentId);
            result.put("code", 200);
            result.put("msg", "绑定成功");
            result.put("studentId", studentId);
            result.put("userId", userId);
            result.put("username", users.get(0).get("username"));
            result.put("studentName", students.get(0).get("name"));
        } catch (Exception e) {
            result.put("code", 500);
            result.put("msg", "绑定失败：" + e.getMessage());
        }
        return result;
    }

    // 解绑学生账号
    public Map<String, Object> unbindAccount(Long studentId) {
        Map<String, Object> result = new HashMap<>();
        try {
            if (studentId == null) {
                result.put("code", 400);
                result.put("msg", "缺少学生ID");
                return result;
            }
            List<Map<String, Object>> students = jdbc.queryForList(
                "SELECT id, name, user_id FROM students WHERE id=? AND (is_deleted IS NULL OR is_deleted=0)", studentId);
            if (students.isEmpty()) {
                result.put("code", 404);
                result.put("msg", "学生不存在");
                return result;
            }
            Object existingUserId = students.get(0).get("user_id");
            if (existingUserId == null) {
                result.put("code", 400);
                result.put("msg", "该学生尚未绑定账号");
                return result;
            }
            jdbc.update("UPDATE students SET user_id=NULL WHERE id=?", studentId);
            result.put("code", 200);
            result.put("msg", "解绑成功");
            result.put("studentId", studentId);
            result.put("studentName", students.get(0).get("name"));
            result.put("unboundUserId", existingUserId);
        } catch (Exception e) {
            result.put("code", 500);
            result.put("msg", "解绑失败：" + e.getMessage());
        }
        return result;
    }
}
