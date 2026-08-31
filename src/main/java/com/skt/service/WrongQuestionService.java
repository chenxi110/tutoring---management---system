package com.skt.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class WrongQuestionService {

    private static final Logger log = LoggerFactory.getLogger(WrongQuestionService.class);

    @Autowired
    private JdbcTemplate jdbc;

    // 学生错题列表
    public List<Map<String, Object>> listByStudent(Long studentId, String subject, String source) {
        StringBuilder sql = new StringBuilder("SELECT * FROM wrong_questions WHERE student_id=?");
        List<Object> params = new ArrayList<>();
        params.add(studentId);
        if (subject != null && !subject.isEmpty()) { sql.append(" AND subject=?"); params.add(subject); }
        if (source != null && !source.isEmpty()) { sql.append(" AND source=?"); params.add(source); }
        sql.append(" ORDER BY last_wrong_at DESC, created_at DESC");
        return jdbc.queryForList(sql.toString(), params.toArray());
    }

    // 添加错题
    public Map<String, Object> addWrongQuestion(Long studentId, String studentName, Long classId,
                                                  String subject, Long knowledgePointId, String knowledgePointName,
                                                  String questionText, String studentAnswer, String correctAnswer,
                                                  String analysis, String source, Long sourceId) {
        Map<String, Object> result = new HashMap<>();
        try {
            // 检查是否已存在相同题目
            List<Map<String, Object>> existing = jdbc.queryForList(
                "SELECT * FROM wrong_questions WHERE student_id=? AND question_text=? AND source=?",
                studentId, questionText, source != null ? source : "exam");
            if (!existing.isEmpty()) {
                // 更新错误次数
                Map<String, Object> exist = existing.get(0);
                int wrongCount = exist.get("wrong_count") != null ? ((Number) exist.get("wrong_count")).intValue() : 0;
                jdbc.update(
                    "UPDATE wrong_questions SET wrong_count=?, last_wrong_at=NOW(), student_answer=? WHERE id=?",
                    wrongCount + 1, studentAnswer, exist.get("id"));
                result.put("code", 200);
                result.put("msg", "错题已更新，错误次数+1");
            } else {
                jdbc.update(
                    "INSERT INTO wrong_questions (student_id, student_name, class_id, subject, knowledge_point_id, knowledge_point_name, question_text, student_answer, correct_answer, analysis, source, source_id, last_wrong_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,NOW())",
                    studentId, studentName, classId, subject, knowledgePointId, knowledgePointName,
                    questionText, studentAnswer, correctAnswer, analysis != null ? analysis : "",
                    source != null ? source : "exam", sourceId);
                result.put("code", 200);
                result.put("msg", "错题添加成功");
            }
        } catch (Exception e) {
            result.put("code", 500);
            result.put("msg", "添加失败：" + e.getMessage());
        }
        return result;
    }

    // 更新掌握程度
    public Map<String, Object> updateMastery(Long id, int masteryLevel) {
        Map<String, Object> result = new HashMap<>();
        try {
            jdbc.update("UPDATE wrong_questions SET mastery_level=? WHERE id=?", masteryLevel, id);
            result.put("code", 200);
            result.put("msg", "掌握程度已更新");
        } catch (Exception e) {
            result.put("code", 500);
            result.put("msg", "更新失败：" + e.getMessage());
        }
        return result;
    }

    // 错题统计
    public Map<String, Object> getStats(Long studentId) {
        Map<String, Object> stats = new HashMap<>();
        try {
            Integer totalCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM wrong_questions WHERE student_id=?", Integer.class, studentId);
            Integer masteredCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM wrong_questions WHERE student_id=? AND mastery_level>=80",
                Integer.class, studentId);
            List<Map<String, Object>> bySubject = jdbc.queryForList(
                "SELECT subject, COUNT(*) as cnt FROM wrong_questions WHERE student_id=? GROUP BY subject", studentId);
            stats.put("totalCount", totalCount != null ? totalCount : 0);
            stats.put("masteredCount", masteredCount != null ? masteredCount : 0);
            stats.put("masteryRate", totalCount != null && totalCount > 0 ?
                Math.round(masteredCount * 100.0 / totalCount) : 0);
            stats.put("bySubject", bySubject);
        } catch (Exception e) { log.warn("查询统计数据失败: {}", e.getMessage()); }
        return stats;
    }
}
