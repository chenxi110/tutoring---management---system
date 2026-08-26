package com.skt.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 考试服务
 * 处理教师启动考试、学生获取考试信息、学生提交考试
 */
@Service
public class ExamService {

    @Autowired
    private JdbcTemplate jdbc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 教师启动考试：保存考试基本信息和题目到数据库
     */
    public Map<String, Object> launchExam(Long teacherId, String examCode, Long classId,
                                           String title, Integer duration, String password,
                                           String configJson, List<Map<String, Object>> questions) {
        Map<String, Object> result = new HashMap<>();
        try {
            // 校验班级是否属于该教师
            Long classTeacherId = jdbc.queryForObject(
                "SELECT teacher_id FROM classes WHERE id=?", Long.class, classId);
            if (classTeacherId == null || !classTeacherId.equals(teacherId)) {
                result.put("code", 403);
                result.put("msg", "无权在该班级启动考试");
                return result;
            }

            // 如果该examCode已存在，先删除旧数据（重新启动）
            List<Long> existingIds = jdbc.queryForList(
                "SELECT id FROM exam WHERE exam_code=?", Long.class, examCode);
            for (Long oldId : existingIds) {
                jdbc.update("DELETE FROM exam_question WHERE exam_id=?", oldId);
                jdbc.update("DELETE FROM exam WHERE id=?", oldId);
            }

            // 插入考试记录
            jdbc.update(
                "INSERT INTO exam (exam_code, class_id, teacher_id, title, duration, password, status, start_time, config_json) VALUES (?,?,?,?,?,?,?,NOW(),?)",
                examCode, classId, teacherId, title != null ? title : "课堂测试",
                duration != null ? duration : 30, password != null ? password : "",
                "running", configJson != null ? configJson : "{}"
            );
            Long examId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);

            // 插入题目
            if (questions != null) {
                int sortOrder = 0;
                for (Map<String, Object> q : questions) {
                    String questionJson = objectMapper.writeValueAsString(q);
                    jdbc.update(
                        "INSERT INTO exam_question (exam_id, question_json, sort_order) VALUES (?,?,?)",
                        examId, questionJson, sortOrder++
                    );
                }
            }

            result.put("code", 200);
            result.put("msg", "考试启动成功");
            result.put("examId", examId);
            result.put("examCode", examCode);
        } catch (Exception e) {
            result.put("code", 500);
            result.put("msg", "启动考试失败：" + e.getMessage());
        }
        return result;
    }

    /**
     * 学生获取考试信息
     * 校验：学生姓名是否属于该班级、考试状态、密码
     */
    public Map<String, Object> getStudentExamInfo(String examCode, String studentName, String password) {
        Map<String, Object> result = new HashMap<>();
        try {
            if (examCode == null || examCode.isEmpty()) {
                result.put("code", 400);
                result.put("msg", "缺少考试编码");
                return result;
            }
            if (studentName == null || studentName.trim().isEmpty()) {
                result.put("code", 400);
                result.put("msg", "请输入您的姓名");
                return result;
            }

            // 查询考试
            List<Map<String, Object>> examList = jdbc.queryForList(
                "SELECT * FROM exam WHERE exam_code=?", examCode);
            if (examList.isEmpty()) {
                result.put("code", 404);
                result.put("msg", "考试不存在或已被删除");
                return result;
            }
            Map<String, Object> exam = examList.get(0);
            Long examId = ((Number) exam.get("id")).longValue();
            Long classId = ((Number) exam.get("class_id")).longValue();
            String status = (String) exam.get("status");
            String examPassword = (String) exam.get("password");

            // 校验考试状态
            if ("pending".equals(status)) {
                result.put("code", 400);
                result.put("msg", "考试尚未开始，请等待教师开启考试");
                return result;
            }
            if ("ended".equals(status)) {
                result.put("code", 400);
                result.put("msg", "考试已结束");
                return result;
            }

            // 校验密码
            if (examPassword != null && !examPassword.isEmpty()) {
                if (password == null || !password.equals(examPassword)) {
                    result.put("code", 403);
                    result.put("msg", "考试密码错误");
                    return result;
                }
            }

            // 校验学生是否属于该班级（按姓名匹配）
            List<Map<String, Object>> students = jdbc.queryForList(
                "SELECT id, name FROM students WHERE name=? AND class_id=? AND (is_deleted IS NULL OR is_deleted=0) AND (status IS NULL OR status='active')",
                studentName.trim(), classId
            );
            if (students.isEmpty()) {
                result.put("code", 403);
                result.put("msg", "无权限参加本次考试：您不属于该考试班级");
                return result;
            }
            Long studentId = ((Number) students.get(0).get("id")).longValue();

            // 查询题目
            List<Map<String, Object>> questionRows = jdbc.queryForList(
                "SELECT question_json FROM exam_question WHERE exam_id=? ORDER BY sort_order ASC", examId);
            List<Map<String, Object>> quizzes = new ArrayList<>();
            for (Map<String, Object> row : questionRows) {
                String qJson = (String) row.get("question_json");
                try {
                    Map<String, Object> q = objectMapper.readValue(qJson, new TypeReference<Map<String, Object>>() {});
                    quizzes.add(q);
                } catch (Exception e) {
                    // 忽略解析失败的题目
                }
            }

            if (quizzes.isEmpty()) {
                result.put("code", 400);
                result.put("msg", "本次考试暂无试题，请联系教师");
                return result;
            }

            // 构建返回数据
            Map<String, Object> examInfo = new HashMap<>();
            examInfo.put("examId", examId);
            examInfo.put("examCode", examCode);
            examInfo.put("title", exam.get("title"));
            examInfo.put("duration", exam.get("duration"));
            examInfo.put("studentId", studentId);
            examInfo.put("studentName", studentName.trim());
            examInfo.put("classId", classId);
            // 解析configJson
            try {
                String configJson = (String) exam.get("config_json");
                if (configJson != null && !configJson.isEmpty()) {
                    Map<String, Object> config = objectMapper.readValue(configJson, new TypeReference<Map<String, Object>>() {});
                    examInfo.put("config", config);
                } else {
                    examInfo.put("config", new HashMap<>());
                }
            } catch (Exception e) {
                examInfo.put("config", new HashMap<>());
            }

            result.put("code", 200);
            result.put("msg", "获取成功");
            result.put("examInfo", examInfo);
            result.put("quizzes", quizzes);
        } catch (Exception e) {
            result.put("code", 500);
            result.put("msg", "获取考试信息失败：" + e.getMessage());
        }
        return result;
    }

    /**
     * 学生提交考试
     */
    public Map<String, Object> submitExam(String examCode, Long studentId, String studentName,
                                           String answersJson, Double score) {
        Map<String, Object> result = new HashMap<>();
        try {
            List<Map<String, Object>> examList = jdbc.queryForList(
                "SELECT id FROM exam WHERE exam_code=?", examCode);
            if (examList.isEmpty()) {
                result.put("code", 404);
                result.put("msg", "考试不存在");
                return result;
            }
            Long examId = ((Number) examList.get(0).get("id")).longValue();
            jdbc.update(
                "INSERT INTO exam_submission (exam_id, student_id, student_name, answers_json, score) VALUES (?,?,?,?,?)",
                examId, studentId, studentName, answersJson, score
            );
            result.put("code", 200);
            result.put("msg", "提交成功");
        } catch (Exception e) {
            result.put("code", 500);
            result.put("msg", "提交失败：" + e.getMessage());
        }
        return result;
    }
}
