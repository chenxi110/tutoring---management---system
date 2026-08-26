package com.skt.controller;

import com.skt.security.RoleAccess;
import com.skt.service.LeaveRequestService;
import com.skt.service.OperationLogService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
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
        r.put("code", 200);
        r.put("data", leaveService.listForTeacher(classId, status));
        return r;
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
