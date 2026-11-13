package com.tongyongfanyi.translate;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.tongyongfanyi.config.ModConfig;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 远程数据表：从 GitHub 拉取最新的物品名表 / 术语表。
 *
 * <p><b>只做手动更新</b>：启动、进游戏、切界面都<b>不会</b>发任何网络请求。
 * 唯一的触发点是设置页「SkyBlock 翻译增强 → 检查并更新数据表」按钮
 * （以及玩家主动输入的 {@code /tyfy tables}）。不点就一直用现有表，
 * 连 manifest 都不会去读。
 *
 * <p>为什么需要它：{@code item_names.json} 和 {@code glossary.json} 是跟着 jar 打包的，
 * 只有发新版模组才有新内容。但这两个表随时会补词（玩家报漏翻、NEU 仓库更新物品），
 * 为了几个译名就让玩家重装 1.6GB 的包不合理。
 *
 * <p>点一次按钮后的流程：
 * <ol>
 *   <li>读仓库里的 {@code manifest.json}（版本号 + 条数 + sha256）；</li>
 *   <li>和本地已下载的版本比，远程 {@code version} 更大才下载；</li>
 *   <li>校验 sha256 后原子替换到 {@code config/tongyongfanyi/tables/}；</li>
 *   <li>主线程 {@link Glossary#reload()}，本次游戏内立即生效。</li>
 * </ol>
 *
 * <p><b>多源兜底</b>是必须的：实测在国内网络下 {@code raw.githubusercontent.com}
 * 直接 TLS 握手失败，而 {@code api.github.com} 正常。所以按「API → raw → jsDelivr →
 * gitmirror → gh-proxy」依次尝试，哪个通用哪个，并把成功的那个记住，下次直接用。
 *
 * <p>全部失败时什么都不做：继续用本地已下载的表，没有就用 jar 内置的那份。
 * 这条链路上没有「必须联网才能用」的环节。
 */
public final class RemoteTables {
    private static final Logger LOGGER = LoggerFactory.getLogger("tongyongfanyi");
    private static final Gson GSON = new Gson();

    private static final String MANIFEST = "manifest.json";
    /** 单次下载上限：现在最大的表约 300KB，8MB 足够防呆 */
    private static final long MAX_BYTES = 8L * 1024 * 1024;
    /** 与网络相关的时间都收紧一点：这是个「有就用、没有就算了」的手动后台任务 */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(4);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(15);

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /** 数据表支持的下载源，按尝试顺序排列 */
    private enum Source {
        /** GitHub Contents API：返回 JSON，正文是 base64。国内通常可直连 */
        API("api.github.com"),
        /** 官方 raw：国外最快，国内常被断 */
        RAW("raw.githubusercontent.com"),
        /** jsDelivr CDN */
        JSDELIVR("cdn.jsdelivr.net"),
        /** gitmirror 镜像 */
        GITMIRROR("raw.gitmirror.com"),
        /** 公共反代 */
        GHPROXY("gh-proxy.com");

        final String host;

        Source(String host) {
            this.host = host;
        }
    }

    /** 从没点过更新时就是这个状态：让设置页能明确告诉玩家「还没联网查过」 */
    private static volatile String status = "未检查";
    private static volatile boolean running;
    /** 上次成功的源。下次优先用它，省掉前面几个的超时等待 */
    private static volatile Source preferred = Source.API;

    private RemoteTables() {
    }

    // ------------------------------------------------------------------ 路径

    /** 数据表目录：{@code config/tongyongfanyi/tables/} */
    public static Path dir() {
        return FabricLoader.getInstance().getConfigDir().resolve("tongyongfanyi").resolve("tables");
    }

    /** 已下载的物品名表；不存在时返回 null，调用方回退到 jar 内置资源 */
    public static Path itemNamesFile() {
        return existing(dir().resolve("item_names.json"));
    }

    /** 已下载的术语表；不存在时返回 null */
    public static Path glossaryFile() {
        return existing(dir().resolve("glossary.json"));
    }

    /** 已下载的地点表（Royal Mines 这类区域名）；不存在时返回 null */
    public static Path locationsFile() {
        return existing(dir().resolve("locations.json"));
    }

    private static Path existing(Path p) {
        try {
            return Files.isRegularFile(p) && Files.size(p) > 2 ? p : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    public static String status() {
        return status;
    }

    /** 数据表现在用的是哪一份：给设置页/命令显示的短描述 */
    public static String sourceLabel() {
        int n = 0;
        if (itemNamesFile() != null) {
            n++;
        }
        if (glossaryFile() != null) {
            n++;
        }
        if (locationsFile() != null) {
            n++;
        }
        return n == 3 ? "已下载" : n == 0 ? "内置" : "已下载(" + n + "/3)";
    }

    // ------------------------------------------------------------------ 入口

    /**
     * 检查并更新数据表。<b>只有玩家手动触发才会走到这里</b>。
     *
     * <p>阻塞方法，调用方负责放到后台线程；点一次就完整查一次，没有时间间隔限制。
     *
     * @param listener 进度回调，可为 null
     * @return 给玩家看的一句话结果
     */
    public static synchronized String update(ModConfig config, java.util.function.Consumer<String> listener) {
        if (config == null) {
            return "配置未就绪";
        }
        if (running) {
            return "正在更新中，请稍候";
        }
        running = true;
        try {
            String repo = config.tableRepo == null || config.tableRepo.isBlank()
                    ? "bgzt/tongyongfanyi-data" : config.tableRepo.trim();
            String branch = config.tableBranch == null || config.tableBranch.isBlank()
                    ? "main" : config.tableBranch.trim();

            say(listener, "正在检查数据表…");
            byte[] manifestBytes = fetch(repo, branch, MANIFEST);
            if (manifestBytes == null) {
                status = "检查失败（网络不通）";
                return "检查失败：连不上 GitHub，继续用本地表";
            }
            JsonObject manifest = GSON.fromJson(new String(manifestBytes, StandardCharsets.UTF_8), JsonObject.class);
            if (manifest == null || !manifest.has("tables")) {
                status = "检查失败（manifest 格式不对）";
                return "检查失败：manifest 格式不对";
            }
            Map<String, String> localVersions = localVersions();

            StringBuilder applied = new StringBuilder();
            int updated = 0;
            JsonObject tables = manifest.getAsJsonObject("tables");
            for (String name : tables.keySet()) {
                JsonObject meta = tables.getAsJsonObject(name);
                if (meta == null || !meta.has("version")) {
                    continue;
                }
                int remoteVersion = meta.get("version").getAsInt();
                int localVersion = parseInt(localVersions.get(name), 0);
                int remoteCount = meta.has("count") ? meta.get("count").getAsInt() : 0;
                // 远程表比 jar 内置还旧（条数更少）时不覆盖：仓库里的 glossary 曾长期
                // 停在 3374 条，而内置已经 3621 条，直接用旧表会把新补的译名盖回去。
                if (name.equals("glossary") && remoteCount > 0 && remoteCount < Glossary.builtinCount()) {
                    LOGGER.info("远程术语表只有 {} 条（内置 {} 条），跳过，避免旧表覆盖新内置",
                            remoteCount, Glossary.builtinCount());
                    continue;
                }
                String file = meta.has("file") ? meta.get("file").getAsString() : name + ".json";
                // 版本号够了、但表文件被玩家删掉（或上次下载中断留下空文件）也要重下，
                // 否则 versions.json 会一直说「已是最新」，本机却根本没有这张表。
                boolean localMissing = (name.equals("item_names") || name.equals("glossary")
                        || name.equals("locations"))
                        && !Files.isRegularFile(dir().resolve(file));
                if (remoteVersion <= localVersion && !localMissing) {
                    continue;
                }
                say(listener, "正在下载 " + file + "（v" + remoteVersion + "）…");
                byte[] data = fetch(repo, branch, file);
                if (data == null) {
                    LOGGER.info("下载数据表 {} 失败，跳过", file);
                    continue;
                }
                String want = meta.has("sha256") ? meta.get("sha256").getAsString() : "";
                String got = sha256(data);
                if (!want.isEmpty() && !want.equalsIgnoreCase(got)) {
                    LOGGER.warn("数据表 {} 校验不通过（期望 {} 实际 {}），已丢弃", file, want, got);
                    continue;
                }
                if (name.equals("item_names") || name.equals("glossary") || name.equals("locations")) {
                    writeAtomic(dir().resolve(file), data);
                    localVersions.put(name, String.valueOf(remoteVersion));
                    applied.append(applied.length() > 0 ? "、" : "").append(file)
                            .append("（").append(remoteCount).append(" 条）");
                    updated++;
                    LOGGER.info("数据表已更新：{} → v{}（{} 条，{} 字节）",
                            file, remoteVersion, remoteCount, data.length);
                }
            }

            // 无论有没有更新都记下版本，避免每次启动都重复下载
            saveLocalVersions(localVersions);
            writeAtomic(dir().resolve(MANIFEST), manifestBytes);

            if (updated == 0) {
                status = "已是最新";
                return "数据表已是最新";
            }
            // 表换了，术语表/物品名的内存副本必须重建
            reloadGlossaryOnMainThread();
            status = "已更新 " + applied;
            return "数据表已更新：" + applied + "，立即生效";
        } catch (Throwable e) {
            LOGGER.info("更新数据表失败：{}", e.toString());
            status = "更新失败：" + e;
            return "更新失败：" + e.getMessage();
        } finally {
            running = false;
        }
    }

    private static void say(java.util.function.Consumer<String> listener, String msg) {
        if (listener != null) {
            try {
                listener.accept(msg);
            } catch (Throwable ignored) {
                // 反馈失败不影响更新
            }
        }
    }

    /** 表变了要在主线程重建 Glossary：它持有那个几千分支的大正则，后台改会被渲染线程读到半成品 */
    private static void reloadGlossaryOnMainThread() {
        try {
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            if (mc != null) {
                mc.execute(Glossary::reload);
                return;
            }
        } catch (Throwable ignored) {
            // 早期启动阶段还没有客户端实例，直接同步重载
        }
        Glossary.reload();
    }

    // ------------------------------------------------------------------ 下载

    /**
     * 按 {@link Source} 顺序尝试下载，成功即返回正文。
     *
     * <p>{@code api.github.com} 返回的是 JSON（content 为 base64），其余源直接返回原文。
     */
    private static byte[] fetch(String repo, String branch, String path) {
        Source[] order = orderedSources();
        for (Source source : order) {
            try {
                byte[] data = fetchFrom(source, repo, branch, path);
                if (data != null && data.length > 1 && data.length <= MAX_BYTES) {
                    preferred = source;
                    return data;
                }
            } catch (Throwable e) {
                LOGGER.debug("数据源 {} 不可用（{}）：{}", source.host, path, e.toString());
            }
        }
        return null;
    }

    private static Source[] orderedSources() {
        Source first = preferred == null ? Source.API : preferred;
        Source[] all = Source.values();
        Source[] out = new Source[all.length];
        int i = 0;
        out[i++] = first;
        for (Source s : all) {
            if (s != first) {
                out[i++] = s;
            }
        }
        return out;
    }

    private static byte[] fetchFrom(Source source, String repo, String branch, String path) throws Exception {
        String url;
        boolean api;
        switch (source) {
            case API -> {
                url = "https://api.github.com/repos/" + repo + "/contents/" + path + "?ref=" + branch;
                api = true;
            }
            case RAW -> {
                url = "https://raw.githubusercontent.com/" + repo + "/" + branch + "/" + path;
                api = false;
            }
            case JSDELIVR -> {
                url = "https://cdn.jsdelivr.net/gh/" + repo + "@" + branch + "/" + path;
                api = false;
            }
            case GITMIRROR -> {
                url = "https://raw.gitmirror.com/" + repo + "/" + branch + "/" + path;
                api = false;
            }
            default -> {
                url = "https://gh-proxy.com/https://raw.githubusercontent.com/" + repo + "/" + branch + "/" + path;
                api = false;
            }
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(READ_TIMEOUT)
                .header("User-Agent", "tongyongfanyi-mod")
                .GET();
        if (api) {
            builder.header("Accept", "application/vnd.github+json");
        }
        HttpResponse<byte[]> resp = HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
        if (resp.statusCode() != 200) {
            throw new IOException("HTTP " + resp.statusCode());
        }
        byte[] body = resp.body();
        if (!api) {
            return body;
        }
        // Contents API：{"encoding":"base64","content":"...","size":123}
        JsonObject json = GSON.fromJson(new String(body, StandardCharsets.UTF_8), JsonObject.class);
        if (json == null || !json.has("content")) {
            // 可能是目录或报错对象，换下一个源
            throw new IOException("API 返回里没有 content");
        }
        String b64 = json.get("content").getAsString().replaceAll("\\s", "");
        return Base64.getDecoder().decode(b64);
    }

    // ------------------------------------------------------------------ 落盘

    private static void writeAtomic(Path target, byte[] data) throws IOException {
        Files.createDirectories(target.getParent());
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.write(tmp, data);
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** 本地已下载的版本号，存在 {@code tables/versions.json}，只用来看要不要更新 */
    private static Map<String, String> localVersions() {
        Map<String, String> out = new LinkedHashMap<>();
        Path p = dir().resolve("versions.json");
        try {
            if (Files.isRegularFile(p)) {
                JsonObject obj = GSON.fromJson(Files.readString(p), JsonObject.class);
                if (obj != null) {
                    for (String k : obj.keySet()) {
                        // checked_at 是时间戳不是版本号，别把它当成某张表的版本
                        if (!"checked_at".equals(k)) {
                            out.put(k, obj.get(k).getAsString());
                        }
                    }
                }
            }
        } catch (Exception ignored) {
            // 版本文件坏了就当没下载过，最多多下一次
        }
        return out;
    }

    private static void saveLocalVersions(Map<String, String> versions) {
        try {
            JsonObject obj = new JsonObject();
            versions.forEach(obj::addProperty);
            obj.addProperty("checked_at", Instant.now().toString());
            writeAtomic(dir().resolve("versions.json"),
                    (GSON.toJson(obj) + "\n").getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            LOGGER.info("写入数据表版本记录失败：{}", e.toString());
        }
    }

    private static int parseInt(String s, int fallback) {
        try {
            return s == null ? fallback : Integer.parseInt(s.trim());
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static String sha256(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(data));
        } catch (Exception e) {
            return "";
        }
    }
}
