package com.tongyongfanyi.config;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.function.Consumer;

/**
 * 模型选择页：把接口返回的模型全部列成一列，点哪个用哪个。
 *
 * <p>为什么不用下拉：模型列表动辄几十上百条（硅基流动、OpenRouter 这类尤甚），
 * 下拉要一圈圈转，还看不到全貌。这里一屏能看十几条、可以滚，当前选中的那个带勾。
 */
public class ModelPickScreen extends BaseSettingsScreen {
    private static final int ROW_H = 20;
    private static final int ROW_GAP = 24;

    private final List<String> models;
    private final String current;
    private final Consumer<String> onPick;

    private int rowY = 40;

    public ModelPickScreen(String title, List<String> models, String current,
                           Screen parent, Consumer<String> onPick) {
        super(Component.literal(title), parent);
        this.models = models;
        this.current = current == null ? "" : current.trim();
        this.onPick = onPick;
    }

    @Override
    protected void rebuild() {
        rowY = baseY;
        if (models.isEmpty()) {
            return;
        }
        int w = Math.min(420, Math.max(200, width - 80));
        for (String m : models) {
            boolean selected = m.equals(current);
            Button b = Button.builder(Component.literal(selected ? "✔ " + m : m), btn -> {
                        onPick.accept(m);
                        if (minecraft != null) {
                            // 选完立刻回上一页，并把选中的模型带回去
                            minecraft.setScreenAndShow(parentScreen());
                        }
                    })
                    .bounds((width - w) / 2, rowY, w, ROW_H)
                    .build();
            addRenderableWidget(b);
            rowY += ROW_GAP;
        }
        baseY = rowY;
    }
}
