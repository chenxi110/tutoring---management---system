package com.skt.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class HomeworkService {

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private OperationLogService operationLogService;

    public List<Map<String, Object>> list(Long classId) {
        String sql = "SELECT h.*, c.name as class_name FROM homework h LEFT JOIN classes c ON h.class_id=c.id";
        if (classId != null) {
            sql += " WHERE h.class_id=? ORDER BY h.created_at DESC";
            return jdbc.queryForList(sql, classId);
        }
        sql += " ORDER BY h.created_at DESC";
        return jdbc.queryForList(sql);
    }

    public List<Map<String, Object>> listForParent(Long parentId) {
        String sql = "SELECT DISTINCT h.*, c.name AS class_name " +
            "FROM homework h " +
            "LEFT JOIN classes c ON h.class_id = c.id " +
            "INNER JOIN students s ON s.class_id = h.class_id " +
            "WHERE s.parent_id=? AND (s.is_deleted IS NULL OR s.is_deleted = 0) AND (s.status IS NULL OR s.status='active') " +
            "ORDER BY h.created_at DESC";
        return jdbc.queryForList(sql, parentId);
    }

    public Long create(Long classId, String title, String content, String deadline, Long createdBy) {
        jdbc.update("INSERT INTO homework (class_id, title, content, deadline, created_by) VALUES (?,?,?,?,?)",
            classId, title, content != null ? content : "", deadline != null ? deadline : "", createdBy);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    public Long submit(Long homeworkId, Long studentId, String studentName, String content) {
        String now = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
        jdbc.update("INSERT INTO homework_submissions (homework_id, student_id, student_name, content, submitted_at) VALUES (?,?,?,?,?)",
            homeworkId, studentId, studentName != null ? studentName : "", content, now);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    public void grade(Long submissionId, Double score, String comment) {
        String now = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
        jdbc.update("UPDATE homework_submissions SET score=?, comment=?, graded_at=? WHERE id=?",
            score, comment != null ? comment : "", now, submissionId);

        // 同步成绩到grades表
        try {
            List<Map<String, Object>> subs = jdbc.queryForList(
                "SELECT hs.*, h.title as homework_title, h.class_id, c.name as class_name, c.teacher_id " +
                "FROM homework_submissions hs " +
                "LEFT JOIN homework h ON h.id=hs.homework_id " +
                "LEFT JOIN classes c ON c.id=h.class_id " +
                "WHERE hs.id=?", submissionId);
            if (!subs.isEmpty()) {
                Map<String, Object> sub = subs.get(0);
                Long studentId = sub.get("student_id") != null ? ((Number) sub.get("student_id")).longValue() : null;
                String studentName = (String) sub.get("student_name");
                Long classId = sub.get("class_id") != null ? ((Number) sub.get("class_id")).longValue() : null;
                String className = (String) sub.get("class_name");
                String homeworkTitle = (String) sub.get("homework_title");
                Long teacherId = sub.get("teacher_id") != null ? ((Number) sub.get("teacher_id")).longValue() : null;

                if (studentId != null && classId != null) {
                    String examName = "作业:" + (homeworkTitle != null ? homeworkTitle : "未命名");
                    // 检查是否已存在该学生该作业的成绩记录
                    List<Map<String, Object>> existing = jdbc.queryForList(
                        "SELECT id FROM grades WHERE student_id=? AND exam_name=? AND class_id=?",
                        studentId, examName, classId);
                    if (!existing.isEmpty()) {
                        Long gradeId = ((Number) existing.get(0).get("id")).longValue();
                        jdbc.update("UPDATE grades SET score=?, remark=?, teacher_id=? WHERE id=?",
                            score, comment != null ? comment : "", teacherId, gradeId);
                    } else {
                        jdbc.update(
                            "INSERT INTO grades (student_id, student_name, class_id, class_name, exam_name, exam_type, score, total_score, teacher_id, remark) VALUES (?,?,?,?,?,?,?,?,?,?)",
                            studentId, studentName, classId, className, examName, "homework", score, 100.0, teacherId, comment != null ? comment : "");
                    }
                }
                // 操作日志
                if (teacherId != null) {
                    operationLogService.log(teacherId, "teacher_"+teacherId, "teacher", "作业批改",
                        "批改作业提交ID="+submissionId+", 分数="+score+", 评语="+comment, null);
                }
            }
        } catch (Exception e) {
            // 成绩同步失败不影响批改主流程
        }
    }
}
