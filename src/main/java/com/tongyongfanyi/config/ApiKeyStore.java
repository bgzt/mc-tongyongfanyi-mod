package com.tongyongfanyi.config;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * API Key 的独立存储。
 *
 * <p>不写进 {@code config/tongyongfanyi.json}：那个文件玩家会截图、会分享、
 * 会随整合包分发，密钥一旦进去基本等于公开。这里放在同目录的
 * {@code tongyongfanyi-secret.json}，设置页只显示 {@link #masked()}，
 * 日志里也永远不打印完整值。
 *
 * <p>环境变量 {@code TYFY_API_KEY} 优先：方便在启动器/容器里临时注入，
 * 不落盘；两条都存在时以环境变量为准。
 */
public final class ApiKeyStore {
    private static final Logger LOGGER = LoggerFactory.getLogger("tongyongfanyi");
    private static final Gson GSON = new Gson();
    private static final String ENV = "TYFY_API_KEY";

    private ApiKeyStore() {
    }

    public static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("tongyongfanyi-secret.json");
    }

    /**
     * 读取密钥：<b>独立密钥文件优先</b>，文件缺失时才看环境变量。
     *
     * <p>不做成「环境变量优先」是因为设置页里填的 Key 必须立刻生效：
     * 如果环境变量一直压着文件，玩家在 UI 里改了 Key 却毫无反应，很难排查。
     * 环境变量只作为「不想落盘」的高级用法：删掉 secret 文件即可。
     */
    public static String load() {
        try {
            Path p = file();
            if (Files.isRegularFile(p)) {
                JsonObject o = GSON.fromJson(Files.readString(p, StandardCharsets.UTF_8), JsonObject.class);
                String fromFile = o != null && o.has("apiKey") ? o.get("apiKey").getAsString().trim() : "";
                if (!fromFile.isEmpty()) {
                    return fromFile;
                }
            }
        } catch (Exception e) {
            LOGGER.warn("读取 API 密钥失败: {}", e.toString());
        }
        String env = System.getenv(ENV);
        return env == null ? "" : env.trim();
    }

    public static boolean hasKey() {
        return !load().isEmpty();
    }

    /** 写入 secret 文件；空串表示清除。不会写进主配置。 */
    public static void save(String key) {
        try {
            JsonObject o = new JsonObject();
            o.addProperty("apiKey", key == null ? "" : key.trim());
            Path p = file();
            Files.createDirectories(p.getParent());
            Path tmp = p.resolveSibling(p.getFileName() + ".tmp");
            Files.writeString(tmp, GSON.toJson(o), StandardCharsets.UTF_8);
            Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING);
            try {
                Files.setPosixFilePermissions(p,
                        java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
            } catch (Exception ignored) {
                // Windows 没有 POSIX 权限，忽略
            }
        } catch (Exception e) {
            LOGGER.warn("保存 API 密钥失败: {}", e.toString());
        }
    }

    /** UI 上显示的遮罩，绝不返回完整密钥 */
    public static String masked() {
        String k = load();
        if (k.isEmpty()) {
            return "";
        }
        if (k.length() <= 8) {
            return "****";
        }
        return k.substring(0, 4) + "****" + k.substring(k.length() - 4);
    }
}
