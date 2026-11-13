package com.tongyongfanyi.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * API 预设与模型列表拉取。
 *
 * <p>内置两家国内常用的 OpenAI 兼容服务：
 * <ul>
 *   <li>DeepSeek：{@code https://api.deepseek.com}</li>
 *   <li>小米 MiMo：{@code https://api.xiaomimimo.com/v1}</li>
 * </ul>
 * 其他服务商选「自定义」，填 Base URL + 协议即可。
 *
 * <p>模型列表在玩家点「获取模型」时才请求，不做后台轮询；结果只用于填下拉框。
 */
public final class ApiModels {
    public static final String PROVIDER_DEEPSEEK = "deepseek";
    public static final String PROVIDER_MIMO = "mimo";
    public static final String PROVIDER_CUSTOM = "custom";

    /** 协议：OpenAI 兼容 / Ollama 原生 / Anthropic 兼容 */
    public static final String PROTOCOL_OPENAI = "openai";
    public static final String PROTOCOL_OLLAMA = "ollama";
    public static final String PROTOCOL_ANTHROPIC = "anthropic";

    public static final String DEEPSEEK_BASE = "https://api.deepseek.com";
    public static final String DEEPSEEK_MODEL = "deepseek-chat";
    public static final String MIMO_BASE = "https://api.xiaomimimo.com/v1";
    public static final String MIMO_MODEL = "mimo-v2.5";
    public static final String ANTHROPIC_BASE = "https://api.anthropic.com";
    public static final String ANTHROPIC_MODEL = "claude-sonnet-4-5";
    /** Anthropic 必须带版本头，否则一律 400 */
    public static final String ANTHROPIC_VERSION = "2023-06-01";

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private ApiModels() {
    }

    /**
     * 地址是不是本机回环（{@code 127.0.0.1} / {@code localhost} / {@code ::1}）。
     *
     * <p>用途：玩家在手机上用 Termux 跑 {@code ollama serve}、再让模组用
     * 「自定义 + Ollama 协议 + http://127.0.0.1:11434」连它 —— 这时虽是"在线 API"
     * 模式，实际跑的是本机 CPU 推理，并发数和超时都不能按云端算
     * （云端能开 6~8 路，手机 6 路并发只会把 CPU 打满、每条都变慢）。
     */
    public static boolean isLoopback(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return false;
        }
        String u = baseUrl.toLowerCase(java.util.Locale.ROOT);
        return u.contains("127.0.0.1") || u.contains("localhost") || u.contains("[::1]");
    }

    public static String defaultBase(String provider) {
        return switch (provider == null ? "" : provider) {
            case PROVIDER_MIMO -> MIMO_BASE;
            case PROVIDER_CUSTOM -> "";
            default -> DEEPSEEK_BASE;
        };
    }

    public static String defaultModel(String provider) {
        return switch (provider == null ? "" : provider) {
            case PROVIDER_MIMO -> MIMO_MODEL;
            case PROVIDER_CUSTOM -> "";
            default -> DEEPSEEK_MODEL;
        };
    }

    /**
     * 拉取模型列表。
     * <ul>
     *   <li>OpenAI 兼容：{@code GET /v1/models}，{@code Authorization: Bearer}</li>
     *   <li>Anthropic 兼容：{@code GET /v1/models}，{@code x-api-key} + {@code anthropic-version}</li>
     *   <li>Ollama：{@code GET /api/tags}</li>
     * </ul>
     * 阻塞方法，调用方放后台线程。
     */
    public static List<String> fetch(String baseUrl, String protocol, String apiKey) throws Exception {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("API 地址为空");
        }
        String base = baseUrl.trim().replaceAll("/+$", "");
        boolean ollama = PROTOCOL_OLLAMA.equalsIgnoreCase(protocol);
        boolean anthropic = PROTOCOL_ANTHROPIC.equalsIgnoreCase(protocol);
        String url = ollama
                ? base + "/api/tags"
                : (base.endsWith("/v1") ? base + "/models" : base + "/v1/models");

        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(12))
                .header("Accept", "application/json")
                .header("User-Agent", "tongyongfanyi-mod")
                .GET();
        boolean hasKey = apiKey != null && !apiKey.isBlank();
        if (anthropic) {
            if (hasKey) {
                builder.header("x-api-key", apiKey.trim());
            }
            builder.header("anthropic-version", ANTHROPIC_VERSION);
        } else if (!ollama && hasKey) {
            builder.header("Authorization", "Bearer " + apiKey.trim());
        }
        HttpResponse<String> response = HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            String body = response.body() == null ? "" : response.body().trim();
            if (body.length() > 180) {
                body = body.substring(0, 180) + "…";
            }
            throw new RuntimeException("HTTP " + response.statusCode() + (body.isEmpty() ? "" : " " + body));
        }
        JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
        // Anthropic 与 OpenAI 的列表结构一致（data[].id），Ollama 是 models[].name
        JsonArray array = ollama ? root.getAsJsonArray("models") : root.getAsJsonArray("data");
        List<String> out = new ArrayList<>();
        if (array != null) {
            for (JsonElement e : array) {
                if (!e.isJsonObject()) {
                    continue;
                }
                JsonObject o = e.getAsJsonObject();
                String id = ollama
                        ? (o.has("name") ? o.get("name").getAsString() : "")
                        : (o.has("id") ? o.get("id").getAsString() : "");
                if (!id.isBlank() && !out.contains(id)) {
                    out.add(id);
                }
            }
        }
        out.sort(String.CASE_INSENSITIVE_ORDER);
        return out;
    }
}
