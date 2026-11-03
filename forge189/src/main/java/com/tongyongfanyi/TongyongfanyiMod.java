package com.tongyongfanyi;

import com.tongyongfanyi.config.ConfigManager;
import com.tongyongfanyi.config.ModConfig;
import com.tongyongfanyi.config.SettingsScreen;
import com.tongyongfanyi.handler.ForgeEventHandlers;
import com.tongyongfanyi.local.LocalModelInstaller;
import com.tongyongfanyi.translate.TranslationManager;
import net.minecraft.client.Minecraft;
import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.util.ChatComponentText;
import net.minecraftforge.client.ClientCommandHandler;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;

/**
 * 通用翻译 - Minecraft 1.8.9 Forge 版。
 * 只改客户端显示（聊天/物品提示/计分板/HUD），不发任何包、不修改发往服务器的内容。
 * 后端：本地 Ollama（默认 qwen2.5:1.5b / HY-MT-1.8B）或 OPUS-MT 本地服务。
 */
@Mod(modid = TongyongfanyiMod.MODID, name = "通用翻译 Together", version = "1.0.0", clientSideOnly = true)
public class TongyongfanyiMod {
    public static final String MODID = "tongyongfanyi";

    public static TongyongfanyiMod instance;
    public static ModConfig config;
    public static ConfigManager configManager;
    public static TranslationManager translationManager;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        instance = this;
        configManager = new ConfigManager(event.getModConfigurationDirectory());
        config = configManager.load();
        translationManager = new TranslationManager(config, event.getModConfigurationDirectory());
        // jar 自带 Qwen2.5 0.5B 时，后台把它铺到 Ollama 的 models 目录（已装好则直接返回）
        LocalModelInstaller.autoInstallOnce(config.ollamaModelsPath);
        MinecraftForge.EVENT_BUS.register(new ForgeEventHandlers());
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        // 客户端命令：/tyfy 打开设置
        ClientCommandHandler.instance.registerCommand(new CommandBase() {
            @Override
            public String getCommandName() {
                return "tyfy";
            }

            @Override
            public String getCommandUsage(ICommandSender sender) {
                return "/tyfy";
            }

            @Override
            public void processCommand(ICommandSender sender, String[] args) {
                Minecraft.getMinecraft().displayGuiScreen(new SettingsScreen());
                sender.addChatMessage(new ChatComponentText("\u00a7a[通用翻译]\u00a7f 已打开设置界面"));
            }

            @Override
            public boolean canCommandSenderUseCommand(ICommandSender sender) {
                return true;
            }
        });
    }
}
