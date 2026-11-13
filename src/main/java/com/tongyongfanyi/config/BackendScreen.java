package com.tongyongfanyi.config;

import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Set;

/**
 * 「翻译后端」子页面：本地模型 / 在线 API 的全部参数。
 *
 * <p>从主设置页独立出来的原因：这块有 7 个控件，和「翻译范围」「界面」那些
 * 开关混在一起时整页太长；而且切换提供商要联动改地址和模型，集中在一页才好理解。
 *
 * <p><b>这里的修改只写进 {@link ModConfig} 草稿</b>，真正的持久化仍由主设置页的
 * 「完成」按钮统一提交 —— 和优先级、SkyBlock 子页面行为一致。
 */
public class BackendScreen extends BaseSettingsScreen {
    private static final int FIELD_W = 230;
    private static final int FIELD_H = 20;
    private static final int ROW_GAP = 26;
    private static final int HEADER_GAP = 22;

    /** API 并发候选值；本地受显存限制只到 8，云端可以更高 */
    private static final List<Integer> API_THREADS = List.of(1, 2, 3, 4, 6, 8, 12, 16);

    /** 只读用（读密钥、当前生效值），不直接改它 */
    private final ModConfig config;
    /** 主设置页的草稿，本页直接改它 */
    private final ModConfig draft;
    /** 主设置页的脏字段集合，本页加进去的字段会一并提交 */
    private final Set<String> dirty;

    private EditBox baseBox;
    private EditBox modelBox;
    private EditBox keyBox;
    private Button fetchButton;
    private Button pickButton;

    /** 最近一次拉到的模型列表；「选择模型」按钮用它开列表页 */
    private List<String> models = List.of();
    /** 拉取失败的提示，画在页脚 */
    private String lastError = "";

    private int col = 0;
    private int rowY = 40;

    public BackendScreen(ModConfig config, ModConfig draft, Set<String> dirty, Screen back) {
        super(I18n.c("title.backend"), back);
        this.config = config;
        this.draft = draft;
        this.dirty = dirty;
    }

    @Override
    protected void rebuild() {
        col = 0;
        rowY = baseY;

        section("section.backend");

        put(CycleButton.<String>builder(
                        value -> Component.literal(I18n.t("option.api_provider." + value)),
                        draft.apiProvider == null ? "deepseek" : draft.apiProvider)
                .withValues(List.of("deepseek", "mimo", "custom"))
                .create(0, 0, FIELD_W, FIELD_H, I18n.c("field.api_provider"),
                        (btn, value) -> {
                            draft.apiProvider = value;
                            dirty.add("apiProvider");
                            if (!ApiModels.PROVIDER_CUSTOM.equals(value)) {
                                // 关键：必须把这两个字段也标脏。以前只加了 apiProvider，
                                // 而 commit 里「值没变就不标脏」的判断看到草稿已被改过，
                                // 于是地址/模型永远写不回配置 —— 表现就是界面显示 MiMo、
                                // 实际请求还在打 DeepSeek，返回 401。
                                draft.apiBaseUrl = ApiModels.defaultBase(value);
                                draft.apiModel = ApiModels.defaultModel(value);
                                dirty.add("apiBaseUrl");
                                dirty.add("apiModel");
                                if (baseBox != null) {
                                    baseBox.setValue(draft.apiBaseUrl);
                                }
                                if (modelBox != null) {
                                    modelBox.setValue(draft.apiModel);
                                }
                                models = List.of();
                            }
                            // 有密钥就顺手拉一次模型列表；本机回环 / Ollama 协议本身
                            // 不需要密钥，这两种也直接拉，省得玩家多点一次
                            if ("api".equalsIgnoreCase(draft.translateBackend) && keyReady()) {
                                fetchModels();
                            }
                        }));

        put(CycleButton.<String>builder(
                        value -> Component.literal(I18n.t("option.api_protocol." + value)),
                        protocolOrDefault(draft.apiProtocol))
                .withValues(List.of(ApiModels.PROTOCOL_OPENAI,
                        ApiModels.PROTOCOL_OLLAMA, ApiModels.PROTOCOL_ANTHROPIC))
                .create(0, 0, FIELD_W, FIELD_H, I18n.c("field.api_protocol"),
                        (btn, value) -> {
                            draft.apiProtocol = value;
                            dirty.add("apiProtocol");
                            // 协议换了，模型列表的来源也变了，旧的作废
                            models = List.of();
                            pickButtonActive();
                        }));

        baseBox = new EditBox(font, 0, 0, FIELD_W, FIELD_H, Component.literal(I18n.t("field.api_base")));
        baseBox.setMaxLength(256);
        baseBox.setValue(draft.apiBaseUrl == null ? "" : draft.apiBaseUrl);
        put(baseBox);

        modelBox = new EditBox(font, 0, 0, FIELD_W, FIELD_H, Component.literal(I18n.t("field.api_model")));
        modelBox.setMaxLength(128);
        modelBox.setValue(draft.apiModel == null ? "" : draft.apiModel);
        put(modelBox);

        // 选择模型：只有拿到列表才启用，没列表时点它等于先拉一次
        pickButton = Button.builder(I18n.c("button.api_model_pick"), b -> openPick())
                .bounds(0, 0, FIELD_W, FIELD_H).build();
        put(pickButton);
        pickButtonActive();

        fetchButton = Button.builder(I18n.c("button.api_models"), b -> fetchModels())
                .bounds(0, 0, FIELD_W, FIELD_H).build();
        put(fetchButton);

        keyBox = new EditBox(font, 0, 0, FIELD_W, FIELD_H, Component.literal(I18n.t("field.api_key")));
        keyBox.setMaxLength(256);
        // 只显示遮罩；玩家不改就沿用文件里的密钥，改了才覆盖。
        keyBox.setValue(ApiKeyStore.masked());
        // 本机 / 局域网服务（Termux 的 ollama serve、LM Studio、vLLM…）通常不校验密钥，
        // 留空直接就能用，这里明确说一句，免得玩家以为必填而放弃
        keyBox.setTooltip(Tooltip.create(I18n.c("tip.api_key_optional")));
        put(keyBox);

        // 思考强度：鼠标悬停提示开思考的代价（翻译是短句任务，开了纯亏时间）
        CycleButton<String> reasoningButton = CycleButton.<String>builder(
                        value -> Component.literal(I18n.t("option.api_reasoning." + value)),
                        draft.apiReasoning == null ? "off" : draft.apiReasoning)
                .withValues(List.of("off", "low", "medium", "high"))
                // 官方 tooltip 通道：CycleButton 的 updateTooltip() 会用 supplier
                // 重新 setTooltip，所以外部 setTooltip 保存不住
                .withTooltip(v -> Tooltip.create(I18n.c("tip.thinking_slow")))
                .create(0, 0, FIELD_W, FIELD_H, I18n.c("field.api_reasoning"),
                        (btn, value) -> {
                            draft.apiReasoning = value;
                            dirty.add("apiReasoning");
                        });
        put(reasoningButton);

        // API 自己的并发数：和「运行 Ollama」里那个本地线程数完全独立。
        // 云端能并发，默认 6；本地那个受显存限制通常留 1。
        List<Integer> threadOptions = withCurrent(API_THREADS, draft.apiThreads);
        CycleButton<Integer> apiThreadButton = CycleButton.<Integer>builder(
                        value -> Component.literal(String.valueOf(value)),
                        threadOptions.contains(draft.apiThreads) ? draft.apiThreads : 6)
                .withValues(threadOptions)
                .withTooltip(v -> Tooltip.create(I18n.c("tip.api_threads")))
                .create(0, 0, FIELD_W, FIELD_H, I18n.c("field.api_threads"),
                        (btn, value) -> {
                            draft.apiThreads = value;
                            dirty.add("apiThreads");
                        });
        put(apiThreadButton);

        baseY = rowY + (col == 0 ? 0 : ROW_GAP);
    }

    /** CycleButton 的初始值必须在候选列表里；配置被手改成非常规值时把它补进去 */
    private static List<Integer> withCurrent(List<Integer> presets, int current) {
        if (presets.contains(current)) {
            return presets;
        }
        List<Integer> out = new java.util.ArrayList<>(presets);
        out.add(current);
        out.sort(Integer::compareTo);
        return out;
    }

    private static String protocolOrDefault(String p) {
        return ApiModels.PROTOCOL_OLLAMA.equalsIgnoreCase(p) || ApiModels.PROTOCOL_ANTHROPIC.equalsIgnoreCase(p)
                ? p.toLowerCase(java.util.Locale.ROOT) : ApiModels.PROTOCOL_OPENAI;
    }

    /**
     * 现在这个配置是否需要先填密钥才能拉模型列表。
     *
     * <p>三种情况不需要：
     * <ul>
     *   <li>已经有存好的密钥；</li>
     *   <li>地址是 127.0.0.1 / localhost —— Termux 的 ollama serve、LM Studio 这类本地服务；</li>
     *   <li>协议选了 Ollama —— {@code /api/tags} 本来就不带鉴权。</li>
     * </ul>
     */
    private boolean keyReady() {
        return ApiKeyStore.hasKey()
                || ApiModels.isLoopback(draft.apiBaseUrl)
                || ApiModels.PROTOCOL_OLLAMA.equalsIgnoreCase(draft.apiProtocol);
    }

    // ------------------------------------------------------------------
    // 布局：和主设置页一致的两列 / 单列
    // ------------------------------------------------------------------

    private void section(String key) {
        if (col != 0) {
            col = 0;
            rowY += ROW_GAP;
        }
        sectionHeaders.add(new LabelPos(I18n.t(key), rowY + 4));
        rowY += HEADER_GAP;
    }

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

    // ------------------------------------------------------------------
    // 模型列表
    // ------------------------------------------------------------------

    private void pickButtonActive() {
        if (pickButton != null) {
            pickButton.active = true;
        }
    }

    /** 打开模型列表页；没有列表就先拉一次 */
    private void openPick() {
        if (models.isEmpty()) {
            fetchModels();
            return;
        }
        if (minecraft == null) {
            return;
        }
        minecraft.setScreenAndShow(new ModelPickScreen(
                I18n.t("title.model_pick"), models, draft.apiModel, this, picked -> {
                    draft.apiModel = picked;
                    dirty.add("apiModel");
                    if (modelBox != null) {
                        modelBox.setValue(picked);
                    }
                }));
    }

    /**
     * 拉模型列表：成功后直接进选择页，失败在页脚 + 聊天栏各提示一次。
     * 网络请求必须离开渲染线程，否则一卡就是十几秒。
     */
    private void fetchModels() {
        if (fetchButton != null) {
            fetchButton.active = false;
            fetchButton.setMessage(I18n.c("button.api_models_loading"));
        }
        lastError = "";
        String base = baseBox == null ? draft.apiBaseUrl : baseBox.getValue();
        String protocol = draft.apiProtocol;
        String typed = keyBox == null ? "" : keyBox.getValue().trim();
        String key = typed.equals(ApiKeyStore.masked()) ? ApiKeyStore.load() : typed;
        new Thread(() -> {
            List<String> got = null;
            String error = null;
            try {
                got = ApiModels.fetch(base, protocol, key);
            } catch (Exception e) {
                error = e.getMessage() == null ? e.toString() : e.getMessage();
            }
            final List<String> result = got;
            final String err = error;
            if (minecraft == null) {
                return;
            }
            minecraft.execute(() -> {
                if (fetchButton != null) {
                    fetchButton.active = true;
                    fetchButton.setMessage(I18n.c("button.api_models"));
                }
                if (result != null && !result.isEmpty()) {
                    models = result;
                    lastError = "";
                    if (minecraft.player != null) {
                        minecraft.player.sendSystemMessage(
                                Component.literal(I18n.t("msg.api_models_ok", result.size())));
                    }
                    openPick();
                } else if (result != null) {
                    lastError = I18n.t("msg.api_models_empty");
                } else {
                    lastError = I18n.t("msg.api_models_failed", err == null ? "" : err);
                }
                if (!lastError.isEmpty() && minecraft.player != null) {
                    minecraft.player.sendSystemMessage(Component.literal(lastError));
                }
            });
        }, "tyfy-api-models").start();
    }

    // ------------------------------------------------------------------
    // 提交
    // ------------------------------------------------------------------

    /** 输入框没有响应器，只能在离开页面时读一次 */
    private void syncInputs() {
        if (baseBox != null) {
            String v = baseBox.getValue().trim();
            if (!v.equals(draft.apiBaseUrl)) {
                draft.apiBaseUrl = v;
                dirty.add("apiBaseUrl");
            }
        }
        if (modelBox != null) {
            String v = modelBox.getValue().trim();
            if (!v.equals(draft.apiModel)) {
                draft.apiModel = v;
                dirty.add("apiModel");
            }
        }
        if (keyBox != null) {
            String typed = keyBox.getValue().trim();
            // 还是遮罩说明没改，沿用文件里的密钥；真改了（或清空）才写 secret 文件
            if (!typed.equals(ApiKeyStore.masked())) {
                ApiKeyStore.save(typed);
            }
        }
        // 玩家进这个页面大概率就是为了修配置（比如刚被 401）：无条件解除熔断，
        // 让下一批请求立刻重试。代价只是多一次请求，不改配置时失败会马上重新熔断。
        com.tongyongfanyi.translate.TranslationManager.clearAuthFailure();
    }

    @Override
    public void onClose() {
        syncInputs();
        super.onClose();
    }

    @Override
    protected void extractFooter(net.minecraft.client.gui.GuiGraphicsExtractor graphics, int x) {
        if (!lastError.isEmpty()) {
            graphics.text(font, lastError, x, height - 46, 0xFFFF7070);
        }
    }
}
