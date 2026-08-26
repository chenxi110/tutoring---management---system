package com.skt.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class OperationLogService {

    @Autowired
    private JdbcTemplate jdbc;

    public void log(Long userId, String username, String role, String operation, String detail, String ip) {
        try {
            jdbc.update(
                "INSERT INTO operation_logs (user_id, username, role, operation, detail, ip) VALUES (?,?,?,?,?,?)",
                userId, username, role, operation,
                detail != null ? detail : "",
                ip != null ? ip : ""
            );
        } catch (Exception e) {
            // 日志记录失败不影响主业务
        }
    }

    public List<Map<String, Object>> list(String operation, Long userId, String startTime, String endTime, int page, int size) {
        StringBuilder sql = new StringBuilder("SELECT * FROM operation_logs WHERE 1=1");
        List<Object> params = new ArrayList<>();
        if (operation != null && !operation.trim().isEmpty()) {
            sql.append(" AND operation LIKE ?");
            params.add("%" + operation.trim() + "%");
        }
        if (userId != null) {
            sql.append(" AND user_id = ?");
            params.add(userId);
        }
        if (startTime != null && !startTime.trim().isEmpty()) {
            sql.append(" AND created_at >= ?");
            params.add(startTime.trim());
        }
        if (endTime != null && !endTime.trim().isEmpty()) {
            sql.append(" AND created_at <= ?");
            params.add(endTime.trim());
        }
        sql.append(" ORDER BY id DESC LIMIT ? OFFSET ?");
        params.add(size);
        params.add((page - 1) * size);
        return jdbc.queryForList(sql.toString(), params.toArray());
    }

    public int count(String operation, Long userId, String startTime, String endTime) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM operation_logs WHERE 1=1");
        List<Object> params = new ArrayList<>();
        if (operation != null && !operation.trim().isEmpty()) {
            sql.append(" AND operation LIKE ?");
            params.add("%" + operation.trim() + "%");
        }
        if (userId != null) {
            sql.append(" AND user_id = ?");
            params.add(userId);
        }
        if (startTime != null && !startTime.trim().isEmpty()) {
            sql.append(" AND created_at >= ?");
            params.add(startTime.trim());
        }
        if (endTime != null && !endTime.trim().isEmpty()) {
            sql.append(" AND created_at <= ?");
            params.add(endTime.trim());
        }
        Integer cnt = jdbc.queryForObject(sql.toString(), Integer.class, params.toArray());
        return cnt != null ? cnt : 0;
    }
}
