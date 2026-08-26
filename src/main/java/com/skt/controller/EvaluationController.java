package com.skt.controller;

import com.skt.security.RoleAccess;
import com.skt.service.EvaluationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import java.util.*;

@RestController
@RequestMapping("/api/evaluation")
public class EvaluationController {

    @Autowired
    private EvaluationService evaluationService;

    // 提交评价
    @PostMapping("/submit")
    public Map<String, Object> submit(@RequestBody Map<String, Object> body, HttpServletRequest req) {
        Long classId = body.get("classId") != null ? Long.valueOf(body.get("classId").toString()) : null;
        String courseName = (String) body.get("courseName");
        Long teacherId = body.get("teacherId") != null ? Long.valueOf(body.get("teacherId").toString()) : null;
        String teacherName = (String) body.get("teacherName");
        Long studentId = body.get("studentId") != null ? Long.valueOf(body.get("studentId").toString()) : null;
        String studentName = (String) body.get("studentName");
        Long parentId = (Long) req.getAttribute("userId");
        int score = body.get("score") != null ? Integer.parseInt(body.get("score").toString()) : 0;
        String content = (String) body.get("content");
        String tags = (String) body.get("tags");
        return evaluationService.submitEvaluation(classId, courseName, teacherId, teacherName,
            studentId, studentName, parentId, score, content, tags);
    }

    // 班级评价列表
    @GetMapping("/class/{classId}")
    public Map<String, Object> listByClass(@PathVariable Long classId, HttpServletRequest req) {
        Map<String, Object> result = new HashMap<>();
        if (!RoleAccess.isTeacher(req)) {
            result.put("code", 403);
            result.put("msg", "无权限");
            return result;
        }
        result.put("code", 200);
        result.put("data", evaluationService.listByClass(classId));
        return result;
    }

    // 教师评价统计
    @GetMapping("/teacher/stats")
    public Map<String, Object> teacherStats(@RequestParam Long teacherId, HttpServletRequest req) {
        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("data", evaluationService.getTeacherStats(teacherId));
        return result;
    }
}
