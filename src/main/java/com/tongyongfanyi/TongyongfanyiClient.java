package com.tongyongfanyi;

import com.tongyongfanyi.config.ConfigManager;
import com.tongyongfanyi.config.ModConfig;
import com.tongyongfanyi.config.SettingsScreen;
import com.tongyongfanyi.local.LocalModelService;
import com.tongyongfanyi.translate.TranslationManager;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

public class TongyongfanyiClient implements ClientModInitializer {
    private static TongyongfanyiClient instance;

    private ConfigManager configManager;
    private ModConfig config;
    private TranslationManager translationManager;
    private MessageInterceptor interceptor;

    @Override
    public void onInitializeClient() {
        instance = this;
        configManager = new ConfigManager();
        config = configManager.load();
        translationManager = new TranslationManager(config);
        interceptor = new MessageInterceptor(translationManager);
        interceptor.register();
        registerCommand();
        // jar 里自带 Qwen2.5 0.5B：首次启动把它铺到 Ollama 的 models 目录（后台线程，
        // 已装好时这一步只是几次文件长度比对，直接返回）
        LocalModelService.ensureBundledModel(config);
        // 翻译只用本地模型：服务没在运行时自动启动，并后台预热模型，加快第一次翻译
        boolean running = LocalModelService.isRunningCached();
        if (!running) {
            running = LocalModelService.start(config);
            if (running) {
                configManager.save(config);
            } else {
                System.err.println("[通用翻译] 自动启动本地模型服务失败: " + LocalModelService.lastError());
            }
        }
        if (running) {
            LocalModelService.warmUp(config);
        }
        // 游戏退出时把缓存刷到磁盘，下次启动直接命中
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> translationManager.flushCache());
    }

    public static TongyongfanyiClient getInstance() {
        return instance;
    }

    public ConfigManager getConfigManager() {
        return configManager;
    }

    public ModConfig getConfig() {
        return config;
    }

    public TranslationManager getTranslationManager() {
        return translationManager;
    }

    private void registerCommand() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, buildContext) ->
                dispatcher.register(literal("tyfy")
                        .then(literal("toggle").executes(ctx -> {
                            config.enabled = !config.enabled;
                            configManager.save(config);
                            feedback(ctx.getSource(), "翻译已" + (config.enabled ? "启用" : "禁用"));
                            return 1;
                        }))
                        .then(literal("reload").executes(ctx -> {
                            config = configManager.load();
                            // 必须把新配置交给翻译管理器，否则它会一直用旧实例，
                            // 表现为「提示已重载、但翻译行为不变」，设置界面也改不动。
                            translationManager.setConfig(config);
                            translationManager.reload();
                            feedback(ctx.getSource(), "配置已重载");
                            return 1;
                        }))
                        .then(literal("tooltip").executes(ctx -> {
                            config.translateItemTooltips = !config.translateItemTooltips;
                            configManager.save(config);
                            feedback(ctx.getSource(), "物品悬停翻译: " + (config.translateItemTooltips ? "已开启" : "已关闭"));
                            return 1;
                        }))
                        .then(literal("status").executes(ctx -> {
                            String model = config.localModel.model;
                            feedback(ctx.getSource(), "启用: " + config.enabled
                                    + " | 模型: " + LocalModelService.displayName(model)
                                    + " | 服务: " + (LocalModelService.isRunningCached() ? "运行中" : "未运行")
                                    + " | 目标语言: " + config.targetLanguage
                                    + " | 物品提示: " + config.translateItemTooltips
                                    + " | 磁盘缓存: " + translationManager.diskCacheSize() + " 条");
                            return 1;
                        }))
                        // 不带子命令时直接打开设置界面：未安装 ModMenu 的玩家也能进设置
                        .executes(ctx -> {
                            Minecraft.getInstance().setScreenAndShow(
                                    new SettingsScreen(config, configManager, translationManager));
                            return 1;
                        })));
    }

    private void feedback(FabricClientCommandSource source, String msg) {
        source.sendFeedback(Component.literal("§a[通用翻译]§f " + msg));
    }
}
