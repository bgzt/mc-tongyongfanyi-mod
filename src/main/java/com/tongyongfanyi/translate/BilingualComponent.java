package com.tongyongfanyi.translate;

import com.tongyongfanyi.TongyongfanyiClient;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentContents;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.contents.PlainTextContents;
import net.minecraft.util.FormattedCharSequence;

import java.util.List;
import java.util.function.Supplier;

/**
 * 一条同时记住「原文」和「译文」的聊天文本组件。
 *
 * <p>聊天栏的消息在收到的那一刻就被换成译文并拆成固定行缓存起来了，
 * 所以只靠绘制层开关没法把历史消息切回原文。这里把两个版本都留在组件里，
 * 由 {@link #getSiblings()} 按全局状态动态返回当前该显示的那一份；
 * 按键切换后调用 {@code ChatComponent#rescaleChat()} 重新拆行，历史消息
 * 就会整体在原文/译文之间切换，不需要重收消息、不会重复刷屏、也不重译。
 *
 * <p>只实现 {@link Component} 的四个抽象方法，子节点仍然是普通原版组件，
 * 因此逐段颜色、点击事件、悬停事件、序列化 codec 全都保持原样。
 */
public final class BilingualComponent implements Component {
    private final Component original;
    private final Supplier<Component> translated;

    public BilingualComponent(Component original, Component translated) {
        this(original, () -> translated);
    }

    /**
     * 译文用 Supplier 而不是固定对象：首次收到消息时缓存里还没有译文，
     * 后台翻完写入缓存后，只要聊天栏重新排版，这里就能拿到最新译文。
     */
    public BilingualComponent(Component original, Supplier<Component> translated) {
        this.original = original;
        this.translated = translated;
    }

    private Component active() {
        TongyongfanyiClient mod = TongyongfanyiClient.getInstance();
        TranslationManager manager = mod == null ? null : mod.getTranslationManager();
        boolean showOriginal = manager != null && manager.isShowingOriginal();
        if (showOriginal) {
            return original;
        }
        Component value = translated.get();
        return value != null ? value : original;
    }

    @Override
    public Style getStyle() {
        // 样式由子节点自己携带；这里留空避免重复叠加。
        return Style.EMPTY;
    }

    @Override
    public ComponentContents getContents() {
        return PlainTextContents.EMPTY;
    }

    @Override
    public List<Component> getSiblings() {
        return List.of(active());
    }

    @Override
    public FormattedCharSequence getVisualOrderText() {
        // 不能缓存：切换后同一个组件要能给出另一份文本。
        return Language.getInstance().getVisualOrder(this);
    }

    @Override
    public String toString() {
        return "BilingualComponent{" + active().getString() + "}";
    }
}
