package com.skt.controller;

import com.skt.security.RoleAccess;
import com.skt.service.KnowledgeService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import java.util.*;

@RestController
@RequestMapping("/api/knowledge")
public class KnowledgeController {

    @Autowired
    private KnowledgeService knowledgeService;

    // 知识点树
    @GetMapping("/tree")
    public Map<String, Object> getTree(@RequestParam(required = false) String subject) {
        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("data", knowledgeService.getKnowledgeTree(subject));
        return result;
    }

    // 新增知识点
    @PostMapping("/add")
    public Map<String, Object> add(@RequestBody Map<String, Object> body, HttpServletRequest req) {
        if (!RoleAccess.isTeacher(req)) {
            Map<String, Object> r = new HashMap<>();
            r.put("code", 403);
            r.put("msg", "无权限");
            return r;
        }
        String name = (String) body.get("name");
        String subject = (String) body.get("subject");
        String gradeLevel = (String) body.get("gradeLevel");
        String description = (String) body.get("description");
        int difficulty = body.get("difficulty") != null ? Integer.parseInt(body.get("difficulty").toString()) : 1;
        Long parentId = body.get("parentId") != null ? Long.valueOf(body.get("parentId").toString()) : null;
        int sortOrder = body.get("sortOrder") != null ? Integer.parseInt(body.get("sortOrder").toString()) : 0;
        return knowledgeService.addKnowledgePoint(name, subject, gradeLevel, description, difficulty, parentId, sortOrder);
    }

    // 学生知识点掌握情况
    @GetMapping("/student-mastery")
    public Map<String, Object> studentMastery(@RequestParam Long studentId,
                                                @RequestParam(required = false) String subject,
                                                HttpServletRequest req) {
        Map<String, Object> result = new HashMap<>();
        if (!RoleAccess.isTeacher(req)) {
            result.put("code", 403);
            result.put("msg", "无权限");
            return result;
        }
        result.put("code", 200);
        result.put("data", knowledgeService.getStudentMastery(studentId, subject));
        return result;
    }
}
