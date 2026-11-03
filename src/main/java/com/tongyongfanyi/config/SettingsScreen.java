package com.tongyongfanyi.config;

import com.tongyongfanyi.translate.TranslationManager;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

/** 通用设置主页面（分级设置入口） */
public class SettingsScreen extends BaseSettingsScreen {
    private final ModConfig config;
    private final ConfigManager configManager;
    private final TranslationManager translationManager;
    private EditBox targetLangField;

    public SettingsScreen(ModConfig config, ConfigManager configManager, TranslationManager translationManager) {
        super(Component.literal("通用翻译设置"));
        this.config = config;
        this.configManager = configManager;
        this.translationManager = translationManager;
    }

    @Override
    protected void rebuild() {
        int x = width / 2 - 110;
        int fieldW = 220;
        int fieldH = 20;

        header("通用");
        Button toggle = Button.builder(
                Component.literal("翻译开关: " + (config.enabled ? "§a已启用" : "§c已禁用")),
                btn -> {
                    config.enabled = !config.enabled;
                    configManager.save(config);
                    btn.setMessage(Component.literal("翻译开关: " + (config.enabled ? "§a已启用" : "§c已禁用")));
                }).bounds(x, baseY, fieldW, fieldH).build();
        addRenderableWidget(toggle);
        baseY += 26;

        Button tooltipToggle = Button.builder(
                Component.literal("翻译物品提示: " + (config.translateItemTooltips ? "§a已开启" : "§c已关闭")),
                btn -> {
                    config.translateItemTooltips = !config.translateItemTooltips;
                    configManager.save(config);
                    btn.setMessage(Component.literal("翻译物品提示: " + (config.translateItemTooltips ? "§a已开启" : "§c已关闭")));
                }).bounds(x, baseY, fieldW, fieldH).build();
        addRenderableWidget(tooltipToggle);
        baseY += 26;

        Button screenTextToggle = Button.builder(
                Component.literal("翻译界面文字(模组界面): " + (config.translateScreenText ? "§a已开启" : "§c已关闭")),
                btn -> {
                    config.translateScreenText = !config.translateScreenText;
                    configManager.save(config);
                    btn.setMessage(Component.literal("翻译界面文字(模组界面): " + (config.translateScreenText ? "§a已开启" : "§c已关闭")));
                }).bounds(x, baseY, fieldW, fieldH).build();
        addRenderableWidget(screenTextToggle);
        baseY += 26;

        List<Integer> threadOptions = List.of(1, 2, 3, 4, 6, 8);
        int currentThreads = threadOptions.contains(config.translationThreads)
                ? config.translationThreads : 4;
        CycleButton<Integer> threadCycle = CycleButton.<Integer>builder(
                        value -> Component.literal("翻译线程数: " + value + "（聊天优先）"),
                        currentThreads)
                .withValues(threadOptions)
                .create(x, baseY, fieldW, fieldH, Component.literal("翻译线程数"),
                        (btn, value) -> {
                            config.translationThreads = value;
                            configManager.save(config);
                            if (translationManager != null) {
                                translationManager.setThreadCount(value);
                            }
                        });
        addRenderableWidget(threadCycle);
        baseY += 26;

        // 请求超时现在是各后端 HTTP 请求的真实超时：云端可设短一些，
        // 本地模型（尤其 CPU 跑）需要放宽，否则会频繁超时失败。
        List<Integer> timeoutOptions = List.of(3000, 5000, 10000, 20000, 30000);
        int currentTimeout = timeoutOptions.contains(config.timeoutMs) ? config.timeoutMs : 10000;
        CycleButton<Integer> timeoutCycle = CycleButton.<Integer>builder(
                        value -> Component.literal("请求超时: " + (value / 1000) + " 秒"),
                        currentTimeout)
                .withValues(timeoutOptions)
                .create(x, baseY, fieldW, fieldH, Component.literal("请求超时"),
                        (btn, value) -> {
                            config.timeoutMs = value;
                            configManager.save(config);
                            if (translationManager != null) {
                                // 后端实例持有超时值，需重建才生效
                                translationManager.reload();
                            }
                        });
        addRenderableWidget(timeoutCycle);
        baseY += 26;

        targetLangField = label("目标语言 (如 zh-CN)", x, fieldW, fieldH);
        targetLangField.setValue(config.targetLanguage);
        targetLangField.setMaxLength(16);
        targetLangField.setResponder(s -> {
            config.targetLanguage = s;
            configManager.save(config);
        });
        addRenderableWidget(targetLangField);

        header("设置分类");
        Button localBtn = Button.builder(Component.literal("本地模型设置..."),
                        btn -> open(new LocalModelSettingsScreen(config, configManager, translationManager, this)))
                .bounds(x, baseY, fieldW, fieldH).build();
        addRenderableWidget(localBtn);
        baseY += 26;

        header("其他");
        Button clearCache = Button.builder(
                        Component.literal("§c清空翻译缓存"),
                        btn -> {
                            if (translationManager != null) {
                                translationManager.clearCache();
                                if (minecraft != null && minecraft.player != null) {
                                    minecraft.player.sendSystemMessage(Component.literal("§a[通用翻译] 已清空翻译缓存"));
                                }
                            }
                        })
                .bounds(x, baseY, fieldW, fieldH).build();
        addRenderableWidget(clearCache);
        baseY += 26;
    }

    private void open(Screen screen) {
        if (minecraft != null) {
            minecraft.setScreenAndShow(screen);
        }
    }
}
