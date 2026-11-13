package com.tongyongfanyi.config;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.tongyongfanyi.translate.Glossary;
import com.tongyongfanyi.translate.TranslationManager;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 「自定义翻译」子页面：玩家自己指定某个词译成什么（{@code book → 苹果}）。
 *
 * <p>条目写进用户术语表 {@code config/tongyongfanyi_glossary.json}，和内置表走完全相同的路：
 * <ul>
 *   <li>整行就是这个词 → 直接显示玩家给的内容，一次模型调用都不发；</li>
 *   <li>句子里出现这个词 → 送模型前先替换成玩家给的内容。</li>
 * </ul>
 *
 * <p>下方条目列表是<b>自己独立滚动</b>的（不设条数上限）：鼠标在列表里滚轮只动列表，
 * 每行右侧带「修改」「移除」两个按钮，点「修改」会把这条填回上面的输入框。
 */
public class CustomTranslateScreen extends BaseSettingsScreen {
    private static final int FIELD_W = 330;
    private static final int FIELD_H = 20;
    private static final int ROW_H = 22;
    private static final int BTN_W = 46;
    private static final int BTN_H = 20;
    private static final Type MAP_TYPE = new TypeToken<LinkedHashMap<String, String>>() {}.getType();
    private static final Gson GSON = new Gson();

    private final TranslationManager translationManager;

    private EditBox wordBox;
    private EditBox valueBox;
    /** 底部一行结果提示（保存/校验信息） */
    private String feedback = "";
    /** 列表框的页面坐标（未减滚动量） */
    private int listTopY = 140;
    private int listBottomY = 200;
    private int listScroll = 0;
    /** 左侧标签占的宽度 */
    private static final int LABEL_W = 46;

    private record Row(String word, Button edit, Button remove) {
    }

    private final List<Row> rows = new ArrayList<>();
    /** 术语表快照：每帧渲染要用，不能每次读文件 */
    private Map<String, String> terms = new LinkedHashMap<>();

    public CustomTranslateScreen(TranslationManager translationManager, Screen parent) {
        super(I18n.c("title.custom"), parent);
        this.translationManager = translationManager;
    }

    /** 这一页强制单列：标签按 columnX(0) 画，单列才和输入框对齐，行按钮坐标也稳定 */
    @Override
    protected int columns() {
        return 1;
    }

    @Override
    protected void rebuild() {
        int x = contentX();
        baseY += 4;

        header(I18n.t("section.custom"));
        // 框内灰色提示和框左侧标签都用「原文」「译文」这两个词。
        // 旧版框里写的是示例 book / 苹果，点进去（聚焦）后 hint 还会按原版习惯消失；
        // 左边手画的标签又因为颜色 alpha=0 被 26.3 直接丢弃，于是看起来就是两个没字的空框。
        wordBox = inlineField(I18n.t("field.custom_word"), x, baseY);
        baseY += FIELD_H + 6;

        valueBox = inlineField(I18n.t("field.custom_value"), x, baseY);
        baseY += FIELD_H + 6;

        baseY += 2;
        // 按钮和输入框右边界对齐，整页看起来是一条竖线
        Button save = Button.builder(I18n.c("button.custom_save"), btn -> save())
                .bounds(x + LABEL_W, baseY, FIELD_W - LABEL_W, FIELD_H).build();
        addRenderableWidget(save);
        baseY += FIELD_H + 12;

        // 说明文字占两行，然后才是列表标题
        hintY = baseY;
        baseY += 26;
        header(I18n.t("section.custom_list"));
        baseY += 2;

        listTopY = baseY + 6;
        // 底部给「保存结果」提示留一行，别和基类的滚动提示挤在同一 y
        listBottomY = Math.max(listTopY + ROW_H * 2, height - 72);

        rows.clear();
        buildRows();
        refreshRows();
        feedback = I18n.t("custom.count", String.valueOf(rows.size()));
    }

    /** 左边标签、右边输入框：标签由 {@link #extractRenderState} 自己画 */
    private EditBox inlineField(String label, int x, int y) {
        EditBox box = new EditBox(font, x + LABEL_W, y, FIELD_W - LABEL_W, FIELD_H,
                Component.literal(label));
        box.setMaxLength(64);
        // 框内占位提示就是「原文」/「译文」；聚焦时 hint 会消失，
        // 但左侧还有常驻标签，点进去之后也不会变成没字的空框。
        box.setHint(Component.literal(label));
        addRenderableWidget(box);
        return box;
    }

    /** 说明文字的绘制位置 */
    private int hintY = 100;

    /** 按当前术语表重建所有行（不动上面输入区） */
    private void buildRows() {
        terms = customTerms();
        int x = contentX();
        int editX = x + FIELD_W - BTN_W * 2 - 6;
        int removeX = x + FIELD_W - BTN_W;
        for (Map.Entry<String, String> e : terms.entrySet()) {
            String key = e.getKey();
            if (key == null || key.startsWith("_")) {
                continue;
            }
            String value = e.getValue() == null ? "" : e.getValue();
            Button edit = Button.builder(I18n.c("custom.edit"), b -> loadForEdit(key))
                    .bounds(editX, 0, BTN_W, BTN_H).build();
            Button remove = Button.builder(I18n.c("custom.remove"), b -> removeEntry(key))
                    .bounds(removeX, 0, BTN_W, BTN_H).build();
            addRenderableWidget(edit);
            addRenderableWidget(remove);
            rows.add(new Row(key, edit, remove));
        }
    }

    /**
     * 基类的 init 会在 rebuild() 之后跑一遍 applyScroll()，按「整页滚动」重算所有控件的
     * 可见性 —— 那会把列表里已经滚出框外的行按钮又显示出来（会画到输入区甚至崩裁剪）。
     * 所以在最后再压一次列表自己的可见性。
     */
    @Override
    protected void init() {
        super.init();
        refreshRows();
    }

    /** 按滚动量重新摆放每行按钮，并把不在可视区里的行隐藏（越界绘制会崩） */
    private void refreshRows() {
        int viewport = Math.max(ROW_H, listBottomY - listTopY);
        int maxScroll = Math.max(0, rows.size() * ROW_H - viewport);
        listScroll = Math.max(0, Math.min(listScroll, maxScroll));
        int baseTop = listTopY - listScroll - scrollOffset;
        int top = listTopY - scrollOffset;
        int bottom = listBottomY - scrollOffset;
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            int y = baseTop + i * ROW_H;
            boolean visible = y >= top && y + BTN_H <= bottom;
            for (Button b : new Button[]{row.edit(), row.remove()}) {
                b.setY(y);
                b.visible = visible;
                b.active = visible;
            }
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        int x = contentX();
        int top = listTopY - scrollOffset;
        int bottom = listBottomY - scrollOffset;
        boolean inList = mouseX >= x - 6 && mouseX <= x + FIELD_W + 6
                && mouseY >= top && mouseY <= bottom;
        if (inList) {
            listScroll -= (int) Math.round(verticalAmount * ROW_H);
            refreshRows();
            return true;
        }
        boolean handled = super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
        refreshRows();
        return handled;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        int x = contentX();
        int top = listTopY - scrollOffset;
        int bottom = listBottomY - scrollOffset;

        // 左侧常驻标签：用输入框的实际坐标，滚动/布局钳制后仍然贴着框
        if (wordBox != null) {
            int wy = wordBox.getY() + 6;
            if (wy > 18 && wy < height - 34) {
                graphics.text(font, I18n.t("field.custom_word"),
                        wordBox.getX() - LABEL_W, wy, 0xFFDDDDDD);
            }
        }
        if (valueBox != null) {
            int vy = valueBox.getY() + 6;
            if (vy > 18 && vy < height - 34) {
                graphics.text(font, I18n.t("field.custom_value"),
                        valueBox.getX() - LABEL_W, vy, 0xFFDDDDDD);
            }
        }

        // 列表框：半透明底 + 上下边线，滚动时能看出边界
        graphics.fill(x - 4, top - 2, x + FIELD_W + 4, bottom, 0x40000000);
        graphics.fill(x - 4, top - 2, x + FIELD_W + 4, top - 1, 0x60FFFFFF);
        graphics.fill(x - 4, bottom - 1, x + FIELD_W + 4, bottom, 0x60FFFFFF);

        if (rows.isEmpty()) {
            graphics.text(font, I18n.t("custom.empty"), x, top + 6, 0xFFAAAAAA);
            return;
        }
        int maxScroll = Math.max(0, rows.size() * ROW_H - (listBottomY - listTopY));
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            int y = top + i * ROW_H - listScroll;
            if (y < top || y + ROW_H > bottom + 2) {
                continue;
            }
            String cn = terms.get(row.word());
            String line = row.word() + " → " + (cn == null ? "" : cn);
            String shown = line.length() > 20 ? line.substring(0, 19) + "…" : line;
            graphics.text(font, shown, x, y + 6, 0xFFFFFFFF);
            // 这条覆盖了内置译名时标出来：删掉它就等于恢复内置，玩家能提前知道。
            String builtin = Glossary.builtin(row.word());
            if (builtin != null && cn != null && !builtin.equalsIgnoreCase(cn)) {
                int noteX = x + font.width(shown) + 8;
                int btnX = x + FIELD_W - BTN_W * 2 - 6;
                String note = I18n.t("custom.overrides", builtin);
                if (noteX + font.width(note) > btnX - 4) {
                    while (!note.isEmpty() && noteX + font.width(note) > btnX - 4) {
                        note = note.substring(0, note.length() - 1);
                    }
                }
                if (!note.isEmpty()) {
                    graphics.text(font, note, noteX, y + 6, 0xFF7F7F7F);
                }
            }
        }
        if (maxScroll > 0) {
            graphics.text(font, I18n.t("custom.scroll_hint"), x + FIELD_W + 10, top + 4, 0xFF808080);
        }
    }

    @Override
    protected void extractFooter(GuiGraphicsExtractor graphics, int x) {
        graphics.text(font, I18n.t("custom.hint1"), x, hintY - scrollOffset, 0xFF808080);
        graphics.text(font, I18n.t("custom.hint2"), x, hintY - scrollOffset + 11, 0xFF808080);
        if (feedback != null && !feedback.isEmpty()) {
            graphics.text(font, feedback, x, height - 60, 0xFFFFFFA0);
        }
    }

    /** 「修改」：把这条填回上面的输入框，改完点保存 */
    private void loadForEdit(String word) {
        if (wordBox != null) {
            wordBox.setValue(word);
        }
        if (valueBox != null) {
            valueBox.setValue(terms.getOrDefault(word, ""));
        }
        feedback = I18n.t("custom.loaded", word);
    }

    /** 「移除」：直接从用户术语表删掉并立即生效 */
    private void removeEntry(String word) {
        Map<String, String> terms = customTerms();
        terms.remove(word);
        if (!writeTerms(terms)) {
            feedback = I18n.t("custom.save_failed");
            return;
        }
        applyToRuntime();
        reloadRows();
        feedback = I18n.t("custom.removed", word);
    }

    /** 保存：译名留空表示删除 */
    private void save() {
        String word = wordBox == null ? "" : wordBox.getValue().trim();
        String value = valueBox == null ? "" : valueBox.getValue().trim();
        if (word.isEmpty()) {
            feedback = I18n.t("custom.need_word");
            return;
        }
        Map<String, String> terms = customTerms();
        String key = word.toLowerCase(Locale.ROOT);
        // 同名但大小写不同的旧键先清掉：否则文件里会同时留着 Book 和 book 两条，
        // reload 之后谁生效取决于遍历顺序，玩家会看到「删了还在」。
        List<String> dup = new ArrayList<>();
        for (String k : terms.keySet()) {
            if (k != null && !k.equals(key) && k.toLowerCase(Locale.ROOT).equals(key)) {
                dup.add(k);
            }
        }
        dup.forEach(terms::remove);
        boolean removed = false;
        if (value.isEmpty()) {
            removed = terms.remove(key) != null;
        } else {
            terms.put(key, value);
        }
        if (!writeTerms(terms)) {
            feedback = I18n.t("custom.save_failed");
            return;
        }
        applyToRuntime();
        reloadRows();
        feedback = removed ? I18n.t("custom.removed", word) : I18n.t("custom.saved", word, value);
        if (valueBox != null) {
            valueBox.setValue("");
        }
    }

    /**
     * 术语表重载 + 清旧译文缓存 + 刷新聊天历史。
     *
     * <p>缓存是按原文存的，改完/删完如果不刷新，屏幕上已翻好的句子还会用旧译名
     * （删掉自定义后看着像「没退回内置」）。清缓存 + rescaleChat 之后，
     * 聊天里的旧句子会重新走一遍：先显示原文，译文到了就是新的（回退后的）结果。
     */
    private void applyToRuntime() {
        Glossary.reload();
        if (translationManager != null) {
            translationManager.clearCache();
        }
        com.tongyongfanyi.TongyongfanyiClient.refreshChatLayout();
    }

    /** 只重建列表行，不动上面输入区 */
    private void reloadRows() {
        for (Row row : rows) {
            removeWidget(row.edit());
            removeWidget(row.remove());
        }
        rows.clear();
        buildRows();
        refreshRows();
    }

    private static Path glossaryPath() {
        return FabricLoader.getInstance().getConfigDir().resolve("tongyongfanyi_glossary.json");
    }

    /** 读用户术语表（文件不存在时返回空表） */
    private static Map<String, String> customTerms() {
        try {
            Path p = glossaryPath();
            if (!Files.isRegularFile(p)) {
                return new LinkedHashMap<>();
            }
            try (Reader r = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
                Map<String, String> m = GSON.fromJson(r, MAP_TYPE);
                return m == null ? new LinkedHashMap<>() : m;
            }
        } catch (Exception t) {
            return new LinkedHashMap<>();
        }
    }

    private static boolean writeTerms(Map<String, String> terms) {
        try {
            Path p = glossaryPath();
            Files.createDirectories(p.getParent());
            try (Writer w = Files.newBufferedWriter(p, StandardCharsets.UTF_8)) {
                GSON.toJson(terms, MAP_TYPE, w);
            }
            return true;
        } catch (Exception t) {
            return false;
        }
    }
}
