package com.skt.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class RecordService {

    @Autowired
    private JdbcTemplate jdbc;

    public List<Map<String, Object>> list(Long classId, String className, String date, Long semesterId) {
        StringBuilder sql = new StringBuilder("SELECT * FROM records WHERE 1=1");
        List<Object> params = new ArrayList<>();
        if (classId != null) { sql.append(" AND class_id=?"); params.add(classId); }
        if (className != null) { sql.append(" AND class_name=?"); params.add(className); }
        if (date != null) { sql.append(" AND date=?"); params.add(date); }
        if (semesterId != null) { sql.append(" AND semester_id=?"); params.add(semesterId); }
        sql.append(" ORDER BY date DESC, id DESC");
        return jdbc.queryForList(sql.toString(), params.toArray());
    }

    public Long create(String date, Long classId, String className, String course,
                       Integer sessions, String type, Long semesterId, String remark,
                       List<Object> absent, List<Object> trialStudents) {
        if (semesterId == null) {
            Map<String, Object> active = jdbc.queryForMap("SELECT id FROM semesters WHERE is_active=1 LIMIT 1");
            semesterId = ((Number) active.get("id")).longValue();
        }
        jdbc.update("INSERT INTO records (date, class_id, class_name, course, sessions, type, semester_id, remark, absent_json, trial_students) " +
            "VALUES (?,?,?,?,?,?,?,?,?,?)",
            date, classId, className,
            course != null ? course : "",
            sessions != null ? sessions : 1,
            type != null ? type : "正常课",
            semesterId,
            remark != null ? remark : "",
            toJson(absent), toJson(trialStudents));
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    public void update(Long id, String date, Long classId, String className, String course,
                       Integer sessions, String type, Long semesterId, String remark,
                       List<Object> absent, List<Object> trialStudents) {
        jdbc.update("UPDATE records SET date=?, class_id=?, class_name=?, course=?, sessions=?, type=?, semester_id=?, remark=?, absent_json=?, trial_students=? WHERE id=?",
            date, classId, className,
            course != null ? course : "",
            sessions != null ? sessions : 1,
            type != null ? type : "正常课",
            semesterId,
            remark != null ? remark : "",
            toJson(absent), toJson(trialStudents), id);
    }

    public List<Map<String, Object>> listForParent(Long parentId) {
        if (parentId == null) return new ArrayList<>();
        String sql = "SELECT r.* FROM records r " +
                     "INNER JOIN students s ON r.class_id = s.class_id " +
                     "WHERE s.parent_id = ? AND s.status = 'active' " +
                     "GROUP BY r.id " +
                     "ORDER BY r.date DESC, r.id DESC";
        return jdbc.queryForList(sql, parentId);
    }

    public void delete(Long id) {
        jdbc.update("DELETE FROM records WHERE id=?", id);
    }

    private String toJson(Object obj) {
        if (obj == null) return "[]";
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(obj);
        } catch (Exception e) {
            return "[]";
        }
    }
}
