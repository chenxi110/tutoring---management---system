package com.skt.controller;

import com.skt.security.RoleAccess;
import com.skt.service.OperationLogService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import java.util.*;

@RestController
@RequestMapping("/api")
public class OperationLogController {

    @Autowired
    private OperationLogService logService;

    @GetMapping("/operation-logs")
    public Map<String, Object> list(
            @RequestParam(required = false) String operation,
            @RequestParam(required = false) Long userId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int size,
            HttpServletRequest req) {
        Map<String, Object> result = new HashMap<>();
        if (!RoleAccess.isTeacher(req)) {
            result.put("code", 403);
            result.put("msg", "无权限：仅教师/管理员可查看操作日志");
            return result;
        }
        if (page < 1) page = 1;
        if (size < 1 || size > 200) size = 50;
        List<Map<String, Object>> list = logService.list(operation, userId, page, size);
        int total = logService.count(operation, userId);
        result.put("code", 200);
        result.put("data", list);
        result.put("total", total);
        result.put("page", page);
        result.put("size", size);
        return result;
    }
}
