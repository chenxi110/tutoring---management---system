package com.skt.controller;

import com.skt.security.RoleAccess;
import com.skt.service.ShareTokenService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import java.util.*;

@RestController
@RequestMapping("/api")
public class ShareTokenController {

    @Autowired
    private ShareTokenService shareTokenService;

    @PostMapping("/share/tokens")
    public Map<String, Object> createToken(@RequestBody Map<String, Object> body, HttpServletRequest req) {
        if (RoleAccess.isParent(req)) {
            return RoleAccess.forbidParentWrite("家长账号无权生成分享链接");
        }
        Long studentId = body.get("studentId") != null ? ((Number) body.get("studentId")).longValue() : null;
        Boolean isPermanent = body.get("isPermanent") != null ? Boolean.valueOf(String.valueOf(body.get("isPermanent"))) : false;
        Integer validDays = body.get("validDays") != null ? ((Number) body.get("validDays")).intValue() : null;
        Long createdBy = (Long) req.getAttribute("userId");
        return shareTokenService.createToken(studentId, isPermanent, validDays, createdBy);
    }

    @GetMapping("/share/tokens")
    public Map<String, Object> listTokens(@RequestParam Long studentId, HttpServletRequest req) {
        if (RoleAccess.isParent(req)) {
            return RoleAccess.forbidParentWrite("家长账号无权查看分享链接列表");
        }
        List<Map<String, Object>> list = shareTokenService.listTokensByStudent(studentId);
        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("data", list);
        return result;
    }

    @DeleteMapping("/share/tokens/{id}")
    public Map<String, Object> deleteToken(@PathVariable Long id, HttpServletRequest req) {
        if (RoleAccess.isParent(req)) {
            return RoleAccess.forbidParentWrite("家长账号无权删除分享链接");
        }
        return shareTokenService.deleteToken(id);
    }

    @GetMapping("/share/validate")
    public Map<String, Object> validateToken(@RequestParam String token) {
        return shareTokenService.validateToken(token);
    }
}
