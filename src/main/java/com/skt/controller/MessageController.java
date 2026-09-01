package com.skt.controller;

import com.skt.security.JwtUtil;
import com.skt.security.RoleAccess;
import com.skt.service.MessageService;
import com.skt.service.OperationLogService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import jakarta.servlet.http.HttpServletRequest;
import java.util.*;

@RestController
@RequestMapping("/api")
public class MessageController {

    @Autowired
    private MessageService messageService;
    @Autowired
    private JwtUtil jwtUtil;
    @Autowired
    private OperationLogService operationLogService;

    @GetMapping("/messages")
    public Map<String, Object> listMessages(HttpServletRequest req) {
        Long userId = (Long) req.getAttribute("userId");
        String role = (String) req.getAttribute("role");
        List<Map<String, Object>> list = messageService.listMessages(userId, role);
        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("data", list);
        return result;
    }

    @PostMapping("/messages")
    public Map<String, Object> sendMessage(@RequestBody Map<String, Object> body, HttpServletRequest req) {
        Long senderId = (Long) req.getAttribute("userId");
        String senderName = (String) req.getAttribute("displayName");
        String senderRole = (String) req.getAttribute("role");

        String title = body.get("title") != null ? body.get("title").toString() : "";
        String content = body.get("content") != null ? body.get("content").toString() : "";
        Long studentId = body.get("studentId") != null ? Long.valueOf(body.get("studentId").toString()) : null;
        Long classId = body.get("classId") != null ? Long.valueOf(body.get("classId").toString()) : null;
        Long receiverId = body.get("receiverId") != null ? Long.valueOf(body.get("receiverId").toString()) : null;
        // 兼容msgType和type两个字段名；有receiverId时默认为私信consult
        String msgType = body.get("msgType") != null ? body.get("msgType").toString()
                : (body.get("type") != null ? body.get("type").toString()
                : (receiverId != null ? "consult" : "notice"));
        // 统一私信类型标识：private和consult都视为私信
        if ("private".equals(msgType)) msgType = "consult";

        if (RoleAccess.isParent(req) && "notice".equals(msgType)) {
            return RoleAccess.forbidParentWrite("家长账号无权发布通知");
        }
        if (RoleAccess.isTeacher(req) && "consult".equals(msgType) && receiverId == null) {
            Map<String, Object> err = new HashMap<>();
            err.put("code", 400);
            err.put("error", "教师发送私信需要指定接收人");
            return err;
        }

        Map<String, Object> result = messageService.sendMessage(senderId, senderName, senderRole, title, content, studentId, classId, receiverId, msgType);
        // 发布班级通知时记录操作日志
        if ("notice".equals(msgType) && "200".equals(String.valueOf(result.get("code")))) {
            String ip = req.getRemoteAddr();
            operationLogService.log(senderId, senderName, senderRole, "发布班级通知",
                "班级ID:" + classId + ",标题:" + title + ",内容:" + (content.length() > 50 ? content.substring(0, 50) + "..." : content),
                ip);
        }
        return result;
    }

    @PutMapping("/messages/{id}/read")
    public Map<String, Object> markRead(@PathVariable Long id, HttpServletRequest req) {
        Long userId = (Long) req.getAttribute("userId");
        return messageService.markRead(id, userId);
    }

    @GetMapping("/messages/teachers")
    public Map<String, Object> listTeachers(HttpServletRequest req) {
        Long userId = (Long) req.getAttribute("userId");
        String role = (String) req.getAttribute("role");
        return messageService.listContactTeachers(userId, role);
    }

    @GetMapping("/messages/unread/count")
    public Map<String, Object> unreadCount(HttpServletRequest req) {
        Long userId = (Long) req.getAttribute("userId");
        int count = messageService.unreadCount(userId);
        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("count", count);
        return result;
    }

    @PostMapping("/messages/{id}/reply")
    public Map<String, Object> reply(@PathVariable Long id, @RequestBody Map<String, Object> body, HttpServletRequest req) {
        Long senderId = (Long) req.getAttribute("userId");
        String senderName = (String) req.getAttribute("displayName");
        String senderRole = (String) req.getAttribute("role");
        String content = body.get("content") != null ? body.get("content").toString() : "";
        return messageService.reply(id, senderId, senderName, senderRole, content);
    }

    @GetMapping("/msg-templates")
    public Map<String, Object> listTemplates() {
        List<Map<String, Object>> list = messageService.listTemplates();
        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("data", list);
        return result;
    }

    @PostMapping("/msg-templates")
    public Map<String, Object> createTemplate(@RequestBody Map<String, Object> body, HttpServletRequest req) {
        if (RoleAccess.isParent(req)) {
            return RoleAccess.forbidParentWrite("家长无权操作消息模板");
        }
        String name = (String) body.get("name");
        String content = (String) body.get("content");
        Integer sort = body.get("sort") != null ? ((Number) body.get("sort")).intValue() : null;
        Long id = messageService.createTemplate(name, content, sort);
        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("id", id);
        return result;
    }

    @PutMapping("/msg-templates/{id}")
    public Map<String, Object> updateTemplate(@PathVariable Long id, @RequestBody Map<String, Object> body, HttpServletRequest req) {
        if (RoleAccess.isParent(req)) {
            return RoleAccess.forbidParentWrite("家长无权操作消息模板");
        }
        String name = (String) body.get("name");
        String content = (String) body.get("content");
        Integer sort = body.get("sort") != null ? ((Number) body.get("sort")).intValue() : null;
        messageService.updateTemplate(id, name, content, sort);
        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        return result;
    }

    @DeleteMapping("/msg-templates/{id}")
    public Map<String, Object> deleteTemplate(@PathVariable Long id, HttpServletRequest req) {
        if (RoleAccess.isParent(req)) {
            return RoleAccess.forbidParentWrite("家长无权操作消息模板");
        }
        messageService.deleteTemplate(id);
        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        return result;
    }

    @GetMapping("/sse")
    public SseEmitter sse(@RequestParam(required = false) String token) {
        if (token == null || !jwtUtil.validateToken(token)) {
            SseEmitter emitter = new SseEmitter();
            emitter.completeWithError(new RuntimeException("未授权"));
            return emitter;
        }
        Long userId = jwtUtil.getUserId(token);
        return messageService.createSse(userId);
    }
}
