package com.skt.controller;

import com.skt.security.RoleAccess;
import com.skt.service.DataPortService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import java.util.*;

@RestController
@RequestMapping("/api")
public class DataPortController {

    @Autowired
    private DataPortService dataPortService;

    @GetMapping("/export")
    public Map<String, Object> export(HttpServletRequest req) {
        if (!RoleAccess.isTeacher(req)) {
            return RoleAccess.forbidTeacherOnly("仅教师账号可导出数据");
        }
        Map<String, Object> data = dataPortService.exportAll();
        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("data", data);
        return result;
    }

    @PostMapping("/import")
    public Map<String, Object> importData(@RequestBody Map<String, Object> body, HttpServletRequest req) {
        if (!RoleAccess.isTeacher(req)) {
            return RoleAccess.forbidTeacherOnly("仅教师账号可导入数据");
        }
        Object dataObj = body.get("data");
        int count = 0;
        if (dataObj instanceof Map) {
            count = dataPortService.importData((Map<String, Object>) dataObj);
        }
        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("count", count);
        return result;
    }
}
