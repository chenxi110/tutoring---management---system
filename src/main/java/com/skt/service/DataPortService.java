package com.skt.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class DataPortService {

    private static final Logger log = LoggerFactory.getLogger(DataPortService.class);

    @Autowired
    private JdbcTemplate jdbc;

    public Map<String, Object> exportAll() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("semesters", jdbc.queryForList("SELECT * FROM semesters"));
        data.put("classes", jdbc.queryForList("SELECT * FROM classes"));
        data.put("students", jdbc.queryForList("SELECT * FROM students"));
        data.put("records", jdbc.queryForList("SELECT * FROM records"));
        data.put("schedules", jdbc.queryForList("SELECT * FROM schedules"));
        data.put("messages", jdbc.queryForList("SELECT * FROM messages"));
        data.put("homework", jdbc.queryForList("SELECT * FROM homework"));
        data.put("grades", jdbc.queryForList("SELECT * FROM grades"));
        data.put("msgTemplates", jdbc.queryForList("SELECT * FROM msg_templates"));
        return data;
    }

    public int importData(Map<String, Object> data) {
        int count = 0;
        List<Map<String, Object>> semesters = (List<Map<String, Object>>) data.get("semesters");
        if (semesters != null) {
            for (Map<String, Object> s : semesters) {
                try {
                    jdbc.update("INSERT IGNORE INTO semesters (name, start_date, end_date, is_active) VALUES (?,?,?,?)",
                        s.get("name"),
                        s.get("start_date") != null ? s.get("start_date") : s.get("startDate"),
                        s.get("end_date") != null ? s.get("end_date") : s.get("endDate"),
                        s.get("is_active") != null ? s.get("is_active") : (s.get("isActive") != null ? 1 : 0));
                    count++;
                } catch (Exception ignored) { log.debug("操作被跳过: {}", ignored.getMessage()); }
            }
        }
        List<Map<String, Object>> classes = (List<Map<String, Object>>) data.get("classes");
        if (classes != null) {
            for (Map<String, Object> c : classes) {
                try {
                    jdbc.update("INSERT IGNORE INTO classes (name, course, semester_id) VALUES (?,?,?)",
                        c.get("name"),
                        c.get("course") != null ? c.get("course") : "",
                        c.get("semester_id") != null ? c.get("semester_id") : c.get("semesterId"));
                    count++;
                } catch (Exception ignored) { log.debug("操作被跳过: {}", ignored.getMessage()); }
            }
        }
        return count;
    }
}
