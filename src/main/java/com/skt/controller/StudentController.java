package com.skt.controller;

import com.skt.security.RoleAccess;
import com.skt.service.StudentService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import java.util.*;

@RestController
@RequestMapping("/api/student")
public class StudentController {

    @Autowired
    private StudentService studentService;

    // 学生个人信息
    @GetMapping("/info")
    public Map<String, Object> getInfo(HttpServletRequest req) {
        Map<String, Object> result = new HashMap<>();
        Long userId = (Long) req.getAttribute("userId");
        String role = (String) req.getAttribute("role");
        if (!"student".equals(role)) {
            result.put("code", 403);
            result.put("msg", "仅学生可访问");
            return result;
        }
        Map<String, Object> info = studentService.getStudentInfo(userId);
        if (info == null) {
            result.put("code", 404);
            result.put("msg", "学生信息不存在，请联系管理员绑定");
            return result;
        }
        result.put("code", 200);
        result.put("data", info);
        return result;
    }

    // 学生成绩
    @GetMapping("/grades")
    public Map<String, Object> getGrades(HttpServletRequest req) {
        Map<String, Object> result = new HashMap<>();
        Long userId = (Long) req.getAttribute("userId");
        String role = (String) req.getAttribute("role");
        if (!"student".equals(role)) {
            result.put("code", 403);
            result.put("msg", "仅学生可访问");
            return result;
        }
        Map<String, Object> info = studentService.getStudentInfo(userId);
        if (info == null) { result.put("code", 404); result.put("msg", "学生信息不存在"); return result; }
        Long studentId = ((Number) info.get("id")).longValue();
        result.put("code", 200);
        result.put("data", studentService.getStudentGrades(studentId));
        return result;
    }

    // 学生作业
    @GetMapping("/homework")
    public Map<String, Object> getHomework(HttpServletRequest req) {
        Map<String, Object> result = new HashMap<>();
        Long userId = (Long) req.getAttribute("userId");
        String role = (String) req.getAttribute("role");
        if (!"student".equals(role)) {
            result.put("code", 403);
            result.put("msg", "仅学生可访问");
            return result;
        }
        Map<String, Object> info = studentService.getStudentInfo(userId);
        if (info == null) { result.put("code", 404); result.put("msg", "学生信息不存在"); return result; }
        Long studentId = ((Number) info.get("id")).longValue();
        Long classId = info.get("class_id") != null ? ((Number) info.get("class_id")).longValue() : null;
        result.put("code", 200);
        result.put("data", studentService.getStudentHomework(studentId, classId));
        return result;
    }

    // 学生出勤
    @GetMapping("/attendance")
    public Map<String, Object> getAttendance(HttpServletRequest req) {
        Map<String, Object> result = new HashMap<>();
        Long userId = (Long) req.getAttribute("userId");
        String role = (String) req.getAttribute("role");
        if (!"student".equals(role)) {
            result.put("code", 403);
            result.put("msg", "仅学生可访问");
            return result;
        }
        Map<String, Object> info = studentService.getStudentInfo(userId);
        if (info == null) { result.put("code", 404); result.put("msg", "学生信息不存在"); return result; }
        Long studentId = ((Number) info.get("id")).longValue();
        result.put("code", 200);
        result.put("data", studentService.getStudentAttendance(studentId));
        return result;
    }

    // 学生课堂行为统计
    @GetMapping("/behavior-stats")
    public Map<String, Object> getBehaviorStats(HttpServletRequest req) {
        Map<String, Object> result = new HashMap<>();
        Long userId = (Long) req.getAttribute("userId");
        String role = (String) req.getAttribute("role");
        if (!"student".equals(role)) {
            result.put("code", 403);
            result.put("msg", "仅学生可访问");
            return result;
        }
        Map<String, Object> info = studentService.getStudentInfo(userId);
        if (info == null) { result.put("code", 404); result.put("msg", "学生信息不存在"); return result; }
        Long studentId = ((Number) info.get("id")).longValue();
        Long classId = info.get("class_id") != null ? ((Number) info.get("class_id")).longValue() : null;
        result.put("code", 200);
        result.put("data", studentService.getStudentBehaviorStats(studentId, classId));
        return result;
    }
}
