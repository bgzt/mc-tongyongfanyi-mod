package com.tongyongfanyi.config;

import com.tongyongfanyi.translate.RemoteTables;
import com.tongyongfanyi.translate.TranslationManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * SkyBlock 翻译增强子页面：术语表相关的开关都收在这里。
 *
 * <ul>
 *   <li><b>整句术语固定译名</b>：整段就是一个术语时（{@code Purse:}、{@code Enchanted Hard Stone}）
 *       直接给固定译名，不交给模型猜。关掉的话模型会把 {@code Bits:} 翻成「位：」。</li>
 *   <li><b>句中术语固定译名</b>：句子内部的术语用占位符锁成固定译名。关掉后整句交给模型，
 *       语序更自然，但固定叫法会变成模型自己猜。</li>
 *   <li><b>内置物品名表</b>：NEU 仓库里的物品名（{@code Acacia Minion XII} 这类）
 *       直接给中文，不排队等模型。关掉后物品名重新走模型。</li>
 * </ul>
 *
 * <p>三者互不影响：可以只关「句中」保住语序，同时保留「整句」和「物品名表」
 * 让计分板、物品提示仍然一次到位。
 */
public class SkyblockScreen extends BaseSettingsScreen {
    private static final int FIELD_W = 230;
    private static final int FIELD_H = 20;
    private static final int ROW_GAP = 26;

    private final ModConfig config;
    private final ConfigManager configManager;
    private final TranslationManager translationManager;

    public SkyblockScreen(ModConfig config, ConfigManager configManager,
                          TranslationManager translationManager, Screen parent) {
        super(I18n.c("title.skyblock"), parent);
        this.config = config;
        this.configManager = configManager;
        this.translationManager = translationManager;
    }

    @Override
    protected void rebuild() {
        int x = contentX();
        baseY += 6;

        addRenderableWidget(toggle("toggle.glossary_exact", config.glossaryExact, x, v -> {
            config.glossaryExact = v;
            if (translationManager != null) {
                // 只影响「整句术语直出」，缓存里存的是模型译文，要重算
                translationManager.clearCache();
            }
        }));
        baseY += ROW_GAP;
        addRenderableWidget(toggle("toggle.glossary", config.useGlossary, x, v -> {
            config.useGlossary = v;
            if (translationManager != null) {
                translationManager.reload();
                // reload() 只清内存；磁盘里还有用占位符生成的旧译文，一并清掉
                translationManager.clearCache();
            }
        }));
        baseY += ROW_GAP;
        addRenderableWidget(toggle("toggle.item_names", config.itemNameTable, x, v -> {
            config.itemNameTable = v;
            // 只换整句直出表，不重编术语表正则，也不需要清缓存：
            // 关掉的瞬间物品名就从「表里直出」退回「缓存/模型」
            com.tongyongfanyi.translate.Glossary.setItemNamesEnabled(v);
        }));
        baseY += ROW_GAP;

        // ---- 数据表更新（只手动）----
        // 不点这个按钮就完全不联网：启动、进游戏、切界面都不会去读 manifest。
        Button checkButton = Button.builder(I18n.c("button.table_check"), btn -> {
            btn.setMessage(I18n.c("button.table_checking"));
            btn.active = false;
            final Minecraft mc = minecraft;
            Thread t = new Thread(() -> {
                String result = RemoteTables.update(config, null);
                if (mc != null) {
                    mc.execute(() -> {
                        btn.setMessage(I18n.c("button.table_check"));
                        btn.active = true;
                        if (mc.player != null) {
                            mc.player.sendSystemMessage(I18n.c("msg.table_result", result));
                        }
                    });
                }
            }, "tongyongfanyi-tables-check");
            t.setDaemon(true);
            t.start();
        }).bounds(x, baseY, FIELD_W, FIELD_H).build();
        addRenderableWidget(checkButton);
        baseY += ROW_GAP;
    }

    private Button toggle(String labelKey, boolean initial, int x, java.util.function.Consumer<Boolean> setter) {
        String label = I18n.t(labelKey);
        boolean[] on = {initial};
        return Button.builder(state(on[0], label), btn -> {
            on[0] = !on[0];
            setter.accept(on[0]);
            configManager.save(config);
            btn.setMessage(state(on[0], label));
        }).bounds(x, baseY, FIELD_W, FIELD_H).build();
    }

    private static Component state(boolean on, String label) {
        return Component.literal(I18n.t("state.row", label, I18n.t(on ? "state.on" : "state.off")));
    }

    @Override
    protected void extractFooter(GuiGraphicsExtractor graphics, int x) {
        // 每帧读一次状态：后台检查完成/失败后不用重建页面就能看到结果
        graphics.text(font, I18n.t("table.status", RemoteTables.sourceLabel(), RemoteTables.status()),
                x, height - 70, 0xFF808080);
        graphics.text(font, I18n.t("skyblock.hint"), x, height - 58, 0xFFAAAAAA);
    }
}
