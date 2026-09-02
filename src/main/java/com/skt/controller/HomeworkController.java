package com.skt.controller;

import com.skt.security.RoleAccess;
import com.skt.service.AuthService;
import com.skt.service.HomeworkService;
import com.skt.util.ExcelExportUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

@RestController
@RequestMapping("/api")
public class HomeworkController {

    @Autowired
    private HomeworkService homeworkService;
    @Autowired
    private AuthService authService;

    @GetMapping("/homework")
    public Map<String, Object> list(@RequestParam(required = false) Long classId, HttpServletRequest req) {
        if (RoleAccess.isParent(req)) {
            return RoleAccess.forbidParentWrite("家长账号请使用「我的作业」查看");
        }
        Long teacherId = (Long) req.getAttribute("userId");
        String role = (String) req.getAttribute("role");
        List<Map<String, Object>> list = homeworkService.list(classId, teacherId, role);
        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("data", list);
        return result;
    }

    // 导出作业列表Excel
    @GetMapping("/homework/export")
    public void exportHomework(@RequestParam(required = false) Long classId,
                               HttpServletRequest req, HttpServletResponse response) {
        if (RoleAccess.isParent(req)) {
            response.setStatus(403);
            return;
        }
        Long teacherId = (Long) req.getAttribute("userId");
        String role = (String) req.getAttribute("role");
        List<Map<String, Object>> list = homeworkService.list(classId, teacherId, role);
        String[] headers = {"作业标题", "班级名称", "截止时间", "创建时间"};
        String[] keys = {"title", "class_name", "deadline", "created_at"};
        byte[] excelData = ExcelExportUtil.export(headers, keys, list, "作业列表");
        try {
            String fileName = URLEncoder.encode("作业列表.xlsx", StandardCharsets.UTF_8);
            response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
            response.setHeader("Content-Disposition", "attachment; filename=" + fileName);
            response.setContentLength(excelData.length);
            response.getOutputStream().write(excelData);
            response.getOutputStream().flush();
        } catch (Exception e) {
            throw new RuntimeException("导出失败: " + e.getMessage(), e);
        }
    }

    @GetMapping("/homework/my")
    public Map<String, Object> listMy(HttpServletRequest req) {
        Long userId = (Long) req.getAttribute("userId");
        List<Map<String, Object>> list;
        if (RoleAccess.isStudent(req)) {
            // 学生：按本人 students.user_id 隔离
            Map<String, Object> stu = authService.getStudentRowByUserId(userId);
            if (stu == null) {
                Map<String, Object> err = new HashMap<>();
                err.put("code", 404);
                err.put("msg", "未找到您的学生档案，请联系教师或家长确认绑定");
                return err;
            }
            Long sid = ((Number) stu.get("id")).longValue();
            Object cidObj = stu.get("class_id");
            Long cid = cidObj == null ? null : ((Number) cidObj).longValue();
            list = homeworkService.listForStudent(sid, cid);
        } else if (RoleAccess.isParent(req)) {
            list = homeworkService.listForParent(userId);
        } else {
            return RoleAccess.forbidParentWrite("仅家长/学生账号可查看作业");
        }
        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("data", list);
        return result;
    }

    @PostMapping("/homework")
    public Map<String, Object> create(@RequestBody Map<String, Object> body, HttpServletRequest req) {
        if (RoleAccess.isParent(req)) {
            return RoleAccess.forbidParentWrite("家长账号无权发布作业");
        }
        Long createdBy = (Long) req.getAttribute("userId");
        Long classId = body.get("classId") != null ? ((Number) body.get("classId")).longValue() : null;
        String title = (String) body.get("title");
        String content = (String) body.get("content");
        String deadline = (String) body.get("deadline");
        Long id = homeworkService.create(classId, title, content, deadline, createdBy);
        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("id", id);
        return result;
    }

    @PostMapping("/homework/{id}/submit")
    public Map<String, Object> submit(@PathVariable Long id, @RequestBody Map<String, Object> body, HttpServletRequest req) {
        if (!RoleAccess.isTeacher(req)) {
            return RoleAccess.forbidTeacherOnly("仅教师账号可登记作业提交");
        }
        Long studentId = body.get("studentId") != null ? ((Number) body.get("studentId")).longValue() : null;
        String studentName = (String) body.get("studentName");
        String content = (String) body.get("content");
        Long subId = homeworkService.submit(id, studentId, studentName, content);
        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("id", subId);
        return result;
    }

    @PutMapping("/homework/submissions/{id}/grade")
    public Map<String, Object> grade(@PathVariable Long id, @RequestBody Map<String, Object> body, HttpServletRequest req) {
        if (!RoleAccess.isTeacher(req)) {
            return RoleAccess.forbidTeacherOnly("仅教师账号可批改作业");
        }
        Double score = body.get("score") != null ? ((Number) body.get("score")).doubleValue() : null;
        String comment = (String) body.get("comment");
        homeworkService.grade(id, score, comment);
        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        return result;
    }
}