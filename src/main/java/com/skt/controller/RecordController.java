package com.skt.controller;

import com.skt.security.RoleAccess;
import com.skt.service.RecordService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import java.util.*;

@RestController
@RequestMapping("/api")
public class RecordController {

    @Autowired
    private RecordService recordService;

    @GetMapping("/records")
    public Map<String, Object> list(@RequestParam(required = false) Long classId,
                                    @RequestParam(required = false) String className,
                                    @RequestParam(required = false) String date,
                                    @RequestParam(required = false) Long semesterId,
                                    HttpServletRequest req) {
        if (RoleAccess.isParent(req)) {
            Long parentId = RoleAccess.getUserId(req);
            List<Map<String, Object>> list = recordService.listForParent(parentId);
            Map<String, Object> result = new HashMap<>();
            result.put("code", 200);
            result.put("data", list);
            return result;
        }
        List<Map<String, Object>> list = recordService.list(classId, className, date, semesterId);
        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("data", list);
        return result;
    }

    @PostMapping("/records")
    public Map<String, Object> create(@RequestBody Map<String, Object> body, HttpServletRequest req) {
        if (RoleAccess.isParent(req)) {
            return RoleAccess.forbidParentWrite("家长账号无权新增上课记录");
        }
        Long id = recordService.create(
            (String) body.get("date"),
            body.get("classId") != null ? ((Number) body.get("classId")).longValue() : null,
            (String) body.get("className"),
            (String) body.get("course"),
            body.get("sessions") != null ? ((Number) body.get("sessions")).intValue() : null,
            (String) body.get("type"),
            body.get("semesterId") != null ? ((Number) body.get("semesterId")).longValue() : null,
            (String) body.get("remark"),
            (List<Object>) body.get("absent"),
            (List<Object>) body.get("trialStudents")
        );
        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("id", id);
        return result;
    }

    @PutMapping("/records/{id}")
    public Map<String, Object> update(@PathVariable Long id, @RequestBody Map<String, Object> body, HttpServletRequest req) {
        if (RoleAccess.isParent(req)) {
            return RoleAccess.forbidParentWrite("家长账号无权修改上课记录");
        }
        recordService.update(id,
            (String) body.get("date"),
            body.get("classId") != null ? ((Number) body.get("classId")).longValue() : null,
            (String) body.get("className"),
            (String) body.get("course"),
            body.get("sessions") != null ? ((Number) body.get("sessions")).intValue() : null,
            (String) body.get("type"),
            body.get("semesterId") != null ? ((Number) body.get("semesterId")).longValue() : null,
            (String) body.get("remark"),
            (List<Object>) body.get("absent"),
            (List<Object>) body.get("trialStudents")
        );
        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        return result;
    }

    @DeleteMapping("/records/{id}")
    public Map<String, Object> delete(@PathVariable Long id, HttpServletRequest req) {
        if (RoleAccess.isParent(req)) {
            return RoleAccess.forbidParentWrite("家长账号无权删除上课记录");
        }
        recordService.delete(id);
        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        return result;
    }
}
