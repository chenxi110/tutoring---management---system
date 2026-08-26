package com.skt.controller;

import com.skt.security.RoleAccess;
import com.skt.service.OperationLogService;
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
    @Autowired
    private OperationLogService operationLogService;

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

    // ===== 学生账号绑定/解绑（仅教师/管理员） =====

    // 查询可绑定的学生账号列表
    @GetMapping("/bindable-accounts")
    public Map<String, Object> getBindableAccounts(HttpServletRequest req) {
        Map<String, Object> result = new HashMap<>();
        if (!RoleAccess.isTeacher(req)) {
            result.put("code", 403);
            result.put("msg", "无权限：仅教师/管理员可查看可绑定账号");
            return result;
        }
        result.put("code", 200);
        result.put("data", studentService.getBindableAccounts());
        return result;
    }

    // 绑定学生账号
    @PostMapping("/bind")
    public Map<String, Object> bindAccount(@RequestBody Map<String, Object> body, HttpServletRequest req) {
        Map<String, Object> result = new HashMap<>();
        if (!RoleAccess.isTeacher(req)) {
            result.put("code", 403);
            result.put("msg", "无权限：仅教师/管理员可绑定学生账号");
            return result;
        }
        Long studentId = body.get("studentId") != null ? Long.valueOf(body.get("studentId").toString()) : null;
        Long userId = body.get("userId") != null ? Long.valueOf(body.get("userId").toString()) : null;
        Map<String, Object> bindResult = studentService.bindAccount(studentId, userId);
        // 操作日志
        if ("200".equals(String.valueOf(bindResult.get("code")))) {
            Long operatorId = (Long) req.getAttribute("userId");
            String operatorRole = (String) req.getAttribute("role");
            String operatorName = (String) req.getAttribute("displayName");
            operationLogService.log(operatorId, operatorName != null ? operatorName : "operator_"+operatorId,
                operatorRole, "学生绑定",
                "绑定学生["+bindResult.get("studentName")+"]到账号["+bindResult.get("username")+"]",
                req.getRemoteAddr());
        }
        return bindResult;
    }

    // 解绑学生账号
    @PostMapping("/unbind")
    public Map<String, Object> unbindAccount(@RequestBody Map<String, Object> body, HttpServletRequest req) {
        Map<String, Object> result = new HashMap<>();
        if (!RoleAccess.isTeacher(req)) {
            result.put("code", 403);
            result.put("msg", "无权限：仅教师/管理员可解绑学生账号");
            return result;
        }
        Long studentId = body.get("studentId") != null ? Long.valueOf(body.get("studentId").toString()) : null;
        Map<String, Object> unbindResult = studentService.unbindAccount(studentId);
        // 操作日志
        if ("200".equals(String.valueOf(unbindResult.get("code")))) {
            Long operatorId = (Long) req.getAttribute("userId");
            String operatorRole = (String) req.getAttribute("role");
            String operatorName = (String) req.getAttribute("displayName");
            operationLogService.log(operatorId, operatorName != null ? operatorName : "operator_"+operatorId,
                operatorRole, "学生解绑",
                "解绑学生["+unbindResult.get("studentName")+"]的账号",
                req.getRemoteAddr());
        }
        return unbindResult;
    }
}
