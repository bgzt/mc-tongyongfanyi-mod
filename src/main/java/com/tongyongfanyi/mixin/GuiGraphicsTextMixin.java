package com.tongyongfanyi.mixin;

import com.tongyongfanyi.translate.HoverTextTracker;
import com.tongyongfanyi.translate.TranslationHelper;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.util.FormattedCharSequence;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

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

    /**
     * textWithBackdrop：带背景条的居中文字（物品栏上方的物品名、动作栏、标题/副标题、
     * 存档提示、演示模式提示）。
     *
     * <p><b>不能只换文字</b>。原版调用点的 x 和 backdrop 宽度都是拿<b>英文</b>算的：
     * <pre>
     * w = font.width(name);  x = (guiWidth - w) / 2;  textWithBackdrop(font, name, x, y, w, c)
     * </pre>
     * 只替换 Component 的话，中文比英文窄，文字就从原来那块区域的**左边缘**开始画，
     * 视觉上整行往左偏（物品名、动作栏都中招）。所以这里按原来那块区域的居中点
     * 重算 x 与新宽度，再照原版的样子自己画一遍（背景 + 文字阴影）。
     */
    @Inject(method = "textWithBackdrop(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;IIII)V",
            at = @At("HEAD"), cancellable = true)
    private void tyfy$textWithBackdrop(Font font, Component text, int x, int y, int width, int color,
                                       CallbackInfo ci) {
        Component translated = TranslationHelper.translateRender(text);
        if (translated == null || translated == text) {
            return;   // 没译文 / 正在看原文：原样交给原版
        }
        int newWidth = font.width(translated);
        // 原来那块区域的水平中心；width 异常时退化成「按左边缘对齐」
        int nx = width > 0 ? x + width / 2 - newWidth / 2 : x;
        GuiGraphicsExtractor self = (GuiGraphicsExtractor) (Object) this;
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc != null && mc.options != null) {
            // 与原版完全一致：背景色由 options 的「文字背景」设置给出，
            // 矩形范围是 (x-2, y-2) ~ (x+w+2, y+11)，再按文字颜色乘一次。
            int background = mc.options.getBackgroundColor(0.0f);
            if (background != 0) {
                self.fill(nx - 2, y - 2, nx + newWidth + 2, y + 11,
                        net.minecraft.util.ARGB.multiply(background, color));
            }
        }
        // 原版这里的 text(...) 是带阴影的
        self.text(font, translated, nx, y, color, true);
        ci.cancel();
    }

    /**
     * String 重载：大量模组界面（机器、商店、任务面板）直接用 {@code graphics.text(font, "Some Text", ...)} 画字，
     * 不走 Component，所以只拦 Component 版会漏掉这些。单个玩家本地世界的模组界面尤其常见。
     */
    @ModifyVariable(method = "text(Lnet/minecraft/client/gui/Font;Ljava/lang/String;IIIZ)V",
            at = @At("HEAD"), argsOnly = true)
    private String tyfy$translateTextString(String text) {
        return TranslationHelper.translateRenderString(text);
    }

    @ModifyVariable(method = "text(Lnet/minecraft/client/gui/Font;Ljava/lang/String;III)V",
            at = @At("HEAD"), argsOnly = true)
    private String tyfy$translateTextStringShort(String text) {
        return TranslationHelper.translateRenderString(text);
    }

    @ModifyVariable(method = "centeredText(Lnet/minecraft/client/gui/Font;Ljava/lang/String;III)V",
            at = @At("HEAD"), argsOnly = true)
    private String tyfy$translateCenteredString(String text) {
        return TranslationHelper.translateRenderString(text);
    }

    /**
     * 折行整段翻译：{@code textWithWordWrap} 会先 {@code font.split()} 再逐行调底层
     * {@code text(FormattedCharSequence)}，绕过了上面所有 String/Component 注入点。
     * 第三方模组（Sodium/Iris 等）设置页右侧的说明文字就是这么一直显示英文的。
     * 这里在拆行之前整段翻，译文再由原版按中文折行，语序不会被切碎。
     *
     * <p>返回类型在两个版本里不一样：26.3 是 {@code int}（返回折行后的高度），
     * 26.2 还是 {@code void}。两个重载各挂一个处理器，并都设 {@code require = 0}，
     * 哪个版本匹配不到就静默跳过 —— 否则 26.2 会因为这个描述符对不上直接崩在启动阶段。
     */
    @ModifyVariable(method = "textWithWordWrap(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/FormattedText;IIIIZ)I",
            at = @At("HEAD"), argsOnly = true, require = 0)
    private FormattedText tyfy$translateWordWrap(FormattedText text) {
        return TranslationHelper.translateRenderFormattedText(text);
    }

    /** 26.2 的 {@code textWithWordWrap} 返回 void，描述符不同，需单独挂一个 */
    @ModifyVariable(method = "textWithWordWrap(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/FormattedText;IIIIZ)V",
            at = @At("HEAD"), argsOnly = true, require = 0)
    private FormattedText tyfy$translateWordWrapVoid(FormattedText text) {
        return TranslationHelper.translateRenderFormattedText(text);
    }

    /**
     * 底层 FormattedCharSequence 兜底：模组自己 split 后逐行画字时不经过 textWithWordWrap。
     * 中文序列/多色序列会原样返回，不会二次翻译或刷掉配色。
     */
    @ModifyVariable(method = "text(Lnet/minecraft/client/gui/Font;Lnet/minecraft/util/FormattedCharSequence;IIIZ)V",
            at = @At("HEAD"), argsOnly = true)
    private FormattedCharSequence tyfy$translateSequence(FormattedCharSequence seq) {
        return TranslationHelper.translateRenderSequence(seq);
    }

    /**
     * tooltip 整块翻译。
     *
     * <p>skyblocker / SkyHanni 的价格行是在 {@code ItemStack.getTooltipLines}
     * <b>返回之后</b>才追加进这个列表的，渲染时又会被拆成「标签 + 数字」多色序列，
     * 各种早退判断很容易把它们漏掉（实测它们从未进过翻译缓存）。
     * 在这里按行过一遍本地模板（价格行 / 整句术语），最可靠。
     */
    @ModifyVariable(method = "setTooltipForNextFrame(Lnet/minecraft/client/gui/Font;Ljava/util/List;Ljava/util/Optional;II)V",
            at = @At("HEAD"), argsOnly = true, require = 0)
    private java.util.List<net.minecraft.network.chat.Component> tyfy$translateTooltipList(
            java.util.List<net.minecraft.network.chat.Component> lines) {
        return TranslationHelper.translateTooltipLines(lines);
    }

    /** 同上的带 Identifier 版本（26.3 新增） */
    @ModifyVariable(method = "setTooltipForNextFrame(Lnet/minecraft/client/gui/Font;Ljava/util/List;Ljava/util/Optional;IILnet/minecraft/resources/Identifier;)V",
            at = @At("HEAD"), argsOnly = true, require = 0)
    private java.util.List<net.minecraft.network.chat.Component> tyfy$translateTooltipListId(
            java.util.List<net.minecraft.network.chat.Component> lines) {
        return TranslationHelper.translateTooltipLines(lines);
    }

    /** setComponentTooltipForNextFrame：部分模组（REI/EMI 预览）走这个更短的入口 */
    @ModifyVariable(method = "setComponentTooltipForNextFrame(Lnet/minecraft/client/gui/Font;Ljava/util/List;II)V",
            at = @At("HEAD"), argsOnly = true, require = 0)
    private java.util.List<net.minecraft.network.chat.Component> tyfy$translateComponentTooltipList(
            java.util.List<net.minecraft.network.chat.Component> lines) {
        return TranslationHelper.translateTooltipLines(lines);
    }

    /** setComponentTooltipForNextFrame 的带 Identifier 版本 */
    @ModifyVariable(method = "setComponentTooltipForNextFrame(Lnet/minecraft/client/gui/Font;Ljava/util/List;IILnet/minecraft/resources/Identifier;)V",
            at = @At("HEAD"), argsOnly = true, require = 0)
    private java.util.List<net.minecraft.network.chat.Component> tyfy$translateComponentTooltipListId(
            java.util.List<net.minecraft.network.chat.Component> lines) {
        return TranslationHelper.translateTooltipLines(lines);
    }

    /**
     * REI / EMI 的 tooltip 是自己画的：它们把 Component 转成
     * {@code List<ClientTooltipComponent>} 后直接调 {@code tooltip(...)}，
     * 完全不走 {@code setTooltipForNextFrame} / {@code setComponentTooltipForNextFrame}。
     * 这就是「背包里三行价格已翻好、右侧物品列表里同样三行还是英文」的原因：
     * 日志连一条 tooltip 入口诊断都没有。这里在最终绘制入口按组件处理一次。
     * 26.3 若方法签名不同，require=0 静默跳过，不影响其它入口。
     */
    @ModifyVariable(method = "tooltip(Lnet/minecraft/client/gui/Font;Ljava/util/List;IILnet/minecraft/client/gui/screens/inventory/tooltip/ClientTooltipPositioner;Lnet/minecraft/resources/Identifier;)V",
            at = @At("HEAD"), argsOnly = true, require = 0)
    private List<ClientTooltipComponent> tyfy$translateTooltipParts(
            List<ClientTooltipComponent> parts) {
        return TranslationHelper.translateTooltipComponents(parts);
    }

    /**
     * 26.3 所有文字绘制（String / Component / centeredText / textWithBackdrop /
     * textWithWordWrap / tooltip）最终都汇到这个 FormattedCharSequence 重载。
     * 只在这里记一次「文字 + 屏幕矩形」，鼠标指向重译就能命中任意界面，
     * 不必为每种重载分别做命中测试。x 已经是居中换算后的绘制起点。
     */
    @Inject(method = "text(Lnet/minecraft/client/gui/Font;Lnet/minecraft/util/FormattedCharSequence;IIIZ)V",
            at = @At("HEAD"))
    private void tyfy$recordDrawnText(Font font, FormattedCharSequence text,
                                      int x, int y, int color, boolean shadow,
                                      CallbackInfo ci) {
        HoverTextTracker.recordSequence((GuiGraphicsExtractor) (Object) this, font, text, x, y);
    }
}
