package com.skt.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class MessageService {

    private static final Logger log = LoggerFactory.getLogger(MessageService.class);

    @Autowired
    private JdbcTemplate jdbc;

    private final ConcurrentHashMap<Long, List<SseEmitter>> sseClients = new ConcurrentHashMap<>();

    public List<Map<String, Object>> listMessages(Long userId, String role) {
        String sql;
        List<Map<String, Object>> list;

        if ("parent".equalsIgnoreCase(role)) {
            sql = "SELECT m.*, s.name AS student_name FROM messages m LEFT JOIN students s ON s.id = m.student_id " +
                    "WHERE m.receiver_id = ? OR m.sender_id = ? OR (m.student_id IN (SELECT id FROM students WHERE parent_user_id = ?)) " +
                    "OR (m.class_id IN (SELECT class_id FROM students WHERE parent_user_id = ? AND class_id IS NOT NULL) AND m.student_id IS NULL) " +
                    "ORDER BY m.created_at DESC";
            list = jdbc.queryForList(sql, userId, userId, userId, userId);
        } else {
            sql = "SELECT m.*, s.name AS student_name FROM messages m LEFT JOIN students s ON s.id = m.student_id " +
                    "WHERE m.sender_id = ? OR m.receiver_id = ? OR m.sender_role = 'parent' " +
                    "ORDER BY m.created_at DESC";
            list = jdbc.queryForList(sql, userId, userId);
        }

        for (Map<String, Object> item : list) {
            Long messageId = ((Number) item.get("id")).longValue();
            List<Map<String, Object>> replyList = jdbc.queryForList(
                    "SELECT * FROM message_reply WHERE message_id = ? ORDER BY created_at ASC",
                    messageId
            );
            item.put("replyList", replyList);
        }
        return list;
    }

    public Map<String, Object> listContactTeachers(Long userId, String role) {
        Map<String, Object> result = new HashMap<>();
        try {
            List<Map<String, Object>> teachers = new ArrayList<>();
            if ("parent".equalsIgnoreCase(role)) {
                // 家长：通过绑定孩子的班级关联教师（兼容 parent_id 旧关联与 parent_user_id 新关联）
                teachers = jdbc.queryForList(
                    "SELECT DISTINCT u.id, u.username, u.display_name AS name, c.name AS class_name " +
                    "FROM students s JOIN classes c ON s.class_id = c.id JOIN users u ON c.teacher_id = u.id " +
                    "WHERE (s.parent_id = ? OR s.parent_user_id = ?) AND (s.is_deleted IS NULL OR s.is_deleted = 0) " +
                    "AND u.role = 'teacher' AND u.id IS NOT NULL",
                    userId, userId);
            }
            result.put("code", 200);
            result.put("data", teachers == null ? Collections.emptyList() : teachers);
            return result;
        } catch (Exception ex) {
            log.error("获取可联系教师失败 userId={}", userId, ex);
            result.put("code", 500);
            result.put("error", "获取教师列表失败：" + ex.getMessage());
            return result;
        }
    }

    public Map<String, Object> sendMessage(Long senderId, String senderName, String senderRole,
                                           String title, String content, Long studentId,
                                           Long classId, Long receiverId, String msgType) {
        Map<String, Object> result = new HashMap<>();
        if (content == null || content.trim().isEmpty()) {
            result.put("code", 400);
            result.put("error", "消息内容不能为空");
            return result;
        }
        if (content.length() > 2000) {
            result.put("code", 400);
            result.put("error", "消息内容不能超过2000字");
            return result;
        }

        String safeTitle = title == null ? "家校通知" : title;
        String safeSenderName = senderName == null ? "用户" : senderName;
        String safeSenderRole = senderRole == null ? "teacher" : senderRole;
        String safeMsgType = msgType == null ? "notice" : msgType;

        jdbc.update(
            "INSERT INTO messages (sender_id, sender_name, sender_role, receiver_id, student_id, class_id, title, content, msg_type, status) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'unread')",
            senderId, safeSenderName, safeSenderRole, receiverId, studentId, classId, safeTitle, content, safeMsgType
        );
        Long newId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);

        if ("notice".equals(safeMsgType) && classId != null) {
            List<Map<String, Object>> parents = jdbc.queryForList(
                "SELECT DISTINCT s.parent_user_id FROM students s WHERE s.class_id = ? AND s.parent_user_id IS NOT NULL AND (s.is_deleted IS NULL OR s.is_deleted = 0) AND (s.status IS NULL OR s.status = 'active')",
                classId
            );
            for (Map<String, Object> p : parents) {
                Long parentId = ((Number) p.get("parent_user_id")).longValue();
                pushToUser(parentId, safeTitle, content.substring(0, Math.min(100, content.length())), safeSenderName, newId);
            }
        } else if (receiverId != null) {
            pushToUser(receiverId, safeTitle, content.substring(0, Math.min(100, content.length())), safeSenderName, newId);
        }

        result.put("code", 200);
        result.put("id", newId);
        result.put("message", "发送成功");
        return result;
    }

    public Map<String, Object> markRead(Long msgId, Long userId) {
        Map<String, Object> result = new HashMap<>();
        int rows = jdbc.update(
            "UPDATE messages SET status='read', read_at=NOW() WHERE id=? AND (receiver_id=? OR receiver_id IS NULL OR sender_id=?)",
            msgId, userId, userId
        );
        result.put("code", rows > 0 ? 200 : 400);
        result.put("message", rows > 0 ? "已读" : "无权限");
        return result;
    }

    public int unreadCount(Long userId) {
        Integer cnt = jdbc.queryForObject(
            "SELECT COUNT(*) FROM messages m WHERE m.status='unread' AND m.sender_id != ? AND (" +
            "  m.receiver_id = ? OR " +
            "  (m.msg_type = 'notice' AND m.receiver_id IS NULL AND m.class_id IN (" +
            "    SELECT DISTINCT s.class_id FROM students s WHERE s.parent_user_id = ? AND s.class_id IS NOT NULL" +
            "  )) OR " +
            "  (m.student_id IN (SELECT id FROM students WHERE parent_user_id = ?) AND m.msg_type != 'notice')" +
            ")",
            Integer.class,
            userId, userId, userId, userId
        );
        return cnt != null ? cnt : 0;
    }

    public Map<String, Object> reply(Long messageId, Long senderId, String senderName, String senderRole, String content) {
        Map<String, Object> result = new HashMap<>();
        if (content == null || content.trim().isEmpty()) {
            result.put("code", 400);
            result.put("error", "回复内容不能为空");
            return result;
        }
        if (content.length() > 2000) {
            result.put("code", 400);
            result.put("error", "回复内容不能超过2000字");
            return result;
        }

        jdbc.update(
            "INSERT INTO message_reply (message_id, sender_id, sender_name, sender_role, content) VALUES (?, ?, ?, ?, ?)",
            messageId, senderId, senderName, senderRole, content
        );

        Map<String, Object> msg = jdbc.queryForMap("SELECT * FROM messages WHERE id=?", messageId);
        Long receiverId = msg.get("receiver_id") != null ? ((Number) msg.get("receiver_id")).longValue() : null;
        Long msgSenderId = msg.get("sender_id") != null ? ((Number) msg.get("sender_id")).longValue() : null;

        if (receiverId != null && !receiverId.equals(senderId)) {
            pushToUser(receiverId, "新回复", content.substring(0, Math.min(100, content.length())), senderName, messageId);
        }
        if (msgSenderId != null && !msgSenderId.equals(senderId) && (receiverId == null || receiverId.equals(senderId))) {
            pushToUser(msgSenderId, "新回复", content.substring(0, Math.min(100, content.length())), senderName, messageId);
        }

        result.put("code", 200);
        result.put("message", "回复成功");
        return result;
    }

    public List<Map<String, Object>> listTemplates() {
        return jdbc.queryForList("SELECT * FROM msg_templates ORDER BY sort ASC");
    }

    public Long createTemplate(String name, String content, Integer sort) {
        jdbc.update("INSERT INTO msg_templates (name, content, sort) VALUES (?, ?, ?)",
            name, content, sort != null ? sort : 99);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    public void updateTemplate(Long id, String name, String content, Integer sort) {
        jdbc.update("UPDATE msg_templates SET name=?, content=?, sort=? WHERE id=?",
            name, content, sort, id);
    }

    public void deleteTemplate(Long id) {
        jdbc.update("DELETE FROM msg_templates WHERE id=?", id);
    }

    public SseEmitter createSse(Long userId) {
        SseEmitter emitter = new SseEmitter(0L);
        sseClients.computeIfAbsent(userId, k -> Collections.synchronizedList(new ArrayList<>())).add(emitter);
        emitter.onCompletion(() -> removeClient(userId, emitter));
        emitter.onTimeout(() -> removeClient(userId, emitter));
        emitter.onError(e -> removeClient(userId, emitter));
        try {
            emitter.send(SseEmitter.event().data("{\"type\":\"connected\"}"));
        } catch (Exception ignored) { log.debug("操作被跳过: {}", ignored.getMessage()); }
        Timer heartbeat = new Timer(true);
        heartbeat.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                try { emitter.send(SseEmitter.event().comment("heartbeat")); }
                catch (Exception e) { this.cancel(); heartbeat.cancel(); }
            }
        }, 30000, 30000);
        return emitter;
    }

    private void pushToUser(Long userId, String title, String content, String senderName, Long messageId) {
        List<SseEmitter> clients = sseClients.get(userId);
        if (clients == null) return;
        String safeTitle = escapeJson(title);
        String safeContent = escapeJson(content);
        String safeName = escapeJson(senderName);
        String data = "{\"type\":\"new_message\",\"id\":" + (messageId != null ? messageId : "null") +
                      ",\"title\":\"" + safeTitle + "\",\"content\":\"" + safeContent +
                      "\",\"senderName\":\"" + safeName + "\"}";
        sendSseData(clients, data);
    }

    public void pushSigninNotification(Long userId, String title, String content) {
        List<SseEmitter> clients = sseClients.get(userId);
        if (clients == null) return;
        String safeTitle = escapeJson(title);
        String safeContent = escapeJson(content);
        String data = "{\"type\":\"signin\",\"title\":\"" + safeTitle + "\",\"content\":\"" + safeContent + "\"}";
        sendSseData(clients, data);
    }

    private void sendSseData(List<SseEmitter> clients, String data) {
        synchronized (clients) {
            Iterator<SseEmitter> it = clients.iterator();
            while (it.hasNext()) {
                SseEmitter e = it.next();
                try { e.send(SseEmitter.event().data(data)); }
                catch (Exception ex) { it.remove(); }
            }
        }
    }

    private void removeClient(Long userId, SseEmitter emitter) {
        List<SseEmitter> clients = sseClients.get(userId);
        if (clients != null) {
            synchronized (clients) {
                clients.remove(emitter);
            }
        }
    }

    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "").replace("\t", " ");
    }
}
