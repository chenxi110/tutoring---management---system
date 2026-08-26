package com.skt.controller;

import com.skt.security.RoleAccess;
import com.skt.service.LeaveRequestService;
import com.skt.service.OperationLogService;
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
public class LeaveRequestController {

    @Autowired
    private LeaveRequestService leaveService;
    @Autowired
    private OperationLogService operationLogService;

    @PostMapping("/leave-requests")
    public Map<String, Object> create(@RequestBody Map<String, Object> body, HttpServletRequest req) {
        if (!RoleAccess.isParent(req)) {
            Map<String, Object> r = new HashMap<>();
            r.put("code", 403);
            r.put("msg", "仅家长账号可提交请假申请");
            return r;
        }
        Long parentId = (Long) req.getAttribute("userId");
        String parentName = (String) req.getAttribute("displayName");
        Long studentId = body.get("studentId") != null ? ((Number) body.get("studentId")).longValue() : null;
        String studentName = (String) body.get("studentName");
        Long classId = body.get("classId") != null ? ((Number) body.get("classId")).longValue() : null;
        String className = (String) body.get("className");
        String leaveDate = (String) body.get("leaveDate");
        String leaveType = (String) body.get("leaveType");
        String reason = (String) body.get("reason");
        Long id = leaveService.create(studentId, studentName, classId, className, parentId, parentName, leaveDate, leaveType, reason);
        Map<String, Object> r = new HashMap<>();
        r.put("code", 200);
        r.put("id", id);
        r.put("msg", "请假申请已提交，等待教师审批");
        return r;
    }

    @GetMapping("/leave-requests")
    public Map<String, Object> list(
            @RequestParam(required = false) Long classId,
            @RequestParam(required = false) String status,
            HttpServletRequest req) {
        Map<String, Object> r = new HashMap<>();
        if (RoleAccess.isParent(req)) {
            Long parentId = (Long) req.getAttribute("userId");
            r.put("code", 200);
            r.put("data", leaveService.listForParent(parentId));
            return r;
        }
        if (!RoleAccess.isTeacher(req)) {
            r.put("code", 403);
            r.put("msg", "无权限");
            return r;
        }
        Long teacherId = (Long) req.getAttribute("userId");
        String role = (String) req.getAttribute("role");
        r.put("code", 200);
        r.put("data", leaveService.listForTeacher(classId, status, teacherId, role));
        return r;
    }

    // 导出请假记录Excel
    @GetMapping("/leave-requests/export")
    public void exportLeaveRequests(@RequestParam(required = false) Long classId,
                                    @RequestParam(required = false) String status,
                                    HttpServletRequest req, HttpServletResponse response) {
        if (!RoleAccess.isTeacher(req)) {
            response.setStatus(403);
            return;
        }
        Long teacherId = (Long) req.getAttribute("userId");
        String role = (String) req.getAttribute("role");
        List<Map<String, Object>> list = leaveService.listForTeacher(classId, status, teacherId, role);
        String[] headers = {"学生姓名", "班级ID", "请假类型", "开始日期", "结束日期", "请假原因", "状态", "审批意见", "申请时间"};
        String[] keys = {"student_name", "class_id", "leave_type", "start_date", "end_date", "reason", "status", "comment", "created_at"};
        byte[] excelData = ExcelExportUtil.export(headers, keys, list, "请假记录");
        try {
            String fileName = URLEncoder.encode("请假记录.xlsx", StandardCharsets.UTF_8);
            response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
            response.setHeader("Content-Disposition", "attachment; filename=" + fileName);
            response.setContentLength(excelData.length);
            response.getOutputStream().write(excelData);
            response.getOutputStream().flush();
        } catch (Exception e) {
            throw new RuntimeException("导出失败: " + e.getMessage(), e);
        }
    }

    @PostMapping("/leave-requests/{id}/approve")
    public Map<String, Object> approve(@PathVariable Long id, @RequestBody Map<String, Object> body, HttpServletRequest req) {
        if (!RoleAccess.isTeacher(req)) {
            Map<String, Object> r = new HashMap<>();
            r.put("code", 403);
            r.put("msg", "仅教师/管理员可审批请假");
            return r;
        }
        Long approverId = (Long) req.getAttribute("userId");
        String approverName = (String) req.getAttribute("displayName");
        String role = (String) req.getAttribute("role");
        String ip = req.getRemoteAddr();
        boolean approved = body.get("approved") != null && (Boolean) body.get("approved");
        String comment = (String) body.get("comment");
        Map<String, Object> result = leaveService.approve(id, approverId, approverName, approved, comment);
        operationLogService.log(approverId, approverName, role,
            approved ? "请假审批通过" : "请假审批拒绝",
            "请假ID:" + id + ",审批结果:" + (approved ? "通过" : "拒绝") + ",备注:" + (comment != null ? comment : ""),
            ip);
        return result;
    }
}
