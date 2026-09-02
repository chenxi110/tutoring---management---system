package com.skt.controller;

import com.skt.security.RoleAccess;
import com.skt.service.CourseFileService;
import com.skt.service.OperationLogService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import jakarta.servlet.http.HttpServletRequest;
import java.io.File;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * 课堂文件互传控制器
 * 接口前缀：/api/course/file
 */
@RestController
@RequestMapping("/api/course/file")
public class CourseFileController {

    @Autowired
    private CourseFileService courseFileService;
    @Autowired
    private OperationLogService operationLogService;

    /**
     * 1. 教师下发课件
     * POST /api/course/file/uploadTeacherFile
     * 参数：file, classId
     */
    @PostMapping("/uploadTeacherFile")
    public Map<String, Object> uploadTeacherFile(
            @RequestParam("file") MultipartFile file,
            @RequestParam("classId") Long classId,
            HttpServletRequest req) {
        if (RoleAccess.isParent(req)) {
            return RoleAccess.forbidParentWrite("家长无权下发课件");
        }
        Long teacherId = RoleAccess.getUserId(req);
        String teacherName = (String) req.getAttribute("displayName");
        Map<String, Object> result = courseFileService.uploadTeacherFile(file, classId, teacherId, teacherName);
        if (result != null && "200".equals(String.valueOf(result.get("code")))) {
            String username = (String) req.getAttribute("username");
            String role = (String) req.getAttribute("role");
            String ip = req.getRemoteAddr();
            operationLogService.log(teacherId, username, role, "下发课堂课件",
                "文件:" + file.getOriginalFilename() + ",班级ID:" + classId + ",大小:" + file.getSize() + "字节", ip);
        }
        return result;
    }

    /**
     * 2. 学生提交作业
     * POST /api/course/file/uploadStudentWork
     * 参数：file, classId, studentId
     */
    @PostMapping("/uploadStudentWork")
    public Map<String, Object> uploadStudentWork(
            @RequestParam("file") MultipartFile file,
            @RequestParam("classId") Long classId,
            @RequestParam("studentId") Long studentId,
            HttpServletRequest req) {
        if (!RoleAccess.isParent(req) && !RoleAccess.isStudent(req)) {
            Map<String, Object> err = new HashMap<>();
            err.put("code", 403);
            err.put("error", "仅家长或学生本人可提交作业文件");
            return err;
        }
        Long userId = RoleAccess.getUserId(req);
        String userName = (String) req.getAttribute("displayName");
        String role = (String) req.getAttribute("role");
        return courseFileService.uploadStudentWork(file, classId, studentId, userId, userName, role);
    }

    /**
     * 3. 根据班级id获取该课堂全部文件列表
     * GET /api/course/file/listByClassId?classId=xxx
     * 教师：可见全部；家长：仅见教师课件+自己孩子作业
     */
    @GetMapping("/listByClassId")
    public Map<String, Object> listByClassId(@RequestParam Long classId, HttpServletRequest req) {
        Long userId = RoleAccess.getUserId(req);
        String role = (String) req.getAttribute("role");
        List<Map<String, Object>> list = courseFileService.listByClassId(classId, userId, role);
        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("data", list);
        return result;
    }

    /**
     * 4. 文件下载（必须鉴权，校验角色+班级归属，禁止越权）
     * GET /api/course/file/download/{fileId}
     * 支持 header Authorization 或 query token 鉴权
     */
    @GetMapping("/download/{fileId}")
    public ResponseEntity<Resource> downloadFile(@PathVariable Long fileId, HttpServletRequest req) {
        Long userId = RoleAccess.getUserId(req);
        String role = (String) req.getAttribute("role");
        if (userId == null) {
            return ResponseEntity.status(401).build();
        }
        Map<String, Object> fileRecord = courseFileService.getFileForDownload(fileId, userId, role);
        // 鉴权失败：返回403
        if (fileRecord.get("code") != null) {
            return ResponseEntity.status(403).build();
        }
        String savePath = (String) fileRecord.get("save_path");
        String fileName = (String) fileRecord.get("file_name");
        File file = new File(savePath);
        if (!file.exists()) {
            return ResponseEntity.status(404).build();
        }
        FileSystemResource resource = new FileSystemResource(file);
        String encodedName = URLEncoder.encode(fileName, StandardCharsets.UTF_8).replaceAll("\\+", "%20");
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encodedName)
                .body(resource);
    }
}
