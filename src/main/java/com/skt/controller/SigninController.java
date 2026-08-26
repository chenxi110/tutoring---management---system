package com.skt.controller;

import com.skt.service.SigninService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import java.util.*;

@RestController
@RequestMapping("/api/signin")
public class SigninController {

    @Autowired
    private SigninService signinService;

    // Teacher creates a signin task
    @PostMapping("/create")
    public Map<String, Object> createSignin(@RequestBody Map<String, Object> body, HttpServletRequest req) {
        Long userId = (Long) req.getAttribute("userId");
        String role = (String) req.getAttribute("role");

        if (!"teacher".equals(role)) {
            Map<String, Object> r = new HashMap<>();
            r.put("code", 403);
            r.put("error", "仅教师可发布签到");
            return r;
        }

        Long classId = body.get("classId") != null ? Long.valueOf(String.valueOf(body.get("classId"))) : null;
        String className = body.get("className") != null ? String.valueOf(body.get("className")) : "";
        String signType = body.get("signType") != null ? String.valueOf(body.get("signType")) : "password";
        String password = body.get("password") != null ? String.valueOf(body.get("password")) : null;
        int duration = body.get("duration") != null ? Integer.parseInt(String.valueOf(body.get("duration"))) : 10;

        return signinService.createSignin(userId, classId, className, signType, password, duration);
    }

    // Get active signin tasks for current user (parent sees only their children's)
    @GetMapping("/active")
    public Map<String, Object> getActiveSignins(HttpServletRequest req) {
        Long userId = (Long) req.getAttribute("userId");
        String role = (String) req.getAttribute("role");

        Map<String, Object> result = new HashMap<>();
        if (userId == null) {
            result.put("code", 401);
            result.put("error", "未登录");
            return result;
        }

        signinService.expireOverdueSignins();

        if ("parent".equals(role)) {
            result.put("code", 200);
            result.put("data", signinService.getActiveSigninsForParent(userId));
        } else {
            // Teacher sees their own signin tasks
            result.put("code", 200);
            result.put("data", signinService.getSigninRecords(userId, null));
        }
        return result;
    }

    // Parent submits signin
    @PostMapping("/submit")
    public Map<String, Object> submitSignin(@RequestBody Map<String, Object> body, HttpServletRequest req) {
        Long userId = (Long) req.getAttribute("userId");
        String role = (String) req.getAttribute("role");

        if (!"parent".equals(role)) {
            Map<String, Object> r = new HashMap<>();
            r.put("code", 403);
            r.put("error", "仅家长可提交签到");
            return r;
        }

        Long signinId = body.get("signinId") != null ? Long.valueOf(String.valueOf(body.get("signinId"))) : null;
        Long studentId = body.get("studentId") != null ? Long.valueOf(String.valueOf(body.get("studentId"))) : null;
        String password = body.get("password") != null ? String.valueOf(body.get("password")).trim() : null;

        if (signinId == null || studentId == null || password == null) {
            Map<String, Object> r = new HashMap<>();
            r.put("code", 400);
            r.put("error", "参数缺失");
            return r;
        }

        return signinService.submitSignin(userId, signinId, studentId, password);
    }

    // Get signin records (teacher: all their signins; parent: their children's history)
    @GetMapping("/records")
    public Map<String, Object> getRecords(@RequestParam(required = false) Long signinId, HttpServletRequest req) {
        Long userId = (Long) req.getAttribute("userId");
        String role = (String) req.getAttribute("role");

        Map<String, Object> result = new HashMap<>();
        if (userId == null) {
            result.put("code", 401);
            result.put("error", "未登录");
            return result;
        }

        if ("parent".equals(role)) {
            result.put("code", 200);
            result.put("data", signinService.getParentSigninHistory(userId));
        } else {
            result.put("code", 200);
            result.put("data", signinService.getSigninRecords(userId, signinId));
        }
        return result;
    }

    // Teacher stops a signin task
    @PostMapping("/stop")
    public Map<String, Object> stopSignin(@RequestBody Map<String, Object> body, HttpServletRequest req) {
        Long userId = (Long) req.getAttribute("userId");
        String role = (String) req.getAttribute("role");

        if (!"teacher".equals(role)) {
            Map<String, Object> r = new HashMap<>();
            r.put("code", 403);
            r.put("error", "仅教师可停止签到");
            return r;
        }

        Long signinId = body.get("signinId") != null ? Long.valueOf(String.valueOf(body.get("signinId"))) : null;
        if (signinId == null) {
            Map<String, Object> r = new HashMap<>();
            r.put("code", 400);
            r.put("error", "参数缺失");
            return r;
        }

        return signinService.stopSignin(userId, signinId);
    }
}
