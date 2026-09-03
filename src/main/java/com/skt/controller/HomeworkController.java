package com.skt.controller;

import com.skt.security.RoleAccess;
import com.skt.service.AuthService;
import com.skt.service.HomeworkService;
import com.skt.util.ExcelExportUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.File;
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
    @Autowired
    private JdbcTemplate jdbc;

    @Value("${file.uploadBasePath:./upload/courseFile/}")
    private String uploadBasePath;

    /** 危险后缀黑名单（允许除可执行文件外的任意安全格式） */
    private static final Set<String> DANGEROUS_EXTENSIONS = Set.of(
        "exe", "bat", "cmd", "sh", "msi", "com", "scr", "vbs", "js", "jar", "war",
        "ps1", "reg", "dll", "so", "apk", "ipa", "html", "htm", "svg"
    );

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
        String className = (String) body.get("className");
        // 前端可能只传班级名，按名称解析班级ID
        if (classId == null && className != null && !className.isEmpty()) {
            // 按当前教师限定班级，避免重名班级解析到其他教师班级
            List<Map<String, Object>> clsRows = jdbc.queryForList("SELECT id FROM classes WHERE name=? AND (teacher_id=? OR ?=0) LIMIT 1", className, createdBy, createdBy);
            if (!clsRows.isEmpty()) {
                classId = ((Number) clsRows.get(0).get("id")).longValue();
            }
        }
        String title = (String) body.get("title");
        String content = (String) body.get("content");
        String deadline = (String) body.get("deadline");
        String teacherName = (String) req.getAttribute("displayName");
        if (classId == null) {
            Map<String, Object> err = new HashMap<>();
            err.put("code", 400);
            err.put("msg", "班级不存在，无法发布作业到家长/学生端，请确认班级已创建");
            return err;
        }
        Long id = homeworkService.create(classId, title, content, deadline, createdBy, teacherName);
        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("id", id);
        return result;
    }

    /**
     * 发布作业（支持附带文件，multipart）
     * POST /api/homework/createWithFile
     * 参数：classId/className/title/content/deadline + file(可选)
     */
    @PostMapping("/homework/createWithFile")
    public Map<String, Object> createWithFile(
            @RequestParam(value = "classId", required = false) Long classId,
            @RequestParam(value = "className", required = false) String className,
            @RequestParam(value = "title", required = false) String title,
            @RequestParam(value = "content", required = false) String content,
            @RequestParam(value = "deadline", required = false) String deadline,
            @RequestParam(value = "file", required = false) MultipartFile file,
            HttpServletRequest req) {
        if (RoleAccess.isParent(req)) {
            return RoleAccess.forbidParentWrite("家长账号无权发布作业");
        }
        Long createdBy = (Long) req.getAttribute("userId");
        if (classId == null && className != null && !className.isEmpty()) {
            List<Map<String, Object>> clsRows = jdbc.queryForList(
                "SELECT id FROM classes WHERE name=? AND (teacher_id=? OR ?=0) LIMIT 1", className, createdBy, createdBy);
            if (!clsRows.isEmpty()) {
                classId = ((Number) clsRows.get(0).get("id")).longValue();
            }
        }
        Map<String, Object> result = new HashMap<>();
        if (classId == null) {
            result.put("code", 400);
            result.put("msg", "班级不存在，无法发布作业到家长/学生端，请确认班级已创建");
            return result;
        }
        if (title == null || title.trim().isEmpty()) {
            result.put("code", 400);
            result.put("msg", "作业标题不能为空");
            return result;
        }
        String fileName = null;
        String filePath = null;
        String fileSuffix = null;
        Long fileSize = null;
        if (file != null && !file.isEmpty()) {
            String validation = validateHomeworkFile(file);
            if (validation != null) {
                result.put("code", 400);
                result.put("msg", validation);
                return result;
            }
            try {
                filePath = storeFile(file);
                fileName = file.getOriginalFilename();
                fileSize = file.getSize();
                fileSuffix = fileName.contains(".")
                    ? fileName.substring(fileName.lastIndexOf(".") + 1).toLowerCase() : "bin";
            } catch (java.io.IOException e) {
                result.put("code", 500);
                result.put("msg", "文件保存失败：" + e.getMessage());
                return result;
            }
        }
        String teacherName = (String) req.getAttribute("displayName");
        Long id = homeworkService.create(classId, title, content, deadline, createdBy, teacherName);
        if (filePath != null) {
            jdbc.update("UPDATE homework SET file_name=?, file_path=?, file_size=?, file_suffix=? WHERE id=?",
                fileName, filePath, fileSize, fileSuffix, id);
        }
        result.put("code", 200);
        result.put("id", id);
        return result;
    }

    /** 作业附件下载（鉴权：教师/管理员可下载全部；家长/学生仅限本班级） */
    @GetMapping("/homework/file/{id}")
    public ResponseEntity<Resource> downloadHomeworkFile(@PathVariable Long id, HttpServletRequest req) {
        Long userId = (Long) req.getAttribute("userId");
        String role = (String) req.getAttribute("role");
        if (userId == null) {
            return ResponseEntity.status(401).build();
        }
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM homework WHERE id=?", id);
        if (rows.isEmpty() || rows.get(0).get("file_path") == null) {
            return ResponseEntity.status(404).build();
        }
        Map<String, Object> rec = rows.get(0);
        Long classId = rec.get("class_id") == null ? null : ((Number) rec.get("class_id")).longValue();
        boolean allowed = "teacher".equalsIgnoreCase(role) || "admin".equalsIgnoreCase(role);
        if (!allowed && ("parent".equalsIgnoreCase(role) || "student".equalsIgnoreCase(role))) {
            if (classId != null) {
                String sql = "SELECT COUNT(*) FROM students WHERE " +
                    ("student".equalsIgnoreCase(role) ? "user_id=?" : "parent_user_id=?") +
                    " AND class_id=? AND (is_deleted IS NULL OR is_deleted=0)";
                Integer cnt = jdbc.queryForObject(sql, Integer.class, userId, classId);
                allowed = cnt != null && cnt > 0;
            }
        }
        if (!allowed) {
            return ResponseEntity.status(403).build();
        }
        java.io.File f = new java.io.File(String.valueOf(rec.get("file_path"))).getAbsoluteFile();
        if (!f.exists()) {
            return ResponseEntity.status(404).build();
        }
        String fileName = String.valueOf(rec.get("file_name"));
        String encodedName = URLEncoder.encode(fileName, StandardCharsets.UTF_8).replaceAll("\\+", "%20");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encodedName)
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(f.length())
                .body(new FileSystemResource(f));
    }

    /** 作业附件校验：非空、大小、危险后缀过滤（支持全安全格式） */
    private String validateHomeworkFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return "文件不能为空";
        }
        long max = 50L * 1024 * 1024;
        if (file.getSize() > max) {
            return "文件大小不能超过50MB";
        }
        String name = file.getOriginalFilename();
        if (name == null || name.isEmpty()) {
            return "文件名不能为空";
        }
        String ext = name.contains(".") ? name.substring(name.lastIndexOf(".") + 1).toLowerCase() : "";
        if (ext.isEmpty()) {
            return "文件缺少后缀名";
        }
        if (DANGEROUS_EXTENSIONS.contains(ext)) {
            return "不允许上传可执行/危险文件类型：" + ext;
        }
        return null;
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

    /** 教师触发 AI 批阅（可选启用）：生成得分+评语+错题解析，供教师复查确认 */
    @PostMapping("/homework/ai-review/{id}")
    public Map<String, Object> aiReview(@PathVariable Long id, HttpServletRequest req) {
        if (!RoleAccess.isTeacher(req)) {
            return RoleAccess.forbidTeacherOnly("仅教师账号可启用AI批阅");
        }
        return homeworkService.aiReview(id);
    }

    /** 教师复查确认：写正式成绩+同步成绩表+错题自动入错题本+通知家长/学生 */
    @PostMapping("/homework/confirm-review/{id}")
    public Map<String, Object> confirmReview(@PathVariable Long id, @RequestBody Map<String, Object> body, HttpServletRequest req) {
        if (!RoleAccess.isTeacher(req)) {
            return RoleAccess.forbidTeacherOnly("仅教师账号可确认反馈");
        }
        Double score = body.get("score") != null ? ((Number) body.get("score")).doubleValue() : null;
        String comment = (String) body.get("comment");
        List<Map<String, Object>> analysis = new ArrayList<>();
        Object an = body.get("analysis");
        if (an instanceof List) {
            for (Object o : (List<?>) an) {
                if (o instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> m = (Map<String, Object>) o;
                    analysis.add(m);
                }
            }
        }
        Long operatorId = (Long) req.getAttribute("userId");
        return homeworkService.confirmReview(id, score, comment, analysis, operatorId);
    }
    // ==================== 家长/学生提交作业文件 ====================

    /** 家长/学生提交作业（支持任意安全格式文件） */
    @PostMapping("/homework/{id}/submitFile")
    public Map<String, Object> submitFile(@PathVariable Long id,
                                          @RequestParam("file") MultipartFile file,
                                          @RequestParam(value = "content", required = false) String content,
                                          @RequestParam(value = "studentId", required = false) Long studentId,
                                          HttpServletRequest req) {
        Map<String, Object> result = new HashMap<>();
        Long userId = RoleAccess.getUserId(req);
        String role = (String) req.getAttribute("role");
        if (userId == null) {
            result.put("code", 401);
            result.put("msg", "未登录");
            return result;
        }
        List<Map<String, Object>> hwRows = jdbc.queryForList("SELECT * FROM homework WHERE id=?", id);
        if (hwRows.isEmpty()) {
            result.put("code", 404);
            result.put("msg", "作业不存在");
            return result;
        }
        Map<String, Object> hw = hwRows.get(0);
        Long classId = hw.get("class_id") != null ? ((Number) hw.get("class_id")).longValue() : null;
        if (classId == null) {
            result.put("code", 400);
            result.put("msg", "作业未关联班级");
            return result;
        }
        Long sid = studentId;
        String submitRole = role;
        if ("teacher".equalsIgnoreCase(role)) {
            if (sid == null) {
                result.put("code", 400);
                result.put("msg", "请选择提交学生");
                return result;
            }
        } else if ("parent".equalsIgnoreCase(role)) {
            List<Map<String, Object>> kids = jdbc.queryForList(
                "SELECT id FROM students WHERE (parent_user_id=? OR parent_id=?) AND class_id=? AND (is_deleted IS NULL OR is_deleted=0)",
                userId, userId, classId);
            if (kids.isEmpty()) {
                result.put("code", 403);
                result.put("msg", "您的孩子不在该作业班级，无法提交");
                return result;
            }
            sid = ((Number) kids.get(0).get("id")).longValue();
            submitRole = "parent";
        } else if ("student".equalsIgnoreCase(role)) {
            List<Map<String, Object>> self = jdbc.queryForList(
                "SELECT id FROM students WHERE user_id=? AND class_id=? AND (is_deleted IS NULL OR is_deleted=0)",
                userId, classId);
            if (self.isEmpty()) {
                result.put("code", 403);
                result.put("msg", "您不在该作业班级，无法提交");
                return result;
            }
            sid = ((Number) self.get(0).get("id")).longValue();
            submitRole = "student";
        } else {
            result.put("code", 403);
            result.put("msg", "无权限提交作业");
            return result;
        }
        String validation = validateFile(file);
        if (validation != null) {
            result.put("code", 400);
            result.put("msg", validation);
            return result;
        }
        String studentName = "";
        try {
            List<Map<String, Object>> stuRows = jdbc.queryForList("SELECT name FROM students WHERE id=?", sid);
            if (!stuRows.isEmpty() && stuRows.get(0).get("name") != null) {
                studentName = String.valueOf(stuRows.get(0).get("name"));
            }
        } catch (Exception ignore) { }
        try {
            String savePath = storeFile(file);
            Long subId = homeworkService.submitFile(id, sid, studentName, content,
                file.getOriginalFilename(), savePath, submitRole, userId);
            result.put("code", 200);
            result.put("id", subId);
            result.put("msg", "作业提交成功");
        } catch (Exception e) {
            result.put("code", 500);
            result.put("msg", "文件保存失败：" + e.getMessage());
        }
        return result;
    }

    /** 教师查看某作业的全部提交记录 */
    @GetMapping("/homework/{id}/submissions")
    public Map<String, Object> listSubmissions(@PathVariable Long id, HttpServletRequest req) {
        Map<String, Object> result = new HashMap<>();
        if (!RoleAccess.isTeacher(req)) {
            return RoleAccess.forbidTeacherOnly("仅教师账号可查看提交记录");
        }
        List<Map<String, Object>> list = homeworkService.listSubmissions(id);
        result.put("code", 200);
        result.put("data", list);
        return result;
    }

    /** 下载作业提交文件（教师/提交者本人/其家长） */
    @GetMapping("/homework/submissions/{sid}/file")
    public ResponseEntity<Resource> downloadSubmissionFile(@PathVariable Long sid, HttpServletRequest req) {
        Long userId = RoleAccess.getUserId(req);
        String role = (String) req.getAttribute("role");
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT hs.*, h.class_id, c.teacher_id FROM homework_submissions hs " +
            "LEFT JOIN homework h ON h.id=hs.homework_id LEFT JOIN classes c ON c.id=h.class_id WHERE hs.id=?",
            sid);
        if (rows.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        Map<String, Object> sub = rows.get(0);
        if (sub.get("file_path") == null || String.valueOf(sub.get("file_path")).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        boolean allowed = false;
        if (userId != null && ("teacher".equalsIgnoreCase(role) || "admin".equalsIgnoreCase(role))) {
            Object tid = sub.get("teacher_id");
            allowed = tid == null || ((Number) tid).longValue() == userId || "admin".equalsIgnoreCase(role);
        } else if (userId != null && "parent".equalsIgnoreCase(role)) {
            Object stuId = sub.get("student_id");
            if (stuId != null) {
                List<Map<String, Object>> kids = jdbc.queryForList(
                    "SELECT id FROM students WHERE (parent_user_id=? OR parent_id=?) AND id=? AND (is_deleted IS NULL OR is_deleted=0)",
                    userId, userId, ((Number) stuId).longValue());
                allowed = !kids.isEmpty();
            }
        } else if (userId != null && "student".equalsIgnoreCase(role)) {
            Object stuId = sub.get("student_id");
            if (stuId != null) {
                List<Map<String, Object>> self = jdbc.queryForList(
                    "SELECT id FROM students WHERE user_id=? AND id=? AND (is_deleted IS NULL OR is_deleted=0)",
                    userId, ((Number) stuId).longValue());
                allowed = !self.isEmpty();
            }
        }
        if (!allowed) {
            return ResponseEntity.status(403).build();
        }
        try {
            File f = new File(String.valueOf(sub.get("file_path"))).getAbsoluteFile();
            if (!f.exists()) {
                return ResponseEntity.notFound().build();
            }
            Resource resource = new FileSystemResource(f);
            String fileName = sub.get("file_name") != null ? String.valueOf(sub.get("file_name")) : "submission";
            String encodedName = URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");
            return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encodedName)
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(f.length())
                .body(resource);
        } catch (Exception e) {
            return ResponseEntity.status(500).build();
        }
    }

    private String validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return "文件不能为空";
        }
        if (file.getSize() > 50L * 1024 * 1024) {
            return "文件大小不能超过50MB";
        }
        String originalName = file.getOriginalFilename();
        if (originalName == null || originalName.isEmpty()) {
            return "文件名不能为空";
        }
        String ext = originalName.contains(".") ? originalName.substring(originalName.lastIndexOf(".") + 1).toLowerCase() : "";
        if (ext.isEmpty()) {
            return "文件缺少后缀名";
        }
        if (DANGEROUS_EXTENSIONS.contains(ext)) {
            return "不允许提交可执行/危险文件类型：" + ext;
        }
        return null;
    }

    private String storeFile(MultipartFile file) throws java.io.IOException {
        java.io.File baseDir = new java.io.File(uploadBasePath).getAbsoluteFile().getParentFile();
        if (baseDir == null) {
            baseDir = new java.io.File("./upload").getAbsoluteFile();
        }
        java.io.File hwDir = new java.io.File(baseDir, "homework");
        if (!hwDir.exists()) {
            hwDir.mkdirs();
        }
        String originalName = file.getOriginalFilename();
        String ext = originalName.contains(".") ? originalName.substring(originalName.lastIndexOf(".") + 1).toLowerCase() : "bin";
        String storedName = "hw_" + System.currentTimeMillis() + "_" + (int)(Math.random() * 9999) + "." + ext;
        java.io.File dest = new java.io.File(hwDir, storedName).getAbsoluteFile();
        file.transferTo(dest);
        return dest.getPath();
    }

}