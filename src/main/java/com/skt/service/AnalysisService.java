package com.skt.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class AnalysisService {

    @Autowired
    private JdbcTemplate jdbc;

    // 学生个体学情分析
    public Map<String, Object> getStudentAnalysis(Long studentId, Long classId) {
        Map<String, Object> analysis = new HashMap<>();

        // 1. 成绩统计
        List<Map<String, Object>> grades = jdbc.queryForList(
            "SELECT * FROM grades WHERE student_id=? ORDER BY created_at", studentId);
        if (!grades.isEmpty()) {
            double avgScore = grades.stream().mapToDouble(g -> ((Number) g.get("score")).doubleValue()).average().orElse(0);
            double maxScore = grades.stream().mapToDouble(g -> ((Number) g.get("score")).doubleValue()).max().orElse(0);
            double minScore = grades.stream().mapToDouble(g -> ((Number) g.get("score")).doubleValue()).min().orElse(0);
            analysis.put("avgScore", Math.round(avgScore * 10) / 10.0);
            analysis.put("maxScore", maxScore);
            analysis.put("minScore", minScore);
            analysis.put("examCount", grades.size());
            // 成绩趋势
            List<Map<String, Object>> trend = new ArrayList<>();
            for (int i = 0; i < grades.size(); i++) {
                Map<String, Object> point = new HashMap<>();
                point.put("exam", grades.get(i).get("exam_name"));
                point.put("score", grades.get(i).get("score"));
                point.put("date", grades.get(i).get("created_at"));
                trend.add(point);
            }
            analysis.put("gradeTrend", trend);
            // 成绩趋势判断
            if (grades.size() >= 2) {
                double lastScore = ((Number) grades.get(grades.size() - 1).get("score")).doubleValue();
                double prevScore = ((Number) grades.get(grades.size() - 2).get("score")).doubleValue();
                if (lastScore > prevScore + 5) analysis.put("trend", "up");
                else if (lastScore < prevScore - 5) analysis.put("trend", "down");
                else analysis.put("trend", "stable");
            }
        }

        // 2. 作业完成情况
        List<Map<String, Object>> homework = jdbc.queryForList(
            "SELECT h.*, hs.id as submission_id, hs.score, hs.submitted_at " +
            "FROM homework h LEFT JOIN homework_submissions hs ON hs.homework_id=h.id AND hs.student_id=? " +
            "WHERE h.class_id=? ORDER BY h.created_at DESC", studentId, classId);
        int submittedCount = (int) homework.stream().filter(h -> h.get("submission_id") != null).count();
        double homeworkAvg = homework.stream()
            .filter(h -> h.get("score") != null)
            .mapToDouble(h -> ((Number) h.get("score")).doubleValue())
            .average().orElse(0);
        analysis.put("homeworkTotal", homework.size());
        analysis.put("homeworkSubmitted", submittedCount);
        analysis.put("homeworkSubmitRate", homework.size() > 0 ? Math.round(submittedCount * 100.0 / homework.size()) : 0);
        analysis.put("homeworkAvgScore", Math.round(homeworkAvg * 10) / 10.0);

        // 3. 出勤情况
        List<Map<String, Object>> attendance = jdbc.queryForList(
            "SELECT type, COUNT(*) as cnt FROM records WHERE student_id=? GROUP BY type", studentId);
        int presentCount = 0, absentCount = 0;
        for (Map<String, Object> a : attendance) {
            String type = (String) a.get("type");
            int cnt = ((Number) a.get("cnt")).intValue();
            if ("absent".equals(type)) absentCount = cnt;
            else presentCount += cnt;
        }
        analysis.put("presentCount", presentCount);
        analysis.put("absentCount", absentCount);
        analysis.put("attendanceRate", (presentCount + absentCount) > 0 ?
            Math.round(presentCount * 100.0 / (presentCount + absentCount)) : 100);

        // 4. 课堂参与度
        Map<String, Object> behavior = new HashMap<>();
        try {
            Integer signinCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM classroom_behavior WHERE student_id=? AND class_id=? AND behavior_type='signin'",
                Integer.class, studentId, classId);
            Integer raiseHandCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM classroom_behavior WHERE student_id=? AND class_id=? AND behavior_type='raise_hand'",
                Integer.class, studentId, classId);
            Integer answerCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM classroom_behavior WHERE student_id=? AND class_id=? AND behavior_type='answer'",
                Integer.class, studentId, classId);
            Integer totalScore = jdbc.queryForObject(
                "SELECT COALESCE(SUM(score_change),0) FROM classroom_behavior WHERE student_id=? AND class_id=?",
                Integer.class, studentId, classId);
            behavior.put("signinCount", signinCount != null ? signinCount : 0);
            behavior.put("raiseHandCount", raiseHandCount != null ? raiseHandCount : 0);
            behavior.put("answerCount", answerCount != null ? answerCount : 0);
            behavior.put("totalScore", totalScore != null ? totalScore : 0);
        } catch (Exception e) {}
        analysis.put("classroomBehavior", behavior);

        // 5. 错题统计
        try {
            Integer wrongCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM wrong_questions WHERE student_id=?", Integer.class, studentId);
            Integer masteredCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM wrong_questions WHERE student_id=? AND mastery_level>=80",
                Integer.class, studentId);
            analysis.put("wrongQuestionCount", wrongCount != null ? wrongCount : 0);
            analysis.put("masteredQuestionCount", masteredCount != null ? masteredCount : 0);
        } catch (Exception e) {}

        // 6. 综合评分
        double overallScore = 0;
        int weight = 0;
        if (analysis.containsKey("avgScore")) {
            overallScore += (Double) analysis.get("avgScore") * 0.4;
            weight += 40;
        }
        overallScore += (Integer) analysis.get("homeworkAvgScore") * 0.2;
        overallScore += (Integer) analysis.get("attendanceRate") * 0.2;
        int participationScore = Math.min(100, ((Integer) behavior.getOrDefault("totalScore", 0)) * 2);
        overallScore += participationScore * 0.2;
        analysis.put("overallScore", Math.round(overallScore * 10) / 10.0);

        // 7. 改进建议
        List<String> suggestions = new ArrayList<>();
        if (analysis.containsKey("trend") && "down".equals(analysis.get("trend"))) {
            suggestions.add("近期成绩呈下降趋势，建议加强薄弱知识点的复习");
        }
        if ((Integer) analysis.get("homeworkSubmitRate") < 80) {
            suggestions.add("作业提交率偏低，建议按时完成作业");
        }
        if ((Integer) analysis.get("attendanceRate") < 90) {
            suggestions.add("出勤率有待提高，建议尽量不缺勤");
        }
        if ((Integer) behavior.getOrDefault("raiseHandCount", 0) < 3) {
            suggestions.add("课堂互动较少，建议积极举手参与课堂");
        }
        if (suggestions.isEmpty()) {
            suggestions.add("整体表现良好，继续保持！");
        }
        analysis.put("suggestions", suggestions);

        return analysis;
    }

    // 班级学情分析
    public Map<String, Object> getClassAnalysis(Long classId) {
        Map<String, Object> analysis = new HashMap<>();

        // 班级成绩统计
        List<Map<String, Object>> grades = jdbc.queryForList(
            "SELECT * FROM grades WHERE class_id=?", classId);
        if (!grades.isEmpty()) {
            double avgScore = grades.stream().mapToDouble(g -> ((Number) g.get("score")).doubleValue()).average().orElse(0);
            double maxScore = grades.stream().mapToDouble(g -> ((Number) g.get("score")).doubleValue()).max().orElse(0);
            double minScore = grades.stream().mapToDouble(g -> ((Number) g.get("score")).doubleValue()).min().orElse(0);
            long passCount = grades.stream().filter(g -> ((Number) g.get("score")).doubleValue() >= 60).count();
            analysis.put("avgScore", Math.round(avgScore * 10) / 10.0);
            analysis.put("maxScore", maxScore);
            analysis.put("minScore", minScore);
            analysis.put("passRate", Math.round(passCount * 100.0 / grades.size()));
            analysis.put("examCount", grades.size());

            // 分数段分布
            Map<String, Integer> scoreDistribution = new LinkedHashMap<>();
            scoreDistribution.put("90-100", 0);
            scoreDistribution.put("80-89", 0);
            scoreDistribution.put("70-79", 0);
            scoreDistribution.put("60-69", 0);
            scoreDistribution.put("<60", 0);
            for (Map<String, Object> g : grades) {
                double score = ((Number) g.get("score")).doubleValue();
                if (score >= 90) scoreDistribution.put("90-100", scoreDistribution.get("90-100") + 1);
                else if (score >= 80) scoreDistribution.put("80-89", scoreDistribution.get("80-89") + 1);
                else if (score >= 70) scoreDistribution.put("70-79", scoreDistribution.get("70-79") + 1);
                else if (score >= 60) scoreDistribution.put("60-69", scoreDistribution.get("60-69") + 1);
                else scoreDistribution.put("<60", scoreDistribution.get("<60") + 1);
            }
            analysis.put("scoreDistribution", scoreDistribution);
        }

        // 学生排名
        List<Map<String, Object>> studentRanking = jdbc.queryForList(
            "SELECT s.id, s.name, AVG(g.score) as avg_score, COUNT(g.id) as exam_count " +
            "FROM students s LEFT JOIN grades g ON g.student_id=s.id " +
            "WHERE s.class_id=? AND (s.is_deleted IS NULL OR s.is_deleted=0) " +
            "GROUP BY s.id, s.name ORDER BY avg_score DESC", classId);
        analysis.put("studentRanking", studentRanking);

        return analysis;
    }
}
