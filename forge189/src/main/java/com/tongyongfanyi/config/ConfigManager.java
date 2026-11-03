package com.tongyongfanyi.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * 配置读写：config/tongyongfanyi.json。
 */
public class ConfigManager {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private final File file;

    public ConfigManager(File configDir) {
        this.file = new File(configDir, "tongyongfanyi.json");
    }

    public File getConfigFile() {
        return file;
    }

    public ModConfig load() {
        if (file.exists()) {
            try {
                String json = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
                ModConfig config = GSON.fromJson(json, ModConfig.class);
                if (config != null) {
                    return config;
                }
            } catch (IOException e) {
                System.err.println("[通用翻译] 读取配置失败: " + e.getMessage());
            }
        }
        ModConfig defaults = new ModConfig();
        save(defaults);
        return defaults;
    }

    public void save(ModConfig config) {
        try {
            if (!file.getParentFile().exists()) {
                file.getParentFile().mkdirs();
            }
            Files.write(file.toPath(), GSON.toJson(config).getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            System.err.println("[通用翻译] 保存配置失败: " + e.getMessage());
        }
    }
}
