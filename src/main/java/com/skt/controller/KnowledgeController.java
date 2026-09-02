package com.skt.controller;

import com.skt.security.RoleAccess;
import com.skt.service.KnowledgeService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import java.util.*;

@RestController
@RequestMapping("/api/knowledge")
public class KnowledgeController {

    @Autowired
    private KnowledgeService knowledgeService;
    @Autowired
    private JdbcTemplate jdbc;

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

    // ========== AI 知识库文章 CRUD ==========
    @GetMapping("")
    public Map<String, Object> list(@RequestParam(required = false) Long id,
                                    @RequestParam(required = false) String keyword,
                                    @RequestParam(required = false) String category) {
        Map<String, Object> result = new HashMap<>();
        try {
            StringBuilder sql = new StringBuilder("SELECT * FROM ai_knowledge WHERE 1=1");
            List<Object> params = new ArrayList<>();
            if (id != null) {
                sql.append(" AND id=?");
                params.add(id);
            }
            if (keyword != null && !keyword.trim().isEmpty()) {
                sql.append(" AND (title LIKE ? OR content LIKE ?)");
                params.add("%" + keyword.trim() + "%");
                params.add("%" + keyword.trim() + "%");
            }
            if (category != null && !category.trim().isEmpty() && !"all".equals(category)) {
                sql.append(" AND category=?");
                params.add(category.trim());
            }
            sql.append(" ORDER BY created_at DESC");
            result.put("code", 200);
            result.put("data", jdbc.queryForList(sql.toString(), params.toArray()));
            return result;
        } catch (Exception e) {
            result.put("code", 500); result.put("msg", "知识库查询失败"); result.put("error", e.getMessage());
            return result;
        }
    }

    @GetMapping("/categories")
    public Map<String, Object> categories() {
        Map<String, Object> result = new HashMap<>();
        try {
            List<String> list = jdbc.queryForList("SELECT DISTINCT category FROM ai_knowledge WHERE category IS NOT NULL AND category<>'' ORDER BY category", String.class);
            result.put("code", 200);
            result.put("data", list);
            return result;
        } catch (Exception e) {
            result.put("code", 500); result.put("msg", "分类查询失败"); result.put("error", e.getMessage());
            return result;
        }
    }

    @PostMapping("")
    public Map<String, Object> create(@RequestBody Map<String, Object> body, HttpServletRequest req) {
        Map<String, Object> result = new HashMap<>();
        if (!RoleAccess.isTeacher(req)) {
            result.put("code", 403); result.put("msg", "无权限"); return result;
        }
        try {
            String title = body.get("title") != null ? String.valueOf(body.get("title")).trim() : "";
            String content = body.get("content") != null ? String.valueOf(body.get("content")) : "";
            String category = body.get("category") != null && !String.valueOf(body.get("category")).trim().isEmpty() ? String.valueOf(body.get("category")).trim() : "通用";
            if (title.isEmpty()) { result.put("code", 400); result.put("msg", "标题不能为空"); return result; }
            Long userId = (Long) req.getAttribute("userId");
            jdbc.update("INSERT INTO ai_knowledge (title, content, category, created_by) VALUES (?,?,?,?)", title, content, category, userId);
            result.put("code", 200); result.put("success", true); result.put("msg", "保存成功");
            return result;
        } catch (Exception e) {
            result.put("code", 500); result.put("msg", "保存失败"); result.put("error", e.getMessage());
            return result;
        }
    }

    @PutMapping("/{id}")
    public Map<String, Object> update(@PathVariable Long id, @RequestBody Map<String, Object> body, HttpServletRequest req) {
        Map<String, Object> result = new HashMap<>();
        if (!RoleAccess.isTeacher(req)) {
            result.put("code", 403); result.put("msg", "无权限"); return result;
        }
        try {
            String title = body.get("title") != null ? String.valueOf(body.get("title")).trim() : "";
            String content = body.get("content") != null ? String.valueOf(body.get("content")) : "";
            String category = body.get("category") != null && !String.valueOf(body.get("category")).trim().isEmpty() ? String.valueOf(body.get("category")).trim() : "通用";
            jdbc.update("UPDATE ai_knowledge SET title=?, content=?, category=? WHERE id=?", title, content, category, id);
            result.put("code", 200); result.put("success", true); result.put("msg", "更新成功");
            return result;
        } catch (Exception e) {
            result.put("code", 500); result.put("msg", "更新失败"); result.put("error", e.getMessage());
            return result;
        }
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> remove(@PathVariable Long id, HttpServletRequest req) {
        Map<String, Object> result = new HashMap<>();
        if (!RoleAccess.isTeacher(req)) {
            result.put("code", 403); result.put("msg", "无权限"); return result;
        }
        try {
            jdbc.update("DELETE FROM ai_knowledge WHERE id=?", id);
            result.put("code", 200); result.put("success", true); result.put("msg", "删除成功");
            return result;
        } catch (Exception e) {
            result.put("code", 500); result.put("msg", "删除失败"); result.put("error", e.getMessage());
            return result;
        }
    }
}
