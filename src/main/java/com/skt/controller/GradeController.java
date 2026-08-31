package com.skt.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.skt.security.RoleAccess;
import com.skt.service.GradeService;
import com.skt.service.OperationLogService;
import com.skt.util.ExcelExportUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

@RestController
@RequestMapping("/api")
public class GradeController {

    private static final Logger log = LoggerFactory.getLogger(GradeController.class);

    @Autowired
    private GradeService gradeService;
    @Autowired
    private OperationLogService operationLogService;

    @GetMapping("/grades")
    public Map<String, Object> list(@RequestParam(required = false) Long classId,
                                    @RequestParam(required = false) Long studentId,
                                    @RequestParam(required = false) String examName,
                                    @RequestParam(required = false) Long semesterId,
                                    HttpServletRequest req) {
        Long userId = (Long) req.getAttribute("userId");
        String role = (String) req.getAttribute("role");
        List<Map<String, Object>> list = gradeService.list(classId, studentId, examName, semesterId, userId, role);
        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("data", list);
        return result;
    }

    @GetMapping("/grades/my")
    public Map<String, Object> myGrades(@RequestParam(required = false) Long classId,
                                       @RequestParam(required = false) Long studentId,
                                       @RequestParam(required = false) String examName,
                                       @RequestParam(required = false) Long semesterId,
                                       HttpServletRequest req) {
        if (!RoleAccess.isParent(req)) {
            return RoleAccess.forbidParentWrite("仅家长账号可查看个人绑定学生成绩");
        }
        Long parentId = (Long) req.getAttribute("userId");
        List<Map<String, Object>> list = gradeService.listForParent(parentId, classId, studentId, examName, semesterId);
        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("data", list);
        return result;
    }

    @PostMapping("/grades")
    public Map<String, Object> create(@RequestBody Map<String, Object> body, HttpServletRequest req) {
        if (RoleAccess.isParent(req)) {
            return RoleAccess.forbidParentWrite("家长账号无权录入成绩");
        }
        try {
            Long teacherId = (Long) req.getAttribute("userId");
            Object classIdValue = firstNonNull(body, "classId", "class_id");
            Object studentIdValue = firstNonNull(body, "studentId", "student_id");
            Object studentNameValue = firstNonNull(body, "studentName", "student_name", "student");
            Object classNameValue = firstNonNull(body, "className", "class_name");
            Object examNameValue = firstNonNull(body, "examName", "exam_name");
            Object examTypeValue = firstNonNull(body, "examType", "exam_type");
            Object scoreValue = firstNonNull(body, "score");
            Object totalScoreValue = firstNonNull(body, "totalScore", "total_score", "fullScore", "full_score");
            Object semesterIdValue = firstNonNull(body, "semesterId", "semester_id");
            Object rankValue = firstNonNull(body, "rank");
            Object remarkValue = firstNonNull(body, "remark", "note");

            Map<String, Object> validation = gradeService.validateCreatePayload(
                classIdValue, studentIdValue, studentNameValue, classNameValue,
                examNameValue, examTypeValue, scoreValue, totalScoreValue,
                semesterIdValue, rankValue, remarkValue
            );
            if (!"200".equals(String.valueOf(validation.get("code")))) {
                return validation;
            }

            Long id = gradeService.create(
                gradeService.toLong(studentIdValue),
                studentNameValue == null ? null : String.valueOf(studentNameValue),
                gradeService.toLong(classIdValue),
                classNameValue == null ? null : String.valueOf(classNameValue),
                examNameValue == null ? null : String.valueOf(examNameValue),
                examTypeValue == null ? null : String.valueOf(examTypeValue),
                gradeService.parseDouble(scoreValue, "分数必须为0~满分之间的有效数字."),
                gradeService.parseDouble(totalScoreValue, "满分必须填写大于0数字."),
                gradeService.toLong(semesterIdValue),
                teacherId,
                remarkValue == null ? null : String.valueOf(remarkValue),
                gradeService.toLong(rankValue)
            );
            String username = (String) req.getAttribute("username");
            String role = (String) req.getAttribute("role");
            String ip = req.getRemoteAddr();
            operationLogService.log(teacherId, username, role, "成绩录入",
                "学生:" + (studentNameValue != null ? studentNameValue : "") +
                ",考试:" + (examNameValue != null ? examNameValue : "") +
                ",分数:" + scoreValue, ip);
            Map<String, Object> result = new HashMap<>();
            result.put("code", 200);
            result.put("id", id);
            return result;
        } catch (Exception e) {
            log.error("成绩操作异常: ", e);
            Map<String, Object> result = new HashMap<>();
            result.put("code", 500);
            result.put("msg", "保存成绩失败，请稍后重试");
            return result;
        }
    }

    @PostMapping("/grades/batch")
    public Map<String, Object> batchCreate(@RequestBody Map<String, Object> body, HttpServletRequest req) {
        if (RoleAccess.isParent(req)) {
            return RoleAccess.forbidParentWrite("家长账号无权批量录入成绩");
        }
        Long teacherId = (Long) req.getAttribute("userId");
        Long classId = body.get("classId") != null ? ((Number) body.get("classId")).longValue() : null;
        String className = (String) body.get("className");
        String examName = (String) body.get("examName");
        String examType = (String) body.get("examType");
        Double totalScore = body.get("totalScore") != null ? ((Number) body.get("totalScore")).doubleValue() : null;
        Long semesterId = body.get("semesterId") != null ? ((Number) body.get("semesterId")).longValue() : null;
        List<Map<String, Object>> entries = (List<Map<String, Object>>) body.get("entries");
        int count = gradeService.batchCreate(classId, className, examName, examType, totalScore, semesterId, teacherId, entries);
        String username = (String) req.getAttribute("username");
        String role = (String) req.getAttribute("role");
        String ip = req.getRemoteAddr();
        operationLogService.log(teacherId, username, role, "成绩批量录入",
            "班级:" + (className != null ? className : "") + ",考试:" + (examName != null ? examName : "") + ",数量:" + count, ip);
        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("count", count);
        return result;
    }

    @PutMapping("/grades/{id}")
    public Map<String, Object> update(@PathVariable Long id, @RequestBody Map<String, Object> body, HttpServletRequest req) {
        if (RoleAccess.isParent(req)) {
            return RoleAccess.forbidParentWrite("家长账号无权修改成绩");
        }
        try {
            Object scoreValue = firstNonNull(body, "score");
            Object totalScoreValue = firstNonNull(body, "totalScore", "total_score", "fullScore", "full_score");
            Object rankValue = firstNonNull(body, "rank");
            Object remarkValue = firstNonNull(body, "remark", "note");

            Map<String, Object> validation = gradeService.validateUpdatePayload(scoreValue, totalScoreValue, rankValue);
            if (!"200".equals(String.valueOf(validation.get("code")))) {
                return validation;
            }

            Double score = gradeService.parseDouble(scoreValue, "分数必须为0~满分之间的有效数字.");
            Double totalScore = gradeService.parseDouble(totalScoreValue, "满分必须填写大于0数字.");
            Integer rank = null;
            if (rankValue != null && !"".equals(String.valueOf(rankValue).trim())) {
                rank = Integer.parseInt(String.valueOf(rankValue).trim());
            }
            String remark = remarkValue == null ? null : String.valueOf(remarkValue);
            gradeService.update(id, score, totalScore, rank, remark);
            Long userId = (Long) req.getAttribute("userId");
            String username = (String) req.getAttribute("username");
            String role = (String) req.getAttribute("role");
            String ip = req.getRemoteAddr();
            operationLogService.log(userId, username, role, "成绩修改",
                "成绩ID:" + id + ",新分数:" + score + ",满分:" + totalScore, ip);
            Map<String, Object> result = new HashMap<>();
            result.put("code", 200);
            return result;
        } catch (Exception e) {
            log.error("成绩操作异常: ", e);
            Map<String, Object> result = new HashMap<>();
            result.put("code", 500);
            result.put("msg", "更新成绩失败，请稍后重试");
            return result;
        }
    }

    @DeleteMapping("/grades/{id}")
    public Map<String, Object> delete(@PathVariable Long id, HttpServletRequest req) {
        if (RoleAccess.isParent(req)) {
            return RoleAccess.forbidParentWrite("家长账号无权删除成绩");
        }
        gradeService.delete(id);
        Long userId = (Long) req.getAttribute("userId");
        String username = (String) req.getAttribute("username");
        String role = (String) req.getAttribute("role");
        String ip = req.getRemoteAddr();
        operationLogService.log(userId, username, role, "成绩删除", "成绩ID:" + id, ip);
        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        return result;
    }

    @GetMapping("/grades/stats")
    public Map<String, Object> stats(@RequestParam Long classId,
                                     @RequestParam String examName,
                                     HttpServletRequest req) {
        Long teacherId = (Long) req.getAttribute("userId");
        String role = (String) req.getAttribute("role");
        Map<String, Object> stats = gradeService.stats(classId, examName, teacherId, role);
        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("data", stats);
        return result;
    }

    @PostMapping("/grades/import")
    public Map<String, Object> importExcel(@RequestParam("file") MultipartFile file,
                                           @RequestParam(value = "classId", required = false) Long classId,
                                           @RequestParam(value = "className", required = false) String className,
                                           @RequestParam(value = "examName", required = false) String examName,
                                           @RequestParam(value = "examType", required = false) String examType,
                                           @RequestParam(value = "totalScore", required = false) Double totalScore,
                                           @RequestParam(value = "semesterId", required = false) Long semesterId,
                                           HttpServletRequest req) {
        if (RoleAccess.isParent(req)) {
            return RoleAccess.forbidParentWrite("家长账号无权导入成绩");
        }
        if (file == null || file.isEmpty()) {
            Map<String, Object> err = new HashMap<>();
            err.put("code", 400);
            err.put("msg", "请选择Excel文件");
            return err;
        }
        String filename = file.getOriginalFilename();
        if (filename == null || (!filename.toLowerCase().endsWith(".xlsx") && !filename.toLowerCase().endsWith(".xls"))) {
            Map<String, Object> err = new HashMap<>();
            err.put("code", 400);
            err.put("msg", "仅支持 .xlsx 和 .xls 格式的Excel文件");
            return err;
        }
        if (classId == null) {
            Map<String, Object> err = new HashMap<>();
            err.put("code", 400);
            err.put("msg", "请先选择班级");
            return err;
        }
        if (totalScore == null) totalScore = 100.0;
        if (examType == null || examType.isEmpty()) examType = "unit_test";
        return gradeService.parseExcelPreview(file, classId, className, examName, examType, totalScore, semesterId);
    }

    @PostMapping("/grades/import/confirm")
    public Map<String, Object> importConfirm(@RequestBody Map<String, Object> body, HttpServletRequest req) {
        if (RoleAccess.isParent(req)) {
            return RoleAccess.forbidParentWrite("家长账号无权导入成绩");
        }
        Long teacherId = (Long) req.getAttribute("userId");
        Long classId = body.get("classId") != null ? ((Number) body.get("classId")).longValue() : null;
        String className = (String) body.get("className");
        String examName = (String) body.get("examName");
        String examType = (String) body.get("examType");
        Double totalScore = body.get("totalScore") != null ? ((Number) body.get("totalScore")).doubleValue() : 100.0;
        Long semesterId = body.get("semesterId") != null ? ((Number) body.get("semesterId")).longValue() : null;
        List<Map<String, Object>> entries = (List<Map<String, Object>>) body.get("entries");
        if (entries == null || entries.isEmpty()) {
            Map<String, Object> err = new HashMap<>();
            err.put("code", 400);
            err.put("msg", "没有可导入的数据");
            return err;
        }
        try {
            int count = gradeService.importBatchCreate(classId, className, examName, examType, totalScore, semesterId, teacherId, entries);
            String username = (String) req.getAttribute("username");
            String role = (String) req.getAttribute("role");
            String ip = req.getRemoteAddr();
            operationLogService.log(teacherId, username, role, "Excel导入成绩",
                "班级:" + (className != null ? className : "") + ",考试:" + (examName != null ? examName : "") + ",导入数量:" + count, ip);
            Map<String, Object> result = new HashMap<>();
            result.put("code", 200);
            result.put("count", count);
            return result;
        } catch (Exception e) {
            log.error("成绩操作异常: ", e);
            Map<String, Object> err = new HashMap<>();
            err.put("code", 500);
            err.put("msg", "批量导入失败：" + e.getMessage());
            return err;
        }
    }

    // 导出成绩Excel
    @GetMapping("/grades/export")
    public void exportGrades(@RequestParam(required = false) Long classId,
                             @RequestParam(required = false) Long studentId,
                             @RequestParam(required = false) String examName,
                             @RequestParam(required = false) Long semesterId,
                             HttpServletRequest req, HttpServletResponse response) {
        if (RoleAccess.isParent(req)) {
            response.setStatus(403);
            return;
        }
        Long userId = (Long) req.getAttribute("userId");
        String role = (String) req.getAttribute("role");
        List<Map<String, Object>> list = gradeService.list(classId, studentId, examName, semesterId, userId, role);
        String[] headers = {"学生姓名", "班级", "考试名称", "考试类型", "分数", "总分", "排名", "创建时间"};
        String[] keys = {"student_name", "class_name", "exam_name", "exam_type", "score", "total_score", "rank", "created_at"};
        byte[] excelData = ExcelExportUtil.export(headers, keys, list, "成绩列表");
        try {
            String fileName = URLEncoder.encode("成绩列表.xlsx", StandardCharsets.UTF_8);
            response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
            response.setHeader("Content-Disposition", "attachment; filename=" + fileName);
            response.setContentLength(excelData.length);
            response.getOutputStream().write(excelData);
            response.getOutputStream().flush();
        } catch (Exception e) {
            throw new RuntimeException("导出失败: " + e.getMessage(), e);
        }
    }

    private Object firstNonNull(Map<String, Object> body, String... keys) {
        if (body == null) return null;
        for (String key : keys) {
            if (body.containsKey(key) && body.get(key) != null && !"".equals(String.valueOf(body.get(key)).trim())) {
                return body.get(key);
            }
        }
        return null;
    }
}
