package com.tongyongfanyi.config;

import com.mojang.blaze3d.platform.InputConstants;
import com.tongyongfanyi.TongyongfanyiClient;
import com.tongyongfanyi.local.BundledRuntime;
import com.tongyongfanyi.translate.TranslationManager;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import com.tongyongfanyi.local.ModelPacks;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 通用设置页。
 *
 * <p>界面文案全部走 {@link I18n}（Minecraft 语言系统），会跟随游戏「选项 → 语言」自动切换；
 * 源/目标语言列表本身在 {@link Languages}，目标语言可选「跟随游戏语言」。
 *
 * <p><b>草稿语义</b>：控件只改一份配置副本 {@link #draft}，不写盘、不重启模型、不重建翻译器。
 * 点底部「完成」才把改过的字段写回真正的 {@link #config} 并统一执行一次必要的动作；
 * 按 Esc 直接丢弃草稿。以前每点一下「线程数」就完整重启一次模型服务
 * （杀进程 → 重新拉 llama-server → 重新加载 1.8B 模型，几十秒），连点几次还会排队重启，
 * 页脚就一直停在「正在启动…」。
 *
 * <p>布局上两个约定：
 * <ul>
 *   <li>每个分区标题后留 {@link #HEADER_GAP}，控件之间留 {@link #ROW_GAP}，
 *       否则标题字会和下面的按钮贴在一起（26.3 的按钮是九宫格贴图，视觉上更挤）。</li>
 *   <li>CycleButton 自己会渲染「标题: 值」，所以传入的显示函数<b>只能返回值本身</b>，
 *       再拼一遍标题就会出现「源语言: 源语言: 自动检测」。</li>
 * </ul>
 */
public class SettingsScreen extends BaseSettingsScreen {
    private static final org.slf4j.Logger LOGGER =
            org.slf4j.LoggerFactory.getLogger("tongyongfanyi-client");

    private static final List<Integer> THREADS = List.of(1, 2, 3, 4, 6, 8);
    private static final List<Integer> TIMEOUTS = List.of(5000, 10000, 20000, 30000, 60000);
    /** 闲置释放显存的候选时长（分钟）：0 = 不释放，模型一直占着那 1.6GB */
    private static final List<Integer> IDLE_RELEASES = List.of(0, 5, 10, 15, 30, 60);

    private static final int FIELD_W = 230;
    private static final int FIELD_H = 20;
    /** 两个控件之间的垂直间距（控件高 20，留 6px 空隙） */
    private static final int ROW_GAP = 26;
    /** 分区标题到第一个控件之间多留的空白 */
    private static final int HEADER_GAP = 22;

    /** 改键和原版「按键绑定」页共用同一个 KeyMapping 对象，注册名在 Client 里统一定义 */
    private static final String TOGGLE_KEY_NAME = TongyongfanyiClient.TOGGLE_KEY_NAME;
    private static final String RESCAN_KEY_NAME = TongyongfanyiClient.RESCAN_KEY_NAME;
    private static final String FORCE_KEY_NAME = TongyongfanyiClient.FORCE_KEY_NAME;
    /** 本页可改的键 → 文案 key；LinkedHashMap 保证界面顺序稳定 */
    private static final Map<String, String> KEY_LABELS = new LinkedHashMap<>();
    static {
        KEY_LABELS.put(TOGGLE_KEY_NAME, "field.toggle_key");
        KEY_LABELS.put(RESCAN_KEY_NAME, "field.rescan_key");
        KEY_LABELS.put(FORCE_KEY_NAME, "field.force_key");
    }

    /** 单个按键的草稿：录到的新键 / 是否正在录键 / 对应的按钮 */
    private static final class KeyDraft {
        InputConstants.Key pending;
        boolean capturing;
        Button button;
    }

    /** 深拷贝配置用；ModConfig 是纯 POJO，Gson 一下最省事也最不容易漏字段 */
    private static final com.google.gson.Gson DRAFT_GSON = new com.google.gson.Gson();

    /** 运行时真正的配置对象（TranslationManager 持有同一个引用） */
    private final ModConfig config;
    private final ConfigManager configManager;
    private final TranslationManager translationManager;

    /** 页面上的草稿：控件只读它、只改它 */
    private final ModConfig draft;
    /** 实际改过的字段名。提交时只写回这些，避免把子页面刚改过的设置覆盖成旧值 */
    private final Set<String> dirty = new HashSet<>();
    /** 「显示原文」是管理器状态而不是配置字段，单独暂存到提交时 */
    private Boolean pendingShowOriginal;

    /** 「显示原文」按钮：切换键也会改这份状态，需要在 tick 里同步按钮文字 */
    private Button showOriginalButton;


    /** 每个按键各自的草稿：录到的新键先存在这里，点「完成」才写入 KeyMapping / options.txt */
    private final Map<String, KeyDraft> keyDrafts = new LinkedHashMap<>();

    /** 两列布局游标：当前列 0/1 与当前行顶部 */
    private int col;
    private int rowY;

    public SettingsScreen(ModConfig config, ConfigManager configManager, TranslationManager translationManager) {
        this(config, configManager, translationManager, null);
    }

    /** @param parent 打开此页面的界面，关闭/点「完成」时回到它 */
    public SettingsScreen(ModConfig config, ConfigManager configManager,
                          TranslationManager translationManager, Screen parent) {
        this(config, configManager, translationManager, parent, null, null, null, null);
    }

    /** 切换界面语言时重建页面：草稿和改动记录必须沿用，否则没点「完成」的修改会凭空消失 */
    private SettingsScreen(ModConfig config, ConfigManager configManager,
                           TranslationManager translationManager, Screen parent,
                           ModConfig sharedDraft, Set<String> sharedDirty, Boolean showOriginal,
                           Map<String, InputConstants.Key> sharedPendingKeys) {
        super(I18n.c("title"), parent);
        this.config = config;
        this.configManager = configManager;
        this.translationManager = translationManager;
        this.draft = sharedDraft != null ? sharedDraft
                : DRAFT_GSON.fromJson(DRAFT_GSON.toJson(config), ModConfig.class);
        if (sharedDirty != null) {
            dirty.addAll(sharedDirty);
        }
        this.pendingShowOriginal = showOriginal;
        for (String name : KEY_LABELS.keySet()) {
            keyDrafts.put(name, new KeyDraft());
        }
        if (sharedPendingKeys != null) {
            sharedPendingKeys.forEach((name, key) -> {
                KeyDraft d = keyDrafts.get(name);
                if (d != null) {
                    d.pending = key;
                }
            });
        }
    }

    @Override
    protected void rebuild() {
        // 两列布局：分区标题独占一行，控件左右并排；窗口太窄时 columns() 自动退回单列
        col = 0;
        rowY = baseY;
        // 在线 API 不需要模型包、也不启动本地 Ollama：这两栏整块隐藏
        boolean apiMode = "api".equalsIgnoreCase(draft.translateBackend);

        // ---- 翻译开关 ----
        section("section.toggle");
        put(Button.builder(stateText(I18n.t("field.enabled"), draft.enabled), btn -> {
                    draft.enabled = !draft.enabled;
                    dirty.add("enabled");
                    btn.setMessage(stateText(I18n.t("field.enabled"), draft.enabled));
                })
                .bounds(0, 0, FIELD_W, FIELD_H).build());

        // ---- 语言 ----
        section("section.language");
        put(CycleButton.<String>builder(
                        value -> Component.literal(Languages.uiLabel(value)),
                        Languages.byId(Languages.SOURCES, draft.sourceLanguage).id())
                .withValues(Languages.ids(Languages.SOURCES))
                .create(0, 0, FIELD_W, FIELD_H, I18n.c("field.source"),
                        (btn, value) -> {
                            draft.sourceLanguage = value;
                            dirty.add("sourceLanguage");
                        }));
        put(CycleButton.<String>builder(
                        value -> Component.literal(Languages.displayTarget(value)),
                        draft.targetLanguage)
                .withValues(Languages.ids(Languages.TARGETS))
                .create(0, 0, FIELD_W, FIELD_H, I18n.c("field.target"),
                        (btn, value) -> {
                            draft.targetLanguage = value;
                            dirty.add("targetLanguage");
                        }));
        put(CycleButton.<String>builder(
                        value -> Component.literal(I18n.uiName(value)),
                        I18n.UI_LANGUAGES.contains(draft.uiLanguage) ? draft.uiLanguage : "auto")
                .withValues(I18n.UI_LANGUAGES)
                .create(0, 0, FIELD_W, FIELD_H, I18n.c("field.ui_language"),
                        (btn, value) -> {
                            draft.uiLanguage = value;
                            dirty.add("uiLanguage");
                            // 界面语言要立刻看到效果，所以只做「预览」：改全局文案、重建本页，
                            // 配置值仍等点「完成」才写盘（Esc 取消时会恢复成 config.uiLanguage）。
                            I18n.setUiLanguage(value);
                            clearCapturing();
                            if (minecraft != null) {
                                minecraft.setScreenAndShow(new SettingsScreen(
                                        config, configManager, translationManager, parentScreen(),
                                        draft, dirty, pendingShowOriginal, pendingKeysSnapshot()));
                            }
                        }));

        // ---- 翻译方式 ----
        // 放在最外层而不是塞进子页面：它决定后面哪些设置生效（本地和 API 各有一套
        // 参数），藏进二级页面会让人必须先点进去才能切，切换成本很高。
        section("section.backend");
        put(backendModeButton(FIELD_H));
        if ("api".equalsIgnoreCase(draft.translateBackend)) {
            // 只有 API 才有额外参数；本地模式的参数全在本页，不需要子页面
            put(Button.builder(backendEntryLabel(), btn -> {
                        if (minecraft != null) {
                            minecraft.setScreenAndShow(new BackendScreen(config, draft, dirty, this));
                        }
                    })
                    .bounds(0, 0, FIELD_W, FIELD_H).build());
        }
        // 请求超时对两种后端都生效（本地等推理、在线等网络），放在「方式」这一栏
        List<Integer> timeoutOptions = withCurrent(TIMEOUTS, draft.timeoutMs);
        put(CycleButton.<Integer>builder(
                        value -> Component.literal(I18n.t("unit.seconds", value / 1000)),
                        draft.timeoutMs)
                .withValues(timeoutOptions)
                .create(0, 0, FIELD_W, FIELD_H, I18n.c("field.timeout"),
                        (btn, value) -> {
                            draft.timeoutMs = value;
                            dirty.add("timeoutMs");
                        }));

        // 在线 API 模式下这两栏没有意义（模型包和 Ollama 进程都用不上），
        // 整块隐藏，免得玩家以为要在这里配点什么。
        if (!apiMode) {
            // ---- 模型包（仅本地模型用）----
            List<ModelPacks.Pack> modelPacks = ModelPacks.all();
            if (!modelPacks.isEmpty()) {
                section("section.model_pack");
                put(modelPackButton(FIELD_H, modelPacks));
            }

            // ---- 运行 Ollama ----
            // 这一栏只放「Ollama 进程本身」的设置：用哪份运行时、跑在什么设备上、
            // 开几路并行、闲置多久释放显存。API 的参数在「翻译方式」和子页面里。
            section("section.runtime");
            put(runtimeModeButton(FIELD_H));
            put(deviceButton(FIELD_H));
            List<Integer> threadOptions = withCurrent(THREADS, draft.translationThreads);
            put(CycleButton.<Integer>builder(
                            value -> Component.literal(String.valueOf(value)),
                            draft.translationThreads)
                    .withValues(threadOptions)
                    .create(0, 0, FIELD_W, FIELD_H, I18n.c("field.local_threads"),
                            (btn, value) -> {
                                draft.translationThreads = value;
                                dirty.add("translationThreads");
                            }));
            // 本地思考：只对本地 Qwen3.5 有效，开了延迟翻几倍，悬停有提示
            Button thinkButton = Button.builder(
                            stateText(I18n.t("field.local_thinking"), draft.localThinking), btn -> {
                                draft.localThinking = !draft.localThinking;
                                dirty.add("localThinking");
                                btn.setMessage(stateText(I18n.t("field.local_thinking"), draft.localThinking));
                            })
                    .bounds(0, 0, FIELD_W, FIELD_H).build();
            // 普通 Button 的原版 tooltip 是能用的，不用绕
            thinkButton.setTooltip(Tooltip.create(I18n.c("tip.thinking_slow")));
            put(thinkButton);
            List<Integer> idleOptions = withCurrent(IDLE_RELEASES, draft.idleReleaseMinutes);
            put(CycleButton.<Integer>builder(
                            value -> Component.literal(value <= 0
                                    ? I18n.t("option.off")
                                    : I18n.t("unit.minutes", value)),
                            draft.idleReleaseMinutes)
                    .withValues(idleOptions)
                    .create(0, 0, FIELD_W, FIELD_H, I18n.c("field.idle_release"),
                            (btn, value) -> {
                                draft.idleReleaseMinutes = value;
                                dirty.add("idleReleaseMinutes");
                            }));
        }

        // ---- 翻译范围 ----
        section("section.scope");
        put(toggle(I18n.t("toggle.smart"), draft.smartTranslate, FIELD_W, FIELD_H,
                on -> {
                    draft.smartTranslate = on;
                    dirty.add("smartTranslate");
                }));
        put(toggle(I18n.t("toggle.chat"),
                draft.translateGameMessages && draft.translateChatMessages, FIELD_W, FIELD_H,
                on -> {
                    draft.translateGameMessages = on;
                    draft.translateChatMessages = on;
                    dirty.add("translateGameMessages");
                    dirty.add("translateChatMessages");
                }));
        put(toggle(I18n.t("toggle.tooltip"), draft.translateItemTooltips, FIELD_W, FIELD_H,
                on -> {
                    draft.translateItemTooltips = on;
                    dirty.add("translateItemTooltips");
                }));
        put(toggle(I18n.t("toggle.speaker"), draft.keepSpeakerNames, FIELD_W, FIELD_H,
                on -> {
                    draft.keepSpeakerNames = on;
                    dirty.add("keepSpeakerNames");
                }));
        put(toggle(I18n.t("toggle.singleword"), draft.translateSingleWords, FIELD_W, FIELD_H,
                on -> {
                    draft.translateSingleWords = on;
                    dirty.add("translateSingleWords");
                }));
        put(toggle(I18n.t("toggle.screentext"), draft.translateScreenText, FIELD_W, FIELD_H,
                on -> {
                    draft.translateScreenText = on;
                    dirty.add("translateScreenText");
                }));
        put(toggle(I18n.t("toggle.sign"), draft.translateSignText, FIELD_W, FIELD_H,
                on -> {
                    draft.translateSignText = on;
                    dirty.add("translateSignText");
                }));
        put(toggle(I18n.t("toggle.book"), draft.translateBookText, FIELD_W, FIELD_H,
                on -> {
                    draft.translateBookText = on;
                    dirty.add("translateBookText");
                }));
        put(toggle(I18n.t("toggle.merge_line"), draft.mergeWholeLine, FIELD_W, FIELD_H,
                on -> {
                    draft.mergeWholeLine = on;
                    dirty.add("mergeWholeLine");
                }));

        // ---- 显示 ----
        section("section.display");
        // 与切换键绑定同一份状态。不能用通用 toggle()：它内部记了一份局部布尔值，
        // 切换键从外部改了管理器状态后，再点按钮会按旧布尔值翻转，两边就对不上了。
        showOriginalButton = Button.builder(
                        stateText(showOriginalLabel(), wantedShowOriginal()),
                        btn -> {
                            if (translationManager != null) {
                                boolean next = !wantedShowOriginal();
                                pendingShowOriginal = next;
                                btn.setMessage(stateText(showOriginalLabel(), next));
                            }
                        })
                .bounds(0, 0, FIELD_W, FIELD_H).build();
        put(showOriginalButton);
        put(toggle(I18n.t("toggle.hud"), draft.showHudStatus, FIELD_W, FIELD_H,
                on -> {
                    draft.showHudStatus = on;
                    dirty.add("showHudStatus");
                }));

        // 三个按键都能在本页直接录，不用跑去原版控制页；和原版「选项 → 控制 →
        // 按键绑定 → 通用翻译」操作的是同一批 KeyMapping，两处互通。
        // 唯独键位不走草稿：录到就立刻 setKey + resetMapping + options.save，
        // 按 Esc 离开也不会被丢弃（玩家改成 Z 却没生效就是被草稿吞掉了）。
        // 每组两个按钮：左边显示/录键，右边恢复默认。
        for (Map.Entry<String, String> spec : KEY_LABELS.entrySet()) {
            final String name = spec.getKey();
            final KeyDraft d = keyDrafts.get(name);
            d.button = Button.builder(keyButtonText(name, d), btn -> {
                        clearCapturing();
                        d.capturing = true;
                        btn.setMessage(keyButtonText(name, d));
                    })
                    .bounds(0, 0, FIELD_W, FIELD_H).build();
            put(d.button);
            put(Button.builder(I18n.c("button.key_reset", I18n.t(spec.getValue())), btn -> {
                        KeyMapping mapping = KeyMapping.get(name);
                        if (mapping != null) {
                            d.capturing = false;
                            // 恢复默认同样立刻生效：键位不做草稿，见 applyKeyBinding()
                            applyKeyBinding(name, mapping.getDefaultKey());
                            refreshKeyWidgets();
                        }
                    })
                    .bounds(0, 0, FIELD_W, FIELD_H).build());
        }

        // ---- SkyBlock 翻译增强（子页面）----
        section("section.glossary");
        put(Button.builder(I18n.c("button.skyblock"), btn -> {
                    if (minecraft != null) {
                        minecraft.setScreenAndShow(new SkyblockScreen(
                                config, configManager, translationManager, this));
                    }
                })
                .bounds(0, 0, FIELD_W, FIELD_H).build());
        // 自定义翻译：玩家自己指定某个词译成什么（写进用户术语表）
        put(Button.builder(I18n.c("button.custom"), btn -> {
                    if (minecraft != null) {
                        minecraft.setScreenAndShow(new CustomTranslateScreen(translationManager, this));
                    }
                })
                .bounds(0, 0, FIELD_W, FIELD_H).build());

        // ---- 其他 ----
        section("section.other");
        put(Button.builder(Component.literal(I18n.t("button.clear")), btn -> {
                    if (translationManager != null) {
                        translationManager.clearCache();
                        if (minecraft != null && minecraft.player != null) {
                            minecraft.player.sendSystemMessage(Component.literal(I18n.t("msg.cleared")));
                        }
                    }
                })
                .bounds(0, 0, FIELD_W, FIELD_H).build());
        put(Button.builder(I18n.c("button.priority"), btn -> {
                    if (minecraft != null) {
                        minecraft.setScreenAndShow(new PriorityScreen(config, configManager, this));
                    }
                })
                .bounds(0, 0, FIELD_W, FIELD_H).build());

        baseY = rowY + (col == 0 ? 0 : ROW_GAP);
    }

    /** 分区标题：总是从新的一行、左列开始 */
    private void section(String key) {
        if (col != 0) {
            col = 0;
            rowY += ROW_GAP;
        }
        sectionHeaders.add(new LabelPos(I18n.t(key), rowY + 4));
        rowY += HEADER_GAP;
    }

    /** 把控件放到当前列，满一行自动换行 */
    private <T extends AbstractWidget> T put(T w) {
        w.setX(columnX(col));
        w.setY(rowY);
        addRenderableWidget(w);
        if (++col >= columns()) {
            col = 0;
            rowY += ROW_GAP;
        }
        return w;
    }

    /** 草稿优先：没点「完成」之前按钮显示的是待提交的目标状态 */
    private boolean wantedShowOriginal() {
        return pendingShowOriginal != null ? pendingShowOriginal : isShowingOriginal();
    }

    private boolean isShowingOriginal() {
        return translationManager != null && translationManager.isShowingOriginal();
    }

    // ------------------------------------------------------------------
    // 两个按键都可以在本页直接改键（草稿），点「完成」才写入
    // ------------------------------------------------------------------

    /** 录键中：本页不响应这两个快捷键，避免刚按下的新键顺手触发一次动作 */
    public boolean isCapturingKey() {
        return capturingName() != null;
    }

    /** 正在录键的那个键的注册名（同一时刻只允许一个按钮在录键），没有就返回 null */
    private String capturingName() {
        for (Map.Entry<String, KeyDraft> e : keyDrafts.entrySet()) {
            if (e.getValue().capturing) {
                return e.getKey();
            }
        }
        return null;
    }

    private void clearCapturing() {
        for (KeyDraft d : keyDrafts.values()) {
            d.capturing = false;
        }
    }

    /** 切换界面语言重建页面时，没提交的改键草稿要一起带过去 */
    private Map<String, InputConstants.Key> pendingKeysSnapshot() {
        Map<String, InputConstants.Key> out = new LinkedHashMap<>();
        keyDrafts.forEach((name, d) -> {
            if (d.pending != null) {
                out.put(name, d.pending);
            }
        });
        return out;
    }

    /** 当前生效的键；本页录过但还没提交时优先显示草稿 */
    private InputConstants.Key wantedKey(String name) {
        KeyDraft d = keyDrafts.get(name);
        if (d != null && d.pending != null) {
            return d.pending;
        }
        KeyMapping mapping = KeyMapping.get(name);
        if (mapping == null) {
            return null;
        }
        try {
            // 原版 Options.load() 也是这样从 options.txt 的字符串还原按键，
            // 所以玩家在原版控制页改过的键这里一定能读到，不是写死的默认值。
            return InputConstants.getKey(mapping.saveString());
        } catch (Throwable ignored) {
            return null;
        }
    }

    private String boundKeyName(String name) {
        InputConstants.Key key = wantedKey(name);
        return key == null || key.equals(InputConstants.UNKNOWN)
                ? I18n.t("key.unbound")
                : key.getDisplayName().getString();
    }

    /** 「显示原文」按钮上的键名跟着改键走，别出现改成 K 了还写着「按 O 切换」 */
    private String showOriginalLabel() {
        return I18n.t("toggle.showoriginal", boundKeyName(TOGGLE_KEY_NAME));
    }

    private Component keyButtonText(String name, KeyDraft d) {
        if (d.capturing) {
            return Component.literal(I18n.t("key.capture"));
        }
        String label = boundKeyName(name);
        String conflict = conflictName(name, wantedKey(name));
        if (conflict != null) {
            label = I18n.t("key.conflict", label, conflict);
        }
        return Component.literal(I18n.t("state.row", I18n.t(KEY_LABELS.get(name)), label));
    }

    /** 和别的按键撞了就提示出来；原版按键绑定页也是这么判冲突的 */
    private String conflictName(String name, InputConstants.Key key) {
        if (minecraft == null || key == null || key.equals(InputConstants.UNKNOWN)) {
            return null;
        }
        KeyMapping self = KeyMapping.get(name);
        for (KeyMapping mapping : minecraft.options.keyMappings) {
            if (mapping != self && mapping.matches(key)) {
                return Component.translatable(mapping.getName()).getString();
            }
        }
        return null;
    }

    private void refreshKeyWidgets() {
        for (Map.Entry<String, String> spec : KEY_LABELS.entrySet()) {
            KeyDraft d = keyDrafts.get(spec.getKey());
            if (d != null && d.button != null) {
                d.button.setMessage(keyButtonText(spec.getKey(), d));
            }
        }
        if (showOriginalButton != null) {
            showOriginalButton.setMessage(stateText(showOriginalLabel(), wantedShowOriginal()));
        }
    }

    /**
     * 改键：<b>立刻生效并写盘</b>，不等「完成」。
     *
     * <p>以前改键也走草稿（点「完成」才 setKey），玩家改完习惯性按 Esc 退出时整段草稿被丢弃，
     * 界面上明明看到的是新键、实际绑的还是旧键 —— 表现就是「改成 Z 了，按 Z 没反应」。
     * 键位没有误触代价，原版「按键绑定」页也是即时生效，这里对齐它。
     *
     * <p>{@code setKey} 只改字段，{@code KeyMapping} 内部的「按键 → 映射」索引（MAP）
     * 仍是旧的；不调 {@code resetMapping()} 的话，按键分发不会记到本模组，
     * {@code isDown()/consumeClick()} 永远是 false。
     */
    private void applyKeyBinding(String name, InputConstants.Key pending) {
        KeyMapping mapping = KeyMapping.get(name);
        if (mapping == null) {
            LOGGER.warn("改键失败：找不到 KeyMapping {}", name);
            return;
        }
        mapping.setKey(pending);
        KeyMapping.resetMapping();
        if (minecraft != null) {
            minecraft.options.save();   // 立刻写 options.txt，重启也还在
        }
        LOGGER.info("快捷键已保存: {} -> {}", name, pending.getName());
        KeyDraft d = keyDrafts.get(name);
        if (d != null) {
            d.pending = null;   // 已经落盘，别让 commit() 重复写一次
        }
    }

    /** 录键：键盘任意键；Esc 按原版习惯表示「解除绑定」 */
    @Override
    public boolean keyPressed(KeyEvent event) {
        String name = capturingName();
        if (name != null) {
            KeyDraft d = keyDrafts.get(name);
            d.capturing = false;
            applyKeyBinding(name,
                    event.isEscape() ? InputConstants.UNKNOWN : InputConstants.getKey(event));
            refreshKeyWidgets();
            return true;
        }
        return super.keyPressed(event);
    }

    /** 录键：鼠标侧键也能绑；点在按钮上（尤其「完成」）时先取消录键并正常点按钮 */
    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        String name = capturingName();
        if (name != null) {
            if (getChildAt(event.x(), event.y()).isPresent()) {
                clearCapturing();
                refreshKeyWidgets();
                return super.mouseClicked(event, doubleClick);
            }
            KeyDraft d = keyDrafts.get(name);
            d.capturing = false;
            applyKeyBinding(name, InputConstants.Type.MOUSE.getOrCreate(event.button()));
            refreshKeyWidgets();
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    /** 切换键在设置页之外也能切原文/译文，这里把按钮文字同步成真实状态（草稿状态下不覆盖） */
    @Override
    public void tick() {
        super.tick();
        if (showOriginalButton != null && translationManager != null && pendingShowOriginal == null) {
            Component want = stateText(showOriginalLabel(), isShowingOriginal());
            if (!want.getString().equals(showOriginalButton.getMessage().getString())) {
                showOriginalButton.setMessage(want);
            }
        }
    }

    /**
     * 切换键在本页面按下时调用。按键是即时生效的（不需要点「完成」），
     * 所以这里要丢掉按钮留下的未提交草稿，并把按钮文字改成刚切换后的真实状态，
     * 免得「按键已经切了、按钮还显示旧值、点完完成又被草稿覆盖回去」。
     */
    public void syncShowOriginalFromHotkey(boolean showingOriginal) {
        pendingShowOriginal = null;
        if (showOriginalButton != null) {
            showOriginalButton.setMessage(stateText(showOriginalLabel(), showingOriginal));
        }
    }

    // ------------------------------------------------------------------
    // 提交 / 取消
    // ------------------------------------------------------------------

    /** 点「完成」：一次性写回草稿，并只执行一次真正需要的动作 */
    @Override
    protected void onDone() {
        clearCapturing();
        commit();
        // 走父类的关闭，绕开本类 onClose() 里的「取消」逻辑
        super.onClose();
    }

    /** 按 Esc：丢弃草稿（包括没提交的改键），运行时配置保持打开页面时的样子 */
    @Override
    public void onClose() {
        clearCapturing();
        if (dirty.contains("uiLanguage")) {
            // 界面语言是即时预览过的，取消时把全局文案恢复成未提交的值
            I18n.setUiLanguage(config.uiLanguage);
        }
        super.onClose();
    }

    private void commit() {

        boolean switchChanged = dirty.contains("enabled");
        // 只复制玩家实际改过的字段：优先级等子页面设置不在这个集合里，不会被旧草稿覆盖。
        // enabled 交给 setEnabled() 处理（它还要正确判断 changed 并刷新聊天）。
        for (String field : dirty) {
            copyField(field);
        }
        configManager.save(config);

        if (switchChanged) {
            // 总开关自带 save + 启停模型 + 刷新聊天；它会顺带用到刚写回的 device / threads
            TongyongfanyiClient.setEnabled(draft.enabled);
        }
        if (pendingShowOriginal != null && translationManager != null) {
            translationManager.setShowOriginal(pendingShowOriginal);
            TongyongfanyiClient.refreshChatLayout();
        }
        // 语言 / 超时 / 单词策略变化：重建翻译器，最多一次
        boolean needReload = dirty.contains("sourceLanguage") || dirty.contains("targetLanguage")
                || dirty.contains("timeoutMs") || dirty.contains("translateSingleWords")
                || dirty.contains("translateBackend") || dirty.contains("apiProvider")
                || dirty.contains("apiProtocol") || dirty.contains("apiBaseUrl")
                || dirty.contains("apiModel") || dirty.contains("apiReasoning")
                // 本地思考是 translator 的构造参数，改了必须重建翻译器
                || dirty.contains("localThinking");
        if (needReload && translationManager != null) {
            applyLanguageChange();
        }
        // 本地和 API 各有一套线程数：切后端或改任一个都要按当前后端重算，
        // 不能只写 draft.translationThreads（那样切到 API 时并发还停在本地的 1 路）
        if ((dirty.contains("translationThreads") || dirty.contains("apiThreads")
                || dirty.contains("translateBackend")) && translationManager != null) {
            translationManager.applyThreadCounts();
        }
        // 闲置释放时长不需要重启服务：看门狗每 20 秒自己读这个值
        if (dirty.contains("idleReleaseMinutes")) {
            BundledRuntime.setIdleReleaseMinutes(config.idleReleaseMinutes);
        }
        if (dirty.contains("uiLanguage")) {
            I18n.setUiLanguage(draft.uiLanguage);
        }
        // 设备 / 线程会改变 llama-server 的启动参数，必须重启服务 —— 但合并成一次。
        // 开关已经处理过启停，模组处于关闭状态也不需要拉起。
        boolean needServiceRestart = dirty.contains("device") || dirty.contains("translationThreads")
                || dirty.contains("modelPack") || dirty.contains("translateBackend")
                || dirty.contains("runtimeMode");
        if (needServiceRestart && !switchChanged && config.enabled) {
            BundledRuntime.restart(config);
        }
        // 改键：setKey 只改字段、不会重建 KeyMapping 内部的「按键 → 映射」索引，
        // 必须像原版按键绑定页那样调一次 resetMapping，否则按键分发仍按旧键走。
        // 两个键一起改完只重建一次索引、只存一次盘。
        boolean anyKeyChanged = false;
        for (Map.Entry<String, KeyDraft> e : keyDrafts.entrySet()) {
            InputConstants.Key pending = e.getValue().pending;
            if (pending == null) {
                continue;
            }
            KeyMapping mapping = KeyMapping.get(e.getKey());
            if (mapping != null) {
                mapping.setKey(pending);
                anyKeyChanged = true;
            }
        }
        if (anyKeyChanged && minecraft != null) {
            KeyMapping.resetMapping();
            minecraft.options.save();
        }
    }

    /** 语言 / 超时改了：只影响提示词与缓存，重建翻译器即可，不用重启服务 */
    private void applyLanguageChange() {
        if (translationManager != null) {
            translationManager.setConfig(config);
            translationManager.reload();
        }
    }

    /**
     * 把草稿里的一个字段写回运行时配置。
     *
     * <p>手写 switch 而不是反射：这些开关必须「真实可用」，编译期就能保证字段名对得上，
     * 也避免了混淆/模块权限导致静默失效。
     */
    private void copyField(String name) {
        switch (name) {
            case "enabled" -> { /* 由 setEnabled 处理 */ }
            case "sourceLanguage" -> config.sourceLanguage = draft.sourceLanguage;
            case "targetLanguage" -> config.targetLanguage = draft.targetLanguage;
            case "uiLanguage" -> config.uiLanguage = draft.uiLanguage;
            case "translateBackend" -> config.translateBackend = draft.translateBackend;
            case "apiProvider" -> config.apiProvider = draft.apiProvider;
            case "apiProtocol" -> config.apiProtocol = draft.apiProtocol;
            case "apiBaseUrl" -> config.apiBaseUrl = draft.apiBaseUrl;
            case "apiModel" -> config.apiModel = draft.apiModel;
            case "apiReasoning" -> config.apiReasoning = draft.apiReasoning;
            case "runtimeMode" -> config.runtimeMode = draft.runtimeMode;
            // modelPack 以前漏了这个 case：下拉能选，但值永远写不进配置，
            // 重启后又回落成第一个模型包。一并补上。
            case "modelPack" -> config.modelPack = draft.modelPack;
            case "device" -> config.device = draft.device;
            case "translationThreads" -> config.translationThreads = draft.translationThreads;
            // 本地思考只影响本地 Ollama 的 think 参数
            case "localThinking" -> config.localThinking = draft.localThinking;
            case "apiThreads" -> config.apiThreads = draft.apiThreads;
            case "timeoutMs" -> config.timeoutMs = draft.timeoutMs;
            case "idleReleaseMinutes" -> config.idleReleaseMinutes = draft.idleReleaseMinutes;
            case "smartTranslate" -> config.smartTranslate = draft.smartTranslate;
            case "translateGameMessages" -> config.translateGameMessages = draft.translateGameMessages;
            case "translateChatMessages" -> config.translateChatMessages = draft.translateChatMessages;
            case "translateItemTooltips" -> config.translateItemTooltips = draft.translateItemTooltips;
            case "translateScreenText" -> config.translateScreenText = draft.translateScreenText;
            case "translateSignText" -> config.translateSignText = draft.translateSignText;
            case "translateBookText" -> config.translateBookText = draft.translateBookText;
            case "mergeWholeLine" -> config.mergeWholeLine = draft.mergeWholeLine;
            case "showHudStatus" -> config.showHudStatus = draft.showHudStatus;
            case "keepSpeakerNames" -> config.keepSpeakerNames = draft.keepSpeakerNames;
            case "translateSingleWords" -> config.translateSingleWords = draft.translateSingleWords;
            default -> { }
        }
    }

    /** CycleButton 的初始值必须在候选列表里；配置文件被手工改过时把当前值补进去 */
    private static List<Integer> withCurrent(List<Integer> presets, int current) {
        if (presets.contains(current)) {
            return presets;
        }
        List<Integer> values = new ArrayList<>(presets);
        values.add(current);
        Collections.sort(values);
        return values;
    }

    /**
     * 设备选项：只有本机确实检测到可用显卡时才提供 GPU，
     * 否则只给「自动」和「CPU」，避免玩家选了个永远用不上的选项。
     */
    private CycleButton<String> deviceButton(int h) {
        boolean gpu = BundledRuntime.gpuAvailable();
        List<String> options = gpu ? List.of("auto", "gpu", "cpu") : List.of("auto", "cpu");
        String current = options.contains(draft.device) ? draft.device : "auto";
        return CycleButton.<String>builder(value -> Component.literal(deviceLabel(value, gpu)), current)
                .withValues(options)
                .create(0, 0, FIELD_W, h, I18n.c("field.device"),
                        (btn, value) -> {
                            draft.device = value;
                            dirty.add("device");
                        });
    }

    /**
     * 运行时来源：自动（优先本机 Ollama）/ 内置（只用运行时包）。
     *
     * <p>拆包后代码包不再自带 Ollama，「本机装的那份」和「mods 里的运行时包」
     * 是二选一。默认自动：本机有且版本够新就用它，省掉 122 MB 解包；
     * 玩家想固定版本、或本机那份被改坏了，可以锁到内置。
     */
    private CycleButton<String> runtimeModeButton(int h) {
        List<String> options = List.of("auto", "bundled");
        String current = options.contains(draft.runtimeMode) ? draft.runtimeMode : "auto";
        return CycleButton.<String>builder(
                        value -> Component.literal(I18n.t("option.runtime_mode." + value)), current)
                .withValues(options)
                .create(0, 0, FIELD_W, h, I18n.c("field.runtime_mode"),
                        (btn, value) -> {
                            draft.runtimeMode = value;
                            dirty.add("runtimeMode");
                        });
    }

    /**
     * 翻译方式：本地模型 / 在线 API。
     *
     * <p>放在主页面而不是子页面里 —— 它决定后面哪些设置生效（两种后端各有一套
     * 地址、密钥、并发），藏进二级页面会让人必须先点进去才能切。
     *
     * <p>切换后重建本页：API 才有「详细设置 ▸」按钮，本地没有；线程数也要跟着换一套。
     * 草稿和脏字段是共享的，重建不会丢已改的项。
     */
    private CycleButton<String> backendModeButton(int h) {
        CycleButton<String> btn = CycleButton.<String>builder(
                        value -> Component.literal(I18n.t("option.backend." + value)),
                        "api".equalsIgnoreCase(draft.translateBackend) ? "api" : "local")
                .withValues(List.of("local", "api"))
                // 必须走 withTooltip：CycleButton 每次 updateValue 都会用
                // tooltipSupplier 重新 setTooltip，外部 setTooltip 会被覆盖成 null
                .withTooltip(v -> Tooltip.create(I18n.c("tip.backend_mode")))
                .create(0, 0, FIELD_W, h, I18n.c("field.backend"),
                        (b, value) -> {
                            draft.translateBackend = value;
                            dirty.add("translateBackend");
                            if (minecraft != null) {
                                minecraft.setScreenAndShow(new SettingsScreen(
                                        config, configManager, translationManager, parentScreen(),
                                        draft, dirty, pendingShowOriginal, pendingKeysSnapshot()));
                            }
                        });
        return btn;
    }

    /** 主设置页的后端入口：直接把「当前用哪家的哪个模型」写在按钮上 */
    private Component backendEntryLabel() {
        String m = draft.apiModel == null || draft.apiModel.isBlank()
                ? I18n.t("option.backend.api") : draft.apiModel;
        return Component.literal(I18n.t("button.backend_entry_api", m));
    }

    /** 模型包下拉：列出 mods/ 下所有 tongyongfanyi-model-*.zip */
    private CycleButton<String> modelPackButton(int h, List<ModelPacks.Pack> packs) {
        List<String> ids = new java.util.ArrayList<>(packs.size());
        java.util.Map<String, String> labels = new java.util.HashMap<>();
        for (ModelPacks.Pack p : packs) {
            ids.add(p.id());
            labels.put(p.id(), p.label());
        }
        String current = draft.modelPack == null ? "" : draft.modelPack;
        if (!ids.contains(current)) {
            current = ids.get(0);
        }
        return CycleButton.<String>builder(
                        id -> Component.literal(labels.getOrDefault(id, id)), current)
                .withValues(ids)
                .create(0, 0, FIELD_W, h, I18n.c("field.model_pack"),
                        (btn, value) -> {
                            draft.modelPack = value;
                            dirty.add("modelPack");
                        });
    }


    private static String deviceLabel(String device, boolean gpuAvailable) {
        return switch (device) {
            case "gpu" -> I18n.t(gpuAvailable ? "device.gpu" : "device.gpu.na");
            case "cpu" -> I18n.t("device.cpu");
            default -> I18n.t(gpuAvailable ? "device.auto.gpu" : "device.auto.cpu");
        };
    }

    private Button toggle(String label, boolean initial, int w, int h,
                          java.util.function.Consumer<Boolean> setter) {
        boolean[] on = {initial};
        return Button.builder(stateText(label, on[0]), btn -> {
            on[0] = !on[0];
            setter.accept(on[0]);
            btn.setMessage(stateText(label, on[0]));
        }).bounds(0, 0, w, h).build();
    }

    private static Component stateText(String label, boolean on) {
        return Component.literal(I18n.t("state.row", label, state(on)));
    }

    private static String state(boolean on) {
        return I18n.t(on ? "state.on" : "state.off");
    }

    @Override
    protected void extractFooter(GuiGraphicsExtractor graphics, int x) {
        super.extractFooter(graphics, x);
        graphics.text(font, BundledRuntime.statusLine(), columnX(0), height - 58, 0xFFFFFFFF);
    }
}
