package com.tongyongfanyi.config;

import com.tongyongfanyi.local.BundledModel;
import com.tongyongfanyi.local.LocalModelService;
import com.tongyongfanyi.translate.TranslationManager;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** 本地模型设置页：模型选择、路径、服务启停、自选模型下载 */
public class LocalModelSettingsScreen extends BaseSettingsScreen {
    private final ModConfig config;
    private final ConfigManager configManager;
    private final TranslationManager translationManager;
    private final Screen parent;

    private EditBox localOllamaPathField;
    private EditBox localModelsPathField;
    private Button switchStatusButton;
    /** 内置模型（jar 自带）的安装/重试按钮 */
    private Button bundledButton;
    private final List<DownloadButton> downloadButtons = new ArrayList<>();

    private record DownloadButton(Button button, String model) {
    }

    public LocalModelSettingsScreen(ModConfig config, ConfigManager configManager,
                                    TranslationManager translationManager, Screen parent) {
        super(Component.literal("本地模型设置"));
        this.config = config;
        this.configManager = configManager;
        this.translationManager = translationManager;
        this.parent = parent;
    }

    @Override
    protected void rebuild() {
        int x = width / 2 - 110;
        int fieldW = 220;
        int fieldH = 20;
        ModConfig.LocalModelConfig bc = config.localModel;

        header("模型");
        String current = LocalModelService.LOCAL_MODELS.contains(bc.model)
                ? bc.model
                : LocalModelService.LOCAL_MODELS.get(0);
        CycleButton<String> modelCycle = CycleButton.<String>builder(
                        value -> Component.literal("模型: " + LocalModelService.displayName(value)),
                        current)
                .withValues(LocalModelService.LOCAL_MODELS)
                .create(x, baseY, fieldW, fieldH, Component.literal("翻译模型"),
                        (btn, value) -> {
                            bc.model = value;
                            configManager.save(config);
                            translationManager.reload();
                            if (!LocalModelService.isModelInstalledCached(value)
                                    && minecraft != null && minecraft.player != null) {
                                String hint = BundledModel.isBundled(value)
                                        ? "是随 mod 一起分发的内置模型，正在安装到本地（约 380MB，只需一次），装好后自动生效。"
                                        : "尚未安装，翻译暂不可用，请在下方的模型下载里点击下载。";
                                minecraft.player.sendSystemMessage(Component.literal(
                                        "§e[通用翻译] §f" + LocalModelService.displayName(value) + hint));
                            }
                        });
        addRenderableWidget(modelCycle);
        baseY += 26;

        // 模型切换进度条控件（按钮控件渲染稳定，保证可见）
        switchStatusButton = Button.builder(Component.literal(""), btn -> { })
                .bounds(x, baseY, fieldW, fieldH).build();
        switchStatusButton.active = false;
        addRenderableWidget(switchStatusButton);
        baseY += 26;

        localOllamaPathField = label("Ollama 程序路径 (ollama.exe)", x, fieldW, fieldH);
        localOllamaPathField.setValue(config.ollamaPath);
        localOllamaPathField.setResponder(s -> {
            config.ollamaPath = s;
            configManager.save(config);
        });
        addRenderableWidget(localOllamaPathField);

        localModelsPathField = label("模型目录 (models 文件夹)", x, fieldW, fieldH);
        localModelsPathField.setValue(config.ollamaModelsPath);
        localModelsPathField.setResponder(s -> {
            config.ollamaModelsPath = s;
            configManager.save(config);
        });
        addRenderableWidget(localModelsPathField);

        header("服务");
        Button startLocal = Button.builder(
                Component.literal("§a启动本地模型服务"),
                btn -> {
                    boolean ok = LocalModelService.start(config);
                    if (ok) {
                        configManager.save(config);
                    }
                    String msg = ok ? "§a已启动本地模型服务（稍候即可用）" : "§c启动失败: " + LocalModelService.lastError();
                    if (minecraft != null && minecraft.player != null) {
                        minecraft.player.sendSystemMessage(Component.literal("§a[通用翻译]§f " + msg));
                    }
                }).bounds(x, baseY, fieldW, fieldH).build();
        addRenderableWidget(startLocal);
        baseY += 26;

        Button stopLocal = Button.builder(
                Component.literal("§c停止本地模型服务"),
                btn -> {
                    // 先取运行状态再停：停完后端口可能还没释放，避免误判成"外部启动"
                    boolean wasRunning = LocalModelService.isRunningCached();
                    boolean stopped = LocalModelService.stop();
                    String msg;
                    if (stopped) {
                        msg = "§a已停止本地模型服务";
                    } else if (wasRunning) {
                        msg = "§e该服务不是由游戏启动的（例如用 启动翻译服务.bat 起的），"
                                + "请用「停止翻译服务.bat」或直接关闭那个窗口来停止";
                    } else {
                        msg = "§e本地模型服务当前未运行";
                    }
                    if (minecraft != null && minecraft.player != null) {
                        minecraft.player.sendSystemMessage(Component.literal("§a[通用翻译]§f " + msg));
                    }
                }).bounds(x, baseY, fieldW, fieldH).build();
        addRenderableWidget(stopLocal);
        baseY += 26;

        // 内置模型：随 jar 分发，不需要下载，只给一个"安装/重试"入口
        bundledButton = null;
        if (LocalModelService.hasBundledModel()) {
            header("内置模型（随 mod 一起分发，无需下载）");
            bundledButton = Button.builder(Component.literal(""), btn -> {
                if (!LocalModelService.installBundledModel(config)
                        && minecraft != null && minecraft.player != null) {
                    String reason = LocalModelService.bundledInstallError();
                    minecraft.player.sendSystemMessage(Component.literal(
                            "§e[通用翻译] §f内置模型无法安装："
                                    + (reason == null || reason.isBlank() ? "当前 jar 没有打包模型" : reason)));
                }
            }).bounds(x, baseY, fieldW, fieldH).build();
            addRenderableWidget(bundledButton);
            baseY += 26;
        }

        header("模型下载（本地没有的模型才会显示下载）");
        downloadButtons.clear();
        for (String model : LocalModelService.LOCAL_MODELS) {
            if (BundledModel.isBundled(model)) {
                continue; // 内置模型在上面单独一行，不走下载
            }
            Button dl = Button.builder(Component.literal(""), btn -> startDownload(model))
                    .bounds(x, baseY, fieldW, fieldH).build();
            addRenderableWidget(dl);
            downloadButtons.add(new DownloadButton(dl, model));
            baseY += 26;
        }

        header("操作");
        Button backBtn = Button.builder(Component.literal("返回"),
                        btn -> back())
                .bounds(x, baseY, fieldW, fieldH).build();
        addRenderableWidget(backBtn);
        baseY += 26;
    }

    private void back() {
        if (minecraft != null) {
            minecraft.setScreenAndShow(parent);
        }
    }

    @Override
    public void onClose() {
        back();
    }

    /** 触发模型下载，失败时给出原因 */
    private void startDownload(String model) {
        if (LocalModelService.isDownloading()) {
            return;
        }
        boolean ok = LocalModelService.downloadModel(config, model);
        if (!ok && minecraft != null && minecraft.player != null) {
            minecraft.player.sendSystemMessage(Component.literal("§c[通用翻译] 下载启动失败: " + LocalModelService.lastError()));
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        refreshDownloadButtons();
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        // 模型切换进度条显示在页面顶部（标题下方，无需滚动即可看到）；
        // 切换结束后持续显示最近一次结果，直到下次切换
        String switchLine;
        if (LocalModelService.isSwitching()) {
            int pct = Math.max(0, Math.min(100, LocalModelService.switchPercent()));
            int filled = pct / 5;
            switchLine = "模型切换: " + LocalModelService.switchStatus() + "  ["
                    + "#".repeat(filled) + "-".repeat(20 - filled) + "] " + pct + "%";
        } else {
            String s = LocalModelService.switchStatus();
            if (s == null || s.isBlank() || LocalModelService.switchPercent() < 100) {
                switchLine = null;
            } else {
                switchLine = s.contains("失败") ? "§c" + s : "§a" + s;
            }
        }
        if (switchLine != null) {
            graphics.text(font, switchLine, contentX(), 34, 0x55FFFF);
        }
    }

    /** 每帧刷新下载按钮状态：已安装显示"已安装"且不可点，未安装显示下载按钮 */
    private void refreshDownloadButtons() {
        boolean downloading = LocalModelService.isDownloading();
        if (switchStatusButton != null) {
            if (LocalModelService.isSwitching()) {
                int pct = Math.max(0, Math.min(100, LocalModelService.switchPercent()));
                int filled = pct / 5;
                switchStatusButton.setMessage(Component.literal(
                        "模型切换: " + LocalModelService.switchStatus() + "  ["
                                + "#".repeat(filled) + "-".repeat(20 - filled) + "] " + pct + "%"));
            } else {
                String s = LocalModelService.switchStatus();
                if (s == null || s.isBlank() || LocalModelService.switchPercent() < 100) {
                    switchStatusButton.setMessage(Component.literal(""));
                } else {
                    switchStatusButton.setMessage(Component.literal(
                            s.contains("失败") ? "§c" + s : "§a" + s));
                }
            }
        }
        if (bundledButton != null) {
            String msg;
            boolean active;
            if (LocalModelService.isBundledInstalling()) {
                int pct = LocalModelService.bundledInstallPercent();
                msg = "正在安装内置模型 " + BundledModel.MODEL_LABEL + "…" + (pct >= 0 ? " " + pct + "%" : "");
                active = false;
            } else if (LocalModelService.isBundledModelReady()) {
                msg = "§a已内置: " + BundledModel.MODEL_LABEL + "（已装到本地）";
                active = false;
            } else {
                String reason = LocalModelService.bundledInstallError();
                msg = (reason == null || reason.isBlank())
                        ? "安装内置模型 " + BundledModel.MODEL_LABEL + " (约400MB，只需一次)"
                        : "§c内置模型安装失败，点此重试";
                active = !downloading;
            }
            bundledButton.setMessage(Component.literal(msg));
            bundledButton.active = active;
        }
        for (DownloadButton db : downloadButtons) {
            String label = LocalModelService.displayName(db.model());
            boolean installed = LocalModelService.isModelInstalledCached(db.model());
            String size = LocalModelService.modelSize(db.model());
            String msg = installed
                    ? "§a已安装: " + label
                    : "下载 " + label + (size.isEmpty() ? "" : " (" + size + ")");
            db.button().setMessage(Component.literal(msg));
            db.button().active = !installed && !downloading;
        }
    }

    @Override
    protected void extractFooter(GuiGraphicsExtractor graphics, int x) {
        graphics.text(font, "模型服务: " + (LocalModelService.isRunningCached() ? "§a运行中" : "§c未运行"),
                x, baseY + 14 - scrollOffset, 0xAAAAAA);
        StringBuilder status = new StringBuilder();
        for (String model : LocalModelService.LOCAL_MODELS) {
            if (status.length() > 0) {
                status.append("    ");
            }
            status.append(LocalModelService.displayName(model)).append(": ")
                    .append(LocalModelService.isModelInstalledCached(model) ? "§a已安装" : "§c未安装");
        }
        graphics.text(font, status.toString(), x, baseY + 26 - scrollOffset, 0xAAAAAA);
        if (LocalModelService.isDownloading()) {
            int pct = LocalModelService.downloadPercent();
            graphics.text(font, "下载中: " + LocalModelService.downloadStatus()
                            + (pct >= 0 ? "  " + pct + "%" : ""),
                    x, baseY + 38 - scrollOffset, 0xFFFF55);
        } else if (LocalModelService.downloadPercent() >= 100) {
            // 可能是"下载完成"，也可能是"内置模型就绪"，直接显示状态原文
            String done = LocalModelService.downloadStatus();
            graphics.text(font, done == null || done.isBlank() ? "下载完成" : done,
                    x, baseY + 38 - scrollOffset, 0x55FF55);
        } else if (LocalModelService.downloadStatus() != null
                && LocalModelService.downloadStatus().contains("失败")) {
            // 下载失败后持续显示原因，避免"点了没反应"
            graphics.text(font, "§c" + LocalModelService.downloadStatus(),
                    x, baseY + 38 - scrollOffset, 0xFF5555);
        }
    }
}
