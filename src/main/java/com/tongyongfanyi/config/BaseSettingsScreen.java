package com.tongyongfanyi.config;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
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
    /** 底部固定「完成」按钮占用的高度：滚动内容不能画进这块区域 */
    private static final int FOOTER_H = 36;
    private static final int DONE_W = 200;
    private static final int DONE_H = 20;

    /** 两列布局：单列宽度、列间距。窗口太窄时自动退回单列 */
    protected static final int COL_W = 230;
    protected static final int COL_GAP = 16;

    protected int baseY = 40;
    protected int scrollOffset = 0;
    protected int maxScroll = 0;
    /** 控件的布局基准位置（未滚动、未钳制前），用于滚动时重新摆放 */
    protected final Map<AbstractWidget, WidgetBase> widgetBase = new LinkedHashMap<>();
    protected final List<LabelPos> fieldLabels = new ArrayList<>();
    protected final List<LabelPos> sectionHeaders = new ArrayList<>();

    /** 打开本设置页的界面；关闭时回到它（ModMenu 列表 / 暂停菜单 / 聊天栏） */
    private final Screen parent;
    /** 底部固定的「完成」按钮，不随内容滚动 */
    private Button doneButton;

    protected record LabelPos(String text, int y) {
    }

    private record WidgetBase(int x, int y) {
    }

    protected BaseSettingsScreen(Component title) {
        this(title, null);
    }

    protected BaseSettingsScreen(Component title, Screen parent) {
        super(title);
        this.parent = parent;
    }

    /** 重建设置页（切换界面语言时）要沿用同一个父界面 */
    protected Screen parentScreen() {
        return parent;
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
        // 和原版选项子页面一样，底部居中放一个不参与滚动的「完成」按钮
        doneButton = Button.builder(I18n.c("button.done"), btn -> onDone())
                .bounds(width / 2 - DONE_W / 2, height - 27, DONE_W, DONE_H)
                .build();
        addRenderableWidget(doneButton);
        scrollOffset = 0;
        maxScroll = Math.max(0, baseY + 30 - height + FOOTER_H);
        for (GuiEventListener child : children()) {
            if (child instanceof AbstractWidget w) {
                widgetBase.put(w, new WidgetBase(w.getX(), w.getY()));
            }
        }
        applyScroll();
    }

    /**
     * 底部「完成」按钮的动作。默认等同关闭；主设置页会覆写成「提交草稿」，
     * 以便和 Esc（取消）区分开 —— 玩家要求「没点完成就不要生效」。
     */
    protected void onDone() {
        onClose();
    }

    /** 关闭时回到打开它的界面；没记父界面时按原版行为直接回游戏 */
    @Override
    public void onClose() {
        if (parent != null && this.minecraft != null) {
            this.minecraft.setScreenAndShow(parent);
        } else {
            super.onClose();
        }
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

    /** 当前窗口能放几列：够宽就两列，窄窗口退回单列，避免右列被切掉 */
    protected int columns() {
        return width >= 2 * COL_W + COL_GAP + 40 ? 2 : 1;
    }

    /** 第 col 列的左边缘（整体居中对齐，和原版设置页一样居中） */
    protected int columnX(int col) {
        int cols = columns();
        int total = cols * COL_W + (cols - 1) * COL_GAP;
        int start = Math.max(4, (width - total) / 2);
        return start + Math.max(0, Math.min(col, cols - 1)) * (COL_W + COL_GAP);
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
        // 两列整体居中，不需要横向补偿；只有窄屏单列才把内容往左推
        int dx = columns() == 1 ? contentX() - (width / 2 - CONTENT_MARGIN) : 0;
        for (Map.Entry<AbstractWidget, WidgetBase> e : widgetBase.entrySet()) {
            AbstractWidget w = e.getKey();
            if (w == doneButton) {
                // 底部「完成」按钮固定在原位，不参与滚动，也不能被 visible 判定隐藏
                w.visible = true;
                continue;
            }
            int y = e.getValue().y() - scrollOffset;
            w.setY(y);
            w.setX(Math.max(0, Math.min(e.getValue().x() + dx, Math.max(0, width - w.getWidth()))));
            // 只允许「完整落在可视区内」的控件参与绘制：上沿越界会崩，下沿会画到
            // 页脚和「完成」按钮上，所以把下界收紧到 height - FOOTER_H。
            w.visible = y >= 0 && y + w.getHeight() <= height - FOOTER_H;
        }
    }

    /** 该行文字在屏幕上是否可见（同样要让出底部「完成」按钮区域，避免文字盖在按钮上） */
    private boolean onScreen(int y) {
        return y >= 0 && y + 9 <= height - FOOTER_H;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        // 26.2 中背景由 extractRenderStateWithTooltipAndSubtitles 在调用本方法前统一绘制，
        // 这里不能再调 extractBackground，否则同一帧会模糊两次直接崩溃。
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        graphics.centeredText(font, this.title, width / 2, 15, 0xFFFFFFFF);
        int x = columnX(0);
        for (LabelPos h : sectionHeaders) {
            int y = h.y() - scrollOffset;
            if (onScreen(y)) {
                graphics.text(font, h.text(), x, y, 0xFFFFFFA0);
            }
        }
        for (LabelPos l : fieldLabels) {
            int y = l.y() - scrollOffset;
            if (onScreen(y)) {
                graphics.text(font, l.text(), x, y, 0xFFAAAAAA);
            }
        }
        if (maxScroll > 0) {
            // 底部让给固定的「完成」按钮，页脚文字整体上移
            graphics.text(font, I18n.t("scroll.hint"), x, height - 46, 0xFF808080);
        }
        extractFooter(graphics, x);
        drawScrollbar(graphics);
    }

    // ------------------------------------------------------------------
    // 右侧滚动条
    // ------------------------------------------------------------------
    // 只有滚轮能滚时玩家常常不知道下面还有内容；26.2 的原版列表都有可见滚动条，
    // 这里补一个一致的：可点轨道跳转、可拖滑块。

    private static final int BAR_W = 6;
    private static final int BAR_MARGIN = 6;
    /** 正在拖滚动条：拖动期间不把点击事件传给下面的控件 */
    private boolean draggingBar;

    private int barX() {
        return width - BAR_MARGIN - BAR_W;
    }

    private int barTop() {
        return 30;
    }

    private int barBottom() {
        return height - FOOTER_H - 6;
    }

    private int barTrackH() {
        return Math.max(1, barBottom() - barTop());
    }

    private int barThumbH() {
        int track = barTrackH();
        if (maxScroll <= 0) {
            return track;
        }
        return Math.max(24, (int) ((long) track * track / (track + maxScroll)));
    }

    private int barThumbY() {
        int travel = barTrackH() - barThumbH();
        if (maxScroll <= 0 || travel <= 0) {
            return barTop();
        }
        return barTop() + (int) ((long) travel * scrollOffset / maxScroll);
    }

    private void drawScrollbar(GuiGraphicsExtractor g) {
        if (maxScroll <= 0) {
            return;   // 内容装得下就不画，免得看着像"还有东西没显示"
        }
        int x = barX();
        int top = barTop();
        int bottom = barBottom();
        g.fill(x, top, x + BAR_W, bottom, 0x30FFFFFF);
        int ty = barThumbY();
        int th = barThumbH();
        g.fill(x, ty, x + BAR_W, ty + th, 0xB0FFFFFF);
    }

    /** 点/拖滚动条：把鼠标 y 换算成滚动位置，滑块中心跟随鼠标 */
    private void scrollFromBar(double mouseY) {
        int travel = barTrackH() - barThumbH();
        if (travel <= 0) {
            return;
        }
        double ratio = (mouseY - barTop() - barThumbH() / 2.0) / travel;
        ratio = Math.max(0.0, Math.min(1.0, ratio));
        int newOffset = (int) Math.round(ratio * maxScroll);
        if (newOffset != scrollOffset) {
            scrollOffset = newOffset;
            applyScroll();
        }
    }

    private boolean inBar(double mouseX, double mouseY) {
        return maxScroll > 0 && mouseX >= barX() - 2 && mouseX <= barX() + BAR_W + 2
                && mouseY >= barTop() && mouseY <= barBottom();
    }

    @Override
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubleClick) {
        if (inBar(event.x(), event.y())) {
            draggingBar = true;
            scrollFromBar(event.y());
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseDragged(net.minecraft.client.input.MouseButtonEvent event, double dragX, double dragY) {
        if (draggingBar) {
            scrollFromBar(event.y());
            return true;
        }
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(net.minecraft.client.input.MouseButtonEvent event) {
        if (draggingBar) {
            draggingBar = false;
            return true;
        }
        return super.mouseReleased(event);
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

    /**
     * 从暂停菜单进来时保持「暂停界面」语义（背景暗化、单人世界继续暂停），
     * 从 ModMenu / 聊天栏进来时沿用父界面的设定 —— 两个入口的观感因此一致。
     */
    @Override
    public boolean isPauseScreen() {
        return parent != null && parent.isPauseScreen();
    }
}
