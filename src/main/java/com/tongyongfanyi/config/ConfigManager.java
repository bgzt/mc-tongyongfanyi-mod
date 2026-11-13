package com.tongyongfanyi.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 配置读写。配置文件位于 config/tongyongfanyi.json。
 *
 * <p>1.5.0 换了配置结构（去掉模型相关字段，新增源语言/目标语言/设备）。
 * Gson 读旧文件时对多出来的键是忽略的，缺失的新键用字段默认值补上，
 * 所以不需要迁移代码——旧配置能直接读，保存一次就变成新结构。
 */
public class ConfigManager {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private final Path path;

    public ConfigManager() {
        this.path = FabricLoader.getInstance().getConfigDir().resolve("tongyongfanyi.json");
    }

    public ModConfig load() {
        if (Files.exists(path)) {
            try {
                ModConfig config = GSON.fromJson(Files.readString(path), ModConfig.class);
                if (config != null) {
                    sanitize(config);
                    return config;
                }
            } catch (Exception e) {
                System.err.println("[通用翻译] 读取配置失败: " + e.getMessage());
            }
        }
        ModConfig defaults = new ModConfig();
        save(defaults);
        return defaults;
    }

    /** 兜住手工改坏的值，避免界面/请求因为一个非法字段直接崩掉 */
    private static void sanitize(ModConfig config) {
        if (!Languages.ids(Languages.SOURCES).contains(config.sourceLanguage)) {
            config.sourceLanguage = "auto";
        }
        if (!Languages.ids(Languages.TARGETS).contains(config.targetLanguage)) {
            config.targetLanguage = "zh-CN";
        }
        if (!"auto".equals(config.device) && !"gpu".equals(config.device) && !"cpu".equals(config.device)) {
            config.device = "auto";
        }
        if (config.translationThreads < 1 || config.translationThreads > 8) {
            config.translationThreads = 1;
        }
        // API 并发和本地的上限不同：云端不受显存限制，允许开到 16
        if (config.apiThreads < 1 || config.apiThreads > 16) {
            config.apiThreads = 6;
        }
        if (config.timeoutMs < 1000 || config.timeoutMs > 60000) {
            config.timeoutMs = 20000;
        }
        if (config.cacheSize < 128) {
            config.cacheSize = 2048;
        }
        // 优先级只能是 1~6（1 最优先），手工改坏了就回到各自默认档
        config.priorityChat = level(config.priorityChat, 1);
        config.priorityTooltip = level(config.priorityTooltip, 2);
        config.priorityScoreboard = level(config.priorityScoreboard, 3);
        config.priorityScreen = level(config.priorityScreen, 4);
        config.priorityHud = level(config.priorityHud, 5);
        config.priorityItemList = level(config.priorityItemList, 6);
    }

    private static int level(int value, int fallback) {
        return value < 1 || value > 6 ? fallback : value;
    }

    public void save(ModConfig config) {
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, GSON.toJson(config));
        } catch (IOException e) {
            System.err.println("[通用翻译] 保存配置失败: " + e.getMessage());
        }
    }
}
