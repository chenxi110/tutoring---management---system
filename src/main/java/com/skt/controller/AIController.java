package com.skt.controller;

import com.skt.service.AIService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import java.util.*;

@RestController
@RequestMapping("/api/ai")
public class AIController {

    @Autowired
    private AIService aiService;

    @PostMapping("/chat")
    public Map<String, Object> chat(@RequestBody Map<String, Object> body, HttpServletRequest req) {
        Long userId = (Long) req.getAttribute("userId");
        String role = (String) req.getAttribute("role");

        if (userId == null) {
            Map<String, Object> r = new HashMap<>();
            r.put("code", 401);
            r.put("error", "未登录");
            return r;
        }

        if (role == null) role = "teacher";
        int limit = "teacher".equals(role) ? 50 : 20;
        int recentCount = aiService.getRecentChatCount(userId);
        if (recentCount >= limit) {
            Map<String, Object> r = new HashMap<>();
            r.put("code", 429);
            r.put("error", "调用频次超限（每小时" + limit + "次），请稍后再试");
            return r;
        }

        String prompt = body.get("message") != null ? String.valueOf(body.get("message")).trim() : null;
        String sessionId = body.get("sessionId") != null ? String.valueOf(body.get("sessionId")) : null;

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> history = (List<Map<String, Object>>) body.get("history");

        return aiService.chat(userId, sessionId, prompt, history);
    }

    @GetMapping("/history")
    public Map<String, Object> history(HttpServletRequest req) {
        Long userId = (Long) req.getAttribute("userId");
        Map<String, Object> result = new HashMap<>();
        if (userId == null) {
            result.put("code", 401);
            result.put("error", "未登录");
            return result;
        }
        result.put("code", 200);
        result.put("data", aiService.getHistory(userId));
        return result;
    }

    @DeleteMapping("/history")
    public Map<String, Object> clearHistory(HttpServletRequest req) {
        Long userId = (Long) req.getAttribute("userId");
        if (userId == null) {
            Map<String, Object> r = new HashMap<>();
            r.put("code", 401);
            r.put("error", "未登录");
            return r;
        }
        return aiService.clearHistory(userId);
    }

    @GetMapping("/config")
    public Map<String, Object> config() {
        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        List<Map<String, Object>> list = new ArrayList<>();
        list.add(aiService.getConfigForApi());
        result.put("data", list);
        return result;
    }

    @PostMapping("/config")
    public Map<String, Object> saveConfig(@RequestBody Map<String, Object> body) {
        String provider = body.get("provider") != null ? String.valueOf(body.get("provider")) : null;
        String apiKey = body.get("apiKey") != null ? String.valueOf(body.get("apiKey")) : null;
        String model = body.get("model") != null ? String.valueOf(body.get("model")) : null;
        String baseUrl = body.get("baseUrl") != null ? String.valueOf(body.get("baseUrl")) : null;
        return aiService.saveConfig(provider, apiKey, model, baseUrl);
    }

    @PostMapping("/generate-questions")
    public Map<String, Object> generateQuestions(@RequestBody Map<String, Object> body) {
        String topic = body.get("topic") != null ? String.valueOf(body.get("topic")) : null;
        int count = 5;
        if (body.get("count") != null) {
            try { count = Integer.parseInt(String.valueOf(body.get("count"))); } catch (Exception ignored) { }
        }
        @SuppressWarnings("unchecked")
        List<String> types = (List<String>) body.get("types");
        return aiService.generateQuestions(topic, count, types);
    }
}
