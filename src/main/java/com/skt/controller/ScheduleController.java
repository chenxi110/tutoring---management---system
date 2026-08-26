package com.skt.controller;

import com.skt.security.RoleAccess;
import com.skt.service.ScheduleService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import java.util.*;

@RestController
@RequestMapping("/api")
public class ScheduleController {

    @Autowired
    private ScheduleService scheduleService;

    @GetMapping("/schedules")
    public Map<String, Object> list(@RequestParam(required = false) Long classId,
                                    @RequestParam(required = false) Long semesterId,
                                    HttpServletRequest req) {
        Long teacherId = (Long) req.getAttribute("userId");
        String role = (String) req.getAttribute("role");
        Long tid = "teacher".equals(role) ? teacherId : null;
        List<Map<String, Object>> list = scheduleService.list(classId, semesterId, tid);
        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("data", list);
        return result;
    }

    @GetMapping("/schedules/my")
    public Map<String, Object> mySchedules(@RequestParam(required = false) Long semesterId,
                                           HttpServletRequest req) {
        if (!RoleAccess.isTeacher(req)) {
            return RoleAccess.forbidTeacherOnly("仅教师账号可查看个人课表");
        }
        Long teacherId = (Long) req.getAttribute("userId");
        List<Map<String, Object>> list = scheduleService.mySchedules(teacherId, semesterId);
        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("data", list);
        return result;
    }

    @GetMapping("/schedules/child")
    public Map<String, Object> childSchedules(HttpServletRequest req) {
        if (!RoleAccess.isParent(req)) {
            return RoleAccess.forbidParentWrite("仅家长账号可查看孩子课表");
        }
        Long parentId = (Long) req.getAttribute("userId");
        List<Map<String, Object>> list = scheduleService.childSchedules(parentId);
        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("data", list);
        return result;
    }

    @GetMapping("/schedules/next")
    public Map<String, Object> nextClass(HttpServletRequest req) {
        Long userId = (Long) req.getAttribute("userId");
        String role = (String) req.getAttribute("role");
        Map<String, Object> info = scheduleService.nextClass(userId, role);
        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("data", info);
        return result;
    }

    @PostMapping("/schedules")
    public Map<String, Object> create(@RequestBody Map<String, Object> body, HttpServletRequest req) {
        if (RoleAccess.isParent(req)) {
            return RoleAccess.forbidParentWrite("家长账号无权编辑课表");
        }
        Long teacherId = (Long) req.getAttribute("userId");
        Long id = scheduleService.create(
            body.get("weekday") != null ? ((Number) body.get("weekday")).intValue() : null,
            (String) body.get("startTime"),
            (String) body.get("endTime"),
            body.get("classId") != null ? ((Number) body.get("classId")).longValue() : null,
            (String) body.get("className"),
            (String) body.get("course"),
            body.get("sessions") != null ? ((Number) body.get("sessions")).intValue() : null,
            body.get("semesterId") != null ? ((Number) body.get("semesterId")).longValue() : null,
            teacherId
        );
        Map<String, Object> result = new HashMap<>();
        if (id == null) {
            result.put("code", 403);
            result.put("error", "无权操作该班级课表");
        } else {
            result.put("code", 200);
            result.put("id", id);
        }
        return result;
    }

    @PostMapping("/schedules/batch")
    public Map<String, Object> batchCreate(@RequestBody Map<String, Object> body, HttpServletRequest req) {
        if (RoleAccess.isParent(req)) {
            return RoleAccess.forbidParentWrite("家长账号无权批量编辑课表");
        }
        Long teacherId = (Long) req.getAttribute("userId");
        Long classId = body.get("classId") != null ? ((Number) body.get("classId")).longValue() : null;
        List<Map<String, Object>> entries = (List<Map<String, Object>>) body.get("entries");
        String startTime = (String) body.get("startTime");
        String endTime = (String) body.get("endTime");
        Integer sessions = body.get("sessions") != null ? ((Number) body.get("sessions")).intValue() : null;
        String course = (String) body.get("course");
        int count = scheduleService.batchCreate(classId, entries, startTime, endTime, sessions, course, teacherId);
        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("count", count);
        return result;
    }

    @DeleteMapping("/schedules/{id}")
    public Map<String, Object> delete(@PathVariable Long id, HttpServletRequest req) {
        if (RoleAccess.isParent(req)) {
            return RoleAccess.forbidParentWrite("家长账号无权删除课表");
        }
        Long teacherId = (Long) req.getAttribute("userId");
        scheduleService.delete(id, teacherId);
        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        return result;
    }
}
