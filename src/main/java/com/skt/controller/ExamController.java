package com.skt.controller;

import com.skt.security.RoleAccess;
import com.skt.service.ExamService;
import com.skt.service.OperationLogService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import java.util.*;

/**
 * 考试控制器
 * 提供教师启动考试、学生获取考试信息、学生提交考试接口
 */
@RestController
@RequestMapping("/api/exam")
public class ExamController {

    @Autowired
    private ExamService examService;
    @Autowired
    private OperationLogService operationLogService;

    /**
     * 教师启动考试
     * POST /api/exam/launch
     * Body: { examCode, classId, title, duration, password, configJson, questions[] }
     */
    @PostMapping("/launch")
    public Map<String, Object> launchExam(@RequestBody Map<String, Object> body, HttpServletRequest req) {
        if (RoleAccess.isParent(req)) {
            return RoleAccess.forbidParentWrite("家长无权启动考试");
        }
        Long teacherId = RoleAccess.getUserId(req);
        String examCode = body.get("examCode") != null ? body.get("examCode").toString() : null;
        Long classId = body.get("classId") != null ? Long.valueOf(body.get("classId").toString()) : null;
        String title = body.get("title") != null ? body.get("title").toString() : "课堂测试";
        Integer duration = body.get("duration") != null ? Integer.valueOf(body.get("duration").toString()) : 30;
        String password = body.get("password") != null ? body.get("password").toString() : "";
        String configJson = body.get("configJson") != null ? body.get("configJson").toString() : "{}";
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> questions = (List<Map<String, Object>>) body.get("questions");

        if (examCode == null || examCode.isEmpty()) {
            Map<String, Object> err = new HashMap<>();
            err.put("code", 400);
            err.put("msg", "缺少考试编码");
            return err;
        }
        if (classId == null) {
            Map<String, Object> err = new HashMap<>();
            err.put("code", 400);
            err.put("msg", "缺少班级ID");
            return err;
        }
        if (questions == null || questions.isEmpty()) {
            Map<String, Object> err = new HashMap<>();
            err.put("code", 400);
            err.put("msg", "考试题目不能为空");
            return err;
        }

        Map<String, Object> result = examService.launchExam(teacherId, examCode, classId, title, duration, password, configJson, questions);
        if (result != null && "200".equals(String.valueOf(result.get("code")))) {
            String username = (String) req.getAttribute("username");
            String role = (String) req.getAttribute("role");
            String ip = req.getRemoteAddr();
            operationLogService.log(teacherId, username, role, "创建并启动考试",
                "考试:" + title + ",班级ID:" + classId + ",题目数:" + (questions != null ? questions.size() : 0), ip);
        }
        return result;
    }

    /**
     * 学生获取考试信息
     * GET /api/exam/getStudentExamInfo?examCode=xxx&studentName=xxx&password=xxx
     * 无需登录（学生端独立页面），通过姓名+班级校验权限
     */
    @GetMapping("/getStudentExamInfo")
    public Map<String, Object> getStudentExamInfo(
            @RequestParam String examCode,
            @RequestParam String studentName,
            @RequestParam(required = false) String password) {
        return examService.getStudentExamInfo(examCode, studentName, password);
    }

    /**
     * 学生提交考试
     * POST /api/exam/submit
     * Body: { examCode, studentId, studentName, answersJson, score }
     */
    @PostMapping("/submit")
    public Map<String, Object> submitExam(@RequestBody Map<String, Object> body) {
        String examCode = body.get("examCode") != null ? body.get("examCode").toString() : null;
        Long studentId = body.get("studentId") != null ? Long.valueOf(body.get("studentId").toString()) : null;
        String studentName = body.get("studentName") != null ? body.get("studentName").toString() : null;
        String answersJson = body.get("answersJson") != null ? body.get("answersJson").toString() : null;
        Double score = body.get("score") != null ? Double.valueOf(body.get("score").toString()) : null;

        if (examCode == null) {
            Map<String, Object> err = new HashMap<>();
            err.put("code", 400);
            err.put("msg", "缺少考试编码");
            return err;
        }
        return examService.submitExam(examCode, studentId, studentName, answersJson, score);
    }

    // 教师手动阅卷（主观题打分）
    @PostMapping("/submissions/{id}/grade")
    public Map<String, Object> gradeSubmission(@PathVariable Long id, @RequestBody Map<String, Object> body, HttpServletRequest req) {
        if (!RoleAccess.isTeacher(req)) {
            return RoleAccess.forbidTeacherOnly("仅教师/管理员可阅卷");
        }
        Double teacherScore = body.get("teacherScore") != null ? ((Number) body.get("teacherScore")).doubleValue() : null;
        String comment = body.get("comment") != null ? body.get("comment").toString() : null;
        return examService.gradeSubmission(id, teacherScore, comment);
    }

    // 获取考试提交列表
    @GetMapping("/{examId}/submissions")
    public Map<String, Object> listSubmissions(@PathVariable Long examId, HttpServletRequest req) {
        Map<String, Object> r = new HashMap<>();
        if (!RoleAccess.isTeacher(req)) {
            r.put("code", 403);
            r.put("msg", "仅教师/管理员可查看提交列表");
            return r;
        }
        r.put("code", 200);
        r.put("data", examService.listSubmissions(examId));
        return r;
    }
}
