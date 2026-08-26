package com.skt.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class ClazzService {

    @Autowired
    private JdbcTemplate jdbc;

    public List<Map<String, Object>> listSemesters() {
        return jdbc.queryForList("SELECT * FROM semesters ORDER BY id");
    }

    public Long createSemester(String name, String startDate, String endDate, boolean isActive) {
        if (isActive) {
            jdbc.update("UPDATE semesters SET is_active=0");
        }
        jdbc.update("INSERT INTO semesters (name, start_date, end_date, is_active) VALUES (?,?,?,?)",
            name, startDate, endDate, isActive ? 1 : 0);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    public void updateSemester(Long id, String name, String startDate, String endDate, boolean isActive) {
        if (isActive) {
            jdbc.update("UPDATE semesters SET is_active=0 WHERE id<>?", id);
        }
        jdbc.update("UPDATE semesters SET name=?, start_date=?, end_date=?, is_active=? WHERE id=?",
            name, startDate, endDate, isActive ? 1 : 0, id);
    }

    public List<Map<String, Object>> listClasses(Long teacherId, Long semesterId) {
        StringBuilder sql = new StringBuilder(
            "SELECT c.*, s.name as semester_name, " +
            "(SELECT COUNT(*) FROM students WHERE class_id=c.id AND status='active') as student_count " +
            "FROM classes c LEFT JOIN semesters s ON c.semester_id=s.id WHERE 1=1");
        List<Object> params = new ArrayList<>();
        if (teacherId != null) {
            sql.append(" AND c.teacher_id=?");
            params.add(teacherId);
        }
        if (semesterId != null) {
            sql.append(" AND c.semester_id=?");
            params.add(semesterId);
        }
        sql.append(" ORDER BY c.id DESC");
        return jdbc.queryForList(sql.toString(), params.toArray());
    }

    public List<Map<String, Object>> myClasses(Long teacherId, Long semesterId) {
        StringBuilder sql = new StringBuilder(
            "SELECT c.*, (SELECT COUNT(*) FROM students WHERE class_id=c.id AND status='active') as student_count " +
            "FROM classes c WHERE c.teacher_id=?");
        List<Object> params = new ArrayList<>();
        params.add(teacherId);
        if (semesterId != null) {
            sql.append(" AND c.semester_id=?");
            params.add(semesterId);
        }
        sql.append(" ORDER BY c.id DESC");
        return jdbc.queryForList(sql.toString(), params.toArray());
    }

    public Long createClass(String name, String course, Long semesterId, Long teacherId) {
        if (semesterId == null) {
            Map<String, Object> active = jdbc.queryForMap("SELECT id FROM semesters WHERE is_active=1 LIMIT 1");
            semesterId = ((Number) active.get("id")).longValue();
        }
        jdbc.update("INSERT INTO classes (name, course, semester_id, teacher_id) VALUES (?,?,?,?)",
            name, course, semesterId, teacherId);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    public void updateClass(Long id, String name, String course, Long semesterId) {
        jdbc.update("UPDATE classes SET name=?, course=?, semester_id=? WHERE id=?",
            name, course, semesterId, id);
    }

    public void deleteClass(Long id) {
        jdbc.update("DELETE FROM classes WHERE id=?", id);
    }
}
