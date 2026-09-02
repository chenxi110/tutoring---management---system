package com.skt.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 课堂文件互传服务
 * - 教师下发课件：全班可见可下载
 * - 学生提交作业：绑定studentId，教师可见全部，学生仅见自己提交
 * - 下载鉴权：校验角色+班级归属，禁止越权
 */
@Service
public class CourseFileService {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MessageService messageService;

    @Value("${file.uploadBasePath:./upload/courseFile/}")
    private String uploadBasePath;

    @Value("${file.maxSingleFileSize:52428800}")
    private long maxSingleFileSize;

    @Value("${file.allowSuffix:xlsx,xls,docx,doc,pptx,png,jpg,jpeg}")
    private String allowSuffix;

    /** 危险后缀黑名单，双重过滤 */
    private static final Set<String> DANGEROUS_EXTENSIONS = Set.of(
        "exe", "bat", "cmd", "sh", "msi", "com", "scr", "vbs", "js", "jar", "war",
        "ps1", "reg", "dll", "so", "apk", "ipa", "html", "htm", "svg"
    );

    // ==================== 教师下发课件 ====================

    public Map<String, Object> uploadTeacherFile(MultipartFile file, Long classId, Long teacherId, String teacherName) {
        Map<String, Object> result = new HashMap<>();
        String validation = validateFile(file);
        if (validation != null) {
            result.put("code", 400);
            result.put("error", validation);
            return result;
        }
        // 校验教师是否属于该班级
        Long classTeacherId = getClassTeacherId(classId);
        if (classTeacherId == null || !classTeacherId.equals(teacherId)) {
            result.put("code", 403);
            result.put("error", "无权向该班级下发课件");
            return result;
        }
        try {
            String savePath = storeFile(file, classId, true);
            String fileSuffix = getFileExtension(file.getOriginalFilename());
            jdbc.update(
                "INSERT INTO course_file (class_id, teacher_id, file_name, save_path, file_suffix, file_size, is_teacher_upload) VALUES (?,?,?,?,?,?,1)",
                classId, teacherId, file.getOriginalFilename(), savePath, fileSuffix, file.getSize()
            );
            Long fileId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);

            // 联动消息通知：给该班级全部学生家长推送通知
            String noticeTitle = "课堂课件通知";
            String noticeContent = "教师下发课堂课件，请前往上课模式查看下载";
            messageService.sendMessage(
                teacherId, teacherName != null ? teacherName : "教师", "teacher",
                noticeTitle, noticeContent, null, classId, null, "notice"
            );

            result.put("code", 200);
            result.put("id", fileId);
            result.put("msg", "课件上传成功");
        } catch (IOException e) {
            result.put("code", 500);
            result.put("error", "文件保存失败：" + e.getMessage());
        }
        return result;
    }

    // ==================== 学生提交作业 ====================

    public Map<String, Object> uploadStudentWork(MultipartFile file, Long classId, Long studentId, Long userId, String userName, String role) {
        Map<String, Object> result = new HashMap<>();
        String validation = validateFile(file);
        if (validation != null) {
            result.put("code", 400);
            result.put("error", validation);
            return result;
        }
        // 校验：家长可代孩子提交（parent_user_id），学生只能本人提交（user_id 严格隔离）
        Integer ownCount;
        if ("student".equalsIgnoreCase(role)) {
            ownCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM students WHERE id=? AND user_id=? AND class_id=? AND (is_deleted IS NULL OR is_deleted=0)",
                Integer.class, studentId, userId, classId
            );
        } else {
            ownCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM students WHERE id=? AND parent_user_id=? AND class_id=? AND (is_deleted IS NULL OR is_deleted=0)",
                Integer.class, studentId, userId, classId
            );
        }
        if (ownCount == null || ownCount == 0) {
            result.put("code", 403);
            result.put("error", "无权为该学生提交作业");
            return result;
        }
        String studentName = jdbc.queryForObject(
            "SELECT name FROM students WHERE id=?", String.class, studentId
        );
        if (studentName == null) studentName = "";

        // 获取该班级授课教师ID（teacher_id NOT NULL）
        Long teacherId = getClassTeacherId(classId);
        if (teacherId == null) {
            result.put("code", 500);
            result.put("error", "该班级未分配授课教师");
            return result;
        }

        try {
            String savePath = storeFile(file, classId, false);
            String fileSuffix = getFileExtension(file.getOriginalFilename());
            jdbc.update(
                "INSERT INTO course_file (class_id, teacher_id, student_id, file_name, save_path, file_suffix, file_size, is_teacher_upload) VALUES (?,?,?,?,?,?,?,0)",
                classId, teacherId, studentId, file.getOriginalFilename(), savePath, fileSuffix, file.getSize()
            );
            Long fileId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);

            // 通知教师：有学生提交了作业（家长代交以家长身份，学生本人提交以学生身份）
            String fromRole = "student".equalsIgnoreCase(role) ? "student" : "parent";
            String fromName = userName != null ? userName : (fromRole.equals("student") ? studentName : "家长");
            messageService.sendMessage(
                userId, fromName, fromRole,
                "学生提交作业", studentName + " 提交了作业：" + file.getOriginalFilename(),
                studentId, classId, teacherId, "notice"
            );

            result.put("code", 200);
            result.put("id", fileId);
            result.put("msg", "作业提交成功");
        } catch (IOException e) {
            result.put("code", 500);
            result.put("error", "文件保存失败：" + e.getMessage());
        }
        return result;
    }

    // ==================== 文件列表 ====================

    /**
     * 根据班级id获取文件列表
     * - 教师：可见该班全部文件（教师课件+全部学生作业）
     * - 家长：可见教师课件 + 自己孩子提交的作业（看不到其他同学）
     */
    public List<Map<String, Object>> listByClassId(Long classId, Long userId, String role) {
        if ("student".equalsIgnoreCase(role)) {
            // 学生：可见教师课件 + 本人提交的作业（按 students.user_id 隔离，看不到其他同学）
            return jdbc.queryForList(
                "SELECT cf.*, " +
                "CASE WHEN cf.is_teacher_upload=1 THEN u.display_name ELSE s.name END as uploader_name, " +
                "s.name as student_name " +
                "FROM course_file cf " +
                "LEFT JOIN users u ON u.id=cf.teacher_id " +
                "LEFT JOIN students s ON s.id=cf.student_id " +
                "WHERE cf.class_id=? AND (" +
                "  cf.is_teacher_upload=1 " +
                "  OR cf.student_id IN (SELECT id FROM students WHERE user_id=? AND class_id=? AND (is_deleted IS NULL OR is_deleted=0))" +
                ") ORDER BY cf.upload_time DESC",
                classId, userId, classId
            );
        }
        if ("parent".equalsIgnoreCase(role)) {
            return jdbc.queryForList(
                "SELECT cf.*, " +
                "CASE WHEN cf.is_teacher_upload=1 THEN u.display_name ELSE s.name END as uploader_name, " +
                "s.name as student_name " +
                "FROM course_file cf " +
                "LEFT JOIN users u ON u.id=cf.teacher_id " +
                "LEFT JOIN students s ON s.id=cf.student_id " +
                "WHERE cf.class_id=? AND (" +
                "  cf.is_teacher_upload=1 " +
                "  OR cf.student_id IN (SELECT id FROM students WHERE parent_user_id=? AND class_id=? AND (is_deleted IS NULL OR is_deleted=0))" +
                ") ORDER BY cf.upload_time DESC",
                classId, userId, classId
            );
        }
        // 教师视角
        return jdbc.queryForList(
            "SELECT cf.*, " +
            "CASE WHEN cf.is_teacher_upload=1 THEN u.display_name ELSE s.name END as uploader_name, " +
            "s.name as student_name " +
            "FROM course_file cf " +
            "LEFT JOIN users u ON u.id=cf.teacher_id " +
            "LEFT JOIN students s ON s.id=cf.student_id " +
            "WHERE cf.class_id=? ORDER BY cf.upload_time DESC",
            classId
        );
    }

    // ==================== 文件下载鉴权 ====================

    /**
     * 下载前鉴权，返回文件记录或错误信息
     * - 教师：必须是该班授课教师
     * - 家长：必须有孩子在该班；学生作业只能下载自己孩子的
     */
    public Map<String, Object> getFileForDownload(Long fileId, Long userId, String role) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM course_file WHERE id=?", fileId);
        if (rows.isEmpty()) {
            Map<String, Object> err = new HashMap<>();
            err.put("code", 404);
            err.put("error", "文件不存在");
            return err;
        }
        Map<String, Object> fileRecord = rows.get(0);
        Long classId = ((Number) fileRecord.get("class_id")).longValue();
        boolean isTeacherUpload = fileRecord.get("is_teacher_upload") != null
            && ((Number) fileRecord.get("is_teacher_upload")).intValue() == 1;

        if ("student".equalsIgnoreCase(role)) {
            // 学生：本人必须在班级；作业只能下载本人提交的
            List<Map<String, Object>> self = jdbc.queryForList(
                "SELECT id FROM students WHERE user_id=? AND class_id=? AND (is_deleted IS NULL OR is_deleted=0)",
                userId, classId
            );
            if (self.isEmpty()) {
                return forbidden("无权下载此文件：您不在该班级");
            }
            if (!isTeacherUpload) {
                Long fileStudentId = fileRecord.get("student_id") != null
                    ? ((Number) fileRecord.get("student_id")).longValue() : null;
                long myStudentId = ((Number) self.get(0).get("id")).longValue();
                if (fileStudentId == null || fileStudentId != myStudentId) {
                    return forbidden("只能下载自己提交的作业文件");
                }
            }
        } else if ("parent".equalsIgnoreCase(role)) {
            // 家长：必须有孩子在该班级
            List<Map<String, Object>> children = jdbc.queryForList(
                "SELECT id FROM students WHERE parent_user_id=? AND class_id=? AND (is_deleted IS NULL OR is_deleted=0)",
                userId, classId
            );
            if (children.isEmpty()) {
                return forbidden("无权下载此文件：您的孩子不在该班级");
            }
            // 学生作业：只能下载自己孩子提交的
            if (!isTeacherUpload) {
                Long fileStudentId = fileRecord.get("student_id") != null
                    ? ((Number) fileRecord.get("student_id")).longValue() : null;
                boolean isOwn = children.stream()
                    .anyMatch(c -> ((Number) c.get("id")).longValue() == (fileStudentId != null ? fileStudentId : -1));
                if (!isOwn) {
                    return forbidden("只能下载自己孩子提交的作业文件");
                }
            }
        } else {
            // 教师：必须是该班授课教师
            Long classTeacherId = getClassTeacherId(classId);
            if (classTeacherId == null || !classTeacherId.equals(userId)) {
                return forbidden("无权下载此文件：您不是该班级授课教师");
            }
        }
        return fileRecord;
    }

    // ==================== 私有工具方法 ====================

    private Map<String, Object> forbidden(String msg) {
        Map<String, Object> err = new HashMap<>();
        err.put("code", 403);
        err.put("error", msg);
        return err;
    }

    private Long getClassTeacherId(Long classId) {
        try {
            return jdbc.queryForObject("SELECT teacher_id FROM classes WHERE id=?", Long.class, classId);
        } catch (Exception e) {
            return null;
        }
    }

    /** 文件校验：非空、大小、后缀白名单、危险后缀黑名单 */
    private String validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return "文件不能为空";
        }
        if (file.getSize() > maxSingleFileSize) {
            return "文件大小不能超过" + (maxSingleFileSize / 1024 / 1024) + "MB";
        }
        String originalName = file.getOriginalFilename();
        if (originalName == null || originalName.isEmpty()) {
            return "文件名不能为空";
        }
        String ext = getFileExtension(originalName).toLowerCase();
        if (ext.isEmpty()) {
            return "文件缺少后缀名";
        }
        // 危险后缀双重拦截
        if (DANGEROUS_EXTENSIONS.contains(ext)) {
            return "不允许上传可执行/危险文件类型：" + ext;
        }
        // 白名单校验
        Set<String> allowed = new HashSet<>(Arrays.asList(allowSuffix.split(",")));
        if (!allowed.contains(ext)) {
            return "不支持的文件格式：" + ext + "，支持的格式：" + allowSuffix;
        }
        return null;
    }

    /** 存储文件：自动创建目录、自动重命名防覆盖，使用绝对路径避免Tomcat相对路径问题 */
    private String storeFile(MultipartFile file, Long classId, boolean isTeacher) throws IOException {
        File baseDir = new File(uploadBasePath).getAbsoluteFile();
        if (!baseDir.exists()) {
            baseDir.mkdirs();
        }
        File classDir = new File(baseDir, "class_" + classId);
        if (!classDir.exists()) {
            classDir.mkdirs();
        }
        String originalName = file.getOriginalFilename();
        String ext = getFileExtension(originalName);
        String prefix = isTeacher ? "teacher_" : "student_";
        // 时间戳+随机数 自动重命名，防止同名覆盖
        String storedName = prefix + System.currentTimeMillis() + "_" + ThreadLocalRandom.current().nextInt(9999) + "." + ext;
        File dest = new File(classDir, storedName).getAbsoluteFile();
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
