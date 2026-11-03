package com.tongyongfanyi.mixin;

import com.tongyongfanyi.translate.TranslationHelper;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * 屏幕绘制层通用翻译：任何模组界面（或未汉化的界面）画出的英文 Component 都走翻译缓存。
 * 只改客户端绘制内容，不影响任何组件/点击事件/发包。
 * 未命中的文字按速率限制后台翻译，缓存命中即时替换（界面每帧重绘，会"现场"变中文）。
 */
@Mixin(GuiGraphicsExtractor.class)
public abstract class GuiGraphicsTextMixin {
    /** text(Font, Component, x, y, color[, shadow]) —— 5 参数版会委托给 6 参数版，只需拦 6 参数版 */
    @ModifyVariable(method = "text(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;IIIZ)V",
            at = @At("HEAD"), argsOnly = true)
    private Component tyfy$translateText(Component component) {
        return TranslationHelper.translateRender(component);
    }

    /** centeredText：在入口翻译，保证居中位置按译文宽度计算 */
    @ModifyVariable(method = "centeredText(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;III)V",
            at = @At("HEAD"), argsOnly = true)
    private Component tyfy$translateCenteredText(Component component) {
        return TranslationHelper.translateRender(component);
    }

    /** textWithBackdrop：带背景文字（如物品名弹窗、HUD 提示） */
    @ModifyVariable(method = "textWithBackdrop(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;IIII)V",
            at = @At("HEAD"), argsOnly = true)
    private Component tyfy$translateBackdrop(Component component) {
        return TranslationHelper.translateRender(component);
    }
}
