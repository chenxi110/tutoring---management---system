package com.skt.controller;

import com.skt.security.RoleAccess;
import com.skt.service.WrongQuestionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import java.util.*;

@RestController
@RequestMapping("/api/wrong-question")
public class WrongQuestionController {

    @Autowired
    private WrongQuestionService wrongQuestionService;

    // 学生错题列表
    @GetMapping("/list")
    public Map<String, Object> list(@RequestParam Long studentId,
                                     @RequestParam(required = false) String subject,
                                     @RequestParam(required = false) String source,
                                     HttpServletRequest req) {
        Map<String, Object> result = new HashMap<>();
        if (!RoleAccess.isTeacher(req)) {
            result.put("code", 403);
            result.put("msg", "无权限");
            return result;
        }
        result.put("code", 200);
        result.put("data", wrongQuestionService.listByStudent(studentId, subject, source));
        return result;
    }

    // 添加错题
    @PostMapping("/add")
    public Map<String, Object> add(@RequestBody Map<String, Object> body, HttpServletRequest req) {
        if (!RoleAccess.isTeacher(req)) {
            Map<String, Object> r = new HashMap<>();
            r.put("code", 403);
            r.put("msg", "无权限");
            return r;
        }
        Long studentId = body.get("studentId") != null ? Long.valueOf(body.get("studentId").toString()) : null;
        String studentName = (String) body.get("studentName");
        Long classId = body.get("classId") != null ? Long.valueOf(body.get("classId").toString()) : null;
        String subject = (String) body.get("subject");
        Long knowledgePointId = body.get("knowledgePointId") != null ? Long.valueOf(body.get("knowledgePointId").toString()) : null;
        String knowledgePointName = (String) body.get("knowledgePointName");
        String questionText = (String) body.get("questionText");
        String studentAnswer = (String) body.get("studentAnswer");
        String correctAnswer = (String) body.get("correctAnswer");
        String analysis = (String) body.get("analysis");
        String source = (String) body.get("source");
        Long sourceId = body.get("sourceId") != null ? Long.valueOf(body.get("sourceId").toString()) : null;
        return wrongQuestionService.addWrongQuestion(studentId, studentName, classId, subject,
            knowledgePointId, knowledgePointName, questionText, studentAnswer, correctAnswer,
            analysis, source, sourceId);
    }

    // 更新掌握程度
    @PostMapping("/update-mastery")
    public Map<String, Object> updateMastery(@RequestBody Map<String, Object> body, HttpServletRequest req) {
        if (!RoleAccess.isTeacher(req)) {
            Map<String, Object> r = new HashMap<>();
            r.put("code", 403);
            r.put("msg", "无权限");
            return r;
        }
        Long id = body.get("id") != null ? Long.valueOf(body.get("id").toString()) : null;
        int masteryLevel = body.get("masteryLevel") != null ? Integer.parseInt(body.get("masteryLevel").toString()) : 0;
        return wrongQuestionService.updateMastery(id, masteryLevel);
    }

    // 错题统计
    @GetMapping("/stats")
    public Map<String, Object> stats(@RequestParam Long studentId, HttpServletRequest req) {
        Map<String, Object> result = new HashMap<>();
        if (!RoleAccess.isTeacher(req)) {
            result.put("code", 403);
            result.put("msg", "无权限");
            return result;
        }
        result.put("code", 200);
        result.put("data", wrongQuestionService.getStats(studentId));
        return result;
    }
}
