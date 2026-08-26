package com.skt.controller;

import com.skt.security.RoleAccess;
import com.skt.service.AIAnalysisService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import java.util.*;

@RestController
@RequestMapping("/api/ai-analysis")
public class AIAnalysisController {

    @Autowired
    private AIAnalysisService aiAnalysisService;

    // 生成AI学情分析报告
    @PostMapping("/generate")
    public Map<String, Object> generate(@RequestBody Map<String, Object> body, HttpServletRequest req) {
        if (!RoleAccess.isTeacher(req)) {
            Map<String, Object> r = new HashMap<>();
            r.put("code", 403);
            r.put("msg", "无权限");
            return r;
        }
        Long studentId = body.get("studentId") != null ? Long.valueOf(body.get("studentId").toString()) : null;
        Long classId = body.get("classId") != null ? Long.valueOf(body.get("classId").toString()) : null;
        String reportType = (String) body.get("reportType");
        return aiAnalysisService.generateReport(studentId, classId, reportType);
    }

    // 报告列表
    @GetMapping("/list")
    public Map<String, Object> list(@RequestParam Long studentId, HttpServletRequest req) {
        Map<String, Object> result = new HashMap<>();
        if (!RoleAccess.isTeacher(req)) {
            result.put("code", 403);
            result.put("msg", "无权限");
            return result;
        }
        result.put("code", 200);
        result.put("data", aiAnalysisService.listReports(studentId));
        return result;
    }

    // 报告详情
    @GetMapping("/{id}")
    public Map<String, Object> detail(@PathVariable Long id, HttpServletRequest req) {
        Map<String, Object> result = new HashMap<>();
        if (!RoleAccess.isTeacher(req)) {
            result.put("code", 403);
            result.put("msg", "无权限");
            return result;
        }
        Map<String, Object> report = aiAnalysisService.getReport(id);
        if (report == null) {
            result.put("code", 404);
            result.put("msg", "报告不存在");
            return result;
        }
        result.put("code", 200);
        result.put("data", report);
        return result;
    }
}
