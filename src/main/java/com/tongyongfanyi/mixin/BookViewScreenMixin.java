package com.tongyongfanyi.mixin;

import com.tongyongfanyi.translate.TranslationHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.BookViewScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentUtils;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * 成书阅读界面（{@code BookViewScreen}）整页文字翻译。
 *
 * <p>原版只在「翻页」时重建 {@code cachedPageComponents}（缓存键就是页号，
 * {@code visitText} 里 {@code cachedPage != currentPage} 才重算），
 * 而译文是后台线程异步落进缓存的 —— 光把 {@code BookAccess.getPage} 的返回值改掉，
 * 第一帧读到的英文就会被永久缓存，翻好之后不翻页永远看不到中文。
 *
 * <p>所以这里在 {@code visitText} 开头自己判重：原文没变就直接返回，
 * 只有「原文变了」或「上一轮还没译文（每 10 帧复查一次，最多 200 次）」才重新翻译并折行。
 * 折行宽度和字体样式都按原版来（114px、黑色、无阴影），排版与原版一致。
 */
@Mixin(BookViewScreen.class)
public abstract class BookViewScreenMixin {

    /** 原版 {@code BookViewScreen.TEXT_WIDTH} */
    @Unique
    private static final int TYFY_TEXT_WIDTH = 114;

    /** 原版 {@code PAGE_TEXT_STYLE}：Style.EMPTY.withoutShadow().withColor(-16777216) */
    @Unique
    private static final Style TYFY_PAGE_STYLE = Style.EMPTY.withoutShadow().withColor(-16777216);

    @Shadow
    private int cachedPage;

    @Shadow
    private int currentPage;

    @Shadow
    private List<FormattedCharSequence> cachedPageComponents;

    @Shadow
    private BookViewScreen.BookAccess bookAccess;

    /** 上次翻译用的原文指纹（页号 + 原文） */
    @Unique
    private String tyfy$srcKey;

    /** 当前已经折行显示出去的文本指纹（页号 + 实际显示内容） */
    @Unique
    private String tyfy$shownKey;

    /** 上一轮还全是原文，译文可能还在后台排队 */
    @Unique
    private boolean tyfy$pending;

    @Unique
    private int tyfy$tick;

    @Unique
    private int tyfy$retries;

    @Inject(method = "visitText", at = @At("HEAD"))
    private void tyfy$translatePage(CallbackInfo ci) {
        try {
            if (bookAccess == null) {
                return;
            }
            Component page = bookAccess.getPage(currentPage);
            if (page == null) {
                return;
            }
            // 把「显示原文」状态算进指纹：按 O 切换后下一帧就重建折行，
            // 不用等 10 帧的后台复查，也不会一直停留在上次缓存的译文/原文上。
            String srcKey = currentPage + "\u0000"
                    + (TranslationHelper.showingOriginal() ? "O" : "T") + "\u0000"
                    + page.getString();

            boolean check;
            if (!srcKey.equals(tyfy$srcKey)) {
                check = true;
                tyfy$retries = 0;
            } else if (tyfy$pending && (++tyfy$tick % 10) == 0 && tyfy$retries < 200) {
                // 译文异步落缓存：还没翻出来时隔几帧再看一眼，翻出来就停
                check = true;
            } else {
                check = false;
            }
            if (!check) {
                return;
            }

            Component shown = TranslationHelper.translateBookPage(page);
            tyfy$pending = shown == page;
            if (tyfy$pending) {
                tyfy$retries++;
            }
            String shownKey = currentPage + "\u0000" + shown.getString();
            if (!srcKey.equals(tyfy$srcKey)) {
                tyfy$srcKey = srcKey;
            }
            if (shownKey.equals(tyfy$shownKey)) {
                return;
            }
            tyfy$shownKey = shownKey;

            Minecraft mc = Minecraft.getInstance();
            if (mc == null || mc.font == null) {
                return;
            }
            cachedPageComponents = mc.font.split(
                    ComponentUtils.mergeStyles(shown, TYFY_PAGE_STYLE), TYFY_TEXT_WIDTH);
            // 让原版那段「页号变了才重算」的逻辑跳过，避免它拿英文覆盖刚翻好的行
            cachedPage = currentPage;
        } catch (Throwable t) {
            // 出任何意外都退回原版排版，别把书界面搞崩
            tyfy$srcKey = null;
            tyfy$shownKey = null;
            tyfy$pending = false;
        }
    }
}
