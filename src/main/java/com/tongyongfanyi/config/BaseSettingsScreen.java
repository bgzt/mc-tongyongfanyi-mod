package com.tongyongfanyi.config;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 设置页基类：统一提供滚动、文字标签、分区标题与页脚。
 *
 * <p><b>滚动实现必须配合「离开可视区就停画」</b>，原因见 {@link #applyScroll()}：
 * 26.2 的 GUI 渲染器在裁剪矩形越界时会直接抛异常，而不是像旧版本那样静默裁剪。
 */
public abstract class BaseSettingsScreen extends Screen {
    /** 内容左边缘到屏幕左边缘的固定边距 */
    private static final int CONTENT_MARGIN = 110;

    protected int baseY = 40;
    protected int scrollOffset = 0;
    protected int maxScroll = 0;
    /** 控件的布局基准位置（未滚动、未钳制前），用于滚动时重新摆放 */
    protected final Map<AbstractWidget, WidgetBase> widgetBase = new LinkedHashMap<>();
    protected final List<LabelPos> fieldLabels = new ArrayList<>();
    protected final List<LabelPos> sectionHeaders = new ArrayList<>();

    protected record LabelPos(String text, int y) {
    }

    private record WidgetBase(int x, int y) {
    }

    protected BaseSettingsScreen(Component title) {
        super(title);
    }

    @Override
    protected void init() {
        super.init();
        baseY = 40;
        clearWidgets();
        fieldLabels.clear();
        sectionHeaders.clear();
        widgetBase.clear();
        rebuild();
        scrollOffset = 0;
        maxScroll = Math.max(0, baseY + 30 - height);
        for (GuiEventListener child : children()) {
            if (child instanceof AbstractWidget w) {
                widgetBase.put(w, new WidgetBase(w.getX(), w.getY()));
            }
        }
        applyScroll();
    }

    /** 子类在此构建控件 */
    protected abstract void rebuild();

    /** 文字标签 + 输入框（标签直接绘制为文字，不是假按钮） */
    protected EditBox label(String labelText, int x, int fieldW, int fieldH) {
        fieldLabels.add(new LabelPos(labelText, baseY + 4));
        baseY += 14;
        EditBox box = new EditBox(font, x, baseY, fieldW, fieldH, Component.literal(labelText));
        baseY += fieldH + 6;
        return box;
    }

    /** 分区标题 */
    protected void header(String text) {
        sectionHeaders.add(new LabelPos(text, baseY + 4));
        baseY += 14;
    }

    /** 内容区的左边缘（窗口很窄时不让内容跑到屏幕外，横向越界同样会触发裁剪异常） */
    protected int contentX() {
        return Math.max(2, Math.min(width / 2 - CONTENT_MARGIN, Math.max(2, width - 224)));
    }

    /**
     * 按当前 {@link #scrollOffset} 重新摆放控件，并隐藏离开可视区的控件。
     *
     * <p><b>为什么必须隐藏而不是放任画到屏幕外：</b>
     * 26.2 的按钮/输入框是用九宫格贴图绘制的，{@code GuiGraphicsExtractor.blitSprite}
     * 会先 {@code enableScissor(x, y, x+width, y+height)} 再绘制。
     * 而 {@code GuiRenderer.enableScissor} 只把矩形的右/下边钳到窗口内（{@code Math.min}），
     * **左/上边不钳**。于是控件被滚到屏幕上沿之外（y &lt; 0）时，换算出的裁剪矩形高度会超过
     * 渲染区高度，{@code RenderPass.enableScissor} 直接抛
     * {@code IllegalArgumentException: Scissor at ... is out of bounds for render area ...}
     * → 渲染线程崩溃、游戏弹出错误报告。
     * {@code AbstractWidget.extractRenderState} 有 {@code if (this.visible)} 守卫，
     * 所以把 visible 置 false 就能彻底避免这次绘制（同时也自动禁用点击）。
     */
    private void applyScroll() {
        int dx = contentX() - (width / 2 - CONTENT_MARGIN);
        for (Map.Entry<AbstractWidget, WidgetBase> e : widgetBase.entrySet()) {
            AbstractWidget w = e.getKey();
            int y = e.getValue().y() - scrollOffset;
            w.setY(y);
            w.setX(Math.max(0, Math.min(e.getValue().x() + dx, Math.max(0, width - w.getWidth()))));
            // 只允许「完整落在可视区内」的控件参与绘制：上沿越界会崩，下沿越界虽然安全，
            // 但会被画到页脚下面，一并隐藏更干净。
            w.visible = y >= 0 && y + w.getHeight() <= height;
        }
    }

    /** 该行文字在屏幕上是否可见（文字绘制不会触发裁剪，但画到屏幕外白费且会压住页脚） */
    private boolean onScreen(int y) {
        return y >= 0 && y + 9 <= height;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        // 26.2 中背景由 extractRenderStateWithTooltipAndSubtitles 在调用本方法前统一绘制，
        // 这里不能再调 extractBackground，否则同一帧会模糊两次直接崩溃。
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        graphics.centeredText(font, this.title, width / 2, 15, 0xFFFFFF);
        int x = contentX();
        for (LabelPos h : sectionHeaders) {
            int y = h.y() - scrollOffset;
            if (onScreen(y)) {
                graphics.text(font, h.text(), x, y, 0xFFFFA0);
            }
        }
        for (LabelPos l : fieldLabels) {
            int y = l.y() - scrollOffset;
            if (onScreen(y)) {
                graphics.text(font, l.text(), x, y, 0xAAAAAA);
            }
        }
        if (maxScroll > 0) {
            graphics.text(font, "滚轮滚动查看更多", x, height - 10, 0x808080);
        }
        extractFooter(graphics, x);
    }

    /** 子类可在底部追加状态文字 */
    protected void extractFooter(GuiGraphicsExtractor graphics, int x) {
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        int newOffset = scrollOffset - (int) Math.round(verticalAmount * 10);
        newOffset = Math.max(0, Math.min(newOffset, maxScroll));
        if (newOffset != scrollOffset) {
            scrollOffset = newOffset;
            applyScroll();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    /**
     * 只把可见控件算作可点击目标。
     * 原版 {@code AbstractWidget.isMouseOver} 只看 active + 矩形范围、**不看 visible**，
     * 所以被 {@link #applyScroll()} 隐藏、但矩形仍压在屏幕内的控件（下沿越界那些）
     * 会被"点不到却点得中"。这里显式跳过不可见控件。
     */
    @Override
    public java.util.Optional<GuiEventListener> getChildAt(double mouseX, double mouseY) {
        for (GuiEventListener child : children()) {
            if (child instanceof AbstractWidget w && !w.visible) {
                continue;
            }
            if (child.isMouseOver(mouseX, mouseY)) {
                return java.util.Optional.of(child);
            }
        }
        return java.util.Optional.empty();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
