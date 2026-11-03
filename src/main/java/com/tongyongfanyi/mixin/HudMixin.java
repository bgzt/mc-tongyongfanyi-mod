package com.tongyongfanyi.mixin;

import com.tongyongfanyi.TongyongfanyiClient;
import com.tongyongfanyi.config.ModConfig;
import com.tongyongfanyi.local.LocalModelService;
import com.tongyongfanyi.translate.TranslationHelper;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 左上角小字显示当前翻译后端/模型；屏幕中央标题、副标题与动作栏文字翻译 */
@Mixin(Hud.class)
public abstract class HudMixin {
    @Shadow
    public abstract Font getFont();

    /** 屏幕中央大标题（title）翻译：只改客户端显示，不发包 */
    @ModifyVariable(method = "setTitle", at = @At("HEAD"), argsOnly = true)
    private Component tyfy$translateTitle(Component component) {
        return TranslationHelper.translate(component);
    }

    /** 屏幕中央副标题（subtitle）翻译 */
    @ModifyVariable(method = "setSubtitle", at = @At("HEAD"), argsOnly = true)
    private Component tyfy$translateSubtitle(Component component) {
        return TranslationHelper.translate(component);
    }

    /** 动作栏（overlay/物品栏上方居中文字）翻译 */
    @ModifyVariable(method = "setOverlayMessage", at = @At("HEAD"), argsOnly = true)
    private Component tyfy$translateOverlay(Component component) {
        return TranslationHelper.translate(component);
    }

    @Inject(method = "extractRenderState", at = @At("RETURN"))
    private void tyfy$drawBackendInfo(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker, CallbackInfo ci) {
        ModConfig config = config();
        if (config == null) {
            return;
        }
        graphics.text(getFont(), describe(config), 2, 2, 0xA0FFFFFF);
        // 模型切换进度条：切换中实时显示，结束后短暂显示完成/失败
        String switchLine = LocalModelService.switchDisplayLine();
        if (switchLine != null) {
            graphics.text(getFont(), switchLine, 2, 12, 0xA0FFAA);
        }
    }

    private static ModConfig config() {
        try {
            TongyongfanyiClient mod = TongyongfanyiClient.getInstance();
            return mod != null ? mod.getConfig() : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static String describe(ModConfig config) {
        if (!config.enabled) {
            return "翻译: 已关闭";
        }
        String model = config.localModel.model;
        String label = LocalModelService.displayName(model);
        if (!LocalModelService.isRunningCached()) {
            return "翻译: 本地 " + label + " §c(服务未运行)";
        }
        return LocalModelService.isModelInstalledCached(model)
                ? "翻译: 本地 " + label
                : "翻译: 本地 " + label + " §c(模型未安装)";
    }
}
