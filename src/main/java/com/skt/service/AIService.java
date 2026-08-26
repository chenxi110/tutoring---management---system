package com.skt.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;

@Service
public class AIService {

    // 密钥来源：本地开发读取 application-local.yml；生产环境通过服务器环境变量 AI_API_KEY 注入。
    // 严禁在源码/Jar 中硬编码明文密钥。当前对接 Agnes-AI（OpenAI 兼容 /v1/chat/completions）。

    @Autowired
    private JdbcTemplate jdbc;

    @Value("${ai.base-url:https://apihub.agnes-ai.com/v1}")
    private String baseUrl;

    @Value("${ai.api-key:}")
    private String apiKey;

    @Value("${ai.model:agnes-ai}")
    private String model;

    @Value("${ai.system-prompt:你是一个专业的教学管理助手，帮助教师和家长解决教学相关问题。}")
    private String systemPrompt;

    @Value("${ai.timeout:60}")
    private int timeoutSeconds;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    public Map<String, Object> chat(Long userId, String sessionId, String prompt, List<Map<String, Object>> history) {
        Map<String, Object> result = new HashMap<>();

        if (prompt == null || prompt.trim().isEmpty()) {
            result.put("code", 400);
            result.put("error", "提问内容不能为空");
            return result;
        }

        if (apiKey == null || apiKey.trim().isEmpty()) {
            result.put("code", 503);
            result.put("error", "AI服务未配置，请联系管理员配置API Key");
            return result;
        }

        // Build messages array for LLM API
        List<Map<String, String>> messages = new ArrayList<>();
        Map<String, String> sysMsg = new HashMap<>();
        sysMsg.put("role", "system");
        sysMsg.put("content", systemPrompt);
        messages.add(sysMsg);

        // Add history context (last 10 turns)
        if (history != null) {
            int count = 0;
            for (Map<String, Object> h : history) {
                if (count >= 20) break;
                String role = String.valueOf(h.get("role"));
                String content = String.valueOf(h.get("content"));
                if ("user".equals(role) || "assistant".equals(role)) {
                    Map<String, String> m = new HashMap<>();
                    m.put("role", role);
                    m.put("content", content);
                    messages.add(m);
                    count++;
                }
            }
        }

        // Add current user message
        Map<String, String> userMsg = new HashMap<>();
        userMsg.put("role", "user");
        userMsg.put("content", prompt);
        messages.add(userMsg);

        // Save user message to DB
        String sid = sessionId != null ? sessionId : "sess_" + System.currentTimeMillis();
        try {
            jdbc.update("INSERT INTO ai_chat_history (user_id, session_id, role, content) VALUES (?,?,?,?)",
                    userId, sid, "user", prompt);
        } catch (Exception e) {
            // DB save failure should not block AI response
        }

        // Call LLM API
        try {
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", model);
            requestBody.put("messages", messages);
            requestBody.put("stream", false);
            requestBody.put("max_tokens", 2000);
            requestBody.put("temperature", 0.7);

            String jsonBody = buildJson(requestBody);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/chat/completions"))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                String errBody = response.body();
                String errMsg = extractErrorMessage(errBody);
                // Handle common errors
                if (response.statusCode() == 401) {
                    errMsg = "AI服务认证失败，请检查API Key配置";
                } else if (response.statusCode() == 402) {
                    errMsg = "AI额度不足，请充值后重试";
                } else if (response.statusCode() == 429) {
                    errMsg = "AI调用频次超限或额度不足，请稍后重试";
                } else if (response.statusCode() == 503) {
                    errMsg = "AI服务暂时不可用，请稍后重试";
                }
                result.put("code", 502);
                result.put("error", errMsg);
                return result;
            }

            // Parse LLM response
            String aiContent = extractAiContent(response.body());
            if (aiContent == null || aiContent.isEmpty()) {
                result.put("code", 502);
                result.put("error", "AI返回内容为空");
                return result;
            }

            // Save assistant response to DB
            try {
                jdbc.update("INSERT INTO ai_chat_history (user_id, session_id, role, content) VALUES (?,?,?,?)",
                        userId, sid, "assistant", aiContent);
            } catch (Exception e) {
                // ignore
            }

            result.put("code", 200);
            result.put("success", true);
            result.put("response", aiContent);
            result.put("sessionId", sid);
            return result;

        } catch (java.net.http.HttpTimeoutException e) {
            result.put("code", 504);
            result.put("error", "AI服务响应超时，请稍后重试");
            return result;
        } catch (java.net.ConnectException e) {
            result.put("code", 503);
            result.put("error", "无法连接AI服务，请检查网络或服务状态");
            return result;
        } catch (Exception e) {
            result.put("code", 500);
            result.put("error", "AI服务调用异常: " + e.getMessage());
            return result;
        }
    }

    public String getModel() {
        return model;
    }

    public boolean isConfigured() {
        return apiKey != null && !apiKey.trim().isEmpty();
    }

    /**
     * Agnes-AI 接口连通性测试（仅供 /api/system/ai-test 调用，不写入业务数据）。
     * 向 Agnes 发送一条 max_tokens=16 的极短请求，返回连通状态/延迟/错误类型，
     * 对超时、402 额度不足、401 认证失败、网络异常等均返回友好中文提示。
     */
    public Map<String, Object> testConnection() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("configured", isConfigured());
        result.put("model", model);
        result.put("baseUrl", baseUrl);

        if (!isConfigured()) {
            result.put("code", 503);
            result.put("success", false);
            result.put("error", "AI服务未配置API Key（本地请检查 application-local.yml，生产请设置环境变量 AI_API_KEY）");
            return result;
        }

        long start = System.currentTimeMillis();
        try {
            List<Map<String, String>> messages = new ArrayList<>();
            Map<String, String> m = new HashMap<>();
            m.put("role", "user");
            m.put("content", "你好");
            messages.add(m);

            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", model);
            requestBody.put("messages", messages);
            requestBody.put("stream", false);
            requestBody.put("max_tokens", 16);
            String jsonBody = buildJson(requestBody);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/chat/completions"))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            result.put("latencyMs", System.currentTimeMillis() - start);

            if (response.statusCode() == 200) {
                String content = extractAiContent(response.body());
                result.put("code", 200);
                result.put("success", content != null && !content.isEmpty());
                result.put("reply", content);
                return result;
            }

            String errMsg = extractErrorMessage(response.body());
            if (response.statusCode() == 401) {
                errMsg = "认证失败：Agnes-AI API Key 无效或已过期";
            } else if (response.statusCode() == 402) {
                errMsg = "额度不足：Agnes-AI 账户余额不足，请充值后重试";
            } else if (response.statusCode() == 429) {
                errMsg = "请求超限：Agnes-AI 调用频次或额度超限，请稍后重试";
            } else if (response.statusCode() == 503) {
                errMsg = "服务不可用：Agnes-AI 服务暂时不可用，请稍后重试";
            }
            result.put("code", 502);
            result.put("success", false);
            result.put("httpStatus", response.statusCode());
            result.put("error", errMsg);
            return result;

        } catch (java.net.http.HttpTimeoutException e) {
            result.put("code", 504);
            result.put("success", false);
            result.put("error", "连接超时：Agnes-AI 服务响应超时，请检查网络或 timeout 配置");
            return result;
        } catch (java.net.ConnectException e) {
            result.put("code", 503);
            result.put("success", false);
            result.put("error", "网络异常：无法连接 Agnes-AI 服务，请检查网络或 Base URL 配置");
            return result;
        } catch (Exception e) {
            result.put("code", 500);
            result.put("success", false);
            result.put("error", "调用异常: " + e.getMessage());
            return result;
        }
    }

    public int getRecentChatCount(Long userId) {
        try {
            Integer cnt = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ai_chat_history WHERE user_id = ? AND created_at > DATE_SUB(NOW(), INTERVAL 1 HOUR)",
                Integer.class, userId);
            return cnt != null ? cnt : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    public List<Map<String, Object>> getHistory(Long userId) {
        try {
            return jdbc.queryForList(
                    "SELECT id, session_id, role, content, created_at FROM ai_chat_history WHERE user_id = ? ORDER BY created_at ASC LIMIT 100",
                    userId);
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    public Map<String, Object> clearHistory(Long userId) {
        Map<String, Object> result = new HashMap<>();
        try {
            jdbc.update("DELETE FROM ai_chat_history WHERE user_id = ?", userId);
            result.put("code", 200);
            result.put("success", true);
        } catch (Exception e) {
            result.put("code", 500);
            result.put("error", "清空对话记录失败");
        }
        return result;
    }

    // Simple JSON builder (avoids adding Jackson dependency)
    private String buildJson(Map<String, Object> map) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            if (!first) sb.append(",");
            first = false;
            sb.append("\"").append(entry.getKey()).append("\":");
            sb.append(toJsonValue(entry.getValue()));
        }
        sb.append("}");
        return sb.toString();
    }

    private String toJsonValue(Object value) {
        if (value == null) return "null";
        if (value instanceof String) return "\"" + escapeJson((String) value) + "\"";
        if (value instanceof Boolean) return value.toString();
        if (value instanceof Number) return value.toString();
        if (value instanceof List) {
            StringBuilder sb = new StringBuilder("[");
            boolean first = true;
            for (Object item : (List<?>) value) {
                if (!first) sb.append(",");
                first = false;
                sb.append(toJsonValue(item));
            }
            sb.append("]");
            return sb.toString();
        }
        if (value instanceof Map) {
            return buildJson((Map<String, Object>) value);
        }
        return "\"" + escapeJson(value.toString()) + "\"";
    }

    private String escapeJson(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private String extractAiContent(String responseBody) {
        try {
            // Simple extraction: find "content":"..." in choices[0].message.content
            int choicesIdx = responseBody.indexOf("\"choices\"");
            if (choicesIdx < 0) return null;
            int msgIdx = responseBody.indexOf("\"message\"", choicesIdx);
            if (msgIdx < 0) return null;
            int contentIdx = responseBody.indexOf("\"content\"", msgIdx);
            if (contentIdx < 0) return null;
            int colonIdx = responseBody.indexOf(":", contentIdx);
            if (colonIdx < 0) return null;
            int quoteStart = responseBody.indexOf("\"", colonIdx);
            if (quoteStart < 0) return null;
            // Find the closing quote (handle escaped quotes)
            int i = quoteStart + 1;
            StringBuilder content = new StringBuilder();
            while (i < responseBody.length()) {
                char c = responseBody.charAt(i);
                if (c == '\\' && i + 1 < responseBody.length()) {
                    char next = responseBody.charAt(i + 1);
                    if (next == 'n') content.append('\n');
                    else if (next == 'r') content.append('\r');
                    else if (next == 't') content.append('\t');
                    else if (next == '"') content.append('"');
                    else if (next == '\\') content.append('\\');
                    else content.append(next);
                    i += 2;
                } else if (c == '"') {
                    break;
                } else {
                    content.append(c);
                    i++;
                }
            }
            return content.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private String extractErrorMessage(String body) {
        if (body == null || body.isEmpty()) return "AI服务返回错误";
        try {
            int msgIdx = body.indexOf("\"message\"");
            if (msgIdx >= 0) {
                int colonIdx = body.indexOf(":", msgIdx);
                int quoteStart = body.indexOf("\"", colonIdx);
                if (quoteStart >= 0) {
                    int quoteEnd = body.indexOf("\"", quoteStart + 1);
                    if (quoteEnd > quoteStart) {
                        return body.substring(quoteStart + 1, quoteEnd);
                    }
                }
            }
        } catch (Exception e) {}
        return "AI服务返回错误";
    }
}
