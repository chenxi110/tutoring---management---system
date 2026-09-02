package com.skt.controller;

import com.skt.security.RoleAccess;
import com.skt.service.ParentAiService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import java.util.*;

/**
 * 家长端 / 学生端 AI 学习助手接口
 * 家长：只能查看自己绑定孩子的数据（students.parent_id）
 * 学生：只能查看本人数据（students.user_id 严格隔离）
 */
@RestController
@RequestMapping("/api/parent-ai")
public class ParentAiController {

    @Autowired
    private ParentAiService parentAiService;

    private Map<String, Object> forbid() {
        Map<String, Object> r = new HashMap<>();
        r.put("code", 403);
        r.put("msg", "仅家长/学生账号可访问该功能");
        return r;
    }

    private boolean isParentOrStudent(HttpServletRequest req) {
        return RoleAccess.isParent(req) || RoleAccess.isStudent(req);
    }

    private String role(HttpServletRequest req) {
        return (String) req.getAttribute("role");
    }

    /** 家长绑定的孩子列表 / 学生本人记录 */
    @GetMapping("/children")
    public Map<String, Object> children(HttpServletRequest req) {
        if (!isParentOrStudent(req)) return forbid();
        Long operatorId = RoleAccess.getUserId(req);
        Map<String, Object> r = new HashMap<>();
        r.put("code", 200);
        r.put("data", parentAiService.getChildren(operatorId, role(req)));
        return r;
    }

    /** 孩子学习概览 */
    @GetMapping("/overview")
    public Map<String, Object> overview(@RequestParam Long studentId, HttpServletRequest req) {
        if (!isParentOrStudent(req)) return forbid();
        return parentAiService.getOverview(RoleAccess.getUserId(req), studentId, role(req));
    }

    /** 孩子错题列表 */
    @GetMapping("/wrong-questions")
    public Map<String, Object> wrongQuestions(@RequestParam Long studentId,
                                               @RequestParam(required = false) String subject,
                                               HttpServletRequest req) {
        if (!isParentOrStudent(req)) return forbid();
        return parentAiService.getWrongQuestions(RoleAccess.getUserId(req), studentId, role(req), subject);
    }

    /** 孩子考试提交列表 */
    @GetMapping("/submissions")
    public Map<String, Object> submissions(@RequestParam Long studentId, HttpServletRequest req) {
        if (!isParentOrStudent(req)) return forbid();
        return parentAiService.getSubmissions(RoleAccess.getUserId(req), studentId, role(req));
    }

    /** 单份试卷详情 */
    @GetMapping("/submissions/{submissionId}/paper")
    public Map<String, Object> paper(@PathVariable Long submissionId, HttpServletRequest req) {
        if (!isParentOrStudent(req)) return forbid();
        return parentAiService.getPaper(RoleAccess.getUserId(req), submissionId, role(req));
    }

    /** 生成 AI 学情分析报告 */
    @PostMapping("/analysis")
    public Map<String, Object> analysis(@RequestBody Map<String, Object> body, HttpServletRequest req) {
        if (!isParentOrStudent(req)) return forbid();
        Long studentId = body.get("studentId") != null ? Long.valueOf(body.get("studentId").toString()) : null;
        Long classId = body.get("classId") != null ? Long.valueOf(body.get("classId").toString()) : null;
        String reportType = (String) body.get("reportType");
        return parentAiService.generateAnalysis(RoleAccess.getUserId(req), studentId, classId, role(req), reportType);
    }

    /** 学情报告列表 */
    @GetMapping("/reports")
    public Map<String, Object> reports(@RequestParam Long studentId, HttpServletRequest req) {
        if (!isParentOrStudent(req)) return forbid();
        return parentAiService.listReports(RoleAccess.getUserId(req), studentId, role(req));
    }

    /** 针对孩子的 AI 问答 */
    @PostMapping("/chat")
    public Map<String, Object> chat(@RequestBody Map<String, Object> body, HttpServletRequest req) {
        if (!isParentOrStudent(req)) return forbid();
        Long studentId = body.get("studentId") != null ? Long.valueOf(body.get("studentId").toString()) : null;
        String message = body.get("message") != null ? String.valueOf(body.get("message")) : null;
        return parentAiService.chat(RoleAccess.getUserId(req), studentId, role(req), message);
    }

    /** 针对孩子薄弱点出题（自检） */
    @PostMapping("/generate-questions")
    public Map<String, Object> generateQuestions(@RequestBody Map<String, Object> body, HttpServletRequest req) {
        if (!isParentOrStudent(req)) return forbid();
        Long studentId = body.get("studentId") != null ? Long.valueOf(body.get("studentId").toString()) : null;
        String topic = body.get("topic") != null ? String.valueOf(body.get("topic")) : null;
        int count = 5;
        if (body.get("count") != null) { try { count = Integer.parseInt(String.valueOf(body.get("count"))); } catch (Exception ignored) { } }
        @SuppressWarnings("unchecked")
        List<String> types = (List<String>) body.get("types");
        return parentAiService.generateQuestions(RoleAccess.getUserId(req), studentId, role(req), topic, count, types);
    }

    /** 生成并保存学习方案 */
    @PostMapping("/learning-plan")
    public Map<String, Object> saveLearningPlan(@RequestBody Map<String, Object> body, HttpServletRequest req) {
        if (!isParentOrStudent(req)) return forbid();
        Long studentId = body.get("studentId") != null ? Long.valueOf(body.get("studentId").toString()) : null;
        String focus = body.get("focus") != null ? String.valueOf(body.get("focus")) : null;
        return parentAiService.saveLearningPlan(RoleAccess.getUserId(req), studentId, role(req), focus);
    }

    /** 学习方案列表 */
    @GetMapping("/learning-plans")
    public Map<String, Object> learningPlans(@RequestParam Long studentId, HttpServletRequest req) {
        if (!isParentOrStudent(req)) return forbid();
        return parentAiService.listLearningPlans(RoleAccess.getUserId(req), studentId, role(req));
    }

    /** 更新学习方案进度 */
    @PostMapping("/learning-plan/{planId}/progress")
    public Map<String, Object> updateProgress(@PathVariable Long planId,
                                               @RequestBody(required = false) Map<String, Object> body,
                                               HttpServletRequest req) {
        if (!isParentOrStudent(req)) return forbid();
        String progress = body != null && body.get("progress") != null ? String.valueOf(body.get("progress")) : null;
        String status = body != null && body.get("status") != null ? String.valueOf(body.get("status")) : null;
        return parentAiService.updatePlanProgress(RoleAccess.getUserId(req), planId, role(req), progress, status);
    }
}
