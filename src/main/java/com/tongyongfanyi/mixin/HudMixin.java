package com.tongyongfanyi.mixin;

import com.tongyongfanyi.TongyongfanyiClient;
import com.tongyongfanyi.config.ModConfig;
import com.tongyongfanyi.local.BundledRuntime;
import com.tongyongfanyi.translate.TranslationHelper;
import com.tongyongfanyi.translate.TranslationManager;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 屏幕中央标题、副标题、动作栏文字翻译 + 左上角状态行 */
@Mixin(Hud.class)
public abstract class HudMixin {
    @Shadow
    public abstract Font getFont();

    /** 屏幕中央大标题（title）翻译：只改客户端显示，不发包 */
    @ModifyVariable(method = "setTitle", at = @At("HEAD"), argsOnly = true)
    private Component tyfy$translateTitle(Component component) {
        return TranslationHelper.translateHud(component);
    }

    /** 屏幕中央副标题（subtitle）翻译 */
    @ModifyVariable(method = "setSubtitle", at = @At("HEAD"), argsOnly = true)
    private Component tyfy$translateSubtitle(Component component) {
        return TranslationHelper.translateHud(component);
    }

    /** 动作栏（overlay/物品栏上方居中文字）翻译 */
    @ModifyVariable(method = "setOverlayMessage", at = @At("HEAD"), argsOnly = true)
    private Component tyfy$translateOverlay(Component component) {
        return TranslationHelper.translateHud(component);
    }

    /**
     * 左上角状态行：用的是哪个模型、以及当前有没有在翻译。
     *
     * <p>26.3 里这个 {@code FormattedCharSequence} 重载<b>也</b>挂了翻译钩子（所有绘制
     * 最后都汇到它），所以光换成 {@code getVisualOrderText()} 挡不住：只要正在翻译，
     * 每帧都会把「翻译: HY-MT2 1.8B (Q4) 正在翻译 (2)…」当待翻文本送模型，
     * 翻不出结果就重试，日志里全是「连续 5 次没翻出结果」，还白占队列。
     * 这里用 {@link TranslationHelper#beginSelfDraw()} 明确告诉翻译层「这段别碰」。
     */
    @Inject(method = "extractRenderState", at = @At("RETURN"))
    private void tyfy$drawStatusLine(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker, CallbackInfo ci) {
        TongyongfanyiClient mod = TongyongfanyiClient.getInstance();
        if (mod == null) {
            return;
        }
        ModConfig config = mod.getConfig();
        if (config == null || !config.showHudStatus) {
            return;
        }
        Component line = statusLine(config, mod.getTranslationManager());
        TranslationHelper.beginSelfDraw();
        try {
            graphics.text(getFont(), line.getVisualOrderText(), 2, 2, 0xA8FFFFFF);
        } finally {
            TranslationHelper.endSelfDraw();
        }
    }

    private static Component statusLine(ModConfig config, TranslationManager manager) {
        if (config != null && !config.enabled) {
            return Component.literal("翻译: ").append(Component.literal("已关闭").withStyle(ChatFormatting.GRAY));
        }
        int busy = manager == null ? 0 : manager.busyCount();
        return switch (BundledRuntime.state()) {
            // 闲置释放显存之后模型不在显存里了，别还写着「空闲」让人以为随时都能秒出译文
            case READY -> BundledRuntime.released()
                    ? Component.literal("翻译: ")
                            .append(Component.literal(BundledRuntime.modelLabel()))
                            .append(Component.literal(" 显存已释放（闲置，下次翻译自动加载）")
                                    .withStyle(ChatFormatting.GRAY))
                    : Component.literal("翻译: ")
                            .append(Component.literal(BundledRuntime.modelLabel()))
                            .append(busy > 0
                                    ? Component.literal(" §e正在翻译" + (busy > 1 ? " (" + busy + ")" : "") + "…")
                                            .withStyle(ChatFormatting.YELLOW)
                                    : Component.literal(" 空闲").withStyle(ChatFormatting.GRAY));
            case PREPARING -> Component.literal("翻译: ")
                    .append(Component.literal("启动中").withStyle(ChatFormatting.YELLOW))
                    .append(Component.literal(BundledRuntime.percent() >= 0
                            ? " " + BundledRuntime.percent() + "%" : "…").withStyle(ChatFormatting.GRAY));
            case FAILED -> Component.literal("翻译: ")
                    .append(Component.literal("启动失败（/tyfy status 看原因）").withStyle(ChatFormatting.RED));
            default -> Component.literal("翻译: ")
                    .append(Component.literal("未启动").withStyle(ChatFormatting.GRAY));
        };
    }
}
