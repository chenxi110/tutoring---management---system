package com.skt.controller;

import com.skt.security.RoleAccess;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import java.util.*;

/**
 * 学生管理 CRUD 接口（前端学生名单模块依赖）。
 * 对应前端 apiService：createStudent/updateStudent/deleteStudent/getStudents/getClassStudents。
 */
@RestController
@RequestMapping("/api")
public class StudentManageController {

    private static final Logger log = LoggerFactory.getLogger(StudentManageController.class);

    @Autowired
    private JdbcTemplate jdbc;

    // 获取学生列表（可按班级过滤）
    @GetMapping("/students")
    public Map<String, Object> listStudents(@RequestParam(required = false) Long classId,
                                            HttpServletRequest req) {
        Map<String, Object> result = new HashMap<>();
        try {
            String role = (String) req.getAttribute("role");
            List<Map<String, Object>> list;
            if ("parent".equals(role)) {
                Long parentId = (Long) req.getAttribute("userId");
                // 家长只能看自己孩子
                list = jdbc.queryForList(
                    "SELECT s.*, c.name AS class_name FROM students s LEFT JOIN classes c ON s.class_id = c.id " +
                    "WHERE (s.parent_id = ? OR s.parent_user_id = ?) AND (s.is_deleted IS NULL OR s.is_deleted = 0) " +
                    "ORDER BY s.name",
                    parentId, parentId);
            } else if ("teacher".equals(role)) {
                Long teacherId = (Long) req.getAttribute("userId");
                if (classId != null) {
                    // 教师只能查看自己班级的学生，防越权
                    list = jdbc.queryForList(
                        "SELECT s.*, c.name AS class_name FROM students s LEFT JOIN classes c ON s.class_id = c.id " +
                        "WHERE s.class_id = ? AND (s.is_deleted IS NULL OR s.is_deleted = 0) " +
                        "AND s.class_id IN (SELECT id FROM classes WHERE teacher_id = ?) ORDER BY s.name",
                        classId, teacherId);
                } else {
                    list = jdbc.queryForList(
                        "SELECT s.*, c.name AS class_name FROM students s LEFT JOIN classes c ON s.class_id = c.id " +
                        "WHERE (s.is_deleted IS NULL OR s.is_deleted = 0) " +
                        "AND s.class_id IN (SELECT id FROM classes WHERE teacher_id = ?) ORDER BY s.name",
                        teacherId);
                }
            } else if (classId != null) {
                list = jdbc.queryForList(
                    "SELECT s.*, c.name AS class_name FROM students s LEFT JOIN classes c ON s.class_id = c.id " +
                    "WHERE s.class_id = ? AND (s.is_deleted IS NULL OR s.is_deleted = 0) ORDER BY s.name",
                    classId);
            } else {
                list = jdbc.queryForList(
                    "SELECT s.*, c.name AS class_name FROM students s LEFT JOIN classes c ON s.class_id = c.id " +
                    "WHERE (s.is_deleted IS NULL OR s.is_deleted = 0) ORDER BY s.name");
            }
            result.put("code", 200);
            result.put("data", list);
            return result;
        } catch (Exception ex) {
            log.error("查询学生列表失败", ex);
            result.put("code", 500);
            result.put("msg", "查询学生列表失败：" + ex.getMessage());
            return result;
        }
    }

    // 新增学生
    @PostMapping("/students")
    public Map<String, Object> createStudent(@RequestBody Map<String, Object> body, HttpServletRequest req) {
        Map<String, Object> result = new HashMap<>();
        if (!RoleAccess.isTeacher(req)) {
            return RoleAccess.forbidTeacherOnly("仅教师/管理员可新增学生");
        }
        try {
            String name = body.get("name") != null ? body.get("name").toString().trim() : "";
            if (name.isEmpty()) {
                result.put("code", 400);
                result.put("msg", "学生姓名不能为空");
                return result;
            }
            Long classId = body.get("classId") != null ? Long.valueOf(body.get("classId").toString()) : null;
            String parentPhone = body.get("parentPhone") != null ? body.get("parentPhone").toString().trim() : null;
            String parentName = body.get("parentName") != null ? body.get("parentName").toString().trim() : null;
            String parentRelation = body.get("parentRelation") != null ? body.get("parentRelation").toString().trim() : null;

            // 同班级同名去重校验
            List<Long> dup = jdbc.queryForList(
                "SELECT id FROM students WHERE name = ? AND (class_id <=> ?) AND (is_deleted IS NULL OR is_deleted = 0) LIMIT 1",
                Long.class, name, classId);
            if (!dup.isEmpty()) {
                result.put("code", 400);
                result.put("msg", "该班级已存在同名学生：" + name);
                return result;
            }

            jdbc.update(
                "INSERT INTO students (name, class_id, parent_phone, parent_name, parent_relation, status, created_at) " +
                "VALUES (?, ?, ?, ?, ?, 'active', NOW())",
                name, classId, parentPhone, parentName, parentRelation);
            Long id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
            result.put("code", 200);
            result.put("id", id);
            result.put("msg", "添加成功");
            return result;
        } catch (Exception ex) {
            log.error("新增学生失败", ex);
            result.put("code", 500);
            result.put("msg", "新增学生失败：" + ex.getMessage());
            return result;
        }
    }

    // 修改学生
    @PutMapping("/students/{id}")
    public Map<String, Object> updateStudent(@PathVariable Long id, @RequestBody Map<String, Object> body,
                                             HttpServletRequest req) {
        Map<String, Object> result = new HashMap<>();
        if (!RoleAccess.isTeacher(req)) {
            return RoleAccess.forbidTeacherOnly("仅教师/管理员可修改学生");
        }
        try {
            String name = body.get("name") != null ? body.get("name").toString().trim() : null;
            Long classId = body.get("classId") != null ? Long.valueOf(body.get("classId").toString()) : null;
            String parentPhone = body.get("parentPhone") != null ? body.get("parentPhone").toString().trim() : null;
            String parentName = body.get("parentName") != null ? body.get("parentName").toString().trim() : null;
            String parentRelation = body.get("parentRelation") != null ? body.get("parentRelation").toString().trim() : null;

            List<Object> args = new ArrayList<>();
            StringBuilder sql = new StringBuilder("UPDATE students SET ");
            boolean first = true;
            if (name != null && !name.isEmpty()) {
                sql.append("name = ?"); args.add(name); first = false;
            }
            if (classId != null) {
                if (!first) sql.append(", ");
                sql.append("class_id = ?"); args.add(classId); first = false;
            }
            if (parentPhone != null) {
                if (!first) sql.append(", ");
                sql.append("parent_phone = ?"); args.add(parentPhone); first = false;
            }
            if (parentName != null) {
                if (!first) sql.append(", ");
                sql.append("parent_name = ?"); args.add(parentName); first = false;
            }
            if (parentRelation != null) {
                if (!first) sql.append(", ");
                sql.append("parent_relation = ?"); args.add(parentRelation); first = false;
            }
            if (first) {
                result.put("code", 400);
                result.put("msg", "没有需要更新的字段");
                return result;
            }
            sql.append(" WHERE id = ?");
            args.add(id);
            jdbc.update(sql.toString(), args.toArray());
            result.put("code", 200);
            result.put("msg", "修改成功");
            return result;
        } catch (Exception ex) {
            log.error("修改学生失败 id={}", id, ex);
            result.put("code", 500);
            result.put("msg", "修改学生失败：" + ex.getMessage());
            return result;
        }
    }

    // 删除学生（软删除，可回收站恢复）
    @DeleteMapping("/students/{id}")
    public Map<String, Object> deleteStudent(@PathVariable Long id, HttpServletRequest req) {
        Map<String, Object> result = new HashMap<>();
        if (!RoleAccess.isTeacher(req)) {
            return RoleAccess.forbidTeacherOnly("仅教师/管理员可删除学生");
        }
        try {
            jdbc.update("UPDATE students SET is_deleted = 1 WHERE id = ?", id);
            result.put("code", 200);
            result.put("msg", "删除成功");
            return result;
        } catch (Exception ex) {
            log.error("删除学生失败 id={}", id, ex);
            result.put("code", 500);
            result.put("msg", "删除学生失败：" + ex.getMessage());
            return result;
        }
    }

    // 班级学生列表（成绩录入等下拉选择用）
    @GetMapping("/classes/{id}/students")
    public Map<String, Object> classStudents(@PathVariable Long id, HttpServletRequest req) {
        Map<String, Object> result = new HashMap<>();
        try {
            List<Map<String, Object>> list = jdbc.queryForList(
                "SELECT id, name, class_id, parent_phone, parent_name, status FROM students " +
                "WHERE class_id = ? AND (is_deleted IS NULL OR is_deleted = 0) ORDER BY name",
                id);
            result.put("code", 200);
            result.put("data", list);
            return result;
        } catch (Exception ex) {
            log.error("查询班级学生失败 classId={}", id, ex);
            result.put("code", 500);
            result.put("msg", "查询班级学生失败：" + ex.getMessage());
            return result;
        }
    }
}
