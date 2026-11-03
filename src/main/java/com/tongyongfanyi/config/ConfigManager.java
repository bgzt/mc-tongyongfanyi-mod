package com.tongyongfanyi.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 配置读写。配置文件位于 config/tongyongfanyi.json。
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
                JsonObject root = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
                boolean changed = migrate(root);
                ModConfig config = GSON.fromJson(root, ModConfig.class);
                if (config != null) {
                    if (changed) {
                        // 迁移后立刻按新结构写回，避免每次启动都重复迁移
                        save(config);
                    }
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

    /**
     * 把旧版本配置迁移到当前结构。
     *
     * 旧版本用 backends.local.{model,url} 存本地模型参数，另有一个 backend 字段用于选后端；
     * 现在只剩「本地模型」一种翻译方式，因此这里把模型名和服务地址搬到 localModel 下，
     * 并丢掉已经没有意义的 backend / backends 节点。
     * 这样做是为了不把用户已选好的模型（比如 HY-MT-1.8B）静默重置成默认的 qwen2.5:0.5b。
     *
     * @return 是否发生了改动（需要写回文件）
     */
    static boolean migrate(JsonObject root) {
        boolean changed = false;

        if (root.remove("backend") != null) {
            changed = true;
        }

        JsonElement backends = root.get("backends");
        if (backends != null && backends.isJsonObject()) {
            JsonObject legacy = backends.getAsJsonObject();
            JsonElement local = legacy.get("local");
            if (local != null && local.isJsonObject()) {
                JsonObject src = local.getAsJsonObject();
                JsonObject target = root.has("localModel") && root.get("localModel").isJsonObject()
                        ? root.getAsJsonObject("localModel")
                        : new JsonObject();
                copyStringIfPresent(src, target, "model");
                copyStringIfPresent(src, target, "url");
                root.add("localModel", target);
            }
            root.remove("backends");
            changed = true;
        }
        return changed;
    }

    /** 仅当来源里存在非空的字符串值时才复制，避免用空值覆盖新配置里的已有设置 */
    private static void copyStringIfPresent(JsonObject from, JsonObject to, String key) {
        JsonElement value = from.get(key);
        if (value != null && value.isJsonPrimitive() && !value.getAsString().isBlank()) {
            to.add(key, value);
        }
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
