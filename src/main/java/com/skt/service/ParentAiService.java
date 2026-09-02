package com.skt.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.*;

/**
 * 家长端 AI 学习助手服务
 * 面向家长：查看孩子作答详情、错题整理、AI 问答、个性化学情分析、学习方案定制跟踪、出题自检
 * 全部接口校验孩子归属（students.parent_id == 家长账号），防止越权查看其他孩子数据
 */
@Service
public class ParentAiService {

    private static final Logger log = LoggerFactory.getLogger(ParentAiService.class);

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private AnalysisService analysisService;

    @Autowired
    private AIAnalysisService aiAnalysisService;

    @Autowired
    private AIService aiService;

    @Autowired
    private WrongQuestionService wrongQuestionService;

    @Autowired
    private AuthService authService;

    @Autowired
    private ExamService examService;

    @PostConstruct
    public void initTable() {
        try {
            jdbc.execute(
                "CREATE TABLE IF NOT EXISTS parent_learning_plan (" +
                " id BIGINT AUTO_INCREMENT PRIMARY KEY," +
                " parent_id BIGINT NOT NULL COMMENT '家长账号ID'," +
                " student_id BIGINT NOT NULL COMMENT '学生ID'," +
                " student_name VARCHAR(50) COMMENT '学生姓名'," +
                " plan_title VARCHAR(200) COMMENT '方案标题'," +
                " plan_content TEXT COMMENT '方案内容'," +
                " focus_area VARCHAR(200) COMMENT '定制关注点'," +
                " status VARCHAR(20) DEFAULT 'ongoing' COMMENT 'ongoing进行中/done已完成'," +
                " week_progress VARCHAR(100) COMMENT '本周进度记录'," +
                " created_at DATETIME DEFAULT CURRENT_TIMESTAMP," +
                " updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='家长端AI学习方案跟踪表'");
        } catch (Exception e) {
            log.warn("parent_learning_plan 表初始化失败(不影响主流程): {}", e.getMessage());
        }
    }

    private Map<String, Object> forbid(String msg) {
        Map<String, Object> r = new HashMap<>();
        r.put("code", 403);
        r.put("msg", msg == null ? "无权限：只能查看自己绑定孩子的数据" : msg);
        return r;
    }

    private Map<String, Object> err(int code, String msg) {
        Map<String, Object> r = new HashMap<>();
        r.put("code", code);
        r.put("msg", msg);
        return r;
    }

    /** 校验孩子归属：students.parent_id == parentId */
    public boolean checkChild(Long parentId, Long studentId) {
        if (parentId == null || studentId == null) return false;
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id FROM students WHERE id=? AND parent_id=? AND (is_deleted IS NULL OR is_deleted=0)",
                studentId, parentId);
            return !rows.isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    /** 家长绑定的孩子列表 */
    public List<Map<String, Object>> getChildren(Long parentId) {
        return authService.getParentChildren(parentId);
    }

    /** 孩子学习概览（成绩/作业/出勤/错题/课堂行为） */
    public Map<String, Object> getOverview(Long parentId, Long studentId) {
        if (!checkChild(parentId, studentId)) return forbid("无权查看该孩子的学习数据");
        List<Map<String, Object>> st = jdbc.queryForList("SELECT * FROM students WHERE id=?", studentId);
        if (st.isEmpty()) return err(404, "学生不存在");
        Map<String, Object> s = st.get(0);
        Long classId = s.get("class_id") != null ? ((Number) s.get("class_id")).longValue() : null;
        Map<String, Object> analysis = analysisService.getStudentAnalysis(studentId, classId);
        Map<String, Object> r = new HashMap<>();
        r.put("code", 200);
        r.put("student", s);
        r.put("analysis", analysis);
        return r;
    }

    /** 孩子错题列表 + 统计 */
    public Map<String, Object> getWrongQuestions(Long parentId, Long studentId, String subject) {
        if (!checkChild(parentId, studentId)) return forbid("无权查看该孩子的错题");
        Map<String, Object> r = new HashMap<>();
        r.put("code", 200);
        r.put("data", wrongQuestionService.listByStudent(studentId, subject, null));
        try { r.put("stats", wrongQuestionService.getStats(studentId)); } catch (Exception e) { r.put("stats", new HashMap<>()); }
        return r;
    }

    /** 孩子考试提交列表 */
    public Map<String, Object> getSubmissions(Long parentId, Long studentId) {
        if (!checkChild(parentId, studentId)) return forbid("无权查看该孩子的作答记录");
        List<Map<String, Object>> subs = jdbc.queryForList(
            "SELECT es.id, es.exam_id, es.student_id, es.student_name, es.score, es.auto_score, es.teacher_score, es.graded, es.submitted_at, e.title, e.exam_code, e.class_id " +
            "FROM exam_submission es LEFT JOIN exam e ON e.id=es.exam_id " +
            "WHERE es.student_id=? ORDER BY es.submitted_at DESC", studentId);
        Map<String, Object> r = new HashMap<>();
        r.put("code", 200);
        r.put("data", subs);
        return r;
    }

    /** 单份试卷详情（含逐题判分） */
    public Map<String, Object> getPaper(Long parentId, Long submissionId) {
        List<Map<String, Object>> subs = examService.listSubmissionsById(submissionId);
        if (subs.isEmpty()) return err(404, "提交记录不存在");
        Long studentId = subs.get(0).get("student_id") != null ? ((Number) subs.get(0).get("student_id")).longValue() : null;
        if (!checkChild(parentId, studentId)) return forbid("无权查看该孩子的试卷");
        return examService.getStudentPaper(submissionId);
    }

    /** 生成 AI 学情分析报告 */
    public Map<String, Object> generateAnalysis(Long parentId, Long studentId, Long classId, String reportType) {
        if (!checkChild(parentId, studentId)) return forbid("无权为该孩子生成学情分析");
        return aiAnalysisService.generateReport(studentId, classId, reportType);
    }

    /** 学情报告列表 */
    public Map<String, Object> listReports(Long parentId, Long studentId) {
        if (!checkChild(parentId, studentId)) return forbid("无权查看该孩子的学情报告");
        Map<String, Object> r = new HashMap<>();
        r.put("code", 200);
        r.put("data", aiAnalysisService.listReports(studentId));
        return r;
    }

    /** 针对孩子的 AI 问答（自动携带孩子学情上下文） */
    public Map<String, Object> chat(Long parentId, Long studentId, String message) {
        if (!checkChild(parentId, studentId)) return forbid("无权针对该孩子提问");
        List<Map<String, Object>> st = jdbc.queryForList("SELECT * FROM students WHERE id=?", studentId);
        if (st.isEmpty()) return err(404, "学生不存在");
        Map<String, Object> s = st.get(0);
        String studentName = String.valueOf(s.get("name"));
        Long classId = s.get("class_id") != null ? ((Number) s.get("class_id")).longValue() : null;
        Map<String, Object> analysis = analysisService.getStudentAnalysis(studentId, classId);
        StringBuilder sb = new StringBuilder();
        sb.append("我是家长，请针对我的孩子【").append(studentName).append("】提供学习辅导建议。\n");
        sb.append("孩子学情摘要：综合评分").append(analysis.getOrDefault("overallScore", "-"))
          .append("分，平均分").append(analysis.getOrDefault("avgScore", "-"))
          .append("，作业提交率").append(analysis.getOrDefault("homeworkSubmitRate", "-"))
          .append("%，出勤率").append(analysis.getOrDefault("attendanceRate", "-"))
          .append("%，错题").append(analysis.getOrDefault("wrongQuestionCount", "-")).append("道");
        sb.append("。\n家长问题：").append(message);
        return aiService.chat(parentId, null, sb.toString(), null);
    }

    /** 针对孩子薄弱点出题（自检） */
    public Map<String, Object> generateQuestions(Long parentId, Long studentId, String topic, int count, List<String> types) {
        if (!checkChild(parentId, studentId)) return forbid("无权针对该孩子出题");
        String t = topic;
        if (t == null || t.trim().isEmpty()) {
            // 默认用孩子错题知识点作为出题主题
            List<Map<String, Object>> wq = wrongQuestionService.listByStudent(studentId, null, null);
            if (!wq.isEmpty()) {
                Map<String, Object> first = wq.get(0);
                Object kp = first.get("knowledge_point_name");
                t = kp != null && String.valueOf(kp).length() > 0 ? String.valueOf(kp) : String.valueOf(first.get("subject"));
            }
            if (t == null || t.trim().isEmpty()) t = "基础巩固";
        }
        return aiService.generateQuestions(t, count, types);
    }

    /** 生成并保存 AI 定制学习方案 */
    public Map<String, Object> saveLearningPlan(Long parentId, Long studentId, String focus) {
        if (!checkChild(parentId, studentId)) return forbid("无权为该孩子定制学习方案");
        List<Map<String, Object>> st = jdbc.queryForList("SELECT * FROM students WHERE id=?", studentId);
        if (st.isEmpty()) return err(404, "学生不存在");
        Map<String, Object> s = st.get(0);
        String studentName = String.valueOf(s.get("name"));
        Long classId = s.get("class_id") != null ? ((Number) s.get("class_id")).longValue() : null;
        Map<String, Object> analysis = analysisService.getStudentAnalysis(studentId, classId);
        String f = (focus == null || focus.trim().isEmpty()) ? "全面提升" : focus.trim();
        StringBuilder prompt = new StringBuilder();
        prompt.append("请为辅导班学生【").append(studentName).append("】制定一份【").append(f).append("】主题的个性化学习方案。\n");
        prompt.append("学情数据：综合评分").append(analysis.getOrDefault("overallScore", "-"))
              .append("，平均分").append(analysis.getOrDefault("avgScore", "-"))
              .append("，作业提交率").append(analysis.getOrDefault("homeworkSubmitRate", "-"))
              .append("%，出勤率").append(analysis.getOrDefault("attendanceRate", "-"))
              .append("%，错题").append(analysis.getOrDefault("wrongQuestionCount", "-")).append("道");
        prompt.append("。\n请输出结构化内容：1.当前学情诊断；2.分阶段学习目标（4周）；3.每周具体学习安排（含复习错题、练习重点）；4.家长配合建议；5.可衡量的验收标准。控制在400字内。");
        Map<String, Object> ai = aiService.chat(parentId, null, prompt.toString(), null);
        String planContent = ai.get("response") != null ? String.valueOf(ai.get("response")) : null;
        if (planContent == null || planContent.trim().isEmpty()) {
            planContent = "【" + f + "】学习方案生成失败：请检查AI服务配置后重试。";
        }
        try {
            jdbc.update(
                "INSERT INTO parent_learning_plan (parent_id, student_id, student_name, plan_title, plan_content, focus_area, status, week_progress) VALUES (?,?,?,?,?,?,?,?)",
                parentId, studentId, studentName, studentName + "的" + f + "学习方案", planContent, f, "ongoing", "第1周：开始执行");
        } catch (Exception e) {
            return err(500, "保存学习方案失败：" + e.getMessage());
        }
        Map<String, Object> r = new HashMap<>();
        r.put("code", 200);
        r.put("msg", "学习方案已生成");
        r.put("planContent", planContent);
        return r;
    }

    /** 学习方案列表 */
    public Map<String, Object> listLearningPlans(Long parentId, Long studentId) {
        if (!checkChild(parentId, studentId)) return forbid("无权查看该孩子的学习方案");
        List<Map<String, Object>> plans = jdbc.queryForList(
            "SELECT id, student_name, plan_title, plan_content, focus_area, status, week_progress, created_at, updated_at " +
            "FROM parent_learning_plan WHERE parent_id=? AND student_id=? ORDER BY created_at DESC", parentId, studentId);
        Map<String, Object> r = new HashMap<>();
        r.put("code", 200);
        r.put("data", plans);
        return r;
    }

    /** 更新学习方案进度 / 完成状态 */
    public Map<String, Object> updatePlanProgress(Long parentId, Long planId, String progress, String status) {
        if (planId == null) return err(400, "缺少方案ID");
        try {
            List<Map<String, Object>> plans = jdbc.queryForList(
                "SELECT id FROM parent_learning_plan WHERE id=? AND parent_id=?", planId, parentId);
            if (plans.isEmpty()) return forbid("无权操作该学习方案");
            String st = (status == null || status.trim().isEmpty()) ? "ongoing" : status.trim();
            String pr = progress == null ? "" : progress.trim();
            jdbc.update("UPDATE parent_learning_plan SET week_progress=?, status=?, updated_at=NOW() WHERE id=?",
                pr.isEmpty() ? "执行中" : pr, st, planId);
            Map<String, Object> r = new HashMap<>();
            r.put("code", 200);
            r.put("msg", "进度已更新");
            return r;
        } catch (Exception e) {
            return err(500, "更新失败：" + e.getMessage());
        }
    }
}
