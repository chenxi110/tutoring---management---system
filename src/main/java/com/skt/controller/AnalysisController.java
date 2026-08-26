package com.skt.controller;

import com.skt.security.RoleAccess;
import com.skt.service.AnalysisService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import java.util.*;

@RestController
@RequestMapping("/api/analysis")
public class AnalysisController {

    @Autowired
    private AnalysisService analysisService;

    // 学生个体学情分析
    @GetMapping("/student/{studentId}")
    public Map<String, Object> getStudentAnalysis(@PathVariable Long studentId,
                                                    @RequestParam(required = false) Long classId,
                                                    HttpServletRequest req) {
        Map<String, Object> result = new HashMap<>();
        if (!RoleAccess.isTeacher(req)) {
            result.put("code", 403);
            result.put("msg", "无权限");
            return result;
        }
        result.put("code", 200);
        result.put("data", analysisService.getStudentAnalysis(studentId, classId));
        return result;
    }

    // 班级学情分析
    @GetMapping("/class/{classId}")
    public Map<String, Object> getClassAnalysis(@PathVariable Long classId, HttpServletRequest req) {
        Map<String, Object> result = new HashMap<>();
        if (!RoleAccess.isTeacher(req)) {
            result.put("code", 403);
            result.put("msg", "无权限");
            return result;
        }
        result.put("code", 200);
        result.put("data", analysisService.getClassAnalysis(classId));
        return result;
    }
}
