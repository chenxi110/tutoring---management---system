package com.skt.security;

import io.jsonwebtoken.Claims;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.PrintWriter;

@Component
public class JwtAuthFilter implements HandlerInterceptor {

    @Autowired
    private JwtUtil jwtUtil;

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) throws Exception {
        String path = req.getRequestURI();

        if ("OPTIONS".equalsIgnoreCase(req.getMethod())) {
            return true;
        }

        if (isPublicPath(path)) {
            return true;
        }

        String authHeader = req.getHeader("Authorization");
        String token = null;
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            token = authHeader.substring(7);
        } else {
            String queryToken = req.getParameter("token");
            if (queryToken != null && !queryToken.isEmpty()) {
                token = queryToken;
            }
        }
        if (token == null) {
            res.setStatus(401);
            res.setContentType("application/json;charset=UTF-8");
            PrintWriter w = res.getWriter();
            w.write("{\"code\":401,\"error\":\"未登录，请先登录\"}");
            return false;
        }
        if (!jwtUtil.validateToken(token)) {
            res.setStatus(401);
            res.setContentType("application/json;charset=UTF-8");
            PrintWriter w = res.getWriter();
            w.write("{\"code\":401,\"error\":\"登录已过期，请重新登录\"}");
            return false;
        }

        Claims claims = jwtUtil.parseToken(token);
        req.setAttribute("userId", claims.get("id", Long.class));
        req.setAttribute("username", claims.get("username", String.class));
        req.setAttribute("role", claims.get("role", String.class));
        req.setAttribute("displayName", claims.get("displayName", String.class));
        return true;
    }

    private boolean isPublicPath(String path) {
        if (path.startsWith("/api/auth/login") || path.startsWith("/api/auth/register")) {
            return true;
        }
        if (path.startsWith("/api/sse")) {
            return true;
        }
        if (path.startsWith("/api/share/validate")) {
            return true;
        }
        // 学生端考试接口：通过姓名+班级校验权限，不需要JWT登录
        if (path.startsWith("/api/exam/getStudentExamInfo") || path.startsWith("/api/exam/submit")) {
            return true;
        }
        if (path.equals("/health") || path.equals("/")) {
            return true;
        }
        if (!path.startsWith("/api/")) {
            return true;
        }
        return false;
    }
}
