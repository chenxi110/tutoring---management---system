package com.skt.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class StudentService {

    @Autowired
    private JdbcTemplate jdbc;

    public List<Map<String, Object>> listByClass(Long classId) {
        String sql = "SELECT s.*, COALESCE(u.display_name, s.parent_name) as parent_name, COALESCE(u.phone, s.parent_phone) as parent_phone " +
            "FROM students s LEFT JOIN users u ON s.parent_id=u.id " +
            "WHERE s.class_id=? AND s.status='active'";
        if (hasSoftDeleteColumn()) {
            sql += " AND (s.is_deleted IS NULL OR s.is_deleted = 0)";
        }
        sql += " ORDER BY s.name";
        return jdbc.queryForList(sql, classId);
    }

    public List<Map<String, Object>> listAll(Long classId) {
        String sql = "SELECT s.*, c.name as class_name FROM students s " +
                     "LEFT JOIN classes c ON s.class_id=c.id WHERE s.status='active'";
        if (hasSoftDeleteColumn()) {
            sql += " AND (s.is_deleted IS NULL OR s.is_deleted = 0)";
        }
        if (classId != null) {
            sql += " AND s.class_id=? ORDER BY s.name";
            return jdbc.queryForList(sql, classId);
        }
        sql += " ORDER BY s.name";
        return jdbc.queryForList(sql);
    }

    public List<Map<String, Object>> listByParent(Long parentId) {
        if (parentId == null) return new ArrayList<>();
        String sql = "SELECT s.*, c.name as class_name, c.course as class_course " +
                     "FROM students s LEFT JOIN classes c ON s.class_id=c.id " +
                     "WHERE s.parent_id=? AND s.status='active'";
        if (hasSoftDeleteColumn()) {
            sql += " AND (s.is_deleted IS NULL OR s.is_deleted = 0)";
        }
        sql += " ORDER BY s.name";
        return jdbc.queryForList(sql, parentId);
    }

    public Map<String, Object> getDetail(Long id) {
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT s.*, c.name as class_name, c.course as class_course, c.id as class_id " +
            "FROM students s LEFT JOIN classes c ON s.class_id=c.id WHERE s.id=?", id);
        if (rows.isEmpty()) return null;
        Map<String, Object> student = new LinkedHashMap<>(rows.get(0));
        Object className = student.get("class_name");
        Object classId = student.get("class_id");
        student.put("records", jdbc.queryForList("SELECT * FROM records WHERE class_name=? ORDER BY date DESC", className));
        student.put("schedule", jdbc.queryForList("SELECT * FROM schedules WHERE class_id=? ORDER BY weekday, start_time", classId));
        return student;
    }

    public Long create(String name, Long classId, String phone, String parentPhone, String parentName, String parentRelation) {
        String today = new java.text.SimpleDateFormat("yyyy-MM-dd").format(new Date());
        String cleanName = cleanStudentName(name);
        String cleanPhone = cleanPhone(phone);
        String cleanParentPhone = cleanPhone(parentPhone);
        jdbc.update("INSERT INTO students (name, class_id, phone, parent_phone, parent_name, parent_relation, enrollment_date, status) " +
            "VALUES (?,?,?,?,?,?,?,?)",
            cleanName, classId,
            cleanPhone,
            cleanParentPhone,
            cleanStudentName(parentName),
            cleanStudentName(parentRelation),
            today, "active");
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    public void update(Long id, String name, Long classId, String phone, String parentPhone,
                       String parentName, String parentRelation, String status, String tags) {
        List<String> sets = new ArrayList<>();
        List<Object> params = new ArrayList<>();
        if (name != null) { sets.add("name=?"); params.add(cleanStudentName(name)); }
        if (classId != null) { sets.add("class_id=?"); params.add(classId); }
        if (phone != null) { sets.add("phone=?"); params.add(cleanPhone(phone)); }
        if (parentPhone != null) { sets.add("parent_phone=?"); params.add(cleanPhone(parentPhone)); }
        if (parentName != null) { sets.add("parent_name=?"); params.add(cleanStudentName(parentName)); }
        if (parentRelation != null) { sets.add("parent_relation=?"); params.add(cleanStudentName(parentRelation)); }
        if (status != null) { sets.add("status=?"); params.add(status); }
        if (tags != null) { sets.add("tags=?"); params.add(tags); }
        if (sets.isEmpty()) return;
        params.add(id);
        jdbc.update("UPDATE students SET " + String.join(", ", sets) + " WHERE id=?", params.toArray());
    }

    public void delete(Long id) {
        try {
            jdbc.update("UPDATE students SET status='inactive', is_deleted=1 WHERE id=?", id);
        } catch (Exception e) {
            jdbc.update("UPDATE students SET status='inactive' WHERE id=?", id);
        }
    }

    private boolean hasSoftDeleteColumn() {
        try {
            Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'students' AND column_name = 'is_deleted'",
                Integer.class);
            return count != null && count > 0;
        } catch (Exception e) {
            return false;
        }
    }

    public static String cleanStudentName(String value) {
        if (value == null) {
            return "";
        }
        return value
            .replace("\u200B", "")
            .replace("\uFEFF", "")
            .replace("\u00A0", "")
            .replace("\u3000", "")
            .replaceAll("[\\r\\n\\t\\s]+", "")
            .trim();
    }

    public static String cleanPhone(String value) {
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
}