package com.tongyongfanyi.translate;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.tongyongfanyi.config.ModConfig;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * 本地模型翻译：调用本机 Ollama 的 /api/chat 原生接口。
 * 保持模型常驻（keep_alive）、限制上下文与输出长度，速度和显存占用都优于 OpenAI 兼容接口。
 * 翻译完全在本机进行，不上传任何内容。
 */
public class OllamaNativeTranslator {
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final String baseUrl;
    private final String model;
    /** 单次请求超时(毫秒)，来自配置 */
    private final int timeoutMs;

    public OllamaNativeTranslator(String baseUrl, String model, int timeoutMs) {
        this.baseUrl = baseUrl == null || baseUrl.isBlank() ? "http://127.0.0.1:11434" : baseUrl;
        this.model = model == null || model.isBlank() ? "qwen2.5:0.5b" : model;
        this.timeoutMs = clampTimeout(timeoutMs);
    }

    /** 把超时毫秒数收敛到 [1000, 60000]，防止配置写错导致请求永久挂起 */
    private static int clampTimeout(int timeoutMs) {
        return Math.max(1000, Math.min(60000, timeoutMs));
    }

    public String translate(String text, String sourceLang, String targetLang) throws Exception {
        String endpoint = baseUrl + (baseUrl.endsWith("/") ? "" : "/") + "api/chat";

        JsonObject payload = new JsonObject();
        payload.addProperty("model", model);
        payload.addProperty("stream", false);
        // 模型常驻内存 60 分钟，避免玩久了闲置后要重新加载
        payload.addProperty("keep_alive", "60m");

        JsonArray messages = new JsonArray();
        JsonObject sys = new JsonObject();
        sys.addProperty("role", "system");
        sys.addProperty("content", "你是一个专业翻译。把用户输入翻译为" + targetLang + "。"
                + "只输出翻译结果，不要解释、不要道歉、不要提问。"
                + "如果输入是人名、ID、网址或无法翻译的内容，直接原样输出。");
        messages.add(sys);
        JsonObject user = new JsonObject();
        user.addProperty("role", "user");
        user.addProperty("content", text);
        messages.add(user);
        payload.add("messages", messages);

        JsonObject options = new JsonObject();
        options.addProperty("temperature", 0.0);
        options.addProperty("num_predict", 256); // 限制输出长度，翻译短文本足够
        options.addProperty("num_ctx", 4096);    // 缩小上下文，减少显存、加快处理
        payload.add("options", options);

        HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofMillis(timeoutMs))
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString(), StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new RuntimeException("本地模型返回 " + response.statusCode() + ": " + response.body());
        }

        JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
        String result = root.getAsJsonObject("message").get("content").getAsString();
        return result == null || result.isBlank() ? text : result.trim();
    }

    public static OllamaNativeTranslator create(ModConfig config) {
        ModConfig.LocalModelConfig lm = config.localModel;
        return new OllamaNativeTranslator(lm.url, lm.model, config.timeoutMs);
    }
}
