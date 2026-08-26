package com.skt.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class DataInitializer implements CommandLineRunner {

    @Autowired
    private JdbcTemplate jdbc;

    @Override
    public void run(String... args) {
        try {
            jdbc.execute("ALTER TABLE users MODIFY display_name VARCHAR(50) NULL DEFAULT ''");
        } catch (Exception e) {
            // 兼容旧表结构，忽略已存在的字段约束情况
        }

        try {
            List<Map<String, Object>> students = jdbc.queryForList(
                "SELECT id, name FROM students WHERE (is_deleted IS NULL OR is_deleted = 0) AND (status IS NULL OR status = 'active')");
            int cleaned = 0;
            for (Map<String, Object> s : students) {
                String rawName = s.get("name") == null ? "" : String.valueOf(s.get("name"));
                String cleanName = com.skt.service.AuthService.normalizeStudentName(rawName);
                if (!cleanName.equals(rawName) && !cleanName.isEmpty()) {
                    jdbc.update("UPDATE students SET name=? WHERE id=?", cleanName, s.get("id"));
                    cleaned++;
                }
            }
            if (cleaned > 0) {
                System.out.println("[初始化] 已清理 " + cleaned + " 条学生姓名中的隐藏字符");
            }
        } catch (Exception e) {
            System.out.println("[初始化] students 清理隐藏字符失败：" + e.getMessage());
        }

        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM users", Integer.class);
        if (count != null && count == 0) {
            BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
            String hash = encoder.encode("admin123");
            jdbc.update("INSERT INTO users (username, password_hash, role, display_name, phone) VALUES (?,?,?,?,?)",
                    "admin", hash, "teacher", "管理员", "");
            System.out.println("[初始化] 默认账号: admin / admin123");
        }
    }
}
