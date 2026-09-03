package com.skt.controller;

import com.skt.security.RoleAccess;
import com.skt.service.OperationLogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import java.io.File;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 教师下发家长文件
 * - 教师向「全部家长 / 某班级家长 / 某位家长」下发任意安全格式文件
 * - 家长端可查看并下载发给自己的文件（all / 自己孩子所在班级 / 直接发给自己）
 * - 下载鉴权：家长只能下载发给自己的文件，教师/管理员可下载全部
 * - 危险后缀（exe/bat 等）过滤，其余安全格式全部允许
 */
@RestController
@RequestMapping("/api/parent-file")
public class ParentFileController {

    private static final Logger log = LoggerFactory.getLogger(ParentFileController.class);

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private OperationLogService operationLogService;

    @Value("${file.uploadBasePath:./upload/courseFile/}")
    private String uploadBasePath;

    /** 危险后缀黑名单（与课堂文件一致，双重过滤） */
    private static final Set<String> DANGEROUS_EXTENSIONS = Set.of(
        "exe", "bat", "cmd", "sh", "msi", "com", "scr", "vbs", "js", "jar", "war",
        "ps1", "reg", "dll", "so", "apk", "ipa", "html", "htm", "svg"
    );

    // ==================== 教师下发文件 ====================

    @PostMapping("/upload")
    public Map<String, Object> upload(@RequestParam("file") MultipartFile file,
                                      @RequestParam("targetType") String targetType,
                                      @RequestParam(value = "classId", required = false) Long classId,
                                      @RequestParam(value = "parentId", required = false) Long parentId,
                                      HttpServletRequest req) {
        Map<String, Object> result = new HashMap<>();
        Long teacherId = RoleAccess.getUserId(req);
        String role = (String) req.getAttribute("role");
        String displayName = (String) req.getAttribute("displayName");
        String username = (String) req.getAttribute("username");
        if (teacherId == null || (!"teacher".equalsIgnoreCase(role) && !"admin".equalsIgnoreCase(role))) {
            result.put("code", 403);
            result.put("msg", "无权限：仅教师/管理员可下发文件");
            return result;
        }
        String type = targetType == null ? "all" : targetType.trim();
        if (!"all".equals(type) && !"class".equals(type) && !"parent".equals(type)) {
            result.put("code", 400);
            result.put("msg", "下发对象类型不正确");
            return result;
        }
        if ("class".equals(type) && classId == null) {
            result.put("code", 400);
            result.put("msg", "请选择目标班级");
            return result;
        }
        if ("parent".equals(type) && parentId == null) {
            result.put("code", 400);
            result.put("msg", "请选择目标家长");
            return result;
        }
        String validation = validateFile(file);
        if (validation != null) {
            result.put("code", 400);
            result.put("msg", validation);
            return result;
        }
        try {
            String savePath = storeFile(file);
            String suffix = getFileExtension(file.getOriginalFilename());
            jdbc.update(
                "INSERT INTO parent_file (teacher_id, file_name, save_path, file_suffix, file_size, target_type, class_id, parent_user_id) VALUES (?,?,?,?,?,?,?,?)",
                teacherId, file.getOriginalFilename(), savePath, suffix, file.getSize(), type,
                "class".equals(type) ? classId : null,
                "parent".equals(type) ? parentId : null
            );
            Long id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
            try {
                String targetDesc = "all".equals(type) ? "全部家长"
                    : ("class".equals(type) ? ("班级" + classId) : ("家长ID=" + parentId));
                operationLogService.log(teacherId, username != null ? username : String.valueOf(teacherId),
                    role, "下发家长文件",
                    "向" + targetDesc + "下发文件：" + file.getOriginalFilename(), req.getRemoteAddr());
            } catch (Exception ignoreLog) { }
            result.put("code", 200);
            result.put("id", id);
            result.put("msg", "文件下发成功");
        } catch (IOException e) {
            log.error("家长文件保存失败", e);
            result.put("code", 500);
            result.put("msg", "文件保存失败：" + e.getMessage());
        }
        return result;
    }

    // ==================== 文件列表 ====================

    @GetMapping("/list")
    public Map<String, Object> list(HttpServletRequest req) {
        Map<String, Object> result = new HashMap<>();
        Long userId = RoleAccess.getUserId(req);
        String role = (String) req.getAttribute("role");
        if (userId == null) {
            result.put("code", 401);
            result.put("msg", "未登录");
            return result;
        }
        if ("parent".equalsIgnoreCase(role)) {
            List<Map<String, Object>> list = jdbc.queryForList(
                "SELECT pf.*, u.display_name AS teacher_name, u.username AS teacher_username " +
                "FROM parent_file pf LEFT JOIN users u ON u.id=pf.teacher_id " +
                "WHERE pf.target_type='all' " +
                "  OR (pf.target_type='class' AND pf.class_id IN (" +
                "        SELECT DISTINCT class_id FROM student_class WHERE student_id IN " +
                "          (SELECT id FROM students WHERE parent_user_id=? AND (is_deleted IS NULL OR is_deleted=0)) " +
                "        UNION " +
                "        SELECT class_id FROM students WHERE parent_user_id=? AND (is_deleted IS NULL OR is_deleted=0)" +
                "      )) " +
                "  OR (pf.target_type='parent' AND pf.parent_user_id=?) " +
                "ORDER BY pf.upload_time DESC",
                userId, userId, userId
            );
            result.put("code", 200);
            result.put("data", list);
            return result;
        }
        if ("teacher".equalsIgnoreCase(role) || "admin".equalsIgnoreCase(role)) {
            List<Map<String, Object>> list = jdbc.queryForList(
                "SELECT pf.*, u.display_name AS teacher_name, u.username AS teacher_username, " +
                "c.name AS class_name, pu.display_name AS parent_name " +
                "FROM parent_file pf " +
                "LEFT JOIN users u ON u.id=pf.teacher_id " +
                "LEFT JOIN classes c ON c.id=pf.class_id " +
                "LEFT JOIN users pu ON pu.id=pf.parent_user_id " +
                "ORDER BY pf.upload_time DESC"
            );
            result.put("code", 200);
            result.put("data", list);
            return result;
        }
        result.put("code", 403);
        result.put("msg", "无权限查看");
        return result;
    }

    /** 按班级获取家长列表（教师下发时选择对象） */
    @GetMapping("/parents")
    public Map<String, Object> parents(@RequestParam Long classId, HttpServletRequest req) {
        Map<String, Object> result = new HashMap<>();
        String role = (String) req.getAttribute("role");
        if (role == null || (!"teacher".equalsIgnoreCase(role) && !"admin".equalsIgnoreCase(role))) {
            result.put("code", 403);
            result.put("msg", "无权限");
            return result;
        }
        try {
            List<Map<String, Object>> list = jdbc.queryForList(
                "SELECT DISTINCT u.id AS user_id, u.username, u.display_name AS name " +
                "FROM users u " +
                "JOIN students s ON s.parent_user_id=u.id AND (s.is_deleted IS NULL OR s.is_deleted=0) " +
                "WHERE s.class_id=? OR s.id IN (SELECT student_id FROM student_class WHERE class_id=?) " +
                "ORDER BY u.display_name",
                classId, classId
            );
            result.put("code", 200);
            result.put("data", list);
        } catch (Exception e) {
            log.error("获取班级家长列表失败 classId={}", classId, e);
            result.put("code", 500);
            result.put("msg", "获取家长列表失败：" + e.getMessage());
        }
        return result;
    }

    // ==================== 下载（鉴权） ====================

    @GetMapping("/download/{id}")
    public ResponseEntity<Resource> download(@PathVariable Long id, HttpServletRequest req) {
        Long userId = RoleAccess.getUserId(req);
        String role = (String) req.getAttribute("role");
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM parent_file WHERE id=?", id);
        if (rows.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        Map<String, Object> rec = rows.get(0);
        boolean allowed = false;
        if (userId != null && ("teacher".equalsIgnoreCase(role) || "admin".equalsIgnoreCase(role))) {
            allowed = true;
        } else if (userId != null && "parent".equalsIgnoreCase(role)) {
            String targetType = rec.get("target_type") == null ? "all" : String.valueOf(rec.get("target_type"));
            if ("all".equals(targetType)) {
                allowed = true;
            } else if ("class".equals(targetType)) {
                Long classId = rec.get("class_id") == null ? null : ((Number) rec.get("class_id")).longValue();
                if (classId != null) {
                    Integer cnt = jdbc.queryForObject(
                        "SELECT COUNT(*) FROM students WHERE parent_user_id=? AND (class_id=? OR id IN (SELECT student_id FROM student_class WHERE class_id=?)) AND (is_deleted IS NULL OR is_deleted=0)",
                        Integer.class, userId, classId, classId);
                    allowed = cnt != null && cnt > 0;
                }
            } else if ("parent".equals(targetType)) {
                Object pid = rec.get("parent_user_id");
                allowed = pid != null && ((Number) pid).longValue() == userId;
            }
        }
        if (!allowed) {
            return ResponseEntity.status(403).build();
        }
        try {
            File f = new File(String.valueOf(rec.get("save_path"))).getAbsoluteFile();
            if (!f.exists()) {
                return ResponseEntity.notFound().build();
            }
            Resource resource = new FileSystemResource(f);
            String fileName = String.valueOf(rec.get("file_name"));
            String encodedName = URLEncoder.encode(fileName, StandardCharsets.UTF_8).replaceAll("\\+", "%20");
            return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encodedName)
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(f.length())
                .body(resource);
        } catch (Exception e) {
            log.error("家长文件下载失败 id={}", id, e);
            return ResponseEntity.status(500).build();
        }
    }

    // ==================== 私有工具 ====================

    /** 文件校验：非空、大小、危险后缀黑名单（不设白名单，支持所有安全格式） */
    private String validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return "文件不能为空";
        }
        if (file.getSize() > 50 * 1024 * 1024) {
            return "文件大小不能超过50MB";
        }
        String originalName = file.getOriginalFilename();
        if (originalName == null || originalName.isEmpty()) {
            return "文件名不能为空";
        }
        String ext = getFileExtension(originalName).toLowerCase();
        if (ext.isEmpty()) {
            return "文件缺少后缀名";
        }
        if (DANGEROUS_EXTENSIONS.contains(ext)) {
            return "不允许上传可执行/危险文件类型：" + ext;
        }
        return null;
    }

    /** 存储文件：自动创建目录、自动重命名防覆盖 */
    private String storeFile(MultipartFile file) throws IOException {
        File baseDir = new File(uploadBasePath).getAbsoluteFile().getParentFile();
        if (baseDir == null) {
            baseDir = new File("./upload").getAbsoluteFile();
        }
        File parentDir = new File(baseDir, "parentFile");
        if (!parentDir.exists()) {
            parentDir.mkdirs();
        }
        String ext = getFileExtension(file.getOriginalFilename());
        String storedName = "pf_" + System.currentTimeMillis() + "_" + ThreadLocalRandom.current().nextInt(9999) + "." + ext;
        File dest = new File(parentDir, storedName).getAbsoluteFile();
        file.transferTo(dest);
        return dest.getPath();
    }

    private String getFileExtension(String fileName) {
        if (fileName == null || !fileName.contains(".")) {
            return "";
        }
        return fileName.substring(fileName.lastIndexOf(".") + 1).toLowerCase();
    }
}
