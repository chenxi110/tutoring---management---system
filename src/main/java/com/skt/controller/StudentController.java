package com.skt.controller;

import com.skt.security.RoleAccess;
import com.skt.service.StudentService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import java.util.*;

@RestController
@RequestMapping("/api")
public class StudentController {

    @Autowired
    private StudentService studentService;

    @GetMapping("/classes/{classId}/students")
    public Map<String, Object> listByClass(@PathVariable Long classId, HttpServletRequest req) {
        if (RoleAccess.isParent(req)) {
            return RoleAccess.forbidTeacherOnly("家长账号无权查看班级学生列表");
        }
        List<Map<String, Object>> list = studentService.listByClass(classId);
        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("data", list);
        return result;
    }

    @GetMapping("/students")
    public Map<String, Object> listAll(@RequestParam(required = false) Long classId, HttpServletRequest req) {
        if (RoleAccess.isParent(req)) {
            Long parentId = RoleAccess.getUserId(req);
            List<Map<String, Object>> list = studentService.listByParent(parentId);
            Map<String, Object> result = new HashMap<>();
            result.put("code", 200);
            result.put("data", list);
            return result;
        }
        List<Map<String, Object>> list = studentService.listAll(classId);
        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("data", list);
        return result;
    }

    @GetMapping("/students/{id}")
    public Map<String, Object> getDetail(@PathVariable Long id, HttpServletRequest req) {
        if (RoleAccess.isParent(req)) {
            Long parentId = RoleAccess.getUserId(req);
            List<Map<String, Object>> children = studentService.listByParent(parentId);
            boolean isBound = children.stream().anyMatch(c -> {
                Object sid = c.get("id");
                return sid instanceof Number && ((Number) sid).longValue() == id;
            });
            if (!isBound) {
                return RoleAccess.forbidTeacherOnly("家长只能查看已绑定孩子的信息");
            }
        }
        Map<String, Object> student = studentService.getDetail(id);
        Map<String, Object> result = new HashMap<>();
        if (student == null) {
            result.put("code", 404);
            result.put("error", "学生不存在");
        } else {
            result.put("code", 200);
            result.put("data", student);
        }
        return result;
    }

    @PostMapping("/students")
    public Map<String, Object> create(@RequestBody Map<String, Object> body, HttpServletRequest req) {
        if (RoleAccess.isParent(req)) {
            return RoleAccess.forbidParentWrite("家长账号无权新增学生信息");
        }
        String name = (String) body.get("name");
        Long classId = body.get("classId") != null ? ((Number) body.get("classId")).longValue() : null;
        String phone = (String) body.get("phone");
        String parentPhone = (String) body.get("parentPhone");
        String parentName = (String) body.get("parentName");
        String parentRelation = (String) body.get("parentRelation");
        Long id = studentService.create(name, classId, phone, parentPhone, parentName, parentRelation);
        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("id", id);
        return result;
    }

    @PutMapping("/students/{id}")
    public Map<String, Object> update(@PathVariable Long id, @RequestBody Map<String, Object> body, HttpServletRequest req) {
        if (RoleAccess.isParent(req)) {
            return RoleAccess.forbidParentWrite("家长账号无权修改学生信息");
        }
        String name = (String) body.get("name");
        Long classId = body.get("classId") != null ? ((Number) body.get("classId")).longValue() : null;
        String phone = (String) body.get("phone");
        String parentPhone = (String) body.get("parentPhone");
        String parentName = (String) body.get("parentName");
        String parentRelation = (String) body.get("parentRelation");
        String status = (String) body.get("status");
        String tags = (String) body.get("tags");
        studentService.update(id, name, classId, phone, parentPhone, parentName, parentRelation, status, tags);
        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        return result;
    }

    @DeleteMapping("/students/{id}")
    public Map<String, Object> delete(@PathVariable Long id, HttpServletRequest req) {
        if (RoleAccess.isParent(req)) {
            return RoleAccess.forbidParentWrite("家长账号无权删除学生信息");
        }
        studentService.delete(id);
        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        return result;
    }
}
