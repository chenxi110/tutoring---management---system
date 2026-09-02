package com.skt.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class ScheduleService {

    @Autowired
    private JdbcTemplate jdbc;

    public List<Map<String, Object>> list(Long classId, Long semesterId, Long teacherId) {
        StringBuilder sql = new StringBuilder("SELECT * FROM schedules WHERE 1=1");
        List<Object> params = new ArrayList<>();
        if (teacherId != null) { sql.append(" AND teacher_id=?"); params.add(teacherId); }
        if (classId != null) { sql.append(" AND class_id=?"); params.add(classId); }
        if (semesterId != null) { sql.append(" AND semester_id=?"); params.add(semesterId); }
        sql.append(" ORDER BY weekday, start_time");
        return jdbc.queryForList(sql.toString(), params.toArray());
    }

    public List<Map<String, Object>> mySchedules(Long teacherId, Long semesterId) {
        String sql = "SELECT s.*, c.name as class_name, c.course FROM schedules s " +
                     "LEFT JOIN classes c ON s.class_id=c.id WHERE s.teacher_id=?";
        if (semesterId != null) {
            sql += " AND s.semester_id=? ORDER BY s.weekday, s.start_time";
            return jdbc.queryForList(sql, teacherId, semesterId);
        }
        sql += " ORDER BY s.weekday, s.start_time";
        return jdbc.queryForList(sql, teacherId);
    }

    public List<Map<String, Object>> childSchedules(Long parentId) {
        return jdbc.queryForList(
            "SELECT s.*, c.name as class_name, c.course, st.name as student_name " +
            "FROM schedules s JOIN classes c ON s.class_id=c.id " +
            "JOIN students st ON st.class_id=c.id " +
            "WHERE st.parent_id=? AND st.status='active' ORDER BY s.weekday, s.start_time",
            parentId);
    }

    /** 学生课表：按本人 students.user_id 严格隔离，只返回本人班级课程 */
    public List<Map<String, Object>> studentSchedules(Long studentId, Long classId) {
        if (classId == null) return Collections.emptyList();
        return jdbc.queryForList(
            "SELECT s.*, c.name as class_name, c.course, st.name as student_name " +
            "FROM schedules s JOIN classes c ON s.class_id=c.id " +
            "JOIN students st ON st.id=? " +
            "WHERE s.class_id=? ORDER BY s.weekday, s.start_time",
            studentId, classId);
    }

    public Map<String, Object> nextClass(Long userId, String role) {
        Calendar now = Calendar.getInstance();
        int currentWeekday = now.get(Calendar.DAY_OF_WEEK) - 1;
        if (currentWeekday < 0) currentWeekday = 0;
        int currentTime = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);

        List<Map<String, Object>> schedules;
        if ("teacher".equals(role)) {
            schedules = jdbc.queryForList(
                "SELECT s.*, c.name as class_name, c.course, " +
                "(SELECT COUNT(*) FROM students WHERE class_id=c.id AND status='active') as student_count " +
                "FROM schedules s JOIN classes c ON s.class_id=c.id WHERE s.teacher_id=?", userId);
        } else if ("student".equals(role)) {
            // 学生：按 students.user_id 隔离，只看本人所在班级的课
            schedules = jdbc.queryForList(
                "SELECT s.*, c.name as class_name, c.course, st.name as student_name " +
                "FROM schedules s JOIN classes c ON s.class_id=c.id " +
                "JOIN students st ON st.class_id=c.id WHERE st.user_id=? AND st.status='active'", userId);
        } else {
            schedules = jdbc.queryForList(
                "SELECT s.*, c.name as class_name, c.course, st.name as student_name " +
                "FROM schedules s JOIN classes c ON s.class_id=c.id " +
                "JOIN students st ON st.class_id=c.id WHERE st.parent_id=? AND st.status='active'", userId);
        }

        if (schedules.isEmpty()) {
            Map<String, Object> r = new HashMap<>();
            r.put("message", "暂无排课");
            return r;
        }

        Map<String, Object> next = null;
        int minDiff = Integer.MAX_VALUE;
        for (Map<String, Object> s : schedules) {
            int wd = ((Number) s.get("weekday")).intValue();
            String startTime = String.valueOf(s.get("start_time"));
            String[] parts = startTime.split(":");
            int startMin = Integer.parseInt(parts[0]) * 60 + Integer.parseInt(parts[1]);
            int weekdayDiff = ((wd - currentWeekday + 7) % 7) * 24 * 60;
            int diff = weekdayDiff + (startMin - currentTime);
            if (wd == currentWeekday && startMin <= currentTime) diff += 7 * 24 * 60;
            if (diff >= 0 && diff < minDiff) {
                minDiff = diff;
                next = s;
            }
        }
        if (next == null) return null;
        next.put("minutesAhead", minDiff);
        return next;
    }

    public Long create(Integer weekday, String startTime, String endTime, Long classId,
                       String className, String course, Integer sessions, Long semesterId, Long teacherId) {
        Map<String, Object> cls = jdbc.queryForMap("SELECT * FROM classes WHERE id=?", classId);
        Object tid = cls.get("teacher_id");
        if (tid == null || !teacherId.equals(((Number) tid).longValue())) return null;
        if (semesterId == null) {
            Map<String, Object> active = jdbc.queryForMap("SELECT id FROM semesters WHERE is_active=1 LIMIT 1");
            semesterId = ((Number) active.get("id")).longValue();
        }
        String cName = className != null ? className : (String) cls.get("name");
        String cCourse = course != null ? course : (String) cls.get("course");
        jdbc.update("INSERT INTO schedules (weekday, start_time, end_time, class_id, class_name, course, sessions, semester_id, teacher_id) " +
            "VALUES (?,?,?,?,?,?,?,?,?)",
            weekday, startTime, endTime, classId, cName, cCourse,
            sessions != null ? sessions : 1, semesterId, teacherId);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    public int batchCreate(Long classId, List<Map<String, Object>> entries, String startTime,
                           String endTime, Integer sessions, String course, Long teacherId) {
        Map<String, Object> cls = jdbc.queryForMap("SELECT * FROM classes WHERE id=?", classId);
        Object tid = cls.get("teacher_id");
        if (tid == null || !teacherId.equals(((Number) tid).longValue())) return 0;
        Map<String, Object> active = jdbc.queryForMap("SELECT id FROM semesters WHERE is_active=1 LIMIT 1");
        Long semId = ((Number) active.get("id")).longValue();
        String clsName = (String) cls.get("name");
        String clsCourse = course != null ? course : (String) cls.get("course");
        int count = 0;
        for (Map<String, Object> e : entries) {
            String st = e.containsKey("startTime") ? (String) e.get("startTime") : startTime;
            String et = e.containsKey("endTime") ? (String) e.get("endTime") : endTime;
            int sess = e.containsKey("sessions") ? ((Number) e.get("sessions")).intValue() : (sessions != null ? sessions : 1);
            jdbc.update("INSERT INTO schedules (weekday, start_time, end_time, class_id, class_name, course, sessions, semester_id, teacher_id) " +
                "VALUES (?,?,?,?,?,?,?,?,?)",
                ((Number) e.get("weekday")).intValue(), st, et, classId, clsName, clsCourse, sess, semId, teacherId);
            count++;
        }
        return count;
    }

    public void delete(Long id, Long teacherId) {
        Map<String, Object> s = jdbc.queryForMap("SELECT teacher_id FROM schedules WHERE id=?", id);
        Object tid = s.get("teacher_id");
        if (tid != null && !teacherId.equals(((Number) tid).longValue())) return;
        jdbc.update("DELETE FROM schedules WHERE id=?", id);
    }
}
