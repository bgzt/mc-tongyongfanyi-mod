package com.tongyongfanyi.local;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.tongyongfanyi.config.ModConfig;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

/**
 * 内置的本地翻译运行时：解包 + 启动 + 状态。
 *
 * <p>jar 里带一个 {@code tongyongfanyi/payload.zip}（STORED 存放），内容为：
 * <pre>
 *   ollama/ollama.exe + lib/ollama/*.dll（CPU 与 Vulkan 后端）
 *   models/manifests/registry.ollama.ai/library/&lt;模型&gt;/&lt;tag&gt;
 *   models/blobs/sha256-*                      （模型权重）
 * </pre>
 * 首次启动解到 {@code <游戏目录>/tongyongfanyi/} 下，之后直接复用。
 *
 * <p><b>优先用本机已装的 Ollama</b>：本机那份往往带 CUDA，N 卡上比 Vulkan 快，
 * 而且省掉 124MB 的解包。找不到才用 jar 里这份。
 * 两种情况下模型目录、监听端口都用模组自己的，不干扰玩家原有的 Ollama（默认 11434）。
 */
public final class BundledRuntime {
    private static final Logger LOGGER = LoggerFactory.getLogger("tongyongfanyi");

    /** 内置运行时优先使用这个端口，被占用就往后顺延 */
    private static final int BASE_PORT = 11634;
    private static final int PORT_TRIES = 8;
    /** 旧单包：运行时 + 模型在同一个 payload */
    private static final String PAYLOAD = "tongyongfanyi/payload.zip";
    /** 新代码包：只带 Ollama 运行时 */
    private static final String RUNTIME = "tongyongfanyi/runtime.zip";
    /** 新模型包：只带模型（manifest + blobs） */
    private static final String MODELS_ZIP = "tongyongfanyi/models.zip";
    private static final String MODEL_PROPS = "tongyongfanyi/model.properties";
    private static final String MARKER = ".payload.ok";
    private static final String RUNTIME_MARKER = ".runtime.ok";
    private static final String MODELS_MARKER = ".models.ok";

    public enum State {
        /** 还没开始 */
        IDLE,
        /** 正在解包 / 启动 / 加载模型 */
        PREPARING,
        /** 可以翻译了 */
        READY,
        /** 出错了，看 {@link #error()} */
        FAILED
    }

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    /**
     * 全模组唯一的上下文长度，<b>所有</b>发往 Ollama 的推理请求都必须用它。
     *
     * <p>为什么必须统一：Ollama 把 {@code num_ctx} 当成常驻 runner 的身份之一，
     * 只要这次请求和当前驻留 runner 的上下文长度不一样，它就会销毁 llama-server
     * 重新加载模型。实测（tyfy-hymt2:1.8b，RTX 4060 Laptop）：
     * <pre>
     *   同参数连续三次 num_ctx=1536 → 2.82s(加载) / 0.08s / 0.08s
     *   依次 1536 → 1024 → 1536 → 4096 → 5.0s / 2.7s / 2.3s / 2.7s（每次都在重载）
     * </pre>
     * 也就是说真正的推理只要 0.08 秒，慢的 99% 都是重复加载。
     * 之前预热用默认 4096、术语用 1024、整句用 1536，三者交替 → 几乎每条都要重载。
     */
    public static final int NUM_CTX = 1536;

    /**
     * 实际使用的上下文长度。
     *
     * <p>手机上降到 512：KV 缓存按 token 数线性占内存，512 相比 1536 能省掉近三分之二，
     * 首 token 延迟也明显更低。翻译一条游戏文本的提示词 + 正文远不到 512 token，
     * 降下来不会截断正常句子。
     *
     * <p>注意这个值必须全局统一：Ollama 发现 num_ctx 变了会销毁 llama-server 重新加载，
     * 实测每次 2.3~5 秒。所以所有请求（预热、术语、整句）都调这一个方法取。
     */
    public static int numCtx() {
        return RuntimePacks.isAndroid() ? 512 : NUM_CTX;
    }

    private static volatile State state = State.IDLE;
    private static volatile String status = "未启动";
    private static volatile int percent = -1;
    private static volatile String error = "";

    /**
     * 运行期错误（不是「启动失败」，而是跑着跑着出的问题：API 鉴权失败、
     * 连续请求失败、模型被卸载等）。和 {@link #error} 分开是因为 API 模式下
     * {@code state} 一直是 READY，用 FAILED 表示"连不上"会让 HUD 一直红着。
     */
    private static volatile String lastError = "";
    private static volatile long lastErrorAt = 0;
    /** 这条错误是否已经在聊天栏提示过；同一条只提示一次，避免刷屏 */
    private static volatile boolean errorNotified = true;
    private static volatile boolean gpuActive = false;
    /** 想用 GPU 但显存不够，模型实际跑在 CPU 上（ollama 会静默把层数降到 0） */
    private static volatile boolean gpuFallback = false;
    /**
     * 是否真的从 /api/ps 读到过模型驻留信息。
     *
     * <p>刚启动时 /api/ps 常常是 {@code {"models":[]}}（模型还没登记），
     * 此时「没查到」不等于「不在 GPU 上」。以前直接当 CPU 处理，于是状态行
     * 误报「显存不足，已退回 CPU」，而 llama-server 日志里明明是
     * {@code offloaded 33/33 layers to GPU}。
     */
    private static volatile boolean gpuConfirmed = false;
    private static volatile long lastGpuProbeAt = 0L;
    /** 最近一次启动用的设备设置，运行中复核时用来判断「是不是本来就想用 CPU」 */
    private static volatile String lastDevice = "auto";
    private static volatile String backend = "";
    private static volatile String port = "";
    private static volatile Process process;
    private static final AtomicBoolean inited = new AtomicBoolean(false);

    // ---- 闲置释放显存 ----
    /** 最近一次「真的要翻译」的时间（发请求前打一次），闲置看门狗按它计时 */
    private static volatile long lastActivityAt = System.currentTimeMillis();
    /** 模型已按闲置策略卸载：显存已还回去，下一次翻译请求会自动把它加载回来 */
    private static volatile boolean released = false;
    /** 闲置多少分钟释放显存，0 = 不释放（设置项 idleReleaseMinutes） */
    private static volatile int idleReleaseMinutes = 10;
    /** 看门狗只起一个 */
    private static final AtomicBoolean idleWatchStarted = new AtomicBoolean(false);
    /** 看门狗轮询间隔：20 秒足够准，也不会白烧 CPU */
    private static final long IDLE_POLL_MS = 20_000L;

    /** 重启服务串行化：防止设置页连点设备/线程时多路 boot 抢端口 */
    private static final Object restartLock = new Object();
    private static final AtomicBoolean restarting = new AtomicBoolean(false);
    private static volatile ModConfig pendingRestartConfig = null;

    private BundledRuntime() {
    }

    // ------------------------------------------------------------------
    // 对外查询
    // ------------------------------------------------------------------

    public static State state() {
        return state;
    }

    public static boolean isReady() {
        return state == State.READY;
    }

    public static String status() {
        return status;
    }

    public static int percent() {
        return percent;
    }

    public static String error() {
        return error;
    }

    /**
     * 记录一条玩家需要知道的运行期错误：API 密钥无效、地址连不上、
     * 本地模型加载失败等。{@code TongyongfanyiClient} 的 tick 会取走一次并
     * 在聊天栏提示，同一条错误只提示一次（30 秒内重复上报不重置提示状态）。
     */
    public static void reportError(String message) {
        if (message == null || message.isBlank()) {
            return;
        }
        // HUD 一行放不下 401 那种带整段响应体的消息，截断保留关键部分
        String msg = message.trim().replaceAll("\\s+", " ");
        if (msg.length() > 140) {
            msg = msg.substring(0, 140) + "…";
        }
        long now = System.currentTimeMillis();
        boolean same = msg.equals(lastError) && now - lastErrorAt < 30_000L;
        lastError = msg;
        lastErrorAt = now;
        if (!same) {
            errorNotified = false;
        }
    }

    /** 最近一条运行期错误（可能是旧的，供 /tyfy status 和设置页查看） */
    public static String lastError() {
        return lastError;
    }

    /** 取走「待提示」标记：true 表示这条错误还没在聊天栏说过 */
    public static boolean takeErrorNotice() {
        if (errorNotified || lastError.isBlank()) {
            return false;
        }
        errorNotified = true;
        return true;
    }

    /** 翻译恢复正常后调用：清掉错误，下次真出问题还能再提示 */
    public static void clearError() {
        lastError = "";
        lastErrorAt = 0;
        errorNotified = true;
    }

    /** “本机 Ollama” / “内置运行时”，空串表示还没确定 */
    public static String backend() {
        return backend;
    }

    /** 实际是否跑在显卡上（预热后用 /api/ps 的 size_vram 复核） */
    public static boolean gpuActive() {
        return gpuActive;
    }

    /** 想用 GPU 但显存不足，实际退回了 CPU */
    public static boolean gpuFallback() {
        return gpuFallback;
    }

    /** GPU 状态是否已经确认过；false = 模型还没驻留，别急着显示「CPU」 */
    public static boolean gpuConfirmed() {
        return gpuConfirmed;
    }

    /** 连续请求超时计数（保留给旧的快速失败判定） */
    private static final java.util.concurrent.atomic.AtomicInteger consecutiveTimeouts =
            new java.util.concurrent.atomic.AtomicInteger();
    /**
     * 滑动窗口超时统计。原来要求「严格连续 6 次」才自愈，但游戏里翻译请求时断时续，
     * 中间夹杂命中缓存 / 术语表的成功调用会把计数清零，于是服务明明卡死了却永远
     * 攒不够 6 次（2026-11-12 日志里就是这样：卡了十几分钟，一条卸载记录都没有）。
     * 改成 30 秒窗口内累计 3 次即触发恢复，成功请求不再抹平整段历史。
     */
    private static final Object timeoutLock = new Object();
    private static long windowStart = 0L;
    private static int windowCount = 0;
    private static final long TIMEOUT_WINDOW_MS = 30_000L;
    private static final int TIMEOUT_TRIGGER = 3;
    private static volatile long lastModelReloadAt = 0L;
    private static final AtomicBoolean reloadingModel = new AtomicBoolean(false);
    /**
     * 卸载模型后的「重新加载窗口」截止时间。
     *
     * <p>实测 1.8B 模型重新装进显存要 11~12 秒（load_duration 11.5s，真正的推理只有
     * 0.08s）。旧版卸载完就撒手不管，把这段加载成本压在玩家的下一个翻译请求上，
     * 而短文本超时只有 6~18 秒 —— 于是「超时 → 攒够 6 次 → 再卸载 → 再加载」
     * 无限循环，日志里就是每隔几分钟一批 request timed out。
     */
    private static volatile long reloadGraceUntil = 0L;

    /** 当前 30 秒窗口内的超时次数：卡住时让请求快速失败，别每条硬等 18~30 秒 */
    public static int timeoutStreak() {
        synchronized (timeoutLock) {
            return System.currentTimeMillis() - windowStart > TIMEOUT_WINDOW_MS ? 0 : windowCount;
        }
    }

    /** 记录一次超时，返回窗口内累计数 */
    private static int bumpTimeout() {
        long now = System.currentTimeMillis();
        synchronized (timeoutLock) {
            if (now - windowStart > TIMEOUT_WINDOW_MS) {
                windowStart = now;
                windowCount = 0;
            }
            return ++windowCount;
        }
    }

    private static void resetTimeouts() {
        synchronized (timeoutLock) {
            windowStart = 0L;
            windowCount = 0;
        }
        consecutiveTimeouts.set(0);
    }

    /** 正在后台重新加载模型：这期间不往队列里塞新请求，直接显示术语表兜底译文 */
    public static boolean inReloadGrace() {
        return state == State.READY && System.currentTimeMillis() < reloadGraceUntil;
    }

    /** 批量任务（例如「重译残留英文」）是否该让路，别和模型恢复抢线程 */
    public static boolean isDegradedForBatch() {
        return inReloadGrace() || timeoutStreak() >= TIMEOUT_TRIGGER;
    }

    /**
     * 翻译请求超时时上报。Ollama 的 /api/tags、/api/ps 仍秒回，但 /api/chat
     * 会一直挂着 —— 实测 CUDA 上下文会死锁，之后所有翻译都超时。
     * 连续 6 次就卸载模型，并**在后台主动把它重新加载回来**，
     * 加载期间客户端不排队，恢复后自动继续翻译。
     */
    public static void reportTimeout() {
        if (state != State.READY) {
            return;
        }
        // 刚卸载过、正在后台加载：这时的超时是预期内的，绝不能累加，
        // 否则加载还没完又攒够 6 次，死循环就是这么来的。
        if (inReloadGrace()) {
            resetTimeouts();
            return;
        }
        int n = bumpTimeout();
        consecutiveTimeouts.set(n);
        if (n < TIMEOUT_TRIGGER) {
            return;
        }
        long now = System.currentTimeMillis();
        // 恢复失败时别等两分钟，45 秒后可以再试一次；成功恢复后窗口会被清零
        if (now - lastModelReloadAt < 45_000L || !reloadingModel.compareAndSet(false, true)) {
            return;
        }
        lastModelReloadAt = now;
        // 先立起保护窗口，再开后台线程；窗口内主流程直接走本地兜底，不再往队列里堆请求
        reloadGraceUntil = now + 90_000L;
        resetTimeouts();
        Thread t = new Thread(() -> {
            boolean recovered = false;
            try {
                // 1) 卸载：让 Ollama 销毁卡死的 llama-server / CUDA 上下文
                String unload = "{\"model\":\"" + modelName() + "\",\"prompt\":\"\","
                        + "\"stream\":false,\"keep_alive\":\"0\",\"options\":{\"num_ctx\":" + numCtx() + "}}";
                HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(
                                URI.create(baseUrl() + "/api/generate"))
                                .header("Content-Type", "application/json")
                                .timeout(Duration.ofSeconds(10))
                                .POST(HttpRequest.BodyPublishers.ofString(unload, StandardCharsets.UTF_8))
                                .build(), HttpResponse.BodyHandlers.ofString());
                LOGGER.info("30 秒内 {} 次请求超时，已卸载模型（HTTP {}），后台重新加载中", n, r.statusCode());

                // 2) 主动预热：把 11~12 秒的加载成本放在后台消化，
                //    而不是让玩家的翻译请求去承担（那样必然又超时一轮）。
                String warm = "{\"model\":\"" + modelName() + "\",\"prompt\":\"ok\","
                        + "\"stream\":false,\"keep_alive\":\"60m\","
                        + "\"options\":{\"num_predict\":1,\"num_ctx\":" + numCtx() + "}}";
                HttpResponse<String> w = HTTP.send(HttpRequest.newBuilder(
                                URI.create(baseUrl() + "/api/generate"))
                                .header("Content-Type", "application/json")
                                .timeout(Duration.ofSeconds(90))
                                .POST(HttpRequest.BodyPublishers.ofString(warm, StandardCharsets.UTF_8))
                                .build(), HttpResponse.BodyHandlers.ofString());
                LOGGER.info("模型重新加载完成（HTTP {}）", w.statusCode());
                recovered = true;

                // 3) 加载完再复核一次是否真在显卡上，修正 CPU/GPU 的超时档位
                try {
                    HttpResponse<String> ps = HTTP.send(HttpRequest.newBuilder(
                                    URI.create(baseUrl() + "/api/ps"))
                            .timeout(Duration.ofSeconds(5)).GET().build(),
                            HttpResponse.BodyHandlers.ofString());
                    if (ps.statusCode() == 200) {
                        gpuActive = ps.body().contains("\"size_vram\":")
                                && !ps.body().contains("\"size_vram\":0");
                    }
                } catch (Exception ignored) {
                    // 复核失败不影响恢复
                }
            } catch (Exception e) {
                LOGGER.warn("自动重载模型失败: {}", e.toString());
            } finally {
                if (recovered) {
                    // 加载完成立刻放行，别让玩家白等满 90 秒
                    reloadGraceUntil = 0L;
                } else {
                    // 服务端整个堵死时连 /api/generate 卸载请求都排不上队。
                    // 给 15 秒静默期：客户端停发请求、只走本地术语兜底，
                    // 让 Ollama 有机会把积压的僵尸请求消化完，再放行一次探测。
                    reloadGraceUntil = System.currentTimeMillis() + 15_000L;
                }
                reloadingModel.set(false);
            }
        }, "tongyongfanyi-model-reload");
        t.setDaemon(true);
        t.start();
    }

    /** 只要有一次请求成功就说明服务还活着，清空连续超时计数 */
    public static void reportSuccess() {
        resetTimeouts();
        // 启动时 /api/ps 还空着、没确认到 GPU 的情况：运行中每 15 秒补查一次。
        // 确认后状态行会自己从「GPU 未确认」变成「GPU」，不用重启游戏。
        if (!gpuConfirmed && state == State.READY) {
            long now = System.currentTimeMillis();
            if (now - lastGpuProbeAt >= 15_000L) {
                lastGpuProbeAt = now;
                Thread t = new Thread(BundledRuntime::refreshGpuState,
                        "tongyongfanyi-gpu-probe");
                t.setDaemon(true);
                t.start();
            }
        }
    }

    public static String baseUrl() {
        return "http://127.0.0.1:" + (port.isEmpty() ? BASE_PORT : port);
    }

    // ------------------------------------------------------------------
    // 闲置释放显存
    // ------------------------------------------------------------------

    /**
     * 设置闲置多久释放显存（分钟，0 = 一直常驻）。设置页改完立即生效，不用重启服务。
     */
    public static void setIdleReleaseMinutes(int minutes) {
        idleReleaseMinutes = Math.max(0, Math.min(240, minutes));
        lastActivityAt = System.currentTimeMillis();
        if (idleReleaseMinutes > 0) {
            startIdleWatch();
        }
        LOGGER.info("闲置释放显存：{}", idleReleaseMinutes <= 0 ? "关闭（模型常驻）"
                : idleReleaseMinutes + " 分钟无翻译请求后释放");
    }

    /** 模型是否已被闲置策略卸载（显存已还回去） */
    public static boolean released() {
        return released;
    }

    /**
     * 每次真正发翻译请求前调一次。
     *
     * <p>如果模型刚被闲置释放，这里的请求会让 Ollama 重新加载它（1~2 秒，只发生一次）；
     * 顺手把 released 清掉，状态行立刻恢复成正常的「空闲/翻译中」。
     */
    public static void noteActivity() {
        lastActivityAt = System.currentTimeMillis();
        if (released) {
            released = false;
            LOGGER.info("有新的翻译请求，模型已重新加载");
        }
    }

    private static void startIdleWatch() {
        if (!idleWatchStarted.compareAndSet(false, true)) {
            return;
        }
        Thread t = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(IDLE_POLL_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (state != State.READY || released || idleReleaseMinutes <= 0) {
                    continue;
                }
                long idleMs = System.currentTimeMillis() - lastActivityAt;
                if (idleMs < idleReleaseMinutes * 60_000L) {
                    continue;
                }
                releaseIdle();
            }
        }, "tongyongfanyi-idle-release");
        t.setDaemon(true);
        t.start();
    }

    /**
     * 闲置够久：卸载模型，把 1.6GB 显存还回去。下次翻译时 Ollama 会自动加载。
     *
     * <p>用 {@code keep_alive: 0} 而不是杀进程：ollama 服务还在，端口还占着，
     * 下一次请求不用重新走一遍启动/探测流程。
     */
    private static void releaseIdle() {
        // 清一下再判断：卸载瞬间正好来了请求就别卸了（那一条会白等一次加载）
        if (System.currentTimeMillis() - lastActivityAt < idleReleaseMinutes * 60_000L) {
            return;
        }
        try {
            String body = "{\"model\":\"" + modelName() + "\",\"prompt\":\"\",\"stream\":false,"
                    + "\"keep_alive\":\"0\",\"options\":{\"num_ctx\":" + numCtx() + "}}";
            HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(
                            URI.create(baseUrl() + "/api/generate"))
                            .header("Content-Type", "application/json")
                            .timeout(Duration.ofSeconds(15))
                            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                            .build(), HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() == 200) {
                released = true;
                gpuActive = false;
                // 模型不在驻留表里了，显存状态要等下次加载完重新确认
                gpuConfirmed = false;
                LOGGER.info("闲置 {} 分钟，已释放模型显存（下次翻译会自动加载）", idleReleaseMinutes);
            } else {
                LOGGER.warn("闲置释放显存失败: HTTP {}", r.statusCode());
            }
        } catch (Exception e) {
            LOGGER.warn("闲置释放显存失败: {}", e.toString());
        }
    }

    /** 内置模型在 Ollama 里的名字，来自 jar 内的 model.properties */
    public static String modelName() {
        return loadModelProps().getProperty("name", "qwen2.5:0.5b");
    }

    /** API 模式下的模型名（HUD/设置页显示）；本地模式为空 */
    private static volatile String apiModelLabel = "";

    public static boolean apiBackend() {
        return apiModelLabel != null && !apiModelLabel.isEmpty();
    }

    /** 界面上显示的模型名 */
    public static String modelLabel() {
        String api = apiModelLabel;
        if (api != null && !api.isEmpty()) {
            return "API · " + api;
        }
        return loadModelProps().getProperty("label", "内置模型");
    }

    /** 是否能用上模型：代码包自带 payload（旧单包）或 mods 里有通用模型包 */
    public static boolean payloadPresent() {
        return selfPayloadPresent() || ModelPacks.active() != null;
    }

    /** 代码包自己是否带 payload（旧单包模式；拆包后为 false） */
    private static boolean selfPayloadPresent() {
        return BundledRuntime.class.getClassLoader().getResource(PAYLOAD) != null;
    }

    /** 打开 payload 所在的 zip：优先代码包自身，其次 mods 里的通用模型包 */
    private static ZipFile openPayloadZip() throws IOException {
        if (selfPayloadPresent()) {
            URL self = BundledRuntime.class.getProtectionDomain().getCodeSource() == null
                    ? null : BundledRuntime.class.getProtectionDomain().getCodeSource().getLocation();
            if (self == null || !self.getPath().endsWith(".jar")) {
                throw new IOException("开发环境没有 payload.zip，请用构建出的 jar 运行");
            }
            try {
                return new ZipFile(Path.of(self.toURI()).toFile());
            } catch (java.net.URISyntaxException e) {
                throw new IOException("无法定位模组 jar: " + self, e);
            }
        }
        ModelPacks.Pack pack = ModelPacks.active();
        if (pack == null) {
            throw new IOException("没有找到模型包：请把 tongyongfanyi-model-*.zip 放进 mods/ 目录");
        }
        return ModelPacks.open(pack);
    }

    /** 本机是否有可用的显卡加速（用于决定设置页要不要显示 GPU 选项） */
    public static boolean gpuAvailable() {
        // 只有 Windows 版 Ollama 带 Vulkan/NVML 后端。Linux 要单独装 ROCm/CUDA 版，
        // Android 官方版是纯 CPU —— 在这两种平台上说「能用 GPU」只会让玩家
        // 选了「GPU」却还是跑在 CPU 上，所以直接返回 false。
        if (!RuntimePacks.isWindows()) {
            return false;
        }
        String sysRoot = System.getenv("SystemRoot");
        if (sysRoot != null) {
            if (Files.isRegularFile(Path.of(sysRoot, "System32", "vulkan-1.dll"))) {
                return true;   // 有 Vulkan 加载器（N/A/Intel 驱动都会装）
            }
            if (Files.isRegularFile(Path.of(sysRoot, "System32", "nvml.dll"))) {
                return true;   // 装了 N 卡驱动
            }
        }
        String path = System.getenv("PATH");
        if (path != null) {
            for (String dir : path.split(";")) {
                if (dir.isBlank()) {
                    continue;
                }
                try {
                    if (Files.isRegularFile(Path.of(dir, "nvidia-smi.exe"))) {
                        return true;
                    }
                } catch (RuntimeException ignored) {
                    // 路径非法就跳过
                }
            }
        }
        return false;
    }

    /** 设置页顶部那行状态文字 */
    public static String statusLine() {
        switch (state) {
            case READY -> {
                String where = backend.isBlank() ? "本地服务" : backend;
                // 没确认到 GPU 时既不写死「CPU」，也不说「显存不足」：刚启动时
                // /api/ps 往往还空着，模型其实已经在显卡上跑了（日志 offloaded 33/33）。
                String dev = gpuActive ? "GPU" : (gpuConfirmed ? "CPU" : "CPU（GPU 未确认）");
                String line = gpuFallback
                        ? "§a就绪 §7| " + where + " · §e模型跑在 CPU 上 §7· " + modelLabel()
                        : "§a就绪 §7| " + where + " · " + dev + " · " + modelLabel();
                // 最近一分钟出过错就挂在状态行后面：API 模式下 state 一直是 READY，
                // 不这样玩家根本看不出「在线接口其实一直在失败」。
                if (!lastError.isBlank() && System.currentTimeMillis() - lastErrorAt < 60_000L) {
                    return line + " §c| " + lastError;
                }
                return line;
            }
            case PREPARING -> {
                return "§e" + status + (percent >= 0 ? " " + percent + "%" : "");
            }
            case FAILED -> {
                return "§c启动失败: " + error;
            }
            default -> {
                return "§7未启动";
            }
        }
    }

    // ------------------------------------------------------------------
    // 生命周期
    // ------------------------------------------------------------------

    /** 客户端初始化时调用（异步，不阻塞启动） */
    public static void init(ModConfig config) {
        if (!inited.compareAndSet(false, true)) {
            return;
        }
        Thread t = new Thread(() -> boot(config), "tongyongfanyi-runtime");
        t.setDaemon(true);
        t.start();
    }

    /** 切换 CPU/GPU 后重启服务 */
    /**
     * 设置页里改「设备」或「线程数」都要重启服务才生效。
     * 以前每次点击都直接开一个线程，快速连点会同时跑好几路 shutdown+boot，
     * 互相清孤儿进程、抢端口，结果反而是「设置改了但服务没按新值起来」。
     * 现在串行化：重启期间的新请求只记下最新配置，由同一个线程接着处理。
     */
    public static void restart(ModConfig config) {
        synchronized (restartLock) {
            pendingRestartConfig = config;
            if (!restarting.compareAndSet(false, true)) {
                return;
            }
        }
        Thread t = new Thread(() -> {
            try {
                while (true) {
                    ModConfig cfg;
                    synchronized (restartLock) {
                        cfg = pendingRestartConfig;
                        pendingRestartConfig = null;
                    }
                    if (cfg == null) {
                        break;
                    }
                    shutdown();
                    inited.set(true);
                    boot(cfg);
                }
            } finally {
                restarting.set(false);
            }
        }, "tongyongfanyi-runtime-restart");
        t.setDaemon(true);
        t.start();
    }

    /** 游戏退出时调用 */
    public static void shutdown() {
        Process p = process;
        process = null;
        killTree(p);
        state = State.IDLE;
        status = "已停止";
    }

    /**
     * 连子进程一起杀。
     *
     * <p>ollama 会再拉起一个 {@code llama-server.exe} 真正干活，Windows 上
     * {@code Process.destroy()} 只杀父进程，子进程会留下来继续占显存。
     * 之前"起了 16 个 llama-server 一个都没关掉"就是这么来的。
     */
    private static void killTree(Process p) {
        if (p == null) {
            return;
        }
        List<ProcessHandle> kids = p.descendants().toList();
        kids.forEach(ProcessHandle::destroy);
        p.destroy();
        try {
            if (!p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) {
                p.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            p.destroyForcibly();
        }
        // 父进程退出后子进程可能还在，强制清一遍
        kids.forEach(h -> {
            if (h.isAlive()) {
                h.destroyForcibly();
            }
        });
    }

    /**
     * 清掉以前遗留的孤儿 llama-server（只认命令行里带我们模型目录的那些，
     * 不会误杀玩家自己开的 ollama）。
     */
    public static int cleanupOrphans() {
        // 这套清理是 Windows 专用的（PowerShell + Win32_Process）。
        // Android/Linux 上进程随父进程一起退出，没有这个需求，
        // 也不该在 API 模式启动时白跑一次 powershell 再报一条 warn。
        if (!"windows".equals(RuntimePacks.currentPlatform())) {
            return 0;
        }
        try {
            Process p = new ProcessBuilder("powershell", "-NoProfile", "-Command",
                    "Get-CimInstance Win32_Process -Filter \"Name='llama-server.exe'\" | "
                            + "Where-Object { $_.CommandLine -like '*tongyongfanyi*' } | "
                            + "ForEach-Object { Stop-Process -Id $_.ProcessId -Force; $_.ProcessId }")
                    .redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            p.waitFor(15, java.util.concurrent.TimeUnit.SECONDS);
            return (int) out.lines().filter(l -> l.trim().matches("\\d+")).count();
        } catch (Exception e) {
            LOGGER.warn("清理孤儿进程失败: {}", e.toString());
            return -1;
        }
    }

    private static void boot(ModConfig config) {
        try {
            // 在线 API 模式：不启动任何本地进程，也不解包模型。
            // 状态行、HUD 只需要知道「现在是 API 模式，用的是哪个模型」。
            if ("api".equalsIgnoreCase(config.translateBackend)) {
                // 但必须先把本地推理彻底停掉：
                // 1) 从「本地」切过来时，模组自己拉起的 ollama 还活着；
                // 2) 配置启动时就是 API 时，上一次异常退出可能留下 llama-server，
                //    它照样占着显存，而 API 模式完全用不到它。
                // shutdown() 只杀本模组启动的进程树，cleanupOrphans() 只认
                // 命令行里带 tongyongfanyi 模型路径的孤儿，都不会动玩家自己的 Ollama。
                shutdown();
                cleanupOrphans();
                apiModelLabel = config.apiModel == null || config.apiModel.isBlank()
                        ? "API" : config.apiModel;
                state = State.READY;
                backend = "在线 API";
                status = "API 模式";
                error = "";
                gpuActive = false;
                gpuFallback = false;
                released = false;
                clearError();
                LOGGER.info("翻译后端：在线 API（{} / {}），本地推理已停止",
                        config.apiBaseUrl, apiModelLabel);
                return;
            }
            apiModelLabel = "";
            // 先确定用哪个模型包（mods 里的通用 zip），再清掉上一次的模型名缓存：
            // 设置页切换模型包后要重启服务，模型名/payload 来源都必须跟着换。
            ModelPacks.init(config);
            modelProps = null;
            state = State.PREPARING;
            error = "";
            gpuActive = false;
            gpuFallback = false;
            // 已经有 Ollama 在跑（桌面常驻服务 / 手机 Termux）就直接用，不再起第二个。
            // 这一步在 cleanupOrphans 之前：如果是别人的服务，它的 llama-server 不能被杀。
            String running = probeRunningOllama();
            if (running != null) {
                port = running;
                backend = "本机 Ollama（已运行）";
                state = State.READY;
                percent = -1;
                status = "就绪";
                error = "";
                gpuActive = false;
                gpuFallback = false;
                released = false;
                clearError();
                lastDevice = config.device == null ? "auto" : config.device;
                // 不是我们拉起的进程，不能去释放它的显存，也不启动闲置看门狗
                LOGGER.info("复用已在运行的本机 Ollama：端口 {} | 模型 {}", port, modelName());
                return;
            }
            // 上次异常退出可能留下孤儿 llama-server（占着显存和端口），先扫一遍
            cleanupOrphans();
            percent = -1;

            Path root = FabricLoader.getInstance().getGameDir().resolve("tongyongfanyi");
            Files.createDirectories(root);

            // 强制内置运行时：完全不探测本机 Ollama，直接解运行时包。
            // 拆包后代码包不再自带 Ollama，本机装的那份和运行时包是「二选一」，
            // 设置页的「运行时」就是用来锁定这个选择的。
            boolean forceBundled = "bundled".equalsIgnoreCase(config.runtimeMode);
            Path local = forceBundled ? null : findLocalOllama();
            boolean useLocal = local != null && supportsBundledArch(local);
            ModelPacks.Pack pack = ModelPacks.active();
            boolean hasPayload = selfPayloadPresent() || (pack != null && pack.hasPayload());
            Path exe;
            Path models;
            if (!hasPayload) {
                // 模型包里只有模型名、没有权重（玩家自己 ollama pull 的模型）：
                // 直接复用本机 Ollama 的模型目录，不解压也不复制。
                if (local == null) {
                    throw new IOException(forceBundled
                            ? "模型包只引用了本机模型 " + modelName() + "，但「运行时」被设为内置。"
                            + "内置运行时读不到本机 Ollama 的模型目录，请改成「自动」，"
                            + "或换成带权重的模型包。"
                            : "模型包只引用了本机模型 " + modelName()
                            + "，但没有找到可用的 Ollama。请先安装 Ollama，或改用带权重的模型包。");
                }
                backend = "本机 Ollama（模型包引用）";
                exe = local;
                models = localModelsDir();
                if (!Files.isDirectory(models)) {
                    throw new IOException("本机 Ollama 模型目录不存在: " + models);
                }
                status = "正在启动本机模型服务…";
            } else if (useLocal) {
                backend = "本机 Ollama";
                exe = local;
                status = "正在解出内置模型…";
                // 本机 Ollama 已经在了，只需要把模型铺进我们自己的模型目录
                extractModels(root, pack);
                models = root.resolve("models");
            } else {
                backend = "内置运行时（" + RuntimePacks.currentKey() + "）";
                status = "正在解包内置运行时…";
                extractRuntime(root);
                // 可执行文件名跟平台走：Windows 是 ollama.exe，Android/Linux 是 ollama
                exe = root.resolve("ollama").resolve(RuntimePacks.exeName());
                if (!Files.isRegularFile(exe)) {
                    throw new IOException("运行时解包不完整，缺少 " + RuntimePacks.exeName()
                            + "（平台 " + RuntimePacks.currentKey() + "）");
                }
                extractModels(root, pack);
                models = root.resolve("models");
            }
            Files.createDirectories(models);

            port = String.valueOf(pickPort());
            status = "正在启动模型服务…";
            startServer(exe, models, port, config);

            status = "正在加载模型 " + modelLabel() + "…";
            if (!waitReady(Duration.ofSeconds(90))) {
                throw new IOException("模型服务启动超时（端口 " + port + "）");
            }
            // 服务起来了也不代表模型认得出来：模型目录空 / 名字对不上时 Ollama 只会在
            // /api/chat 上回 404，玩家看到的就是「翻译没反应」，所以这里先自己查一遍。
            verifyModelVisible();
            lastDevice = config.device == null ? "auto" : config.device;
            warmUp();
            // 想用 GPU 但显存不够时，llama.cpp 会静默把层数降到 0（甚至 CUDA 崩掉），
            // 玩家只会觉得"怎么这么慢"。这里记下来，在状态行和 doctor 里明说。
            // 但只有「确认过」才敢下这个结论：刚启动时 /api/ps 还空着，
            // 旧逻辑会把「查不到」当成「不在 GPU 上」，于是误报显存不足。
            gpuFallback = gpuConfirmed && !gpuActive && !"cpu".equals(lastDevice) && gpuAvailable();

            state = State.READY;
            percent = -1;
            status = "就绪";
            clearError();
            // 闲置释放显存：刚加载完肯定不算闲置，从这一刻重新计时
            released = false;
            lastActivityAt = System.currentTimeMillis();
            idleReleaseMinutes = Math.max(0, Math.min(240, config.idleReleaseMinutes));
            if (idleReleaseMinutes > 0) {
                startIdleWatch();
            }
            LOGGER.info("本地翻译服务就绪：{} | {} | 端口 {} | 模型 {}",
                    backend, gpuActive ? "GPU" : "CPU", port, modelName());
        } catch (Throwable t) {
            state = State.FAILED;
            error = String.valueOf(t.getMessage());
            percent = -1;
            status = "启动失败";
            // 启动失败必须让玩家看见原因（缺运行时包 / 模型包对不上 / 端口占用…），
            // 只写日志的话玩家只会觉得「翻译没反应」。
            reportError("启动失败：" + error);
            LOGGER.error("本地翻译服务启动失败: {}", t.toString(), t);
        }
    }

    // ------------------------------------------------------------------
    // 解包
    // ------------------------------------------------------------------

    /**
     * 解出 Ollama 运行时。来源优先级：
     * <ol>
     *   <li>{@code mods/} 里匹配当前平台的运行时包（新拆分模式，跨平台靠它）；</li>
     *   <li>代码包内置的 {@code runtime.zip}（{@code -PembedRuntime=true} 打的老包）；</li>
     *   <li>旧单包的 {@code payload.zip} 里的 {@code ollama/}。</li>
     * </ol>
     * 标记里带运行时包 id + 平台：同一台机器换运行时包（比如 x64 → arm64 模拟器）
     * 必须重新解，否则会拿旧平台的二进制去跑。
     */
    private static void extractRuntime(Path root) throws IOException {
        Path marker = root.resolve(RUNTIME_MARKER);
        RuntimePacks.Pack pack = RuntimePacks.match();
        // 标记**不再带代码包版本**：运行时包的二进制跟 MC 版本无关，
        // 以前写 modVersion() 会导致每次更新代码包（哪怕只改一行）都重解 122 MB。
        // 现在只跟「运行时包这个文件本身」走：换包 / 换版本 / 换平台才重解。
        String want = pack != null
                ? "rt-" + pack.id() + "-" + pack.zip().toFile().length()
                + "-" + pack.zip().toFile().lastModified()
                : "jar-" + RuntimePacks.currentKey() + "-" + modVersion();
        if (markerMatches(marker, want)) {
            return;
        }
        // 换平台/换包时旧二进制不能留：Windows 的 ollama.exe 和 Android 的 ollama
        // 文件名不同，混在一个目录里会让 exe 检查通过但跑不起来。
        deleteTree(root.resolve("ollama"));
        if (pack != null) {
            try (ZipFile z = RuntimePacks.open(pack)) {
                unzipStreamEntry(z, RuntimePacks.RUNTIME_ZIP, "ollama/", root);
            }
            LOGGER.info("已解出 Ollama 运行时: {} [{}] -> {}", pack.label(), pack.key(),
                    pack.zip().getFileName());
        } else if (resourceExists(RUNTIME)) {
            unzipJarEntry(RUNTIME, "ollama/", root);
            LOGGER.info("已解出代码包内置的 Ollama 运行时");
        } else if (selfPayloadPresent()) {
            unzipJarEntry(PAYLOAD, "ollama/", root);
        } else {
            throw new IOException("找不到当前平台的 Ollama 运行时（" + RuntimePacks.currentKey()
                    + "）：请把 tongyongfanyi-runtime-" + RuntimePacks.currentKey()
                    + ".zip 放进 mods/ 目录，或安装本机 Ollama");
        }
        RuntimePacks.markExecutable(root.resolve("ollama").resolve(RuntimePacks.exeName()));
        Files.writeString(marker, want, StandardCharsets.UTF_8);
    }

    /** 删目录树（解包前清旧运行时用）。不存在时静默返回。 */
    private static void deleteTree(Path dir) {
        if (!Files.exists(dir)) {
            return;
        }
        try (java.util.stream.Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // 文件被占用（比如上一次的 ollama 还没退干净）就留着，
                    // 解包会逐条覆盖，不影响本次启动
                }
            });
        } catch (IOException e) {
            LOGGER.warn("清理旧运行时目录 {} 失败: {}", dir, e.toString());
        }
    }

    /**
     * 解出模型。新模型包读 {@code models.zip}，旧单包读 {@code payload.zip} 的 models/。
     * 标记带模型名：同版本换模型包必须重新解。
     */
    private static void extractModels(Path root, ModelPacks.Pack pack) throws IOException {
        Path marker = root.resolve(MODELS_MARKER);
        // 同理不能带 modVersion()：模型包动辄 1.2 GB，换一次代码包就重解一次太亏。
        // 只认「模型名 + 模型包文件指纹」—— 换模型包（哪怕同名不同文件）才会重解。
        String want = pack != null
                ? "md-" + modelName() + "-" + pack.zip().toFile().length()
                + "-" + pack.zip().toFile().lastModified()
                : modVersion() + "-" + modelName() + "-models";
        if (markerMatches(marker, want)) {
            return;
        }
        boolean done = false;
        if (pack != null && pack.hasModelsZip()) {
            try (ZipFile z = ModelPacks.open(pack)) {
                unzipStreamEntry(z, MODELS_ZIP, null, root);
                done = true;
            }
        }
        if (!done && selfPayloadPresent()) {
            unzipJarEntry(PAYLOAD, "models/", root);
            done = true;
        }
        if (!done) {
            throw new IOException("模型包里缺少 " + MODELS_ZIP + "（"
                    + (pack == null ? "没有找到模型包" : pack.label()) + "）");
        }
        Files.writeString(marker, want, StandardCharsets.UTF_8);
        LOGGER.info("已解出内置模型: {}", modelName());
    }

    private static String modVersion() {
        return FabricLoader.getInstance().getModContainer("tongyongfanyi")
                .map(c -> c.getMetadata().getVersion().getFriendlyString())
                .orElse("dev");
    }

    private static boolean markerMatches(Path marker, String want) {
        try {
            return Files.isRegularFile(marker)
                    && want.equals(Files.readString(marker, StandardCharsets.UTF_8).trim());
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean resourceExists(String name) {
        return BundledRuntime.class.getClassLoader().getResource(name) != null;
    }

    /** 从代码包 jar 里读一个 zip 条目，按 prefix 过滤后解到 root */
    private static void unzipJarEntry(String entry, String prefix, Path root) throws IOException {
        URL self = BundledRuntime.class.getProtectionDomain().getCodeSource() == null
                ? null : BundledRuntime.class.getProtectionDomain().getCodeSource().getLocation();
        if (self == null || !self.getPath().endsWith(".jar")) {
            throw new IOException("开发环境没有 " + entry + "，请用构建出的 jar 运行");
        }
        try (ZipFile jar = new ZipFile(Path.of(self.toURI()).toFile())) {
            unzipStreamEntry(jar, entry, prefix, root);
        } catch (java.net.URISyntaxException e) {
            throw new IOException("无法定位模组 jar: " + self, e);
        }
    }

    /** 从已打开的 zip 里取 entry（它本身是一个 zip），按 prefix 过滤后解到 root */
    private static void unzipStreamEntry(ZipFile source, String entry, String prefix, Path root) throws IOException {
        ZipEntry payloadEntry = source.getEntry(entry);
        if (payloadEntry == null) {
            throw new IOException("缺少 " + entry);
        }
        long total = Math.max(1L, payloadEntry.getSize());
        CountingInputStream counting = new CountingInputStream(
                new BufferedInputStream(source.getInputStream(payloadEntry)));
        try (ZipInputStream zip = new ZipInputStream(counting)) {
            ZipEntry e;
            while ((e = zip.getNextEntry()) != null) {
                String name = e.getName();
                if (prefix != null && !name.startsWith(prefix)) {
                    // 跳过不要的部分，但要读掉数据，否则 zip 流会卡住
                    zip.transferTo(OutputStream.nullOutputStream());
                    percent = (int) Math.min(99, counting.count() * 100 / total);
                    continue;
                }
                if (e.isDirectory()) {
                    Files.createDirectories(root.resolve(name));
                    continue;
                }
                Path target = root.resolve(name);
                Files.createDirectories(target.getParent());
                Path tmp = target.resolveSibling(target.getFileName() + ".part");
                try (OutputStream out = Files.newOutputStream(tmp)) {
                    zip.transferTo(out);
                }
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
                percent = (int) Math.min(99, counting.count() * 100 / total);
            }
        }
    }

    /**
     * 本机 Ollama 能不能跑内置模型。
     * qwen3.5 需要 0.30+；版本太旧的直接弃用，改走内置运行时，免得玩家看到一堆 404。
     */
    private static boolean supportsBundledArch(Path exe) {
        try {
            Process p = new ProcessBuilder(exe.toString(), "--version").redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!p.waitFor(6, java.util.concurrent.TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return false;
            }
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("(\\d+)\\.(\\d+)\\.(\\d+)").matcher(out);
            if (!m.find()) {
                return false;
            }
            int major = Integer.parseInt(m.group(1));
            int minor = Integer.parseInt(m.group(2));
            boolean ok = major > 0 || minor >= 30;
            if (!ok) {
                LOGGER.warn("本机 Ollama 版本过旧（{}），改用内置运行时", out.trim());
            }
            return ok;
        } catch (Exception e) {
            LOGGER.warn("检测本机 Ollama 版本失败: {}，改用内置运行时", e.toString());
            return false;
        }
    }

    /** 启动后确认模型真的被服务识别到，否则给出能看懂的错误而不是让翻译静默失败 */
    private static void verifyModelVisible() throws IOException {
        try {
            HttpResponse<String> r = HTTP.send(
                    HttpRequest.newBuilder(URI.create(baseUrl() + "/api/tags"))
                            .timeout(Duration.ofSeconds(10)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() == 200 && r.body().contains("\"" + modelName() + "\"")) {
                return;
            }
            throw new IOException("模型 " + modelName() + " 没有被本地服务识别（模型目录可能不完整）");
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("检查模型列表失败: " + e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------------
    // 进程
    // ------------------------------------------------------------------

    /** 按顺序找本机 Ollama；找不到返回 null */
    /**
     * 探测本机是否已经有 Ollama 服务在跑、而且认识我们要用的模型。
     *
     * <p>为什么需要这个：
     * <ul>
     *   <li>手机上没法保证能 exec 自己解压出来的二进制（Android 10+ 的 W^X 限制），
     *       玩家在 Termux 里 {@code ollama serve} 是唯一稳定可行的本地推理方式；</li>
     *   <li>桌面装了 Ollama 并设了开机自启时，没必要再起一个（端口、显存都要抢）。</li>
     * </ul>
     *
     * <p>只认 {@code /api/tags} 里真的列出了目标模型的服务 —— 否则宁可自己启动，
     * 也不能把请求发给一个没有该模型的服务（那只会得到 404）。
     *
     * @return 可用服务的端口字符串；没有则 null
     */
    private static String probeRunningOllama() {
        String want = modelName();
        if (want == null || want.isBlank()) {
            return null;
        }
        String[] ports = {"11434", "11435"};
        for (String p : ports) {
            try {
                HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(
                                URI.create("http://127.0.0.1:" + p + "/api/tags"))
                        .timeout(Duration.ofMillis(900))
                        .GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                if (r.statusCode() == 200 && r.body().contains("\"" + want + "\"")) {
                    return p;
                }
            } catch (Exception ignored) {
                // 端口没服务 / 超时：试下一个
            }
        }
        return null;
    }

    private static Path findLocalOllama() {
        if (RuntimePacks.isWindows()) {
            return findLocalOllamaWindows();
        }
        return findLocalOllamaUnix();
    }

    private static Path findLocalOllamaWindows() {
        String localAppData = System.getenv("LOCALAPPDATA");
        if (localAppData != null) {
            Path p = Path.of(localAppData, "Programs", "Ollama", "ollama.exe");
            if (Files.isRegularFile(p)) {
                return p;
            }
        }
        String path = System.getenv("PATH");
        if (path != null) {
            for (String dir : path.split(";")) {
                if (dir.isBlank()) {
                    continue;
                }
                try {
                    Path p = Path.of(dir, "ollama.exe");
                    if (Files.isRegularFile(p)) {
                        return p;
                    }
                } catch (RuntimeException ignored) {
                    // 跳过非法路径
                }
            }
        }
        return null;
    }

    /**
     * Linux / Android / macOS 上找 ollama 可执行文件。
     *
     * <p>Android 上真实可用的那个几乎只会在 Termux 里：官方 Ollama 的 Android 版
     * 就是 Linux ARM64 二进制，装在 Termux 的 {@code $PREFIX/bin}。
     * 从 app 私有目录解压出来的二进制在 Android 10+ 会因为 W^X 限制 exec 失败，
     * 所以我们优先找 Termux 那个，其次才是 PATH。
     */
    private static Path findLocalOllamaUnix() {
        java.util.List<String> candidates = new java.util.ArrayList<>();
        if (RuntimePacks.isAndroid()) {
            // Termux 的默认前缀；从 FCL 的进程里读不到 Termux 的环境变量，
            // 但这几个路径是固定的
            candidates.add("/data/data/com.termux/files/usr/bin/ollama");
            String prefix = System.getenv("PREFIX");
            if (prefix != null && !prefix.isBlank()) {
                candidates.add(prefix + "/bin/ollama");
            }
        } else {
            candidates.add("/usr/local/bin/ollama");
            candidates.add("/usr/bin/ollama");
            candidates.add("/opt/homebrew/bin/ollama");   // macOS Apple Silicon
        }
        for (String c : candidates) {
            try {
                Path p = Path.of(c);
                if (Files.isRegularFile(p)) {
                    return p;
                }
            } catch (RuntimeException ignored) {
                // 非法路径跳过
            }
        }
        String path = System.getenv("PATH");
        if (path != null) {
            for (String dir : path.split(java.io.File.pathSeparator)) {
                if (dir.isBlank()) {
                    continue;
                }
                try {
                    Path p = Path.of(dir, "ollama");
                    if (Files.isRegularFile(p)) {
                        return p;
                    }
                } catch (RuntimeException ignored) {
                    // 跳过非法路径
                }
            }
        }
        return null;
    }

    /**
     * 本机 Ollama 的模型目录。模型包只写模型名（没有权重）时直接读这里，
     * 玩家用 {@code ollama pull} 下的模型不用再复制一份。
     */
    private static Path localModelsDir() {
        String env = System.getenv("OLLAMA_MODELS");
        if (env != null && !env.isBlank()) {
            return Path.of(env);
        }
        String home = System.getProperty("user.home");
        if (home == null || home.isBlank() || "/".equals(home)) {
            // Android 上 user.home 常常是 "/" 或空，指过去会对着一整个根目录扫，
            // 直接落到我们自己的目录里
            return FabricLoader.getInstance().getGameDir()
                    .resolve("tongyongfanyi").resolve("models");
        }
        return Path.of(home, ".ollama", "models");
    }

    /** 找一个没被占用的端口 */
    private static int pickPort() {
        for (int i = 0; i < PORT_TRIES; i++) {
            int candidate = BASE_PORT + i;
            try (ServerSocket ignored = new ServerSocket(candidate, 1,
                    java.net.InetAddress.getByName("127.0.0.1"))) {
                return candidate;
            } catch (IOException e) {
                // 被占用，试下一个
            }
        }
        return BASE_PORT;
    }

    private static void startServer(Path exe, Path models, String port, ModConfig config) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(exe.toString(), "serve");
        pb.directory(exe.getParent().toFile());
        pb.redirectErrorStream(true);
        Path log = FabricLoader.getInstance().getGameDir().resolve("tongyongfanyi").resolve("ollama.log");
        // Ollama 会给每个请求打一行访问日志，玩久了能涨到几十 MB；超过 8MB 就重开一份
        try {
            if (Files.isRegularFile(log) && Files.size(log) > 8L * 1024 * 1024) {
                Files.delete(log);
            }
        } catch (IOException ignored) {
            // 删不掉就让追加继续，不影响功能
        }
        pb.redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()));

        var env = pb.environment();
        env.put("OLLAMA_MODELS", models.toString());
        env.put("OLLAMA_HOST", "127.0.0.1:" + port);
        env.put("OLLAMA_KEEP_ALIVE", "60m");
        env.put("OLLAMA_NOPRUNE", "1");     // 别清理我们铺进去的模型
        // 服务端并行数固定为 1，不再跟着「翻译线程」设置走。
        // 实测（2026-11-12）：设成 4 时，客户端 4 线程并发发请求，超时后连接进入
        // CLOSE_WAIT 却仍排在 Ollama 的推理队列里，越堆越多，最后调度器状态卡死 ——
        // /api/ps 仍谎报「模型在显存里」，但任何 /api/chat 都永不返回，连杀掉
        // llama-server 都没用。1.8B 在 GPU 上单次推理只要 0.2 秒，串行完全够用，
        // 翻译线程继续用于队列调度 / 术语预处理，不要再开服务端并行槽。
        env.put("OLLAMA_NUM_PARALLEL", "1");
        // 排队上限：客户端超时后残留的僵尸请求不能无限堆，超出的直接被服务端拒掉，
        // 这样即使偶发卡住，队列也能自己消化完，不会拖成整局游戏都翻不出来。
        env.put("OLLAMA_MAX_QUEUE", "8");
        // 允许 flash attention 省 KV（不支持的模型会自动忽略）
        env.put("OLLAMA_FLASH_ATTENTION", "1");
        if (RuntimePacks.isWindows()) {
            if ("cpu".equals(config.device)) {
                // 强制走 CPU：指定 cpu 库，并屏蔽各家的显卡后端
                env.put("OLLAMA_LLM_LIBRARY", "cpu");
                env.put("OLLAMA_VULKAN", "false");
                env.put("CUDA_VISIBLE_DEVICES", "");
                env.put("HIP_VISIBLE_DEVICES", "");
            } else {
                env.put("OLLAMA_VULKAN", "true");
            }
        } else {
            // Linux / Android / macOS：Ollama 靠 LD_LIBRARY_PATH 找 lib/ollama/*.so，
            // 不设的话启动就报找不到 libggml。macOS 用 DYLD_ 前缀，两个都设上不冲突。
            Path base = exe.getParent();
            Path libs = base.resolve("lib").resolve("ollama");
            String libPath = base + ":" + libs;
            env.put("LD_LIBRARY_PATH", libPath);
            env.put("DYLD_LIBRARY_PATH", libPath);
            // 需要一个可写的 HOME/TMPDIR：Android 上系统临时目录往往不可写，
            // Ollama 会把临时文件写到 TMPDIR，失败就直接起不来
            Path runtime = FabricLoader.getInstance().getGameDir().resolve("tongyongfanyi");
            Path tmp = runtime.resolve("tmp");
            Files.createDirectories(tmp);
            env.put("HOME", runtime.toString());
            env.put("TMPDIR", tmp.toString());
            env.put("TMP", tmp.toString());
            // 官方 Android/Linux 版没有 Vulkan 后端，开只会拖慢初始化
            env.put("OLLAMA_VULKAN", "false");
        }

        process = pb.start();
        LOGGER.info("已启动模型服务: {} provide port {}", exe, port);
    }

    private static boolean waitReady(Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            Process p = process;
            if (p != null && !p.isAlive()) {
                return false;
            }
            try {
                HttpResponse<String> r = HTTP.send(
                        HttpRequest.newBuilder(URI.create(baseUrl() + "/api/version"))
                                .timeout(Duration.ofSeconds(2)).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                if (r.statusCode() == 200) {
                    return true;
                }
            } catch (Exception ignored) {
                // 还没起来，继续等
            }
            sleep(500);
        }
        return false;
    }

    /** 预热 + 复核是否真的用上了显卡 */
    private static void warmUp() {
        // 预热就必须按翻译请求的参数加载（num_ctx 一致），否则预热出来的 runner
        // 和第一条真实翻译的配置不匹配，Ollama 会再加载一次 —— 启动后第一句要等 3 秒就是这么来的。
        String body = "{\"model\":\"" + modelName() + "\",\"prompt\":\"ok\",\"stream\":false,"
                + "\"keep_alive\":\"60m\",\"options\":{\"num_predict\":1,\"num_ctx\":" + numCtx() + "}}";
        boolean warmed = false;
        try {
            HTTP.send(HttpRequest.newBuilder(URI.create(baseUrl() + "/api/generate"))
                            .header("Content-Type", "application/json")
                            .timeout(Duration.ofSeconds(120))
                            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build(),
                    HttpResponse.BodyHandlers.ofString());
            warmed = true;
        } catch (Exception e) {
            LOGGER.warn("预热失败: {}", e.toString());
            // 预热失败多半只是首次加载慢/服务刚起，不能据此断言「显存不足」。
            // 保持 gpuConfirmed=false（未知），运行中由 reportSuccess 异步复核。
        }
        if (warmed) {
            // /api/ps 在 generate 返回的瞬间经常还是 {"models":[]}（Ollama 还没更新
            // 驻留表）。以前只查一次，空列表就被当成「不在 GPU 上」，于是状态行误报
            // 「显存不足，已退回 CPU」，而 llama-server 日志里是 offloaded 33/33 layers。
            for (int i = 0; i < 3 && !gpuConfirmed; i++) {
                if (refreshGpuState()) {
                    break;
                }
                sleep(300);
            }
        }
    }

    /**
     * 读 /api/ps 复核模型是否在显存里。
     *
     * @return true = 拿到了明确结论（gpuActive 已更新）；false = 模型还没驻留或查询失败，
     *         状态仍未知，调用方不能据此说「显存不足」
     */
    private static boolean refreshGpuState() {
        try {
            HttpResponse<String> r = HTTP.send(
                    HttpRequest.newBuilder(URI.create(baseUrl() + "/api/ps"))
                            .timeout(Duration.ofSeconds(5)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() != 200) {
                return false;
            }
            Boolean onGpu = parseGpuState(r.body());
            if (onGpu == null) {
                LOGGER.info("GPU 状态暂未确认（模型尚未驻留）: {}", trimBody(r.body()));
                return false;
            }
            gpuActive = onGpu;
            gpuConfirmed = true;
            gpuFallback = !onGpu && !"cpu".equals(lastDevice);
            LOGGER.info("GPU 状态复核: {} -> {}", onGpu ? "size_vram>0" : "size_vram=0",
                    onGpu ? "GPU" : "CPU");
            return true;
        } catch (Exception e) {
            LOGGER.warn("读取显存占用失败: {}", e.toString());
            return false;
        }
    }

    /**
     * 解析 /api/ps 的驻留信息。不能再用字符串 contains 判断 ——
     * {@code {"models":[]}} 会被误当成「不在 GPU 上」。
     *
     * @return TRUE = 至少一个模型 size_vram &gt; 0；FALSE = 有模型但全在内存；
     *         null = 没有模型驻留，无法判断
     */
    private static Boolean parseGpuState(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            JsonArray models = root.getAsJsonArray("models");
            if (models == null || models.isEmpty()) {
                return null;
            }
            boolean hasInfo = false;
            for (JsonElement el : models) {
                if (!el.isJsonObject()) {
                    continue;
                }
                JsonObject m = el.getAsJsonObject();
                if (!m.has("size_vram")) {
                    continue;
                }
                hasInfo = true;
                if (m.get("size_vram").getAsLong() > 0) {
                    return Boolean.TRUE;
                }
            }
            return hasInfo ? Boolean.FALSE : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String trimBody(String body) {
        if (body == null) {
            return "";
        }
        String s = body.replaceAll("\\s+", " ");
        return s.length() <= 200 ? s : s.substring(0, 200) + "…";
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static volatile Properties modelProps;

    private static Properties loadModelProps() {
        Properties p = modelProps;
        if (p != null) {
            return p;
        }
        p = new Properties();
        // 旧单包：模型信息跟着代码包走；拆包：从 mods 里选中的通用模型包读。
        if (selfPayloadPresent()) {
            try (InputStream in = BundledRuntime.class.getClassLoader().getResourceAsStream(MODEL_PROPS)) {
                if (in != null) {
                    p.load(new java.io.InputStreamReader(in, StandardCharsets.UTF_8));
                }
            } catch (Exception e) {
                LOGGER.warn("读取 model.properties 失败: {}", e.toString());
            }
        } else {
            ModelPacks.Pack pack = ModelPacks.active();
            if (pack != null) {
                p.putAll(pack.props());
            }
        }
        modelProps = p;
        return p;
    }

    /** 统计从 jar 里读了多少字节，用来算解包进度 */
    private static final class CountingInputStream extends FilterInputStream {
        private long count;

        CountingInputStream(InputStream in) {
            super(in);
        }

        long count() {
            return count;
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b >= 0) {
                count++;
            }
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = super.read(b, off, len);
            if (n > 0) {
                count += n;
            }
            return n;
        }
    }
}
