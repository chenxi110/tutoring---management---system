package com.skt.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@Service
public class AIService {

    private static final Logger log = LoggerFactory.getLogger(AIService.class);

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

    private String provider = "doubao-free";
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    /**
     * 单次对话（无历史），供作业AI审阅等内部场景使用。
     * 成功返回大模型文本回复；未配置/失败返回 null（调用方自行降级）。
     */
    public String chatOnce(String systemPrompt, String userPrompt) {
        if (userPrompt == null || userPrompt.trim().isEmpty()) return null;
        refreshConfig();
        if (apiKey == null || apiKey.trim().isEmpty()) return null;
        try {
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", model);
            List<Map<String, String>> messages = new ArrayList<>();
            Map<String, String> sys = new HashMap<>();
            sys.put("role", "system");
            sys.put("content", systemPrompt != null && !systemPrompt.isEmpty() ? systemPrompt : this.systemPrompt);
            messages.add(sys);
            Map<String, String> usr = new HashMap<>();
            usr.put("role", "user");
            usr.put("content", userPrompt);
            messages.add(usr);
            requestBody.put("messages", messages);
            requestBody.put("stream", false);
            requestBody.put("max_tokens", 800);
            requestBody.put("temperature", 0.3);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/chat/completions"))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .timeout(Duration.ofSeconds(30))
                    .POST(HttpRequest.BodyPublishers.ofString(buildJson(requestBody)))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("AI审阅调用失败, httpStatus={}", response.statusCode());
                return null;
            }
            JsonNode root = objectMapper.readTree(response.body());
            String content = root.path("choices").path(0).path("message").path("content").asText(null);
            return (content == null || content.trim().isEmpty()) ? null : content.trim();
        } catch (Exception e) {
            log.warn("AI审阅异常: {}", e.getMessage());
            return null;
        }
    }

    public Map<String, Object> chat(Long userId, String sessionId, String prompt, List<Map<String, Object>> history) {
        refreshConfig();
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
        } catch (Exception e) { log.debug("AI对话记录保存失败（不影响AI响应）: {}", e.getMessage()); }

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
            } catch (Exception e) { log.debug("AI助手回复保存失败（不影响AI响应）: {}", e.getMessage()); }

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
        refreshConfig();
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

    // 运行时 AI 配置（数据库 ai_config 优先，未配置时使用 yml 默认）
    private void refreshConfig() {
        try {
            List<Map<String, Object>> list = jdbc.queryForList("SELECT * FROM ai_config LIMIT 1");
            if (!list.isEmpty()) {
                Map<String, Object> row = list.get(0);
                String pv = sv(row.get("provider")); if (!pv.isEmpty()) this.provider = pv;
                String m = sv(row.get("model")); if (!m.isEmpty()) this.model = m;
                String b = sv(row.get("base_url")); if (!b.isEmpty()) this.baseUrl = b;
                String k = sv(row.get("api_key")); if (!k.isEmpty()) this.apiKey = k;
            }
        } catch (Exception e) {
            log.debug("AI运行时配置读取失败(使用默认配置): {}", e.getMessage());
        }
    }

    private String sv(Object o) { return o == null ? "" : String.valueOf(o).trim(); }

    // 供前端 AI配置页读取（格式：data[0].provider/apiKey/model/baseUrl/configured）
    public Map<String, Object> getConfigForApi() {
        refreshConfig();
        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("provider", provider);
        cfg.put("apiKey", apiKey);
        cfg.put("model", model);
        cfg.put("baseUrl", baseUrl);
        cfg.put("configured", isConfigured());
        return cfg;
    }

    // 保存 AI 运行时配置（数据库 ai_config，保存后立即生效）
    public Map<String, Object> saveConfig(String provider, String apiKey, String model, String baseUrl) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            String p = provider != null && !provider.trim().isEmpty() ? provider.trim() : "doubao-free";
            String ak = apiKey, m = model, bu = baseUrl;
            // 免费渠道统一走已验证可用的免费通道（Agnes-2.5-flash）
            if ("doubao-free".equals(p) || "freegpt".equals(p) || "freechat".equals(p)) {
                ak = this.apiKey;
                m = "agnes-2.5-flash";
                bu = "https://apihub.agnes-ai.com/v1";
            }
            if (ak == null || ak.trim().isEmpty()) ak = this.apiKey;
            if (m == null || m.trim().isEmpty()) m = this.model;
            if (bu == null || bu.trim().isEmpty()) bu = this.baseUrl;
            Integer cnt = jdbc.queryForObject("SELECT COUNT(*) FROM ai_config", Integer.class);
            if (cnt != null && cnt > 0) {
                Long minId = jdbc.queryForObject("SELECT MIN(id) FROM ai_config", Long.class);
                jdbc.update("UPDATE ai_config SET provider=?, api_key=?, model=?, base_url=? WHERE id=?", p, ak, m, bu, minId);
            } else {
                jdbc.update("INSERT INTO ai_config (provider, api_key, model, base_url) VALUES (?,?,?,?)", p, ak, m, bu);
            }
            refreshConfig();
            result.put("code", 200);
            result.put("success", true);
            result.put("msg", "配置保存成功");
            return result;
        } catch (Exception e) {
            log.error("AI配置保存失败", e);
            result.put("code", 500);
            result.put("success", false);
            result.put("error", "配置保存失败: " + e.getMessage());
            return result;
        }
    }

    // 智能出题：调用 AI 生成测试题目
    public Map<String, Object> generateQuestions(String topic, int count, List<String> types) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (topic == null || topic.trim().isEmpty()) {
            result.put("code", 400); result.put("success", false); result.put("error", "请填写出题主题");
            return result;
        }
        refreshConfig();
        if (!isConfigured()) {
            result.put("code", 503); result.put("success", false);
            result.put("error", "AI服务未配置，请联系管理员配置API Key");
            return result;
        }
        int c = count <= 0 ? 5 : Math.min(count, 20);
        String typeStr = (types == null || types.isEmpty()) ? "单选题、判断题、填空题、问答题" : String.join("、", types);
        List<Map<String, String>> messages = new ArrayList<>();
        Map<String, String> sys = new HashMap<>();
        sys.put("role", "system");
        sys.put("content", "你是一位经验丰富的K12课外辅导出题专家，根据主题生成教学测试题目，题目严谨、难度适中、答案准确。");
        messages.add(sys);
        Map<String, String> user = new HashMap<>();
        user.put("role", "user");
        user.put("content", "请围绕《" + topic + "》生成" + c + "道" + typeStr + "。只返回JSON数组，每项格式：{\"type\":\"single|multiple|truefalse|fill|essay\",\"question\":\"题目内容\",\"options\":[\"A.选项1\",\"B.选项2\",\"C.选项3\",\"D.选项4\"](选择题必填),\"answer\":\"正确答案\"}。不要输出多余文字。");
        messages.add(user);
        try {
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", model);
            requestBody.put("messages", messages);
            requestBody.put("stream", false);
            requestBody.put("max_tokens", 3000);
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
                String errMsg = extractErrorMessage(response.body());
                if (response.statusCode() == 401) errMsg = "AI服务认证失败，请检查API Key配置";
                else if (response.statusCode() == 429) errMsg = "AI调用频率超限或额度不足，请稍后重试";
                else if (response.statusCode() == 503) errMsg = "AI服务暂时不可用，请稍后重试";
                result.put("code", 502); result.put("success", false); result.put("error", errMsg);
                return result;
            }
            String aiContent = extractAiContent(response.body());
            if (aiContent == null || aiContent.trim().isEmpty()) {
                result.put("code", 502); result.put("success", false); result.put("error", "AI返回内容为空");
                return result;
            }
            List<Map<String, Object>> questions = parseQuestionsFromAI(aiContent);
            if (questions.isEmpty()) {
                result.put("code", 502); result.put("success", false); result.put("error", "AI生成题目解析失败，请重试");
                return result;
            }
            result.put("code", 200); result.put("success", true);
            result.put("questions", questions);
            result.put("local", false);
            result.put("msg", "AI生成成功，共 " + questions.size() + " 道题");
            return result;
        } catch (java.net.http.HttpTimeoutException e) {
            result.put("code", 504); result.put("success", false); result.put("error", "AI服务响应超时，请稍后重试");
            return result;
        } catch (Exception e) {
            log.error("AI出题失败", e);
            result.put("code", 500); result.put("success", false); result.put("error", "AI出题异常: " + e.getMessage());
            return result;
        }
    }

    private List<Map<String, Object>> parseQuestionsFromAI(String content) {
        List<Map<String, Object>> out = new ArrayList<>();
        try {
            String c = content;
            // 去掉 markdown 代码块标记
            c = c.replaceAll("(?s)```[a-zA-Z]*", "").replaceAll("(?s)```", "");
            JsonNode arr = null;
            // 尝试1：整体直接解析为数组
            try { arr = objectMapper.readTree(c); } catch (Exception ignored) { }
            if (arr == null || !arr.isArray()) {
                // 尝试2：提取第一个 [ 到最后一个 ]
                int start = c.indexOf('[');
                int end = c.lastIndexOf(']');
                if (start < 0 || end < 0 || end <= start) return out;
                try { arr = objectMapper.readTree(c.substring(start, end + 1)); } catch (Exception ignored2) { }
            }
            if (arr == null || !arr.isArray()) return out;
            for (JsonNode node : arr) {
                if (node == null) continue;
                String type = node.has("type") ? node.get("type").asText("essay") : "essay";
                String question = node.has("question") ? node.get("question").asText("") : "";
                String answer = node.has("answer") ? node.get("answer").asText("") : "";
                if (question.trim().isEmpty()) continue;
                Map<String, Object> q = new LinkedHashMap<>();
                q.put("type", type);
                q.put("question", question);
                List<String> opts = new ArrayList<>();
                if (node.has("options") && node.get("options").isArray()) {
                    for (JsonNode o : node.get("options")) opts.add(o.asText(""));
                }
                q.put("options", opts);
                q.put("answer", answer);
                out.add(q);
            }
        } catch (Exception e) {
            log.debug("AI出题JSON解析失败: {}", e.getMessage());
        }
        return out;
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
        } catch (Exception e) { log.debug("解析AI错误响应失败: {}", e.getMessage()); }
        return "AI服务返回错误";
    }
}
