package com.skt.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 应用启动时的数据初始化器：
 * 1. 兼容旧表结构（自动添加缺失字段）
 * 2. 清理学生姓名中的隐藏字符
 * 3. 当 users 表为空时，初始化演示账号和演示数据
 *
 * 所有数据库操作均有异常保护，数据库不可用时不会导致应用启动失败。
 */
@Component
public class DataInitializer implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DataInitializer.class);

    @Autowired
    private JdbcTemplate jdbc;

    @Override
    public void run(String... args) {
        try {
            // 确保 parent_file 表存在（教师下发家长文件）
            try {
                jdbc.execute("CREATE TABLE IF NOT EXISTS parent_file (" +
                    "id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键id'," +
                    "teacher_id BIGINT NOT NULL COMMENT '下发教师ID'," +
                    "file_name VARCHAR(255) NOT NULL COMMENT '原始文件名'," +
                    "save_path VARCHAR(500) NOT NULL COMMENT '存储相对路径'," +
                    "file_suffix VARCHAR(50) NOT NULL COMMENT '文件后缀'," +
                    "file_size BIGINT NOT NULL COMMENT '字节大小'," +
                    "target_type VARCHAR(20) NOT NULL DEFAULT 'all' COMMENT 'all=全部家长 class=班级家长 parent=指定家长'," +
                    "class_id BIGINT NULL COMMENT '目标班级ID'," +
                    "parent_user_id BIGINT NULL COMMENT '目标家长用户ID'," +
                    "upload_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP," +
                    "PRIMARY KEY (id)" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='教师下发家长文件表'");
            } catch (Exception e) {
                log.debug("parent_file 表创建跳过: {}", e.getMessage());
            }
            // 作业提交文件字段（家长/学生提交任意格式文件）
            try {
                jdbc.execute("ALTER TABLE homework_submissions ADD COLUMN file_name VARCHAR(255) NULL COMMENT '提交文件名'");
            } catch (Exception e) { log.debug("homework_submissions.file_name 已存在: {}", e.getMessage()); }
            try {
                jdbc.execute("ALTER TABLE homework_submissions ADD COLUMN file_path VARCHAR(500) NULL COMMENT '提交文件存储路径'");
            } catch (Exception e) { log.debug("homework_submissions.file_path 已存在: {}", e.getMessage()); }
            try {
                jdbc.execute("ALTER TABLE homework_submissions ADD COLUMN submit_role VARCHAR(20) NULL DEFAULT 'parent' COMMENT '提交角色 parent=家长 student=学生 teacher=教师'");
            } catch (Exception e) { log.debug("homework_submissions.submit_role 已存在: {}", e.getMessage()); }
            try {
                jdbc.execute("ALTER TABLE homework_submissions ADD COLUMN submit_user_id BIGINT NULL COMMENT '提交人用户ID'");
            } catch (Exception e) { log.debug("homework_submissions.submit_user_id 已存在: {}", e.getMessage()); }
            // 兼容旧表结构：确保 users.display_name 允许 NULL
            try {
                jdbc.execute("ALTER TABLE users MODIFY display_name VARCHAR(50) NULL DEFAULT ''");
            } catch (Exception e) {
                log.debug("users.display_name 字段已存在或无需修改: {}", e.getMessage());
            }

            // 确保 students 表有 user_id 字段（兼容旧数据库）
            try {
                jdbc.execute("ALTER TABLE students ADD COLUMN user_id BIGINT COMMENT '绑定的学生账号ID'");
                log.info("已为 students 表添加 user_id 字段");
            } catch (Exception e) {
                log.debug("students.user_id 字段已存在: {}", e.getMessage());
            }

            // 清理学生姓名隐藏字符
            cleanupStudentNames();

            // 初始化默认账号和演示数据（仅当 users 表为空时）
            initDemoData();

        } catch (Exception e) {
            // 数据库不可用或表结构异常时，记录警告但不阻断启动
            log.warn("数据初始化过程中发生异常（应用仍可启动，部分演示数据可能缺失）: {}", e.getMessage());
        }
    }

    private void cleanupStudentNames() {
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
                log.info("已清理 {} 条学生姓名中的隐藏字符", cleaned);
            }
        } catch (Exception e) {
            log.warn("清理学生姓名隐藏字符失败: {}", e.getMessage());
        }
    }

    private void initDemoData() {
        Integer userCount = jdbc.queryForObject("SELECT COUNT(*) FROM users", Integer.class);
        if (userCount == null || userCount > 0) {
            return;
        }

        log.info("检测到 users 表为空，开始初始化演示账号和数据...");
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
        String hash = encoder.encode("admin123");

        // 1. 超级管理员
        jdbc.update("INSERT INTO users (username, password_hash, role, display_name, phone) VALUES (?,?,?,?,?)",
            "admin", hash, "admin", "系统管理员", "13800000000");
        log.info("已创建超级管理员: admin / admin123");

        // 2. 教师账号
        jdbc.update("INSERT INTO users (username, password_hash, role, display_name, phone) VALUES (?,?,?,?,?)",
            "teacher", hash, "teacher", "张老师", "13800000001");
        log.info("已创建教师账号: teacher / admin123");

        // 3. 家长账号
        jdbc.update("INSERT INTO users (username, password_hash, role, display_name, phone) VALUES (?,?,?,?,?)",
            "parent", hash, "parent", "王家长", "13800000002");
        log.info("已创建家长账号: parent / admin123");

        // 4. 学生账号 student01~student05
        String[] studentNames = {"小明", "小红", "小刚", "小丽", "小强"};
        for (int i = 0; i < 5; i++) {
            String username = "student0" + (i + 1);
            jdbc.update("INSERT INTO users (username, password_hash, role, display_name, phone) VALUES (?,?,?,?,?)",
                username, hash, "student", studentNames[i], "1380000001" + (i + 1));
        }
        log.info("已创建学生账号: student01~student05 / admin123");

        // 5. 演示班级
        jdbc.update("INSERT INTO classes (id, name, course, semester_id, teacher_id) VALUES (?,?,?,?,?)",
            1, "三年级数学提高班", "数学", 3, 2L);
        jdbc.update("INSERT INTO classes (id, name, course, semester_id, teacher_id) VALUES (?,?,?,?,?)",
            2, "四年级英语基础班", "英语", 3, 2L);
        log.info("已创建演示班级");

        // 6. 演示学生（绑定 student 账号）
        Long[] studentUserIds = {4L, 5L, 6L, 7L, 8L};
        String[] parentRelations = {"父亲", "母亲", "父亲", "母亲", "父亲"};
        for (int i = 0; i < 5; i++) {
            Long classId = i < 3 ? 1L : 2L;
            jdbc.update("INSERT INTO students (id, name, class_id, parent_id, user_id, parent_name, parent_relation, parent_phone, status) VALUES (?,?,?,?,?,?,?,?,?)",
                (long)(i + 1), studentNames[i], classId, 3L, studentUserIds[i], "王家长", parentRelations[i], "13800000002", "active");
        }
        log.info("已创建演示学生并绑定 student01~student05 账号");

        // 7. 演示成绩数据
        String[] examNames = {"第一单元测试", "期中考试", "第二单元测试"};
        double[] scores = {85.5, 92.0, 78.0, 88.5, 95.0};
        for (int i = 0; i < 5; i++) {
            for (int j = 0; j < 3; j++) {
                double score = scores[i] + (j - 1) * 3;
                jdbc.update("INSERT INTO grades (student_id, student_name, class_id, class_name, exam_name, exam_type, score, total_score, semester_id, teacher_id, rank) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                    (long)(i + 1), studentNames[i], i < 3 ? 1L : 2L, i < 3 ? "三年级数学提高班" : "四年级英语基础班",
                    examNames[j], "unit_test", Math.max(0, Math.min(100, score)), 100.0, 3L, 2L, i + 1);
            }
        }
        log.info("已创建演示成绩数据");

        // 8. 演示作业数据
        for (int i = 0; i < 5; i++) {
            jdbc.update("INSERT INTO homework (id, class_id, class_name, title, content, teacher_id, deadline, status) VALUES (?,?,?,?,?,?,?,?)",
                (long)(i + 1), i < 3 ? 1L : 2L, i < 3 ? "三年级数学提高班" : "四年级英语基础班",
                "第" + (i + 1) + "次课后作业", "完成课本第" + (i + 10) + "页练习题", 2L, "2025-06-15 23:59:59", "published");
        }
        log.info("已创建演示作业数据");

        log.info("全部演示数据初始化完成！测试账号: admin/teacher/parent/student01~student05，密码均为 admin123");
    }
}
