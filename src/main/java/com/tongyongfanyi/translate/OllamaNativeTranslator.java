package com.tongyongfanyi.translate;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.tongyongfanyi.config.Languages;
import com.tongyongfanyi.config.ModConfig;
import com.tongyongfanyi.local.BundledRuntime;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * 本地模型翻译：调用模组自己拉起的 Ollama 服务的 /api/chat。
 *
 * <p>模型固定为 jar 内置的那一个（见 {@link BundledRuntime#modelName()}），
 * 语言对来自设置页的两个下拉框。翻译完全在本机进行，不上传任何内容。
 */
public class OllamaNativeTranslator {
    /**
     * 全局只允许一个在途的 /api/chat 请求。
     *
     * <p>Ollama 单模型一次只能真正跑一路推理，但客户端超时（HttpRequest.timeout）
     * 只是自己放弃等待，并不会把请求从服务端队列里撤掉：连接进入 CLOSE_WAIT，
     * 服务端仍在慢慢生成那个已经没人要的结果。模组 4 线程 + 每帧重复提交时，
     * 僵尸请求越堆越多，最终 Ollama 调度器状态卡死，连 /api/ps 都在撒谎。
     * 串行化后服务端最多只有一个待处理请求，卡死也就无从堆积。
     */
    private static final java.util.concurrent.Semaphore IN_FLIGHT =
            new java.util.concurrent.Semaphore(1, true);

    /**
     * 在线 API 的并发槽。
     *
     * <p><b>为什么必须和本地分开：</b>串行是为本地 Ollama 设计的（单模型一次只能跑
     * 一路推理），但云端 API 本来就能并发几十路。共用那把 1 槽信号量时，每条请求
     * 3~5 秒，排队 20 秒只放行 4~6 条，一屏文字立刻堵死，日志里就是成片的
     * {@code request timed out (model busy)} —— 看起来像"模型很慢"，其实是自己把
     * 并发卡成了 1。实际并发量仍受翻译线程数限制（默认 4），这里给 8 只是不额外卡脖子。
     */
    private static final java.util.concurrent.Semaphore IN_FLIGHT_API =
            new java.util.concurrent.Semaphore(8, true);

    /** 串行发送；排队超过上限按超时处理，避免线程池被等待占满 */
    private HttpResponse<String> sendSerialized(HttpRequest request)
            throws java.io.IOException, InterruptedException {
        // 记一次「玩家真的在翻译」：闲置释放显存的看门狗靠这个计时，
        // 刚被释放过的模型也会在这一次请求里自动加载回来
        BundledRuntime.noteActivity();
        java.util.concurrent.Semaphore slots = apiMode ? IN_FLIGHT_API : IN_FLIGHT;
        long waitMs = apiMode ? 60_000L : 20_000L;
        if (!slots.tryAcquire(waitMs, java.util.concurrent.TimeUnit.MILLISECONDS)) {
            throw new java.io.IOException(apiMode
                    ? "API 排队超时（并发槽已满）"
                    : "request timed out (model busy)");
        }
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        } finally {
            slots.release();
        }
    }

    /**
     * 统一的对话请求入口：本地 Ollama 的 {@code /api/chat}，或 OpenAI 兼容的
     * {@code /v1/chat/completions}。上层提示词、术语表、缓存、质量判定全部复用，
     * 换后端只影响这一层。
     */
    private String postChat(String system, String userText, double temperature, int maxTokens) throws Exception {
        if (!apiMode) {
            return postOllama(system, userText, temperature, maxTokens);
        }
        // 自定义服务如果是 Ollama，直接走 /api/chat，不套 OpenAI 的 /v1 路径
        if (com.tongyongfanyi.config.ApiModels.PROTOCOL_OLLAMA.equalsIgnoreCase(apiProtocol)) {
            return postOllama(system, userText, temperature, maxTokens);
        }
        // Anthropic 的 /v1/messages 与 OpenAI 的 /v1/chat/completions 结构差得远，
        // system 是顶层字段、content 是数组、鉴权走 x-api-key，必须单独实现
        if (com.tongyongfanyi.config.ApiModels.PROTOCOL_ANTHROPIC.equalsIgnoreCase(apiProtocol)) {
            return postAnthropic(system, userText, temperature, maxTokens);
        }
        return postOpenAi(system, userText, temperature, maxTokens);
    }

    private String postOllama(String system, String userText, double temperature, int maxTokens) throws Exception {
        JsonObject payload = new JsonObject();
        payload.addProperty("model", model);
        payload.addProperty("stream", false);
        payload.addProperty("keep_alive", "60m");
        // 本地模式听配置（默认关）；走 Ollama 协议的「在线」服务一律关：
        // 云端 thinking 模型同样会先吐一大段推理，把 num_predict 挤空、正文变空。
        payload.addProperty("think", !apiMode && localThinking);

        JsonArray messages = new JsonArray();
        JsonObject sys = new JsonObject();
        sys.addProperty("role", "system");
        sys.addProperty("content", system);
        messages.add(sys);
        JsonObject user = new JsonObject();
        user.addProperty("role", "user");
        user.addProperty("content", userText);
        messages.add(user);
        payload.add("messages", messages);

        JsonObject options = new JsonObject();
        options.addProperty("temperature", temperature);
        if (temperature > 0.4) {
            // 每次重译换一个采样种子，否则同参数解码会永远得到同一句译文
            options.addProperty("seed", (int) (System.nanoTime() % 1_000_000L));
        }
        options.addProperty("num_predict", maxTokens);
        // 与所有请求保持同一个 num_ctx，否则 Ollama 会卸载重载模型
        options.addProperty("num_ctx", BundledRuntime.numCtx());
        payload.add("options", options);

        HttpRequest request = HttpRequest.newBuilder(
                        URI.create(baseUrl + (baseUrl.endsWith("/") ? "" : "/") + "api/chat"))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofMillis(requestTimeout(userText)))
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString(), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = sendSerialized(request);
        if (response.statusCode() != 200) {
            throw new RuntimeException("本地模型返回 " + response.statusCode());
        }
        JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
        String result = root.getAsJsonObject("message").get("content").getAsString();
        return result == null || result.isBlank() ? userText : result.trim();
    }

    private String postOpenAi(String system, String userText, double temperature, int maxTokens) throws Exception {
        JsonObject payload = new JsonObject();
        payload.addProperty("model", model);
        payload.addProperty("stream", false);
        payload.addProperty("temperature", temperature);
        payload.addProperty("max_tokens", maxTokens);
        // 思考强度：各家字段名不统一，两个都发，认识的用、不认识的忽略。
        // 翻译默认 off：思考 token 又慢又贵，还会把 max_tokens 挤光导致正文为空。
        boolean thinkingOff = "off".equalsIgnoreCase(apiReasoning);
        JsonObject thinking = new JsonObject();
        thinking.addProperty("type", thinkingOff ? "disabled" : "enabled");
        payload.add("thinking", thinking);
        payload.addProperty("reasoning_effort", thinkingOff ? "minimal" : apiReasoning);

        JsonArray messages = new JsonArray();
        JsonObject sys = new JsonObject();
        sys.addProperty("role", "system");
        sys.addProperty("content", system);
        messages.add(sys);
        JsonObject user = new JsonObject();
        user.addProperty("role", "user");
        user.addProperty("content", userText);
        messages.add(user);
        payload.add("messages", messages);

        String base = baseUrl.replaceAll("/+$", "");
        // 允许玩家填 https://api.openai.com 或 https://api.openai.com/v1
        String endpoint = base.endsWith("/v1") ? base + "/chat/completions" : base + "/v1/chat/completions";
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(endpoint))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofMillis(requestTimeout(userText)))
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString(), StandardCharsets.UTF_8));
        if (!apiKey.isEmpty()) {
            builder.header("Authorization", "Bearer " + apiKey);
        }
        HttpResponse<String> response = sendSerialized(builder.build());
        if (response.statusCode() != 200) {
            String body = response.body() == null ? "" : response.body();
            if (body.length() > 200) {
                body = body.substring(0, 200) + "…";
            }
            // 绝不能把 Authorization 头写进异常；body 里通常只有 error.message
            throw new RuntimeException("API 返回 " + response.statusCode() + ": " + body);
        }
        JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
        JsonArray choices = root.getAsJsonArray("choices");
        if (choices == null || choices.isEmpty()) {
            throw new RuntimeException("API 响应里没有 choices");
        }
        JsonObject message = choices.get(0).getAsJsonObject().getAsJsonObject("message");
        String result = message != null && message.has("content") ? message.get("content").getAsString() : "";
        return result == null || result.isBlank() ? userText : result.trim();
    }

    /**
     * Anthropic 兼容的 {@code /v1/messages}。
     *
     * <p>和 OpenAI 的差异都在结构上，必须单独拼：
     * <ul>
     *   <li>{@code system} 是顶层字段，不是 messages 里的一条；</li>
     *   <li>鉴权用 {@code x-api-key}，而且必须带 {@code anthropic-version}；</li>
     *   <li>响应是 {@code content: [{type:"text",text:"..."}]} 数组，
     *       开思考时还会混进 {@code thinking} 块，只能挑 type=text 的拼。</li>
     * </ul>
     */
    private String postAnthropic(String system, String userText, double temperature, int maxTokens)
            throws Exception {
        JsonObject payload = new JsonObject();
        payload.addProperty("model", model);
        payload.addProperty("system", system);

        JsonArray messages = new JsonArray();
        JsonObject user = new JsonObject();
        user.addProperty("role", "user");
        user.addProperty("content", userText);
        messages.add(user);
        payload.add("messages", messages);

        String effort = apiReasoning == null ? "off" : apiReasoning.toLowerCase(java.util.Locale.ROOT);
        int budget = switch (effort) {
            case "low" -> 1024;
            case "medium" -> 2048;
            case "high" -> 4096;
            default -> 0;
        };
        if (budget == 0) {
            payload.addProperty("temperature", temperature);
            payload.addProperty("max_tokens", maxTokens);
        } else {
            // Anthropic 开思考时要求 temperature 必须为 1，且 max_tokens 必须大于 budget，
            // 否则直接 400。翻译场景不推荐开，开着也只是照它的规则发。
            payload.addProperty("temperature", 1.0);
            payload.addProperty("max_tokens", Math.max(maxTokens, budget + 512));
            JsonObject thinking = new JsonObject();
            thinking.addProperty("type", "enabled");
            thinking.addProperty("budget_tokens", budget);
            payload.add("thinking", thinking);
        }

        String base = baseUrl.replaceAll("/+$", "");
        // 允许玩家填 https://api.anthropic.com 或 https://api.anthropic.com/v1
        String endpoint = base.endsWith("/v1") ? base + "/messages" : base + "/v1/messages";
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(endpoint))
                .header("Content-Type", "application/json")
                .header("anthropic-version", com.tongyongfanyi.config.ApiModels.ANTHROPIC_VERSION)
                .timeout(Duration.ofMillis(requestTimeout(userText)))
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString(), StandardCharsets.UTF_8));
        if (!apiKey.isEmpty()) {
            builder.header("x-api-key", apiKey);
        }
        HttpResponse<String> response = sendSerialized(builder.build());
        if (response.statusCode() != 200) {
            String body = response.body() == null ? "" : response.body();
            if (body.length() > 200) {
                body = body.substring(0, 200) + "…";
            }
            // 同样不能把 x-api-key 写进异常
            throw new RuntimeException("API 返回 " + response.statusCode() + ": " + body);
        }
        JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
        JsonArray content = root.getAsJsonArray("content");
        StringBuilder sb = new StringBuilder();
        if (content != null) {
            for (JsonElement e : content) {
                if (!e.isJsonObject()) {
                    continue;
                }
                JsonObject o = e.getAsJsonObject();
                if (o.has("type") && "text".equals(o.get("type").getAsString()) && o.has("text")) {
                    sb.append(o.get("text").getAsString());
                }
            }
        }
        String result = sb.toString();
        return result.isBlank() ? userText : result.trim();
    }

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))            .build();
    private final String baseUrl;
    private final String model;
    /** 单次请求超时(毫秒)，来自配置 */
    private final int timeoutMs;
    /** true = 走 OpenAI 兼容的在线接口；false = 本地 Ollama */
    private final boolean apiMode;
    /** API Key，只在 apiMode 下使用；不落配置、不进日志 */
    private final String apiKey;
    /** API 协议：openai（/v1/chat/completions）或 ollama（/api/chat） */
    private final String apiProtocol;
    /** 思考强度：off / low / medium / high（仅在线 API；本地没有强度档） */
    private final String apiReasoning;
    /** 本地模型是否开思考（Ollama 的 think 参数）；开了延迟会翻几倍 */
    private final boolean localThinking;

    public OllamaNativeTranslator(String baseUrl, String model, int timeoutMs) {
        this(baseUrl, model, timeoutMs, false, null, "openai", "off");
    }

    public OllamaNativeTranslator(String baseUrl, String model, int timeoutMs,
                                  boolean apiMode, String apiKey) {
        this(baseUrl, model, timeoutMs, apiMode, apiKey, "openai", "off", false);
    }

    public OllamaNativeTranslator(String baseUrl, String model, int timeoutMs,
                                  boolean apiMode, String apiKey,
                                  String apiProtocol, String apiReasoning) {
        this(baseUrl, model, timeoutMs, apiMode, apiKey, apiProtocol, apiReasoning, false);
    }

    public OllamaNativeTranslator(String baseUrl, String model, int timeoutMs,
                                  boolean apiMode, String apiKey,
                                  String apiProtocol, String apiReasoning, boolean localThinking) {
        this.apiMode = apiMode;
        this.apiProtocol = apiProtocol == null || apiProtocol.isBlank() ? "openai" : apiProtocol;
        this.apiReasoning = apiReasoning == null || apiReasoning.isBlank() ? "off" : apiReasoning;
        this.localThinking = localThinking;
        if (apiMode) {
            this.baseUrl = baseUrl == null || baseUrl.isBlank()
                    ? "https://api.deepseek.com" : baseUrl.replaceAll("/+$", "");
            this.model = model == null || model.isBlank() ? "deepseek-chat" : model;
        } else {
            this.baseUrl = baseUrl == null || baseUrl.isBlank() ? BundledRuntime.baseUrl() : baseUrl;
            this.model = model == null || model.isBlank() ? BundledRuntime.modelName() : model;
        }
        this.timeoutMs = clampTimeout(timeoutMs);
        this.apiKey = apiKey == null ? "" : apiKey.trim();
    }

    public boolean apiMode() {
        return apiMode;
    }

    /** 把超时毫秒数收敛到 [1000, 60000]，防止配置写错导致请求永久挂起 */
    private static int clampTimeout(int timeoutMs) {
        return Math.max(1000, Math.min(60000, timeoutMs));
    }

    /**
     * 短文本（含所有单词术语）没必要等配置的 20s：模型翻一个词通常 100ms 内完事，
     * 真挂住的都是无意义字符串。
     *
     * <p>上限从 3s 放宽到 6s：并发槽只有 2 个，一屏文字同时出现时要排队，
     * 3s 会把“还在排队”的请求直接判超时，客户端重试又排一次队 ——
     * 实测 2 分半里 45 次超时，绝大多数是这么来的，纯属浪费 GPU 时间。
     */
    /**
     * 单次请求的超时。
     *
     * <p>短词条（≤24 字）原本压到 6 秒，为的是不让一两个词的碎片把队列占住。
     * 但 CPU 推理比 GPU 慢一个量级：日志里 {@code Personal Bank}、{@code Sell Instantly}、
     * {@code popot} 这类 13 字符的东西在 CPU 上经常要 8~15 秒，6 秒必然超时，
     * 重试 5 次后永久放弃 —— 屏幕上就是「物品名一直英文，正文却翻了」。
     *
     * <p>所以按实际后端放宽：GPU 沿用 6 秒（够用且不容易堵），CPU 给短词条 18 秒、
     * 长文本沿用用户配置（默认 20 秒）。上限仍卡在 60 秒。
     */
    private int requestTimeout(String text) {
        boolean shortText = text != null && text.length() <= 24;
        // 在线 API 不迁就本地模型：不用等模型加载、没有显存问题，
        // 也不该因为「本地已经连续超时」就被压到 8 秒（那个 streak 是本地自愈用的）。
        // 云端首 token 通常 1~3 秒，短文本给 15 秒上限、其余按配置走。
        //
        // 例外：地址是 127.0.0.1 / localhost（Termux 里的 ollama serve）时，
        // 本质还是本机 CPU 推理，按云端那套 15 秒会直接把正常请求判超时。
        if (apiMode && !com.tongyongfanyi.config.ApiModels.isLoopback(baseUrl)) {
            int budget = shortText ? Math.min(timeoutMs, 15000) : timeoutMs;
            return Math.max(5000, Math.min(60000, budget));
        }
        int base = shortText ? Math.min(timeoutMs, 6000) : timeoutMs;
        if (!com.tongyongfanyi.local.BundledRuntime.gpuActive()) {
            base = shortText ? Math.max(base, 18000) : Math.max(base, 30000);
        }
        // 服务已经连续超时：快速失败，别让每条请求都硬等 18~30 秒把线程池占满，
        // 也能让「连续 6 次触发自愈」更快攒够，缩短整体卡住的时间。
        if (com.tongyongfanyi.local.BundledRuntime.timeoutStreak() >= 2
                && !com.tongyongfanyi.local.BundledRuntime.inReloadGrace()) {
            base = Math.min(base, 8000);
        }
        // 后台正在重新加载模型（1.8B 约 11.5 秒）：这一条大概率要排队等加载，
        // 给足预算，别刚发出去就自己判超时、又给自愈添一笔。
        if (com.tongyongfanyi.local.BundledRuntime.inReloadGrace()) {
            base = Math.max(base, 30000);
        }
        return Math.max(1000, Math.min(60000, base));
    }

    public String translate(String text, String sourceLang, String targetLang) throws Exception {
        return translate(text, sourceLang, targetLang, false, false, false);
    }

    /**
     * 重试用的强约束版本：第一次原样返回了原文时再调一次。
     *
     * <p>小模型偶尔会把输入照抄回来（尤其长句、带数字/符号的句子）。第二次在提示词里
     * 明确“不能原样返回”，并放宽输出长度，命中率明显高一些。
     */
    public String translateStrict(String text, String sourceLang, String targetLang) throws Exception {
        return translate(text, sourceLang, targetLang, true, false, false);
    }

    /**
     * 玩家按下「重译残留英文」键时专用：上一次译文里还夹着英文实词（SkyBlock. Collect …），
     * 说明小模型把首字母大写的词当成专有名词照抄了。这里用完整原文再译一次，
     * 并额外强调「任何英文实词都不许保留」，比普通 strict 重试更针对这一类漏翻。
     * 带 [数字] 分段标记的多色整句自动沿用分段提示词，避免丢掉颜色对应关系。
     */
    public String translateFull(String text, String sourceLang, String targetLang) throws Exception {
        boolean segmented = text != null
                && java.util.regex.Pattern.compile("(?m)^\\s*\\[\\d+\\]").matcher(text).find();
        return translate(text, sourceLang, targetLang, true, segmented, true);
    }

    /**
     * 「强制重译」键专用：玩家觉得现有译文不够好，想换一种译法。
     *
     * <p>普通请求是 temperature=0 的贪心解码，同一句原文每次都吐出完全一样的译文，
     * 按了键等于没按。这里换成随机 seed + 稍高温度，并在提示词里要求「换一种更
     * 通顺自然、符合游戏用语习惯的表达」，这样每次按都能拿到一份可比较的新译法。
     * 带 [数字] 分段标记的多色句仍沿用分段提示词，颜色边界不丢。
     */
    public String translateAlternative(String text, String sourceLang, String targetLang) throws Exception {
        boolean segmented = text != null
                && java.util.regex.Pattern.compile("(?m)^\\s*\\[\\d+\\]").matcher(text).find();
        return translate(text, sourceLang, targetLang, true, segmented, true, true);
    }

    /**
     * 单词术语专用：{@code Dungeon}、{@code Minion} 这种单个词，走普通提示词时
     * 0.8B 会把它原样（甚至小写）吐回来。换成“必须输出汉字、不许出现英文”的
     * 术语提示词，命中率高很多。
     */
    public String translateTerm(String text, String targetLang) throws Exception {
        String target = Languages.promptName(Languages.TARGETS, targetLang);
        // 目标语言不一定是中文（可以跟随游戏语言），不能写死「必须包含汉字」
        String system = "把用户输入的英文游戏术语翻译成" + target + "。"
                + "输出必须是" + target + "，绝对不能输出英文单词本身。"
                + "只输出译名，不要解释、不要括号注释。";

        // 术语请求输出很短、温度固定 0，直接走统一入口
        return postChat(system, text, 0.0, 64);
    }

    /**
     * 分段标记模式：整句送进去，但要求模型把 {@code [数字]} 标记原样留在对应译文前面。
     *
     * <p>实测（tyfy-hymt2:1.8b，2026-11-10）：用 {@code [0]} 这种方括号数字，
     * 9 个标记能 9 个原样保留且顺序不乱；换成 {@code ⟦0⟧} 会把右括号改成左括号
     * （{@code ⟦1⟨}）、还会打乱顺序，所以这里定死用方括号数字。
     *
     * <p>为什么值得这么做：一句话在游戏里按颜色拆成十几段，逐段翻会得到
     * 「Don Expresso wants some 美味秘银 从开始 Cliffside 静脉」这种逐词硬拼；
     * 带标记整句送进去，模型自己在段边界断句，颜色能精确对回原来那段。
     */
    public String translateSegmented(String text, String sourceLang, String targetLang) throws Exception {
        return translate(text, sourceLang, targetLang, false, true, false);
    }

    private String translate(String text, String sourceLang, String targetLang,
                             boolean strict, boolean segmented, boolean full) throws Exception {
        return translate(text, sourceLang, targetLang, strict, segmented, full, false);
    }

    private String translate(String text, String sourceLang, String targetLang,
                             boolean strict, boolean segmented, boolean full,
                             boolean alternative) throws Exception {
        String source = Languages.promptName(Languages.SOURCES, sourceLang);
        String target = Languages.promptName(Languages.TARGETS, targetLang);
        boolean auto = sourceLang == null || "auto".equals(sourceLang);
        // 真检测一次比让 0.8B 猜准得多：假名→日语、谚文→韩语、汉字→中文，其余按英语
        String detected = auto ? detectSource(text) : source;
        // 源语言和目标语言相同（中文行里夹了几个英文词）时，别让模型"中译中"，
        // 直接退化成混合模式提示词
        if (detected != null && detected.equals(target)) {
            detected = null;
        }

        // 短的走精简提示词：省 token，短句上也更不容易跑偏
        String briefText = text == null ? "" : text.trim();
        boolean brief = briefText.length() <= 40 && briefText.split("\\s+").length <= 6;
        String systemPrompt = buildSystemPrompt(detected, target, strict, brief, full);
        if (alternative) {
            systemPrompt += "这是一次重新翻译：请换一种更通顺自然、符合游戏用语习惯的"
                    + target + "表达，不要生硬直译、不要逐词拼接、不要照抄原文。"
                    + "只输出一份新的译文，不要解释，也不要列出多个版本。";
        }
        if (segmented) {
            systemPrompt += "文本里的 [数字] 是颜色分段标记，必须原样保留："
                    + "位置、编号、顺序都不能改，不能删除、不能合并、不要把标记翻译成中文。"
                    + "每个标记后面只放它那一段文字的译文，整句读起来要连贯。";
        }

        // 输出上限跟着输入长度走。固定 512/768 时，1.8B 遇到 "CH"、"gt"、"[VIP"
        // 这类无意义碎片会一直续写到上限（实测 decode 最高 257 token，单请求 3~6 秒），
        // 把翻译线程占住、后面的正常文本全排队。短输入给 64 token 足够出译文。
        int budget = Math.max(64, Math.min(512, text.length() * 3));
        if (strict) {
            budget = Math.min(768, budget + 128);
        }

        // 本地 Ollama 与在线 API 共用同一套提示词和预算，只换请求层
        return postChat(systemPrompt, text, alternative ? 0.45 : 0.0, budget);
    }

    /** 粗略按字符集判断源语言：够用，且不依赖模型 */
    /**
     * 按「字符数占比」判断源语言，而不是「出现过某类字符」。
     *
     * <p>之前只要出现一个汉字就判成中文，于是
     * {@code [NPC] 托尔胡斯峡谷: The second island of Galatea...} 这种
     * 「中文名 + 英文正文」的行会得到「从简体中文翻译为简体中文」，
     * 模型直接把原文吐回来 —— 这是聊天漏翻的主因。
     */
    private static String detectSource(String text) {
        int kana = 0;
        int hangul = 0;
        int han = 0;
        int latin = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= 0x3040 && c <= 0x30FF) {
                kana++;
            } else if (c >= 0xAC00 && c <= 0xD7AF) {
                hangul++;
            } else if (c >= 0x4E00 && c <= 0x9FFF) {
                han++;
            } else if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')) {
                latin++;
            }
        }
        int other = Math.max(kana, Math.max(hangul, han));
        if (other == 0 && latin == 0) {
            return null;
        }
        if (kana > 0 && kana >= other) {
            return "日语";
        }
        if (hangul > 0 && hangul >= other) {
            return "韩语";
        }
        // 英文字母明显多于汉字 → 当成英文（中文名只是夹带）
        if (latin > han) {
            return "英语";
        }
        if (han > 0) {
            return "简体中文";
        }
        // 拉丁字母/其他文字但占绝对多数：来源不确定，交给模型判断
        return null;
    }

    /**
     * 系统提示词：明确任务 + 硬约束。
     *
     * <p>需要提示词 —— 内置的是通用小模型，不给任务它会续写、解释或直接复述。
     * 旧版只有「只输出译文、保留颜色码」，漏了三件最要命的事：
     * <ol>
     *   <li><b>占位符</b>：术语/网址在送模型前已换成 {@code <t0>}、{@code <u0>}，
     *       不写明「原样保留」，0.8B 会把它改写成 {@code <bag>} 或直接吃掉；</li>
     *   <li><b>不要补内容</b>：模型爱给短句补「…」，之前只能靠 cleanTranslation 事后擦。</li>
     * </ol>
     * 注意<b>不要在提示词里写出完整标记字符串，也不要塞 few-shot 对话</b>：
     * 0.8B/1.8B 会把示例或标记原文复述出来（曾导致聊天栏刷「收集 20 个 &lt;t0&gt; 日志」）。
     * 只用文字描述规则，模型照样能从 payload 里认出这些标记。
     * 长度控制在 ~90 token，prefill 开销仍远小于 200 token 的老版本。
     */
    /**
     * 拼系统提示词。
     *
     * <p>分两档，短的走精简版：
     * <ul>
     *   <li><b>短文本</b>（单词、属性行、地点、按钮，≤40 字且 ≤6 个词）：只讲「翻成什么语言、
     *       什么不能动」。规则一多，1.8B 反而容易在短句上跑偏（把 Purse 翻成「钱包:」时
     *       还硬塞一句解释），也省 30~40ms。</li>
     *   <li><b>长文本</b>（句子、lore、聊天）：完整规则（语序重组、地名、罗马数字等级、
     *       省略号、颜色码…）。</li>
     * </ul>
     */
    /** 手动重译时才加的硬约束：专治「首字母大写 = 专有名词」被照抄（Collect、Boost …） */
    private static String fullRule(String target) {
        return "特别注意：译文里不允许残留任何英文实词；每个英文词都必须译成" + target
                + "，不要因为某个词首字母大写就当成专有名词照抄。"
                + "玩家 ID、网址、罗马数字等级除外。只输出最终译文。";
    }

    private static String buildSystemPrompt(String source, String target, boolean strict,
                                            boolean brief, boolean full) {
        StringBuilder sb = new StringBuilder(192);
        sb.append("你是游戏文本翻译器。把用户消息");
        if (source != null) {
            sb.append("从").append(source);
        } else {
            // 源语言不确定（拉丁字母，或中文里夹英文）：让模型自己判断
            sb.append("里的外文");
        }
        sb.append("翻译成").append(target).append("，只输出译文。\n");

        if (brief) {
            sb.append("规则：不解释、不加引号、不补原文没有的内容；")
                    .append("数字、§颜色码、[数字] 标记原样保留；")
                    .append("玩家 ID、UUID、网址保留原文；")
                    .append("已经是").append(target).append("的词原样保留，不要改写；")
                    // 这一句是「Recipe Book → Recipe 书」的根因修复：
                    // 术语预替换后文本变成「Recipe 书」这种中英混排，模型会以为
                    // 「已经有中文了，剩下的英文是专有名词」，于是只翻一半。
                    // 实测（tyfy-hymt2:1.8b）：加这句前 → 「Recipe 书」，
                    // 加这句后 → 「食谱书」，「Gold 锭」→「金锭」，「附魔 Book」→「附魔书」。
                    .append("输入里同时有中文和英文时，英文部分也必须全部译成").append(target)
                    .append("，不得照抄英文。");
            if (strict) {
                sb.append("必须给出").append(target).append("译文，绝对不能照抄原文。");
            }
            if (full) {
                sb.append(fullRule(target));
            }
            return sb.toString();
        }

        sb.append("规则：不解释、不加引号、不补原文没有的内容（尤其省略号）；")
                .append("输入里的 §颜色码、[数字] 标记、数字、空格、换行必须原样保留，")
                .append("不得翻译、改写、简化或替换成其它字母；")
                // 术语现在是「送模型前就替换好的译文」，不再用 <tN> 占位符，
                // 所以这里只需要让模型别去改写已经译好的名词（目标语言不一定是中文）
                .append("输入里已经处于").append(target).append("的名词原样保留，不要改写或再翻译；")
                .append("物品/技能/NPC 名要译出，玩家 ID、UUID、网址保留原文；")
                // 地点不再用占位符锁住，原文会直接出现在输入里，必须让模型自己译
                .append("地名/区域名必须音译或意译成").append(target)
                .append("，绝对不要照抄英文地名；")
                .append("已有的").append(target).append("文字原样保留；")
                // 同上：预替换后的中英混排必须把剩下的英文也翻掉
                .append("句子里同时存在").append(target).append("和英文时，英文部分同样必须译完，")
                .append("不能因为句子里已经有").append(target).append("就保留英文；")
                // 计分板 / 任务目标里常见 "Talk to ..."：三个点是占位符，别照搬成「与…交谈」
                .append("原文里的 ... 是占位符，译文里不要保留，按上下文自然表达；")
                // 附魔/技能等级：Growth I、Piercing I、Lava Rune I
                .append("大写 I、II、III 等罗马数字是等级，必须原样保留，")
                .append("绝不能把等级 I 当成英语代词译成「我」。\n")
                // 语序：0.8B/1.8B 小模型最容易逐词对译，不明确要求重组的话
                // 会翻出「收集 20 个 原木 为 我」这种英文词序。
                .append("语序：按").append(target)
                .append("的自然语序重组整句，严禁逐词对译；英语的后置定语、介词短语、")
                .append("时间与地点状语要移到").append(target)
                .append("习惯的位置；数量词后补合适的量词；")
                .append("编号标记按它在句中的位置自然嵌入，不要因为它打乱语序。");
        if (strict) {
            sb.append("必须给出").append(target)
                    .append("译文，绝对不能照抄原文，也不能沿用英文词序。");
        }
        if (full) {
            sb.append(fullRule(target));
        }
        return sb.toString();
    }

    public static OllamaNativeTranslator create(ModConfig config) {
        if ("api".equalsIgnoreCase(config.translateBackend)) {
            return new OllamaNativeTranslator(config.apiBaseUrl, config.apiModel, config.timeoutMs,
                    true, com.tongyongfanyi.config.ApiKeyStore.load(),
                    config.apiProtocol, config.apiReasoning);
        }
        return new OllamaNativeTranslator(BundledRuntime.baseUrl(), BundledRuntime.modelName(),
                config.timeoutMs, false, null, "openai", "off", config.localThinking);
    }
}
