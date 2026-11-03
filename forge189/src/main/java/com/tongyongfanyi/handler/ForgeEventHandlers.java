package com.tongyongfanyi.handler;

import com.tongyongfanyi.TongyongfanyiMod;
import com.tongyongfanyi.config.ModConfig;
import com.tongyongfanyi.translate.TranslationHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.scoreboard.Score;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.ScoreObjective;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.IChatComponent;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.event.entity.player.ItemTooltipEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Forge 事件处理：聊天翻译、物品提示翻译、计分板/HUD 绘制。
 */
public class ForgeEventHandlers {

    @SubscribeEvent
    public void onChat(ClientChatReceivedEvent event) {
        ModConfig config = TongyongfanyiMod.config;
        if (config == null || !config.enabled || !config.translateChat) {
            return;
        }
        IChatComponent message = event.message;
        if (message == null) {
            return;
        }
        String original = message.getUnformattedText();
        String translated = TranslationHelper.translateText(original);
        if (translated != null && !translated.equals(original)) {
            event.message = new ChatComponentText(translated);
        }
    }

    @SubscribeEvent
    public void onTooltip(ItemTooltipEvent event) {
        ModConfig config = TongyongfanyiMod.config;
        if (config == null || !config.enabled || !config.translateItemTooltips) {
            return;
        }
        List<String> toolTip = event.toolTip;
        if (toolTip == null) {
            return;
        }
        for (int i = 0; i < toolTip.size(); i++) {
            String line = toolTip.get(i);
            String translated = TranslationHelper.translateLineWithCodes(line);
            if (translated != null && !translated.equals(line)) {
                toolTip.set(i, translated);
            }
        }
    }

    @SubscribeEvent
    public void onRenderOverlay(RenderGameOverlayEvent.Post event) {
        if (event.type != RenderGameOverlayEvent.ElementType.ALL) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.fontRendererObj == null) {
            return;
        }
        ScaledResolution res = event.resolution;
        int w = res.getScaledWidth();
        int h = res.getScaledHeight();
        FontRenderer font = mc.fontRendererObj;

        // 左上角当前后端/模型
        String info = describe();
        font.drawStringWithShadow(info, 2, 2, 0xFFFFFF);

        if (TongyongfanyiMod.config == null || !TongyongfanyiMod.config.enabled) {
            return;
        }

        // 计分板侧栏（覆盖翻译）
        if (mc.theWorld == null || mc.theWorld.getScoreboard() == null) {
            return;
        }
        Scoreboard scoreboard = mc.theWorld.getScoreboard();
        ScoreObjective objective = scoreboard.getObjectiveInDisplaySlot(1);
        if (objective == null) {
            return;
        }
        try {
            String title = TranslationHelper.translateText(objective.getDisplayName());
            Collection<Score> scores = scoreboard.getSortedScores(objective);
            List<Score> list = new ArrayList<Score>(scores);
            if (list.size() > 15) {
                list = list.subList(0, 15);
            }
            List<String> lines = new ArrayList<String>();
            int maxWidth = 0;
            for (Score score : list) {
                String name = score.getPlayerName();
                String translated = TranslationHelper.translateText(name);
                lines.add(translated);
                maxWidth = Math.max(maxWidth, font.getStringWidth(translated));
            }
            maxWidth = Math.max(maxWidth, font.getStringWidth(title));
            int boxW = maxWidth + 2;
            int x = w - boxW - 2;
            int y = 10;
            int lineH = font.FONT_HEIGHT;
            int boxH = 2 + lineH + lines.size() * lineH + 2;

            GuiHelper.drawRect(x - 1, y - 1, x + boxW + 1, y + boxH + 1, 0xD0000000);
            GuiHelper.drawRect(x, y, x + boxW, y + boxH, 0x60000000);
            font.drawStringWithShadow(title, x + (boxW - font.getStringWidth(title)) / 2, y + 1, 0xFFFF00);
            for (int i = 0; i < lines.size(); i++) {
                font.drawStringWithShadow(lines.get(i), x + 1, y + 1 + lineH + i * lineH, 0xFFFFFF);
            }
        } catch (Exception e) {
            // 计分板结构异常时忽略，不影响游戏
        }
    }

    private String describe() {
        ModConfig config = TongyongfanyiMod.config;
        if (config == null) {
            return "翻译: 未初始化";
        }
        if (!config.enabled) {
            return "翻译: 已关闭";
        }
        return "翻译: 本地 " + config.ollamaModel;
    }

    /** 用 Gui.drawRect 画实心矩形 */
    private static final class GuiHelper {
        private GuiHelper() {
        }

        static void drawRect(int left, int top, int right, int bottom, int color) {
            net.minecraft.client.gui.Gui.drawRect(left, top, right, bottom, color);
        }
    }
}
