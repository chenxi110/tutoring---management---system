package com.skt.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class KnowledgeService {

    @Autowired
    private JdbcTemplate jdbc;

    // 知识点树
    public List<Map<String, Object>> getKnowledgeTree(String subject) {
        List<Map<String, Object>> allPoints;
        if (subject != null && !subject.isEmpty()) {
            allPoints = jdbc.queryForList(
                "SELECT * FROM knowledge_points WHERE subject=? ORDER BY parent_id, sort_order", subject);
        } else {
            allPoints = jdbc.queryForList(
                "SELECT * FROM knowledge_points ORDER BY subject, parent_id, sort_order");
        }
        // 构建树
        Map<Long, List<Map<String, Object>>> childrenMap = new HashMap<>();
        List<Map<String, Object>> roots = new ArrayList<>();
        for (Map<String, Object> point : allPoints) {
            Long parentId = point.get("parent_id") != null ? ((Number) point.get("parent_id")).longValue() : 0L;
            if (parentId == 0) {
                roots.add(point);
            } else {
                childrenMap.computeIfAbsent(parentId, k -> new ArrayList<>()).add(point);
            }
        }
        for (Map<String, Object> root : roots) {
            buildChildren(root, childrenMap);
        }
        return roots;
    }

    private void buildChildren(Map<String, Object> node, Map<Long, List<Map<String, Object>>> childrenMap) {
        Long id = ((Number) node.get("id")).longValue();
        List<Map<String, Object>> children = childrenMap.get(id);
        if (children != null) {
            node.put("children", children);
            for (Map<String, Object> child : children) {
                buildChildren(child, childrenMap);
            }
        }
    }

    // 新增知识点
    public Map<String, Object> addKnowledgePoint(String name, String subject, String gradeLevel,
                                                   String description, int difficulty, Long parentId, int sortOrder) {
        Map<String, Object> result = new HashMap<>();
        try {
            jdbc.update(
                "INSERT INTO knowledge_points (name, subject, grade_level, description, difficulty, parent_id, sort_order) VALUES (?,?,?,?,?,?,?)",
                name, subject, gradeLevel, description != null ? description : "", difficulty,
                parentId != null ? parentId : 0, sortOrder);
            result.put("code", 200);
            result.put("msg", "知识点添加成功");
        } catch (Exception e) {
            result.put("code", 500);
            result.put("msg", "添加失败：" + e.getMessage());
        }
        return result;
    }

    // 学生知识点掌握情况
    public List<Map<String, Object>> getStudentMastery(Long studentId, String subject) {
        // 基于错题本统计知识点掌握情况
        StringBuilder sql = new StringBuilder(
            "SELECT knowledge_point_id, knowledge_point_name, COUNT(*) as wrong_count, " +
            "AVG(mastery_level) as avg_mastery, MAX(last_wrong_at) as last_wrong " +
            "FROM wrong_questions WHERE student_id=?");
        List<Object> params = new ArrayList<>();
        params.add(studentId);
        if (subject != null && !subject.isEmpty()) {
            sql.append(" AND subject=?");
            params.add(subject);
        }
        sql.append(" GROUP BY knowledge_point_id, knowledge_point_name ORDER BY avg_mastery ASC");
        return jdbc.queryForList(sql.toString(), params.toArray());
    }
}
