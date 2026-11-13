package com.tongyongfanyi.config;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.function.IntConsumer;

/**
 * 翻译优先级子页面。
 *
 * <p>翻译线程是有限的（默认 1~2 个），一屏文字同时涌进来时只能排队。
 * 这里让玩家决定「谁先翻」：1 最优先，6 最后。数字小的来源会先出队，
 * 排在 {@link com.tongyongfanyi.translate.TranslationManager} 的优先级队列前面。
 *
 * <p>改动立刻写盘并生效 —— 队列里的任务本来就是按优先级排序的，
 * 下一次提交就会用新档位，不需要重启。
 */
public class PriorityScreen extends BaseSettingsScreen {
    private static final List<Integer> LEVELS = List.of(1, 2, 3, 4, 5, 6);
    private static final int FIELD_W = 230;
    private static final int FIELD_H = 20;
    private static final int ROW_GAP = 26;

    private final ModConfig config;
    private final ConfigManager configManager;

    public PriorityScreen(ModConfig config, ConfigManager configManager, Screen parent) {
        super(I18n.c("title.priority"), parent);
        this.config = config;
        this.configManager = configManager;
    }

    @Override
    protected void rebuild() {
        int x = contentX();
        baseY += 6;

        addRenderableWidget(level("priority.chat", config.priorityChat, x, v -> config.priorityChat = v));
        baseY += ROW_GAP;
        addRenderableWidget(level("priority.tooltip", config.priorityTooltip, x, v -> config.priorityTooltip = v));
        baseY += ROW_GAP;
        addRenderableWidget(level("priority.scoreboard", config.priorityScoreboard, x,
                v -> config.priorityScoreboard = v));
        baseY += ROW_GAP;
        addRenderableWidget(level("priority.screen", config.priorityScreen, x, v -> config.priorityScreen = v));
        baseY += ROW_GAP;
        addRenderableWidget(level("priority.hud", config.priorityHud, x, v -> config.priorityHud = v));
        baseY += ROW_GAP;
        addRenderableWidget(level("priority.itemlist", config.priorityItemList, x,
                v -> config.priorityItemList = v));
        baseY += ROW_GAP;
    }

    private CycleButton<Integer> level(String labelKey, int current, int x, IntConsumer setter) {
        int value = Math.max(1, Math.min(6, current));
        return CycleButton.<Integer>builder(v -> Component.literal(String.valueOf(v)), value)
                .withValues(LEVELS)
                .create(x, baseY, FIELD_W, FIELD_H, I18n.c(labelKey),
                        (btn, v) -> {
                            setter.accept(v);
                            configManager.save(config);
                        });
    }

    @Override
    protected void extractFooter(GuiGraphicsExtractor graphics, int x) {
        graphics.text(font, I18n.t("priority.hint"), x, height - 58, 0xFFAAAAAA);
    }
}
