package com.skt.controller;

import com.skt.security.RoleAccess;
import com.skt.service.AIService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * 系统级调试接口（仅本地开发调试与上线前自检使用）。
 * /api/system/ai-test：验证 Agnes-AI 接口连通性，不涉及任何教学业务数据，不改动原有业务接口。
 * 上线部署后建议关闭或限制访问，密钥始终走服务端配置，不暴露给前端。
 */
@RestController
@RequestMapping("/api/system")
public class SystemController {

    @Autowired
    private AIService aiService;

    /**
     * AI 接口连通性测试：向 Agnes-AI 发送一条极短请求，返回连通状态/延迟/错误类型。
     * 仅教师账号可调用（JwtAuthFilter 已校验登录态，此处再做角色校验）。
     */
    @GetMapping("/ai-test")
    public Map<String, Object> aiTest(HttpServletRequest req) {
        Map<String, Object> guard = new HashMap<>();
        if (!RoleAccess.isTeacher(req)) {
            guard.put("code", 403);
            guard.put("success", false);
            guard.put("error", "此接口仅限教师账号调试使用");
            return guard;
        }
        // testConnection() 内部已处理超时 / 402 额度不足 / 401 认证失败 / 网络异常，返回友好中文提示
        return aiService.testConnection();
    }
}
