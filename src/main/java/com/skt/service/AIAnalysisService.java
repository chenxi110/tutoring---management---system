package com.skt.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class AIAnalysisService {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private AnalysisService analysisService;

    // 生成AI学情分析报告
    public Map<String, Object> generateReport(Long studentId, Long classId, String reportType) {
        Map<String, Object> result = new HashMap<>();
        try {
            // 获取学生信息
            List<Map<String, Object>> students = jdbc.queryForList(
                "SELECT * FROM students WHERE id=?", studentId);
            if (students.isEmpty()) {
                result.put("code", 404);
                result.put("msg", "学生不存在");
                return result;
            }
            Map<String, Object> student = students.get(0);
            String studentName = (String) student.get("name");

            // 获取学情分析数据
            Map<String, Object> analysis = analysisService.getStudentAnalysis(studentId, classId);

            // 构建报告内容
            StringBuilder reportContent = new StringBuilder();
            reportContent.append("【").append(studentName).append("】学情分析报告\n\n");
            reportContent.append("一、综合评分：").append(analysis.getOrDefault("overallScore", 0)).append("分\n\n");

            reportContent.append("二、成绩分析\n");
            if (analysis.containsKey("avgScore")) {
                reportContent.append("平均分：").append(analysis.get("avgScore")).append("分\n");
                reportContent.append("最高分：").append(analysis.get("maxScore")).append("分\n");
                reportContent.append("最低分：").append(analysis.get("minScore")).append("分\n");
                reportContent.append("考试次数：").append(analysis.get("examCount")).append("次\n");
                if (analysis.containsKey("trend")) {
                    String trend = (String) analysis.get("trend");
                    reportContent.append("成绩趋势：").append("up".equals(trend) ? "上升" : "down".equals(trend) ? "下降" : "稳定").append("\n");
                }
            }
            reportContent.append("\n");

            reportContent.append("三、作业完成情况\n");
            reportContent.append("作业总数：").append(analysis.getOrDefault("homeworkTotal", 0)).append("次\n");
            reportContent.append("已提交：").append(analysis.getOrDefault("homeworkSubmitted", 0)).append("次\n");
            reportContent.append("提交率：").append(analysis.getOrDefault("homeworkSubmitRate", 0)).append("%\n");
            reportContent.append("作业平均分：").append(analysis.getOrDefault("homeworkAvgScore", 0)).append("分\n\n");

            reportContent.append("四、出勤情况\n");
            reportContent.append("出勤次数：").append(analysis.getOrDefault("presentCount", 0)).append("次\n");
            reportContent.append("缺勤次数：").append(analysis.getOrDefault("absentCount", 0)).append("次\n");
            reportContent.append("出勤率：").append(analysis.getOrDefault("attendanceRate", 100)).append("%\n\n");

            reportContent.append("五、课堂参与度\n");
            Map<String, Object> behavior = (Map<String, Object>) analysis.getOrDefault("classroomBehavior", new HashMap<>());
            reportContent.append("签到次数：").append(behavior.getOrDefault("signinCount", 0)).append("次\n");
            reportContent.append("举手次数：").append(behavior.getOrDefault("raiseHandCount", 0)).append("次\n");
            reportContent.append("答题次数：").append(behavior.getOrDefault("answerCount", 0)).append("次\n");
            reportContent.append("课堂积分：").append(behavior.getOrDefault("totalScore", 0)).append("分\n\n");

            reportContent.append("六、错题情况\n");
            reportContent.append("错题总数：").append(analysis.getOrDefault("wrongQuestionCount", 0)).append("道\n");
            reportContent.append("已掌握：").append(analysis.getOrDefault("masteredQuestionCount", 0)).append("道\n\n");

            reportContent.append("七、改进建议\n");
            List<String> suggestions = (List<String>) analysis.getOrDefault("suggestions", new ArrayList<>());
            for (int i = 0; i < suggestions.size(); i++) {
                reportContent.append(i + 1).append(". ").append(suggestions.get(i)).append("\n");
            }

            // 保存报告
            String type = reportType != null ? reportType : "custom";
            jdbc.update(
                "INSERT INTO ai_analysis_reports (student_id, student_name, class_id, report_type, overall_score, grade_trend, suggestions, report_content, generated_by) VALUES (?,?,?,?,?,?,?,?, 'ai')",
                studentId, studentName, classId, type,
                analysis.getOrDefault("overallScore", 0),
                analysis.getOrDefault("trend", "stable"),
                suggestions.toString(), reportContent.toString());

            result.put("code", 200);
            result.put("msg", "AI学情分析报告生成成功");
            result.put("reportContent", reportContent.toString());
            result.put("analysis", analysis);
        } catch (Exception e) {
            result.put("code", 500);
            result.put("msg", "生成失败：" + e.getMessage());
        }
        return result;
    }

    // 报告列表
    public List<Map<String, Object>> listReports(Long studentId) {
        return jdbc.queryForList(
            "SELECT id, student_name, report_type, period_start, period_end, overall_score, grade_trend, created_at FROM ai_analysis_reports WHERE student_id=? ORDER BY created_at DESC", studentId);
    }

    // 报告详情
    public Map<String, Object> getReport(Long id) {
        List<Map<String, Object>> reports = jdbc.queryForList(
            "SELECT * FROM ai_analysis_reports WHERE id=?", id);
        return reports.isEmpty() ? null : reports.get(0);
    }
}
