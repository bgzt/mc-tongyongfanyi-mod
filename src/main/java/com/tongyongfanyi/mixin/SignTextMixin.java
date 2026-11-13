package com.tongyongfanyi.mixin;

import com.tongyongfanyi.translate.TranslationHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.level.block.entity.SignText;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;
import java.util.function.Function;

/**
 * 告示牌文字翻译（立牌 / 悬挂牌、正面背面都走这里）。
 *
 * <p>26.x 的告示牌渲染只有一个入口：{@code AbstractSignRenderer.submitSignText} 调
 * {@code SignText.getRenderMessages(filtered, converter)} 拿到 4 行 {@link FormattedCharSequence}，
 * 所以拦这一处就能通吃所有牌子，而且是在「组件 → 字形序列」之前替换，
 * 颜色、发光、点击事件全部由原版 converter 重新生成，不会丢。
 *
 * <p>但原版这个方法把结果缓存进 {@code renderMessages} 字段，只在「为空」或「filtered 变了」时重算。
 * 译文是后台线程异步落进缓存的，第一帧拿到的必然是英文并被永久缓存 ——
 * 所以这里自己维护一份缓存：原文没变就直接返回上次的数组，
 * 还有英文残留时每 10 帧再看一次（译文到位后自动停）。
 */
@Mixin(SignText.class)
public abstract class SignTextMixin {

    @Unique
    private FormattedCharSequence[] tyfy$lines;

    @Unique
    private String tyfy$sourceKey;

    @Unique
    private boolean tyfy$waiting;

    @Unique
    private int tyfy$tick;

    @Unique
    private int tyfy$retries;

    @Inject(method = "getRenderMessages", at = @At("HEAD"), cancellable = true)
    private void tyfy$translatedRenderMessages(boolean filtered,
                                               Function<Component, FormattedCharSequence> converter,
                                               CallbackInfoReturnable<FormattedCharSequence[]> cir) {
        try {
            boolean showingOriginal = TranslationHelper.showingOriginal();
            List<Component> messages = tyfy$messages(filtered);
            // 状态算进指纹：按 O 后下一帧就重算，牌子上的文字立刻在原文/译文之间切换
            String key = (showingOriginal ? "O" : "T") + "\u0000" + tyfy$key(messages);

            boolean recompute = tyfy$lines == null || !key.equals(tyfy$sourceKey);
            if (!recompute && tyfy$waiting) {
                // 原文没变、但上一轮还有英文没翻出来：译文是异步落缓存的，隔几帧再看
                recompute = (++tyfy$tick % 10) == 0 && tyfy$retries < 200;
            }
            if (!recompute) {
                cir.setReturnValue(tyfy$lines);
                return;
            }

            FormattedCharSequence[] lines = new FormattedCharSequence[messages.size()];
            boolean waiting = false;
            for (int i = 0; i < messages.size(); i++) {
                Component translated = TranslationHelper.translateSign(messages.get(i));
                // 显示原文时残留英文是刻意的，不要每 10 帧白跑一次复查
                if (!showingOriginal && tyfy$hasLatin(translated.getString())) {
                    waiting = true;
                }
                lines[i] = converter.apply(translated);
            }

            if (!key.equals(tyfy$sourceKey)) {
                tyfy$retries = 0;
            } else {
                tyfy$retries++;
            }
            tyfy$lines = lines;
            tyfy$sourceKey = key;
            tyfy$waiting = waiting;
            cir.setReturnValue(lines);
        } catch (Throwable t) {
            // 任何意外都退回原版行为，别让一块牌子把渲染搞崩
            tyfy$lines = null;
            tyfy$sourceKey = null;
            tyfy$waiting = false;
        }
    }

    /** 缓存 getMessages 方法引用：签名在两个版本间不同，只能反射拿 */
    @Unique
    private static java.lang.reflect.Method tyfy$getMessagesMethod;

    /**
     * 取这块牌子的 4 行文字。
     *
     * <p>{@code SignText.getMessages(boolean)} 的返回类型变过：
     * 26.2 是 {@code Component[]}，26.3 改成了 {@code List<Component>}。
     * 反射调用后统一转成 List，两个版本都能编译、都能跑。
     */
    @Unique
    @SuppressWarnings("unchecked")
    private List<Component> tyfy$messages(boolean filtered) throws Exception {
        if (tyfy$getMessagesMethod == null) {
            tyfy$getMessagesMethod = SignText.class.getMethod("getMessages", boolean.class);
        }
        Object raw = tyfy$getMessagesMethod.invoke(this, filtered);
        if (raw instanceof List<?> list) {
            return (List<Component>) list;
        }
        if (raw instanceof Component[] array) {
            return List.of(array);
        }
        return List.of();
    }

    /** 原文指纹：行数 + 每行纯文本。牌子内容是方块实体数据，改名/换字就会变。 */
    @Unique
    private static String tyfy$key(List<Component> messages) {
        StringBuilder sb = new StringBuilder(messages.size() * 16);
        for (int i = 0; i < messages.size(); i++) {
            sb.append(messages.get(i).getString()).append('\u0000');
        }
        return sb.toString();
    }

    /** 还剩拉丁字母就说明可能没翻完（中文里夹的 HP / SkyBlock 之类不影响，最多多看几次） */
    @Unique
    private static boolean tyfy$hasLatin(String text) {
        if (text == null) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')) {
                return true;
            }
        }
        return false;
    }
}
