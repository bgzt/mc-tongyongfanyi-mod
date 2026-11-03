package com.tongyongfanyi.config;

import com.tongyongfanyi.TongyongfanyiMod;
import com.tongyongfanyi.local.LocalModelInstaller;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;

/**
 * 通用翻译设置界面（/tyfy 打开）。
 */
public class SettingsScreen extends GuiScreen {
    private static final int BTN_TOGGLE = 0;
    private static final int BTN_TOOLTIP = 1;
    private static final int BTN_INSTALL = 2;
    private static final int BTN_CLEAR = 3;
    private static final int BTN_DONE = 4;

    private GuiTextField modelField;
    private GuiTextField modelsPathField;
    private GuiButton installButton;

    @Override
    public void initGui() {
        int x = width / 2 - 110;
        int y = 40;
        buttonList.clear();
        buttonList.add(new GuiButton(BTN_TOGGLE, x, y, 220, 20, toggleText()));
        y += 26;
        buttonList.add(new GuiButton(BTN_TOOLTIP, x, y, 220, 20, tooltipText()));
        y += 26;
        installButton = new GuiButton(BTN_INSTALL, x, y, 220, 20, installText());
        buttonList.add(installButton);
        y += 26;
        buttonList.add(new GuiButton(BTN_CLEAR, x, y, 220, 20, "\u00a7c清空翻译缓存"));
        y += 26;
        buttonList.add(new GuiButton(BTN_DONE, x, y, 220, 20, "保存并关闭"));

        modelField = new GuiTextField(0, fontRendererObj, x, y + 46, 220, 20);
        modelField.setMaxStringLength(64);
        modelField.setText(TongyongfanyiMod.config.ollamaModel);
        modelField.setCanLoseFocus(true);

        modelsPathField = new GuiTextField(1, fontRendererObj, x, y + 96, 220, 20);
        modelsPathField.setMaxStringLength(260);
        modelsPathField.setText(TongyongfanyiMod.config.ollamaModelsPath);
        modelsPathField.setCanLoseFocus(true);
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        super.drawScreen(mouseX, mouseY, partialTicks);
        drawCenteredString(fontRendererObj, "通用翻译设置", width / 2, 20, 0xFFFFFF);
        int x = width / 2 - 110;
        drawString(fontRendererObj, "本地模型名 (Ollama)", x, modelField.yPosition - 12, 0xAAAAAA);
        modelField.drawTextBox();
        drawString(fontRendererObj, "模型目录 (models 文件夹，自带模型会装到这里)",
                x, modelsPathField.yPosition - 12, 0xAAAAAA);
        modelsPathField.drawTextBox();
        drawString(fontRendererObj, LocalModelInstaller.displayLine(),
                x, modelsPathField.yPosition + 26, installButton.enabled ? 0xAAAAAA : 0xFFFF55);
        drawString(fontRendererObj, "提示: 游戏里输入 /tyfy 重新打开本设置", x, height - 30, 0x555555);
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        ModConfig config = TongyongfanyiMod.config;
        if (button.id == BTN_TOGGLE) {
            config.enabled = !config.enabled;
            TongyongfanyiMod.configManager.save(config);
            button.displayString = toggleText();
        } else if (button.id == BTN_TOOLTIP) {
            config.translateItemTooltips = !config.translateItemTooltips;
            TongyongfanyiMod.configManager.save(config);
            button.displayString = tooltipText();
        } else if (button.id == BTN_INSTALL) {
            // 先把输入框里的目录落盘，再按它安装
            config.ollamaModelsPath = modelsPathField.getText().trim();
            TongyongfanyiMod.configManager.save(config);
            LocalModelInstaller.install(config.ollamaModelsPath);
            button.displayString = installText();
        } else if (button.id == BTN_CLEAR) {
            TongyongfanyiMod.translationManager.clearCache();
            if (mc.thePlayer != null) {
                mc.thePlayer.addChatMessage(new net.minecraft.util.ChatComponentText(
                        "\u00a7a[通用翻译]\u00a7f 已清空翻译缓存"));
            }
        } else if (button.id == BTN_DONE) {
            config.ollamaModel = modelField.getText().trim();
            config.ollamaModelsPath = modelsPathField.getText().trim();
            TongyongfanyiMod.configManager.save(config);
            TongyongfanyiMod.translationManager.reload();
            mc.displayGuiScreen(null);
        }
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws java.io.IOException {
        if (modelField.isFocused()) {
            modelField.textboxKeyTyped(typedChar, keyCode);
            if (keyCode == 28 || keyCode == 1) {
                modelField.setFocused(false);
            }
            return;
        }
        if (modelsPathField.isFocused()) {
            modelsPathField.textboxKeyTyped(typedChar, keyCode);
            if (keyCode == 28 || keyCode == 1) {
                modelsPathField.setFocused(false);
            }
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws java.io.IOException {
        modelsPathField.mouseClicked(mouseX, mouseY, mouseButton);
        modelField.mouseClicked(mouseX, mouseY, mouseButton);
        super.mouseClicked(mouseX, mouseY, mouseButton);
    }

    @Override
    public void updateScreen() {
        super.updateScreen();
        modelField.updateCursorCounter();
        modelsPathField.updateCursorCounter();
        // 安装进度是后台线程改的，每 tick 刷新一次按钮文字
        if (installButton != null) {
            installButton.displayString = installText();
            installButton.enabled = LocalModelInstaller.hasBundledModel()
                    && !LocalModelInstaller.isInstalling();
        }
    }

    @Override
    public void onGuiClosed() {
        TongyongfanyiMod.configManager.save(TongyongfanyiMod.config);
    }

    private String toggleText() {
        return "翻译开关: " + (TongyongfanyiMod.config.enabled ? "\u00a7a已启用" : "\u00a7c已禁用");
    }

    private String tooltipText() {
        return "翻译物品提示: " + (TongyongfanyiMod.config.translateItemTooltips ? "\u00a7a已开启" : "\u00a7c已关闭");
    }

    private String installText() {
        if (!LocalModelInstaller.hasBundledModel()) {
            return "此 jar 未打包模型";
        }
        if (LocalModelInstaller.isInstalling()) {
            return "正在安装内置模型 " + Math.max(0, LocalModelInstaller.percent()) + "%";
        }
        if (LocalModelInstaller.isReady()) {
            return "\u00a7a内置模型已就绪";
        }
        if (LocalModelInstaller.error() != null && !LocalModelInstaller.error().isEmpty()) {
            return "\u00a7c安装失败，点此重试";
        }
        return "安装内置模型 Qwen2.5 0.5B";
    }
}
