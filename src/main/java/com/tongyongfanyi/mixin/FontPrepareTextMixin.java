package com.tongyongfanyi.mixin;

import com.tongyongfanyi.translate.TranslationHelper;
import net.minecraft.client.gui.Font;
import net.minecraft.util.FormattedCharSequence;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * 最底层兜底：{@code Font.prepareText} 预生成字形。
 *
 * <p>有些模组不经过 {@code GuiGraphicsExtractor.text(...)}，而是自己算好位置后
 * 直接调 {@code Font.prepareText()} 生成 {@code PreparedText}，再交给自己的
 * 渲染管线批量画：
 * <ul>
 *   <li>Skyblocker 6.x 的世界空间标签（挖矿地点 {@code Royal Mines}、距离、
 *       路径点名）走 {@code PrimitiveCollectorImpl.submitText()} → {@code prepareText}；</li>
 *   <li>SkyHanni 的物品栏自绘文字最终也会落到 {@code GuiTextRenderState} /
 *       {@code prepareText}，绕开屏幕层的所有注入点。</li>
 * </ul>
 * 这些文字以前从来没进过翻译链路，日志里连一条请求都没有。
 *
 * <p>放在 {@code prepareText} 而不是各模组的类上，是为了不依赖具体模组：
 * 模组升级、类名变化都不会让注入失效；没有装这些模组时也不会有任何影响。
 *
 * <p>两个版本的签名一致（26.2 / 26.3 均为
 * {@code prepareText(FormattedCharSequence, float, float, int, boolean, boolean, int)}
 * 与 {@code prepareText(String, float, float, int, boolean, int)}）。
 * 已经翻成中文的文本会在 {@link TranslationHelper#translateRenderSequence} 的
 * 拉丁字母快速检查处直接返回，不会二次翻译，也不会重复入队。
 */
@Mixin(Font.class)
public abstract class FontPrepareTextMixin {

    /** 世界内/自绘管线的 FormattedCharSequence 版 */
    @ModifyVariable(
            method = "prepareText(Lnet/minecraft/util/FormattedCharSequence;FFIZZI)Lnet/minecraft/client/gui/Font$PreparedText;",
            at = @At("HEAD"), argsOnly = true, require = 0)
    private FormattedCharSequence tyfy$translatePreparedSequence(FormattedCharSequence text) {
        return TranslationHelper.translateRenderSequence(text);
    }

    /** 同一管线的 String 版（SkyHanni 的部分 HUD 文本用 String 拼接后直接画） */
    @ModifyVariable(
            method = "prepareText(Ljava/lang/String;FFIZI)Lnet/minecraft/client/gui/Font$PreparedText;",
            at = @At("HEAD"), argsOnly = true, require = 0)
    private String tyfy$translatePreparedString(String text) {
        return TranslationHelper.translateRenderString(text);
    }
}
