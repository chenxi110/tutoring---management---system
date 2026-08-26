package com.skt.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class HomeworkService {

    @Autowired
    private JdbcTemplate jdbc;

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
        jdbc.update("UPDATE homework_submissions SET score=?, comment=? WHERE id=?",
            score, comment != null ? comment : "", submissionId);
    }
}
