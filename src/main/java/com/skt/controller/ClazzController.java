package com.skt.controller;

import com.skt.security.RoleAccess;
import com.skt.service.ClazzService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import java.util.*;

@RestController
@RequestMapping("/api")
public class ClazzController {

    @Autowired
    private ClazzService clazzService;

    @Autowired
    private JdbcTemplate jdbc;

    @GetMapping("/semesters")
    public Map<String, Object> listSemesters() {
        List<Map<String, Object>> list = clazzService.listSemesters();
        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("data", list);
        return result;
    }

    @PostMapping("/semesters")
    public Map<String, Object> createSemester(@RequestBody Map<String, Object> body, HttpServletRequest req) {
        if (RoleAccess.isParent(req)) {
            return RoleAccess.forbidParentWrite("家长账号无权管理学期");
        }
        String name = (String) body.get("name");
        String startDate = (String) body.get("start_date");
        String endDate = (String) body.get("end_date");
        boolean isActive = body.get("is_active") != null && (
            body.get("is_active").equals(true) || body.get("is_active").equals(1));
        Long id = clazzService.createSemester(name, startDate, endDate, isActive);
        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("id", id);
        return result;
    }

    @PutMapping("/semesters/{id}")
    public Map<String, Object> updateSemester(@PathVariable Long id, @RequestBody Map<String, Object> body, HttpServletRequest req) {
        if (RoleAccess.isParent(req)) {
            return RoleAccess.forbidParentWrite("家长账号无权管理学期");
        }
        String name = (String) body.get("name");
        String startDate = (String) body.get("start_date");
        String endDate = (String) body.get("end_date");
        boolean isActive = body.get("is_active") != null && (
            body.get("is_active").equals(true) || body.get("is_active").equals(1));
        clazzService.updateSemester(id, name, startDate, endDate, isActive);
        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        return result;
    }

    @GetMapping("/classes")
    public Map<String, Object> listClasses(@RequestParam(required = false) Long semesterId,
                                           HttpServletRequest req) {
        Long teacherId = (Long) req.getAttribute("userId");
        String role = (String) req.getAttribute("role");
        List<Map<String, Object>> list;
        if ("parent".equals(role)) {
            // 家长只能看到自己孩子所在的班级
            List<Long> childClassIds = jdbc.queryForList(
                "SELECT DISTINCT class_id FROM students WHERE parent_user_id = ? AND is_deleted = 0 AND class_id IS NOT NULL",
                Long.class, teacherId);
            if (childClassIds.isEmpty()) {
                list = new ArrayList<>();
            } else {
                String placeholders = String.join(",", Collections.nCopies(childClassIds.size(), "?"));
                String sql = "SELECT c.*, s.name as semester_name, (SELECT COUNT(*) FROM students st WHERE st.class_id = c.id AND st.is_deleted = 0) as student_count FROM classes c LEFT JOIN semesters s ON c.semester_id = s.id WHERE c.id IN (" + placeholders + ")";
                if (semesterId != null) {
                    sql += " AND c.semester_id = ?";
                    list = jdbc.queryForList(sql, childClassIds.toArray(), semesterId);
                } else {
                    list = jdbc.queryForList(sql, childClassIds.toArray());
                }
            }
        } else {
            Long tid = "teacher".equals(role) ? teacherId : null;
            list = clazzService.listClasses(tid, semesterId);
        }
        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("data", list);
        return result;
    }

    @GetMapping("/classes/my")
    public Map<String, Object> myClasses(@RequestParam(required = false) Long semesterId,
                                         HttpServletRequest req) {
        if (!RoleAccess.isTeacher(req)) {
            return RoleAccess.forbidTeacherOnly("仅教师账号可查看个人班级");
        }
        Long teacherId = (Long) req.getAttribute("userId");
        List<Map<String, Object>> list = clazzService.myClasses(teacherId, semesterId);
        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("data", list);
        return result;
    }

    @PostMapping("/classes")
    public Map<String, Object> createClass(@RequestBody Map<String, Object> body, HttpServletRequest req) {
        if (RoleAccess.isParent(req)) {
            return RoleAccess.forbidParentWrite("家长账号无权创建班级");
        }
        String name = (String) body.get("name");
        String course = (String) body.get("course");
        Long semesterId = body.get("semesterId") != null ? ((Number) body.get("semesterId")).longValue() : null;
        Long teacherId = (Long) req.getAttribute("userId");
        // 同一教师、同一学年（学期）内班级名唯一，禁止重复创建
        if (semesterId == null) {
            Map<String, Object> active = jdbc.queryForMap("SELECT id FROM semesters WHERE is_active=1 LIMIT 1");
            semesterId = ((Number) active.get("id")).longValue();
        }
        if (clazzService.existsSameName(teacherId, name, semesterId, null)) {
            Map<String, Object> dup = new HashMap<>();
            dup.put("code", 400);
            dup.put("msg", "同一学年内已存在同名班级，请勿重复创建");
            return dup;
        }
        Long id = clazzService.createClass(name, course, semesterId, teacherId);
        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("id", id);
        return result;
    }

    @PutMapping("/classes/{id}")
    public Map<String, Object> updateClass(@PathVariable Long id, @RequestBody Map<String, Object> body, HttpServletRequest req) {
        if (RoleAccess.isParent(req)) {
            return RoleAccess.forbidParentWrite("家长账号无权修改班级");
        }
        String name = (String) body.get("name");
        String course = (String) body.get("course");
        Long semesterId = body.get("semesterId") != null ? ((Number) body.get("semesterId")).longValue() : null;
        // 编辑班级同样校验同名（同一教师同学期，排除自身）
        Long origTeacher = jdbc.queryForObject("SELECT teacher_id FROM classes WHERE id=?", Long.class, id);
        if (clazzService.existsSameName(origTeacher, name, semesterId, id)) {
            Map<String, Object> dup = new HashMap<>();
            dup.put("code", 400);
            dup.put("msg", "同一学年内已存在同名班级，请勿重复");
            return dup;
        }
        clazzService.updateClass(id, name, course, semesterId);
        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        return result;
    }

    @DeleteMapping("/classes/{id}")
    public Map<String, Object> deleteClass(@PathVariable Long id, HttpServletRequest req) {
        if (RoleAccess.isParent(req)) {
            return RoleAccess.forbidParentWrite("家长账号无权删除班级");
        }
        clazzService.deleteClass(id);
        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        return result;
    }
}
