package com.tongyongfanyi.local;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.tongyongfanyi.config.ModConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 本地模型服务（Ollama）管理：从模组设置里一键启动/停止。
 * 只监听 127.0.0.1，翻译完全在本地完成，不修改任何发往服务器的内容。
 */
public final class LocalModelService {
    private static final Logger LOGGER = LoggerFactory.getLogger("tongyongfanyi");
    private static final String HEALTH_URL = "http://127.0.0.1:11434/api/tags";
    private static final String CHAT_URL = "http://127.0.0.1:11434/api/chat";
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(500))
            .build();

    /** 由本模组拉起的 serve 进程（外部启动的不受 stop 影响） */
    private static volatile Process process;
    /**
     * 服务是否在运行。由后台轮询线程刷新（见 STATUS_POLLER），
     * HUD/设置界面每帧只读这个缓存值，绝不在渲染线程上发起 HTTP 请求。
     */
    private static volatile boolean cachedRunning;
    /** 是否已启动状态轮询线程（CAS 保证只启动一次） */
    private static final AtomicBoolean pollerStarted = new AtomicBoolean(false);
    /** 状态刷新间隔（毫秒） */
    private static final long STATUS_REFRESH_MILLIS = 2000L;
    /** 状态轮询线程：所有 HTTP 探测都在这里做 */
    private static final ScheduledExecutorService STATUS_POLLER =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "tongyongfanyi-status");
                t.setDaemon(true);
                return t;
            });
    /** 最近一次启动失败的具体原因，供设置界面显示 */
    private static volatile String lastError = "";
    /** 模型下载状态 */
    private static volatile boolean downloading;
    private static volatile String downloadStatus = "";
    private static volatile int downloadPercent = -1;
    /** 模型切换状态（卸载旧模型/加载新模型） */
    private static volatile boolean switching;
    private static volatile String switchStatus = "";
    private static volatile int switchPercent = -1;
    /** 切换结束时间戳（用于 HUD/界面短暂显示完成状态） */
    private static volatile long switchFinishedMillis = 0L;
    /** 模型是否已安装（后台刷新，供界面与 HUD 每帧读取） */
    private static final Map<String, Boolean> installedCache = new ConcurrentHashMap<>();
    /** 内置模型（jar 自带）是否已经铺到 models 目录 */
    private static volatile boolean bundledReady;
    /** 是否正在安装内置模型（与"从网上拉模型"共用 downloading 互斥，但状态要分开显示） */
    private static volatile boolean bundledInstalling;
    /** 内置模型安装失败原因（空 = 没失败过） */
    private static volatile String bundledInstallError = "";
    /** 启动时只自动尝试安装内置模型一次 */
    private static final AtomicBoolean bundledAutoAttempt = new AtomicBoolean(false);
    /** 标准模型名 -> HuggingFace 国内镜像仓库（自动切换镜像时使用） */
    private static final Map<String, String> HF_MODELS = Map.of(
            "qwen2.5:0.5b", "Qwen/Qwen2.5-0.5B-Instruct-GGUF:Q4_K_M",
            "qwen2.5:1.5b", "Qwen/Qwen2.5-1.5B-Instruct-GGUF:Q4_K_M",
            "kaelri/hy-mt2:1.8b", "Tencent/Hy-MT2-1.8B-GGUF:Q4_K_M"
    );

    /** 本地模型可选列表（名称为 Ollama 模型名） */
    public static final List<String> LOCAL_MODELS = List.of(
            "qwen2.5:0.5b", "qwen2.5:1.5b", "kaelri/hy-mt2:1.8b");

    private static final Map<String, String> MODEL_LABELS = Map.of(
            "qwen2.5:0.5b", "Qwen2.5 0.5B",
            "qwen2.5:1.5b", "Qwen2.5 1.5B",
            "kaelri/hy-mt2:1.8b", "HY-MT-1.8B");

    private static final Map<String, String> MODEL_SIZES = Map.of(
            "qwen2.5:0.5b", "约400MB",
            "qwen2.5:1.5b", "约1GB",
            "kaelri/hy-mt2:1.8b", "约1.2GB");

    /** 常见国内加速器的本地代理端口（v2rayN/Clash 等） */
    private static final int[] PROXY_PORTS = {10808, 10809, 7890, 7897, 1080, 2080, 1081, 7891, 8888, 8080};

    /** 模型显示名称 */
    public static String displayName(String model) {
        String label = MODEL_LABELS.get(model);
        return label != null ? label : (model == null || model.isBlank() ? "Qwen2.5 0.5B" : model);
    }

    /** 模型下载大小说明 */
    public static String modelSize(String model) {
        String size = MODEL_SIZES.get(model);
        return size != null ? size : "";
    }

    private LocalModelService() {
    }

    /**
     * 服务是否在运行。只读后台轮询线程维护的缓存，因此可以在渲染线程安全调用。
     * 第一次调用会顺带启动轮询线程。
     */
    public static boolean isRunningCached() {
        ensurePoller();
        return cachedRunning;
    }

    /**
     * 模型是否已安装。同样只读后台缓存，渲染线程安全。
     * （旧实现会在缓存过期时就地发一次阻塞 HTTP，HUD 每帧调用它，
     * 一旦本机 11434 端口不可达就会每 2 秒卡住渲染线程约 500ms。）
     */
    public static boolean isModelInstalledCached(String model) {
        ensurePoller();
        return installedCache.getOrDefault(model, false);
    }

    /** 只有真的要读状态时才启动轮询，避免没用本地模型的用户也被无谓探测 */
    private static void ensurePoller() {
        if (pollerStarted.compareAndSet(false, true)) {
            STATUS_POLLER.scheduleWithFixedDelay(LocalModelService::refreshStatus,
                    0L, STATUS_REFRESH_MILLIS, TimeUnit.MILLISECONDS);
        }
    }

    /** 在轮询线程上刷新「服务是否运行 + 各模型是否已安装」 */
    private static void refreshStatus() {
        try {
            boolean running = checkRunning();
            cachedRunning = running;
            for (String model : LOCAL_MODELS) {
                installedCache.put(model, running && isModelInstalled(model));
            }
        } catch (Throwable t) {
            // scheduleWithFixedDelay 在任务抛异常后会停止排期，所以这里必须吞掉一切异常
            LOGGER.warn("刷新本地模型状态失败: {}", t.getMessage());
        }
    }

    public static String lastError() {
        return lastError;
    }

    public static boolean isDownloading() {
        return downloading;
    }

    public static String downloadStatus() {
        return downloadStatus;
    }

    public static int downloadPercent() {
        return downloadPercent;
    }

    // ------------------------------------------------------------------
    // 内置模型（随 mod jar 一起分发，首次启动自动铺到 models 目录）
    // ------------------------------------------------------------------

    /** 这个 jar 里是否带着内置模型 */
    public static boolean hasBundledModel() {
        return BundledModel.payloadPresent(LocalModelService.class.getClassLoader());
    }

    /** 内置模型是否已就绪（已经铺到 models 目录） */
    public static boolean isBundledModelReady() {
        return bundledReady;
    }

    /** 是否正在安装内置模型 */
    public static boolean isBundledInstalling() {
        return bundledInstalling;
    }

    /** 内置模型安装进度（0~100，未在安装时为 -1） */
    public static int bundledInstallPercent() {
        return bundledInstalling ? Math.max(0, downloadPercent) : -1;
    }

    /** 内置模型安装失败的原因；没失败过返回空串 */
    public static String bundledInstallError() {
        return bundledInstallError;
    }

    /** 启动时自动安装一次：已就绪、已在装、或已经试过就直接返回 */
    public static void ensureBundledModel(ModConfig config) {
        if (!hasBundledModel() || !bundledAutoAttempt.compareAndSet(false, true)) {
            return;
        }
        startBundledInstall(config);
    }

    /** 设置页手动触发安装/重试 */
    public static boolean installBundledModel(ModConfig config) {
        if (!hasBundledModel() || downloading) {
            return false;
        }
        bundledAutoAttempt.set(true);
        return startBundledInstall(config);
    }

    /**
     * 启动内置模型安装线程。会先同步确认是否已经装好（只是几次文件长度比对），
     * 免得白闪一下"安装中"。返回是否真的需要安装。
     */
    private static boolean startBundledInstall(ModConfig config) {
        ClassLoader resources = LocalModelService.class.getClassLoader();
        Path modelsDir = resolveModelsDir(config);
        try {
            if (BundledModel.isInstalled(modelsDir, resources)) {
                bundledReady = true;
                bundledInstallError = "";
                LOGGER.info("内置模型 {} 已就绪（{}）", BundledModel.MODEL_LABEL, modelsDir);
                return false;
            }
        } catch (Exception e) {
            LOGGER.warn("检查内置模型状态失败: {}", e.getMessage());
        }
        downloading = true;
        bundledInstalling = true;
        downloadStatus = "正在安装内置模型 " + BundledModel.MODEL_LABEL;
        downloadPercent = 0;
        LOGGER.info("开始把内置模型 {} 安装到 {}", BundledModel.MODEL_LABEL, modelsDir);
        Thread thread = new Thread(() -> bundledInstallTask(config, modelsDir), "tongyongfanyi-bundled");
        thread.setDaemon(true);
        thread.start();
        return true;
    }

    private static void bundledInstallTask(ModConfig config, Path modelsDir) {
        ClassLoader resources = LocalModelService.class.getClassLoader();
        try {
            BundledModel.Result result = BundledModel.install(modelsDir, resources, (done, total) -> {
                downloadPercent = total <= 0 ? 0 : (int) Math.round(100.0 * done / total);
            });
            bundledReady = true;
            bundledInstallError = "";
            if (result != null && result.copiedFiles() > 0) {
                downloadStatus = "内置模型就绪（写入 " + result.copiedFiles() + " 个文件，"
                        + Math.round(result.copiedBytes() / 1048576.0) + "MB）";
                LOGGER.info("内置模型安装完成: {}", downloadStatus);
            } else {
                downloadStatus = "内置模型已就绪";
            }
            downloadPercent = 100;
            // 装好立刻预热，省掉接下来第一次翻译的等待
            warmUp(config);
        } catch (Throwable t) {
            bundledReady = false;
            bundledInstallError = String.valueOf(t.getMessage());
            downloadStatus = "内置模型安装失败: " + bundledInstallError;
            downloadPercent = -1;
            LOGGER.warn("内置模型安装失败: {}", t.getMessage(), t);
        } finally {
            bundledInstalling = false;
            downloading = false;
        }
    }

    /**
     * 定位 Ollama 的 models 目录（只做路径推导，不触碰文件）：
     * 配置路径 → 便携包结构（ollama.exe 同级的 ../models）→ Ollama 默认位置。
     * 都找不到时回落到 {@code <用户目录>/.ollama/models}，这是 Ollama 自己的默认位置，
     * 装在那里系统版 Ollama 也能认。
     */
    private static Path resolveModelsDir(ModConfig config) {
        String cfgDir = config.ollamaModelsPath;
        if (cfgDir != null && !cfgDir.isBlank()) {
            return Path.of(cfgDir);
        }
        String exe = config.ollamaPath;
        if (exe == null || exe.isBlank()) {
            String localAppData = System.getenv("LOCALAPPDATA");
            if (localAppData != null) {
                Path detected = Path.of(localAppData, "Programs", "Ollama", "ollama.exe");
                if (Files.isRegularFile(detected)) {
                    exe = detected.toString();
                }
            }
        }
        if (exe != null && !exe.isBlank()) {
            Path parent = Path.of(exe).toAbsolutePath().getParent();
            if (parent != null) {
                // 便携包结构: <包>/ollama/ollama.exe => <包>/models
                Path pkg = parent.getParent();
                if (pkg != null && Files.isDirectory(pkg.resolve("models"))) {
                    return pkg.resolve("models");
                }
                if (Files.isDirectory(parent.resolve("models"))) {
                    return parent.resolve("models");
                }
            }
        }
        return Path.of(System.getProperty("user.home"), ".ollama", "models");
    }

    /** 查询某个模型是否已安装（阻塞式，只允许在后台线程调用） */
    private static boolean isModelInstalled(String model) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(HEALTH_URL))
                    .timeout(Duration.ofSeconds(2))
                    .GET()
                    .build();
            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return false;
            }
            JsonArray models = JsonParser.parseString(response.body()).getAsJsonObject().getAsJsonArray("models");
            for (JsonElement e : models) {
                String name = e.getAsJsonObject().get("name").getAsString();
                if (name.equals(model) || name.equals(model + ":latest")) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean checkRunning() {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(HEALTH_URL))
                    .timeout(Duration.ofMillis(500))
                    .GET()
                    .build();
            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    /** 启动本地模型服务；已在运行则直接返回 true */
    public static boolean start(ModConfig config) {
        if (checkRunning()) {
            lastError = "";
            return true;
        }
        String exe = findOllamaExe(config);
        if (exe == null) {
            return false;
        }
        String models = findModelsDir(config, exe);
        // 把检测到的路径写回配置对象，方便用户直接保存
        config.ollamaPath = exe;
        if (models != null) {
            config.ollamaModelsPath = models;
        }
        try {
            ProcessBuilder builder = new ProcessBuilder(exe, "serve");
            if (models != null && !models.isBlank()) {
                builder.environment().put("OLLAMA_MODELS", models);
            }
            // 允许同一模型并行处理多个翻译请求，配合模组多线程翻译显著提速
            builder.environment().put("OLLAMA_NUM_PARALLEL", "4");
            // 自动检测本地加速器代理（如 v2rayN/Clash 的 SOCKS5），
            // 让 Ollama 下载模型时也能经过代理访问外网。
            String proxy = detectSocksProxy();
            if (proxy != null) {
                builder.environment().put("HTTP_PROXY", proxy);
                builder.environment().put("HTTPS_PROXY", proxy);
                builder.environment().put("ALL_PROXY", proxy);
                builder.environment().put("NO_PROXY", "127.0.0.1,localhost");
                LOGGER.info("检测到本地代理: {}, Ollama 下载将使用该代理", proxy);
            }
            builder.redirectErrorStream(true);
            builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            process = builder.start();
            lastError = "";
            // 立刻异步刷新一次状态，省去最多一个轮询周期的显示延迟
            ensurePoller();
            STATUS_POLLER.execute(LocalModelService::refreshStatus);
            // 后台预热模型，让进游戏后第一次翻译就很快
            warmUp(config);
            return true;
        } catch (IOException e) {
            lastError = "启动进程失败: " + e.getMessage();
            LOGGER.warn("启动本地模型服务失败: {}", e.getMessage());
            return false;
        }
    }

    /** 探测常见端口是否有可用的 SOCKS5 代理（仅做 SOCKS5 握手，不发送实际网络请求） */
    private static String detectSocksProxy() {
        for (int port : PROXY_PORTS) {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", port), 250);
                socket.setSoTimeout(1000);
                OutputStream out = socket.getOutputStream();
                InputStream in = socket.getInputStream();
                // SOCKS5 握手: 版本5、一种认证方式、无需认证
                out.write(new byte[]{5, 1, 0});
                out.flush();
                byte[] resp = new byte[2];
                if (in.read(resp) == 2 && resp[0] == 5 && resp[1] == 0) {
                    return "socks5://127.0.0.1:" + port;
                }
            } catch (Exception ignored) {
                // 该端口不是 SOCKS5 或不可用，继续尝试下一个
            }
        }
        return null;
    }

    /** 定位 ollama.exe：优先配置路径，缺失/无效时自动检测默认安装位置 */
    private static String findOllamaExe(ModConfig config) {
        String cfg = config.ollamaPath;
        if (cfg != null && !cfg.isBlank()) {
            if (Files.isRegularFile(Path.of(cfg))) {
                return cfg;
            }
            lastError = "配置的 Ollama 路径不存在: " + cfg;
        }
        String localAppData = System.getenv("LOCALAPPDATA");
        if (localAppData != null) {
            Path def = Path.of(localAppData, "Programs", "Ollama", "ollama.exe");
            if (Files.isRegularFile(def)) {
                lastError = "";
                return def.toString();
            }
        }
        lastError = "未找到 ollama.exe：请在设置里填写 Ollama 程序路径（便携包内 ollama\\ollama.exe）";
        return null;
    }

    /** 定位模型目录：优先配置路径，其次便携包结构（exe 的同级 ../models），最后默认目录 */
    private static String findModelsDir(ModConfig config, String exe) {
        String cfg = config.ollamaModelsPath;
        if (cfg != null && !cfg.isBlank()) {
            if (Files.isDirectory(Path.of(cfg))) {
                return cfg;
            }
        }
        // 便携包结构: <包>/ollama/ollama.exe => <包>/models
        Path pkgModels = Path.of(exe).toAbsolutePath().getParent().getParent().resolve("models");
        if (Files.isDirectory(pkgModels)) {
            return pkgModels.toString();
        }
        Path def = Path.of(System.getProperty("user.home"), ".ollama", "models");
        if (Files.isDirectory(def)) {
            return def.toString();
        }
        return null;
    }

    /** 后台预热：等待服务就绪后加载一次模型，避免首条消息等待模型加载 */
    public static void warmUp(ModConfig config) {
        String model = config.localModel.model;
        if (model == null || model.isBlank()) {
            return;
        }
        Thread thread = new Thread(() -> warmUpTask(model), "tongyongfanyi-warmup");
        thread.setDaemon(true);
        thread.start();
    }

    private static void warmUpTask(String model) {
        long deadline = System.currentTimeMillis() + 45_000;
        while (System.currentTimeMillis() < deadline && !checkRunning()) {
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                return;
            }
        }
        if (!checkRunning()) {
            return;
        }
        try {
            JsonObject payload = new JsonObject();
            payload.addProperty("model", model);
            payload.addProperty("keep_alive", "60m");
            payload.addProperty("stream", false);
            JsonArray messages = new JsonArray();
            JsonObject user = new JsonObject();
            user.addProperty("role", "user");
            user.addProperty("content", "hi");
            messages.add(user);
            payload.add("messages", messages);
            JsonObject options = new JsonObject();
            options.addProperty("num_predict", 1);
            options.addProperty("num_ctx", 4096);
            payload.add("options", options);

            HttpRequest request = HttpRequest.newBuilder(URI.create(CHAT_URL))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(60))
                    .POST(HttpRequest.BodyPublishers.ofString(payload.toString(), StandardCharsets.UTF_8))
                    .build();
            HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            LOGGER.info("本地模型 {} 预热完成", model);
        } catch (Exception e) {
            LOGGER.warn("本地模型预热失败: {}", e.getMessage());
        }
    }

    /**
     * 停止由本模组启动的服务。
     * 如果服务是外部启动的（例如双击了 启动翻译服务.bat，或本机已装有 Ollama），
     * 本模组没有它的进程句柄，停不了，会返回 false —— 调用方必须如实提示，不要谎报成功。
     *
     * @return true 表示确实终止了本模组启动的进程
     */
    public static boolean stop() {
        Process p = process;
        if (p == null) {
            return false;
        }
        p.destroy();
        process = null;
        // 停掉之后立刻把缓存状态置为「未运行」，界面马上能看到变化
        cachedRunning = false;
        installedCache.clear();
        return true;
    }

    /** 下载模型到本地（新设备无模型时使用）；需要服务运行，会自动尝试启动 */
    public static boolean downloadModel(ModConfig config, String model) {
        if (downloading) {
            return false;
        }
        if (!checkRunning() && !start(config)) {
            return false;
        }
        downloading = true;
        downloadStatus = "开始下载";
        downloadPercent = 0;
        Thread thread = new Thread(() -> {
            try {
                doDownload(model);
            } finally {
                downloading = false;
            }
        }, "tongyongfanyi-download");
        thread.setDaemon(true);
        thread.start();
        return true;
    }

    /** 是否正在切换模型 */
    public static boolean isSwitching() {
        return switching;
    }

    public static String switchStatus() {
        return switchStatus;
    }

    public static int switchPercent() {
        return switchPercent;
    }

    /** 供 HUD/设置页顶部显示的切换状态行；无状态或已结束超过 8 秒返回 null */
    public static String switchDisplayLine() {
        if (switching) {
            int pct = Math.max(0, Math.min(100, switchPercent));
            int filled = pct / 5;
            return "模型切换: " + switchStatus + "  ["
                    + "#".repeat(filled) + "-".repeat(20 - filled) + "] " + pct + "%";
        }
        if (switchStatus == null || switchStatus.isBlank() || switchFinishedMillis == 0L) {
            return null;
        }
        boolean recent = System.currentTimeMillis() - switchFinishedMillis < 8000;
        if (!recent) {
            return null;
        }
        return switchStatus.contains("失败")
                ? "§c" + switchStatus
                : "§a" + switchStatus;
    }

    /**
     * 切换本地模型：先卸载旧模型释放显存，再加载新模型（newModel 为空表示只卸载）。
     * 异步执行，进度通过 switchStatus/switchPercent 查询。
     */
    public static boolean switchModel(ModConfig config, String oldModel, String newModel) {
        if (switching) {
            return false;
        }
        boolean hasNew = newModel != null && !newModel.isBlank();
        if (!hasNew && (oldModel == null || oldModel.isBlank())) {
            return false;
        }
        if (hasNew && oldModel != null && oldModel.equals(newModel)) {
            return false;
        }
        if (!checkRunning() && !start(config)) {
            return false;
        }
        switching = true;
        switchStatus = "开始切换模型...";
        switchPercent = 0;
        LOGGER.info("开始切换本地模型: {} -> {}", displayName(oldModel), hasNew ? displayName(newModel) : "(仅卸载)");
        Thread thread = new Thread(() -> {
            try {
                sendChatMessage("§e[通用翻译] 正在切换模型: "
                        + (oldModel == null || oldModel.isBlank() ? "(无)" : displayName(oldModel))
                        + " → " + (hasNew ? displayName(newModel) : "(仅卸载)"));
                if (oldModel != null && !oldModel.isBlank() && !oldModel.equals(newModel)) {
                    switchStatus = "正在卸载旧模型 " + displayName(oldModel);
                    switchPercent = 15;
                    LOGGER.info("正在卸载旧模型: {}", displayName(oldModel));
                    try {
                        unloadModel(oldModel);
                    } catch (Exception e) {
                        LOGGER.warn("卸载旧模型失败（忽略，继续加载新模型）: {}", e.getMessage());
                    }
                }
                if (hasNew) {
                    switchPercent = 50;
                    switchStatus = "正在加载新模型 " + displayName(newModel) + " ...";
                    LOGGER.info("正在加载新模型: {}", displayName(newModel));
                    loadModel(newModel);
                    switchPercent = 100;
                    switchStatus = "模型切换完成: " + displayName(newModel);
                    LOGGER.info("本地模型切换完成: {} -> {}", displayName(oldModel), displayName(newModel));
                    sendChatMessage("§a[通用翻译] 模型切换完成: " + displayName(newModel));
                } else {
                    switchPercent = 100;
                    switchStatus = "已卸载旧模型: " + displayName(oldModel);
                    LOGGER.info("已卸载本地模型: {}", displayName(oldModel));
                    sendChatMessage("§a[通用翻译] 已卸载本地模型: " + displayName(oldModel));
                }
            } catch (Exception e) {
                switchPercent = -1;
                switchStatus = "模型切换失败: " + e.getMessage();
                LOGGER.warn("切换本地模型失败: {}", e.getMessage());
                sendChatMessage("§c[通用翻译] 模型切换失败: " + e.getMessage());
            } finally {
                switchFinishedMillis = System.currentTimeMillis();
                switching = false;
            }
        }, "tongyongfanyi-switch");
        thread.setDaemon(true);
        thread.start();
        return true;
    }

    /** 在主线程发一条系统消息（切换提示），保证用户能看到 */
    private static void sendChatMessage(String message) {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc != null) {
                mc.execute(() -> {
                    if (mc.player != null) {
                        mc.player.sendSystemMessage(Component.literal(message));
                    }
                });
            }
        } catch (Throwable ignored) {
            // 某些环境可能拿不到 Minecraft 实例，忽略即可（不影响切换本身）
        }
    }

    /** 卸载模型释放显存：keep_alive=0 让 Ollama 在请求结束后立即卸载 */
    private static void unloadModel(String model) throws Exception {
        JsonObject payload = new JsonObject();
        payload.addProperty("model", model);
        payload.addProperty("prompt", "");
        payload.addProperty("stream", false);
        payload.addProperty("keep_alive", 0);
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:11434/api/generate"))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(15))
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString(), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new RuntimeException("HTTP " + response.statusCode() + ": " + response.body());
        }
    }

    /** 把模型加载进显存（空生成 + keep_alive 60 分钟），之后翻译请求即点即用 */
    private static void loadModel(String model) throws Exception {
        JsonObject payload = new JsonObject();
        payload.addProperty("model", model);
        payload.addProperty("stream", false);
        payload.addProperty("keep_alive", "60m");
        JsonArray messages = new JsonArray();
        JsonObject user = new JsonObject();
        user.addProperty("role", "user");
        user.addProperty("content", "hi");
        messages.add(user);
        payload.add("messages", messages);
        JsonObject options = new JsonObject();
        options.addProperty("num_predict", 1);
        options.addProperty("num_ctx", 4096);
        payload.add("options", options);
        HttpRequest request = HttpRequest.newBuilder(URI.create(CHAT_URL))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(120))
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString(), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new RuntimeException("HTTP " + response.statusCode() + ": " + response.body());
        }
    }

    private static void doDownload(String model) {
        // 优先尝试官方源（Ollama 启动时会自动带本地加速器代理，官方源通常可达）
        if (pullStream(model)) {
            return;
        }
        // HY-MT 系列模型的官方 Ollama 打包带专用提示词模板，
        // 国内镜像（hf-mirror/hf.co 桥接）打包会损坏模板导致翻译乱码，因此只走官方源。
        if (model.startsWith("kaelri/hy-mt2")) {
            if (downloadStatus == null || downloadStatus.isBlank() || downloadStatus.startsWith("开始下载")) {
                downloadStatus = "下载失败: 官方源不可达，请开启加速器/本地代理后重试";
            } else {
                downloadStatus = downloadStatus + "（HY-MT 仅支持官方源，请开启加速器后重试）";
            }
            return;
        }
        downloadStatus = "官方源下载失败，改用国内镜像...";
        downloadPercent = 0;

        String repo = HF_MODELS.get(model);
        if (repo == null) {
            downloadStatus = "下载失败，且该模型暂不支持国内镜像: " + model;
            return;
        }
        String mirrorModel = "hf-mirror.com/" + repo;
        if (pullStream(mirrorModel)) {
            try {
                copyModel(mirrorModel, model);
                downloadStatus = "下载完成（国内镜像）";
                downloadPercent = 100;
            } catch (Exception e) {
                downloadStatus = "镜像下载完成，但命名失败: " + e.getMessage()
                        + "（可用模型名: " + mirrorModel + "）";
            }
            return;
        }
        if (downloadStatus == null || downloadStatus.isBlank() || downloadStatus.startsWith("开始下载")) {
            downloadStatus = "国内镜像下载失败";
        }
    }

    /** 通过 /api/pull 拉取模型，流式更新进度；成功返回 true */
    private static boolean pullStream(String model) {
        try {
            JsonObject payload = new JsonObject();
            payload.addProperty("model", model);
            payload.addProperty("stream", true);
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:11434/api/pull"))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofMinutes(30))
                    .POST(HttpRequest.BodyPublishers.ofString(payload.toString(), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<java.io.InputStream> response = HTTP.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                downloadStatus = "下载失败: HTTP " + response.statusCode();
                return false;
            }
            boolean success = false;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank()) {
                        continue;
                    }
                    JsonObject obj = JsonParser.parseString(line).getAsJsonObject();
                    if (obj.has("error") && !obj.get("error").isJsonNull()) {
                        downloadStatus = "下载失败: " + obj.get("error").getAsString();
                        return false;
                    }
                    String status = obj.has("status") ? obj.get("status").getAsString() : "";
                    if ("success".equals(status)) {
                        downloadStatus = "下载完成";
                        downloadPercent = 100;
                        success = true;
                        break;
                    } else {
                        downloadStatus = status;
                        if (obj.has("percent") && !obj.get("percent").isJsonNull()) {
                            downloadPercent = (int) Math.round(obj.get("percent").getAsDouble());
                        } else if (obj.has("completed") && obj.has("total")
                                && !obj.get("total").isJsonNull() && obj.get("total").getAsLong() > 0) {
                            downloadPercent = (int) Math.round(100.0 * obj.get("completed").getAsLong()
                                    / obj.get("total").getAsLong());
                        }
                    }
                }
            }
            if (!success) {
                downloadStatus = "下载中断或失败";
                return false;
            }
            return true;
        } catch (Exception e) {
            downloadStatus = "下载失败: " + e.getMessage();
            return false;
        }
    }

    /** 把镜像源下载的模型复制为标准模型名 */
    private static void copyModel(String source, String destination) throws Exception {
        JsonObject payload = new JsonObject();
        payload.addProperty("source", source);
        payload.addProperty("destination", destination);
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:11434/api/copy"))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofMinutes(5))
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString(), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new RuntimeException("HTTP " + response.statusCode() + ": " + response.body());
        }
    }
}
