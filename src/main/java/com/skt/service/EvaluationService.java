package com.skt.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class EvaluationService {

    private static final Logger log = LoggerFactory.getLogger(EvaluationService.class);

    @Autowired
    private JdbcTemplate jdbc;

    // 提交评价
    public Map<String, Object> submitEvaluation(Long classId, String courseName, Long teacherId, String teacherName,
                                                  Long studentId, String studentName, Long parentId,
                                                  int score, String content, String tags) {
        Map<String, Object> result = new HashMap<>();
        try {
            if (score < 1 || score > 5) {
                result.put("code", 400);
                result.put("msg", "评分必须在1-5之间");
                return result;
            }
            jdbc.update(
                "INSERT INTO course_evaluations (class_id, course_name, teacher_id, teacher_name, student_id, student_name, parent_id, score, content, tags) VALUES (?,?,?,?,?,?,?,?,?,?)",
                classId, courseName, teacherId, teacherName, studentId, studentName, parentId, score,
                content != null ? content : "", tags != null ? tags : "");
            result.put("code", 200);
            result.put("msg", "评价提交成功");
        } catch (Exception e) {
            result.put("code", 500);
            result.put("msg", "评价提交失败：" + e.getMessage());
        }
        return result;
    }

    // 班级评价列表
    public List<Map<String, Object>> listByClass(Long classId) {
        return jdbc.queryForList(
            "SELECT * FROM course_evaluations WHERE class_id=? ORDER BY created_at DESC", classId);
    }

    // 教师评价列表
    public List<Map<String, Object>> listByTeacher(Long teacherId) {
        return jdbc.queryForList(
            "SELECT * FROM course_evaluations WHERE teacher_id=? ORDER BY created_at DESC", teacherId);
    }

    // 教师评价统计
    public Map<String, Object> getTeacherStats(Long teacherId) {
        Map<String, Object> stats = new HashMap<>();
        try {
            Double avgScore = jdbc.queryForObject(
                "SELECT COALESCE(AVG(score),0) FROM course_evaluations WHERE teacher_id=?", Double.class, teacherId);
            Integer totalCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM course_evaluations WHERE teacher_id=?", Integer.class, teacherId);
            // 各评分分布
            List<Map<String, Object>> distribution = jdbc.queryForList(
                "SELECT score, COUNT(*) as cnt FROM course_evaluations WHERE teacher_id=? GROUP BY score ORDER BY score", teacherId);
            stats.put("avgScore", avgScore != null ? Math.round(avgScore * 10) / 10.0 : 0);
            stats.put("totalCount", totalCount != null ? totalCount : 0);
            stats.put("distribution", distribution);
        } catch (Exception e) { log.warn("查询统计数据失败: {}", e.getMessage()); }
        return stats;
    }
}
