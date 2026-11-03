package com.tongyongfanyi.translate;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Ollama 本地模型后端（/api/chat）。
 * 完全本地翻译，只访问 127.0.0.1。
 */
public class OllamaTranslator implements Translator {
    private final String baseUrl;
    private final String model;

    public OllamaTranslator(String baseUrl, String model) {
        this.baseUrl = baseUrl == null || baseUrl.isEmpty() ? "http://127.0.0.1:11434" : baseUrl;
        this.model = model == null || model.isEmpty() ? "qwen2.5:1.5b" : model;
    }

    @Override
    public String translate(String text, String sourceLang, String targetLang) throws Exception {
        String endpoint = baseUrl.endsWith("/") ? baseUrl + "api/chat" : baseUrl + "/api/chat";

        JsonObject payload = new JsonObject();
        payload.addProperty("model", model);
        payload.addProperty("stream", false);
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
        options.addProperty("num_predict", 256);
        options.addProperty("num_ctx", 4096);
        payload.add("options", options);

        String body = post(endpoint, payload.toString(), 60000);
        JsonObject root = new JsonParser().parse(body).getAsJsonObject();
        if (root.has("error")) {
            throw new RuntimeException(root.get("error").getAsString());
        }
        String result = root.getAsJsonObject("message").get("content").getAsString();
        return result == null || result.trim().isEmpty() ? text : result.trim();
    }

    private static String post(String url, String json, int timeoutMs) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(timeoutMs);
            conn.setDoOutput(true);
            byte[] data = json.getBytes(StandardCharsets.UTF_8);
            conn.setRequestProperty("Content-Length", String.valueOf(data.length));
            OutputStream os = conn.getOutputStream();
            os.write(data);
            os.flush();
            os.close();
            int code = conn.getResponseCode();
            BufferedReader reader = new BufferedReader(new InputStreamReader(
                    code >= 400 ? conn.getErrorStream() : conn.getInputStream(), StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            reader.close();
            if (code != 200) {
                throw new RuntimeException("HTTP " + code + ": " + sb.toString());
            }
            return sb.toString();
        } finally {
            conn.disconnect();
        }
    }
}
