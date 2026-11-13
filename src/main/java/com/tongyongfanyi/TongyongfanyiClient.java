package com.tongyongfanyi;

import com.tongyongfanyi.config.ConfigManager;
import com.tongyongfanyi.config.I18n;
import com.tongyongfanyi.config.Languages;
import com.tongyongfanyi.config.ModConfig;
import com.tongyongfanyi.config.SettingsScreen;
import com.tongyongfanyi.local.BundledRuntime;
import com.tongyongfanyi.mixin.ChatComponentAccessor;
import com.tongyongfanyi.translate.HoverTextTracker;
import com.tongyongfanyi.translate.RemoteTables;
import com.tongyongfanyi.translate.TranslationManager;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
// 26.3 起 Fabric API 把 keybinding 模块改名为 keymapping，
// 注册方法也从 registerKeyBinding 改成 registerKeyMapping。
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.resources.Identifier;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

public class TongyongfanyiClient implements ClientModInitializer {
    private static final org.slf4j.Logger LOGGER =
            org.slf4j.LoggerFactory.getLogger("tongyongfanyi-client");

    /** 三个按键的注册名：设置页改键、原版按键绑定页、语言文件都用它对齐 */
    public static final String TOGGLE_KEY_NAME = "key.tongyongfanyi.toggle_original";
    public static final String RESCAN_KEY_NAME = "key.tongyongfanyi.rescan_english";
    public static final String FORCE_KEY_NAME = "key.tongyongfanyi.force_retranslate";

    private static TongyongfanyiClient instance;

    private ConfigManager configManager;
    private ModConfig config;
    private TranslationManager translationManager;
    private MessageInterceptor interceptor;

    @Override
    public void onInitializeClient() {
        instance = this;
        configManager = new ConfigManager();
        config = configManager.load();
        // 界面文案自己读 lang 资源，先把玩家选的界面语言装上
        I18n.setUiLanguage(config.uiLanguage);
        translationManager = new TranslationManager(config);
        // 译文写进缓存后让聊天栏重新排版：首次显示出来的英文会原地变成中文，
        // 这是聊天栏不再漏翻的关键（消息进栏后就被拆成固定行了）。
        translationManager.setOnCacheUpdated(TongyongfanyiClient::scheduleChatRefresh);
        interceptor = new MessageInterceptor(translationManager);
        interceptor.register();
        registerCommand();
        // 数据表不在启动时做任何检查：玩家不点设置里的「检查并更新数据表」就不联网。
        // 已经下载过的表会在 Glossary.reload() 里被正常加载，那只是读本地文件。
        // 启动自带/本机的 Ollama 服务并预热模型（后台线程，不阻塞游戏启动）
        // 开关关着就别启动模型：省显存/内存，等玩家在设置里打开时再拉起
        if (config.enabled) {
            BundledRuntime.init(config);
        }
        // 启动后把「缓存里没翻干净的英文词」汇总成清单，方便批量补术语表。
        // 玩家不用再一个个报漏翻，维护时直接读 config/tongyongfanyi_missing.txt。
        java.util.concurrent.CompletableFuture.runAsync(() -> {
            try {
                translationManager.exportMissingWords();
            } catch (Throwable ignored) {
                // 导出失败不影响翻译
            }
        });
        // 游戏退出时刷缓存 + 关掉我们拉起的模型服务
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            translationManager.flushCache();
            BundledRuntime.shutdown();
        });
    }

    public static TongyongfanyiClient getInstance() {
        return instance;
    }

    public ConfigManager getConfigManager() {
        return configManager;
    }

    public ModConfig getConfig() {
        return config;
    }

    public TranslationManager getTranslationManager() {
        return translationManager;
    }

    private void registerCommand() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, buildContext) ->
                dispatcher.register(literal("tyfy")
                        .then(literal("toggle").executes(ctx -> {
                            setEnabled(!config.enabled);
                            feedback(ctx.getSource(), "模组已" + (config.enabled ? "开启（模型启动中）" : "关闭（模型已停止，内存已释放）"));
                            return 1;
                        }))
                        .then(literal("reload").executes(ctx -> {
                            config = configManager.load();
                            I18n.setUiLanguage(config.uiLanguage);
                            // 必须把新配置交给翻译管理器，否则它会一直用旧实例，
                            // 表现为「提示已重载、但翻译行为不变」，设置界面也改不动。
                            translationManager.setConfig(config);
                            translationManager.reload();
                            feedback(ctx.getSource(), "配置已重载");
                            return 1;
                        }))
                        .then(literal("tooltip").executes(ctx -> {
                            config.translateItemTooltips = !config.translateItemTooltips;
                            configManager.save(config);
                            feedback(ctx.getSource(), "物品悬停翻译: " + (config.translateItemTooltips ? "已开启" : "已关闭"));
                            return 1;
                        }))
                        .then(literal("doctor").executes(ctx -> {
                            var rt = BundledRuntime.state();
                            feedback(ctx.getSource(), "§e—— 诊断 ——");
                            feedback(ctx.getSource(), "服务: " + BundledRuntime.statusLine());
                            feedback(ctx.getSource(), "后端: " + (BundledRuntime.backend().isBlank() ? "未确定" : BundledRuntime.backend())
                                    + " | 设备: " + (BundledRuntime.gpuActive() ? "GPU 生效"
                                    : BundledRuntime.gpuConfirmed() ? "CPU" : "CPU（GPU 未确认）")
                                    + " | 端口: " + BundledRuntime.baseUrl().replace("http://127.0.0.1:", ""));
                            feedback(ctx.getSource(), "模型: " + BundledRuntime.modelName()
                                    + " | 线程: " + config.translationThreads
                                    + " | 超时: " + (config.timeoutMs / 1000) + "s"
                                    + " | 闲置释放: " + (config.idleReleaseMinutes <= 0 ? "关闭"
                                    : config.idleReleaseMinutes + " 分钟"));
                            feedback(ctx.getSource(), "术语表: " + com.tongyongfanyi.translate.Glossary.size() + " 条"
                                    + (config.useGlossary ? "（启用）" : "（已关闭）")
                                    + " | 缓存: " + translationManager.diskCacheSize() + " 条"
                                    + " | 在翻: " + translationManager.busyCount());
                            feedback(ctx.getSource(), "数据表: " + RemoteTables.sourceLabel()
                                    + " | " + RemoteTables.status()
                                    + " | 更新方式: 仅手动"
                                    + " | 仓库: " + config.tableRepo);
                            if (BundledRuntime.gpuFallback()) {
                                feedback(ctx.getSource(), "§e模型当前跑在 CPU 上："
                                        + "显存不足或 GPU 后端未启用；关掉光影/降低视距后重进可恢复 GPU 加速");
                            } else if (!BundledRuntime.gpuConfirmed()) {
                                feedback(ctx.getSource(), "§7GPU 状态尚未确认（模型还没驻留），"
                                        + "翻一句后会自动复核，不用重启");
                            }
                            if (!BundledRuntime.error().isBlank()) {
                                feedback(ctx.getSource(), "§c最近错误: " + BundledRuntime.error());
                            }
                            // 运行期错误（API 鉴权失败、连不上）单独一条
                            if (!BundledRuntime.lastError().isBlank()) {
                                feedback(ctx.getSource(), "§c最近失败: " + BundledRuntime.lastError());
                            }
                            return 1;
                        }))
                        .then(literal("tables").executes(ctx -> {
                            // 手动立即检查数据表：网络请求放后台线程，结果回聊天栏，
                            // 不卡渲染线程。这是玩家主动输入的，不会自动触发。
                            Minecraft mc = Minecraft.getInstance();
                            final ModConfig cfg = config;
                            feedback(ctx.getSource(), "§e—— 数据表 ——");
                            feedback(ctx.getSource(), "来源: " + RemoteTables.sourceLabel()
                                    + " | 状态: " + RemoteTables.status()
                                    + " | 仓库: " + cfg.tableRepo + "@" + cfg.tableBranch
                                    + " | 更新方式: 仅手动");
                            chatFeedback(mc, "§8[§b通用翻译§8] §7正在后台检查数据表更新…");
                            Thread t = new Thread(() -> chatFeedback(mc,
                                            "§8[§b通用翻译§8] §7" + RemoteTables.update(cfg, null)),
                                    "tongyongfanyi-tables-manual");
                            t.setDaemon(true);
                            t.start();
                            return 1;
                        }))
                        .then(literal("rescan").executes(ctx -> {
                            // 和「重译残留英文」按键走同一套扫描，方便不想改键的玩家
                            int n = translationManager.rescanResidualEnglish();
                            feedback(ctx.getSource(), n < 0
                                    ? "§c模型正在重新加载，稍后再执行 /tyfy rescan"
                                    : "扫描完成，已重新提交 " + n + " 句残留英文给模型重译");
                            return 1;
                        }))
                        .then(literal("hover").executes(ctx -> {
                            // 诊断用：按下重译键到底会扫到哪几句，方便确认「为什么说没有英文」
                            double[] pos = mouseGuiPos(Minecraft.getInstance());
                            List<String> group = pos == null ? List.of()
                                    : HoverTextTracker.pickResidualGroup(pos[0], pos[1]);
                            String picked = pos == null ? null
                                    : HoverTextTracker.pick(pos[0], pos[1]);
                            feedback(ctx.getSource(), picked == null
                                    ? "鼠标下没找到可重译的文字"
                                    : "鼠标指向的原文: " + picked);
                            feedback(ctx.getSource(), group.isEmpty()
                                    ? "§7这块区域没有需要重译的英文残留"
                                    : "§e这块区域待重译 " + group.size() + " 句: " + String.join(" ｜ ", group));
                            return 1;
                        }))
                        .then(literal("force").executes(ctx -> {
                            // 按键之外的备用入口，也方便确认鼠标实际选中了哪几行
                            Minecraft mc = Minecraft.getInstance();
                            double[] pos = mouseGuiPos(mc);
                            List<String> group = pos == null ? List.of()
                                    : HoverTextTracker.pickGroup(pos[0], pos[1]);
                            if (group.isEmpty()) {
                                feedback(ctx.getSource(), "鼠标下没找到可强制重译的文字");
                                return 1;
                            }
                            int n = translationManager.forceRetranslateHovered(
                                    group, msg -> chatFeedback(mc, msg));
                            feedback(ctx.getSource(), n < 0
                                    ? "§c模型正在重新加载，稍后再执行 /tyfy force"
                                    : "已强制重译 " + n + " 行");
                            return 1;
                        }))
                        .then(literal("keys").executes(ctx -> {
                            // 诊断「改了键按不动」：绑定值、KeyMapping 状态、物理读取结果
                            // 三样一起打出来，一眼能分清是「没存上」还是「读不到」。
                            feedback(ctx.getSource(), "§e—— 快捷键 ——");
                            keyLine(ctx.getSource(), toggleOriginalKey, "切换原文/译文");
                            keyLine(ctx.getSource(), rescanKey, "重译残留英文");
                            keyLine(ctx.getSource(), forceKey, "强制重译");
                            return 1;
                        }))
                        .then(literal("board").executes(ctx -> {
                            dumpScoreboard(ctx.getSource());
                            return 1;
                        }))
                        .then(literal("chat").executes(ctx -> {
                            var lines = com.tongyongfanyi.translate.TranslationHelper.dumpRecentChat();
                            if (lines.isEmpty()) {
                                feedback(ctx.getSource(), "还没有收到任何聊天消息");
                                return 0;
                            }
                            feedback(ctx.getSource(), "§e—— 最近 " + lines.size() + " 条聊天 ——");
                            for (String l : lines) {
                                ctx.getSource().sendFeedback(Component.literal(l));
                            }
                            return 1;
                        }))
                        .then(literal("test").then(argument("text", StringArgumentType.greedyString())
                                .executes(ctx -> {
                                    String text = StringArgumentType.getString(ctx, "text");
                                    feedback(ctx.getSource(), "翻译中… " + text);
                                    translationManager.translateChatAsync(text, r ->
                                            Minecraft.getInstance().execute(() ->
                                                    feedback(ctx.getSource(), "→ " + r)));
                                    return 1;
                                })))
                        .then(literal("cleanup").executes(ctx -> {
                            feedback(ctx.getSource(), "正在清理遗留的 llama-server…");
                            int n = BundledRuntime.cleanupOrphans();
                            feedback(ctx.getSource(), n < 0
                                    ? "§c清理失败（可能被安全软件拦了），可手动结束 llama-server.exe"
                                    : "§a已清理 " + n + " 个遗留进程");
                            return 1;
                        }))
                        .then(literal("status").executes(ctx -> {
                            String src = Languages.byId(Languages.SOURCES, config.sourceLanguage).label();
                            String dst = Languages.displayTarget(config.targetLanguage);
                            feedback(ctx.getSource(), "启用: " + config.enabled
                                    + " | 语言: " + src + " → " + dst
                                    + " | 服务: " + BundledRuntime.statusLine()
                                    + " | 物品提示: " + config.translateItemTooltips
                                    + " | 磁盘缓存: " + translationManager.diskCacheSize() + " 条");
                            return 1;
                        }))
                        // 不带子命令时直接打开设置界面：未安装 ModMenu 的玩家也能进设置
                        .executes(ctx -> {
                            Minecraft.getInstance().setScreenAndShow(
                                    new SettingsScreen(config, configManager, translationManager));
                            return 1;
                        })));

        registerToggleKey();
    }

    /**
     * 原文/译文一键切换。默认 O 键，可在「选项 → 控制 → 按键绑定」里改。
     * 切换只影响显示层，不会清缓存，也不会重新翻译。
     */
    private void registerToggleKey() {
        // 键码用原版 InputConstants.KEY_O / KEY_V。
        // 「键盘」这个 Type 的枚举名在两个版本里不一样（26.2 = KEYSYM，26.3 = KEYBOARD），
        // 用 keyboardType() 按名字取，源码只维护一份。
        // 两个键共用同一个分类，Category.register 只能调一次。
        KeyMapping.Category category =
                KeyMapping.Category.register(Identifier.fromNamespaceAndPath("tongyongfanyi", "main"));
        toggleOriginalKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                TOGGLE_KEY_NAME,
                keyboardType(),
                InputConstants.KEY_O,
                category));
        rescanKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                RESCAN_KEY_NAME,
                keyboardType(),
                InputConstants.KEY_V,
                category));
        forceKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                FORCE_KEY_NAME,
                keyboardType(),
                InputConstants.KEY_R,
                category));
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            pollToggleKey(client);
            pollRescanKey(client);
            pollForceKey(client);
            tickChatRefresh();
            tickErrorNotice(client);
        });
    }

    /**
     * 把「启动失败 / API 连不上 / 密钥无效」这类错误在聊天栏说一次。
     *
     * <p>以前这些只写日志和 HUD 状态行：玩家不看日志、HUD 又可能关着，
     * 结果就是「翻译没反应」而无从排查。这里是唯一一次的地方，
     * 同一条错误只提示一次（{@code takeErrorNotice} 会消费掉标记）。
     */
    private static void tickErrorNotice(net.minecraft.client.Minecraft client) {
        if (client.player == null) {
            return;
        }
        if (!com.tongyongfanyi.local.BundledRuntime.takeErrorNotice()) {
            return;
        }
        client.player.sendSystemMessage(
                Component.literal("§c[通用翻译] " + com.tongyongfanyi.local.BundledRuntime.lastError()));
    }

    /** 上一帧切换键是否按着：把「按住」变成一次点击，长按不会来回切 */
    private static volatile boolean toggleHeld;
    /** 物理按键边沿和 KeyMapping.click 可能同一帧都报一次，用它去重 */
    private static volatile long lastToggleAt;
    /** 「重译残留英文」键：同样做成边沿触发 + 防连发 */
    private static KeyMapping rescanKey;
    private static volatile boolean rescanHeld;
    private static volatile long lastRescanAt;
    /** 「强制重译」键：鼠标指向的文字无论是否中文都再翻一遍 */
    private static KeyMapping forceKey;
    private static volatile boolean forceHeld;
    private static volatile long lastForceAt;

    /**
     * 在任何界面都能按切换键，唯独正在输入文字时不抢。
     *
     * <p>以前只在 tick 里 {@code consumeClick()}：原版把按键记进 KeyMapping.click
     * 之前会先交给当前屏幕分发，屏幕一打开（箱子、设置、暂停菜单、书本…）就常常收不到，
     * 表现为「游戏里按 O 有用、开个界面就没用」。现在直接读 SDL 的全局按键状态
     * （和 {@code KeyboardHandler} 自己用的 {@code InputConstants.isKeyDown} 同一份），
     * 屏幕怎么分发都不影响；consumeClick 只留作鼠标绑定等其它路径的补充。
     */
    private static void pollToggleKey(Minecraft client) {
        KeyMapping key = toggleOriginalKey;
        if (key == null) {
            return;
        }
        boolean clicked = false;
        while (key.consumeClick()) {
            clicked = true;   // 顺手清空计数，避免和物理按键边沿重复触发
        }
        boolean down = toggleKeyDown(key);
        boolean pressed = (down && !toggleHeld) || clicked;
        toggleHeld = down;
        if (!pressed) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastToggleAt < 120L) {
            return;
        }
        if (isTyping(client)) {
            return;   // 正在输入：让这个字母正常打进输入框
        }
        lastToggleAt = now;
        applyToggle(client);
    }

    /** 读当前绑定的键；玩家在控制界面改过键也立刻跟着变 */
    private static boolean toggleKeyDown(KeyMapping mapping) {
        if (mapping.isUnbound()) {
            return false;
        }
        try {
            InputConstants.Key bound = InputConstants.getKey(mapping.saveString());
            if (bound.getType() == keyboardType()) {
                // 物理读取和 KeyMapping 自己的状态取「或」：
                // 26.2 走 glfwGetKey(Window, int)，万一窗口句柄/反射那条路出问题，
                // mapping.isDown()（由 KeyboardHandler 维护）还能兜住，不至于整个键失灵。
                return isPhysicallyKeyDown(bound.getValue()) || mapping.isDown();
            }
        } catch (Throwable ignored) {
            // 配置里写了解析不了的键名，退回 KeyMapping 自己的状态
        }
        return mapping.isDown();   // 鼠标键 / 兜底
    }

    /**
     * 「键盘」类型的枚举常量：26.2 叫 {@code KEYSYM}，26.3 叫 {@code KEYBOARD}。
     * 按名字反射取，两个版本共用一份源码。
     */
    private static InputConstants.Type keyboardType() {
        for (InputConstants.Type t : InputConstants.Type.values()) {
            String name = t.name();
            if ("KEYBOARD".equals(name) || "KEYSYM".equals(name)) {
                return t;
            }
        }
        return InputConstants.Type.values()[0];
    }

    /** 缓存找到的 isKeyDown 重载，避免每帧反射扫描 */
    private static volatile java.lang.reflect.Method isKeyDownMethod;

    /**
     * 直接读物理按键状态（和 {@code KeyboardHandler} 用的同一份）。
     *
     * <p>签名在两个版本里不一样：26.2 是 {@code isKeyDown(Window, int)}，
     * 26.3 改成 {@code isKeyDown(int)}（输入后端换成 SDL，不再需要窗口）。
     * 这里按参数个数反射适配，找不到就返回 false，退回 {@code mapping.isDown()}。
     */
    private static boolean isPhysicallyKeyDown(int keyCode) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) {
            return false;
        }
        try {
            if (isKeyDownMethod == null) {
                for (java.lang.reflect.Method m : InputConstants.class.getMethods()) {
                    if (!"isKeyDown".equals(m.getName())) {
                        continue;
                    }
                    Class<?>[] p = m.getParameterTypes();
                    if (p.length == 1 && p[0] == int.class) {
                        isKeyDownMethod = m;
                        break;
                    }
                    if (p.length == 2 && p[1] == int.class) {
                        isKeyDownMethod = m;
                        break;
                    }
                }
            }
            if (isKeyDownMethod == null) {
                return false;
            }
            Object result = isKeyDownMethod.getParameterCount() == 2
                    ? isKeyDownMethod.invoke(null, mc.getWindow(), keyCode)
                    : isKeyDownMethod.invoke(null, keyCode);
            return result instanceof Boolean b && b;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 缓存 capturesInput 方法（26.2 的 GuiEventListener 还没有这个默认方法） */
    private static volatile java.lang.reflect.Method capturesInputMethod;

    /**
     * 这个控件是不是「正在接收字符输入」。
     *
     * <p>{@code capturesInput()} 是 26.3 才加到 {@code GuiEventListener} 的默认方法，
     * 26.2 没有，直接调会编译不过。反射调用，取不到就当 false ——
     * 聊天/告示牌/成书这些界面已经被上面的类名判断覆盖了。
     */
    private static boolean capturesInput(GuiEventListener listener) {
        if (listener == null) {
            return false;
        }
        try {
            if (capturesInputMethod == null) {
                capturesInputMethod = GuiEventListener.class.getMethod("capturesInput");
            }
            return Boolean.TRUE.equals(capturesInputMethod.invoke(listener));
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 是否正在输入文字：聊天栏、设置页输入框、书/告示牌/铁砧等编辑界面 */
    private static boolean isTyping(Minecraft client) {
        if (client == null || client.gui == null) {
            return false;
        }
        Screen screen = client.gui.screen();
        if (screen == null) {
            return false;
        }
        String name = screen.getClass().getName();
        // 本模组设置页正在「录一个切换键」时也不能抢，否则刚按下的新键会顺手切一次原文
        if (screen instanceof SettingsScreen settings && settings.isCapturingKey()) {
            return true;
        }
        // 原版按键绑定界面里按 O 是在「录一个按键」，不抢，否则改不了键
        if (name.endsWith("KeyBindsScreen")) {
            return true;
        }
        // 成书 / 告示牌 / 命令方块 / 世界重命名这类界面自己接收字符，不用 EditBox。
        // 阅读界面（BookViewScreen）不含 Edit，按 O 仍可切换原文/译文。
        if (name.endsWith("ChatScreen") || name.endsWith("InBedChatScreen")
                || name.endsWith("SignScreen")
                || (name.contains("Edit") && !name.contains("View"))) {
            return true;
        }
        // 通用：焦点链上有没有正在接收字符输入的控件（EditBox 覆写了 capturesInput）
        GuiEventListener focus = screen.getFocused();
        for (int i = 0; focus != null && i < 8; i++) {
            if (capturesInput(focus)) {
                return true;
            }
            if (focus instanceof ContainerEventHandler container) {
                GuiEventListener next = container.getFocused();
                if (next == focus) {
                    break;
                }
                focus = next;
            } else {
                break;
            }
        }
        return false;
    }

    /** 真正切换：状态 + 聊天重排 + 设置页按钮同步 + 提示 */
    private static void applyToggle(Minecraft client) {
        TongyongfanyiClient mod = getInstance();
        if (mod == null || mod.translationManager == null) {
            return;
        }
        boolean original = mod.translationManager.toggleShowOriginal();
        // 每次切换都留一条 INFO：万一玩家说「按了没反应」，日志里有没有这行就能分清
        // 是「按键没读到」还是「切换没生效」。
        LOGGER.info("切换显示: {}", original ? "原文" : "译文");
        refreshChatLayout();
        if (client != null && client.gui != null
                && client.gui.screen() instanceof SettingsScreen settings) {
            // 设置页是草稿模式，按键是即时生效的：清掉未提交的草稿并同步按钮文字
            settings.syncShowOriginalFromHotkey(original);
        }
        Component message = Component.literal(I18n.t("key.feedback",
                I18n.t(original ? "key.original" : "key.translated")));
        try {
            if (client != null && client.player != null) {
                client.player.sendSystemMessage(message);
            } else if (client != null && client.gui != null && client.gui.hud != null) {
                // 主菜单 / 服务器列表也允许切换，只是没有世界里的系统消息通道
                client.gui.hud.getChat().addClientSystemMessage(message);
            }
        } catch (Throwable ignored) {
            // HUD 还没就绪时静默，状态本身已经切换
        }
    }

    /**
     * 第二个键：鼠标指着某条文字时重译那一条，没指到才扫最近显示过的残留英文。
     * 和切换键一样读 SDL 全局按键状态，所以箱子、物品栏、设置页、书本里都能按，
     * 只有正在输入文字（聊天框、告示牌、按键绑定页录键）时才让开。
     */
    private static void pollRescanKey(Minecraft client) {
        KeyMapping key = rescanKey;
        if (key == null) {
            return;
        }
        boolean clicked = false;
        while (key.consumeClick()) {
            clicked = true;
        }
        boolean down = toggleKeyDown(key);
        boolean pressed = (down && !rescanHeld) || clicked;
        rescanHeld = down;
        if (!pressed) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastRescanAt < 500L) {
            return;   // 长按 / 同帧重复事件只算一次
        }
        if (isTyping(client)) {
            return;
        }
        lastRescanAt = now;
        applyRescan(client);
    }

    /**
     * 第三个键：强制重译。V 只在「译文还夹英文」时才动手，这个键不看有没有残留，
     * 反查回原文换一种译法重送模型，专门用来修「翻出来了但翻得不好」。
     * 同样是 SDL 全局物理按键状态，除正在输入外任何界面都能按。
     */
    private static void pollForceKey(Minecraft client) {
        KeyMapping key = forceKey;
        if (key == null) {
            return;
        }
        boolean clicked = false;
        while (key.consumeClick()) {
            clicked = true;
        }
        boolean down = toggleKeyDown(key);
        boolean pressed = (down && !forceHeld) || clicked;
        forceHeld = down;
        if (!pressed) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastForceAt < 500L) {
            return;
        }
        if (isTyping(client)) {
            return;
        }
        lastForceAt = now;
        applyForce(client);
    }

    /** 给聊天栏发一条提示；客户端 tick 与后台线程都会调用，统一切回主线程 */
    private static void chatFeedback(Minecraft client, String text) {
        if (client == null || text == null || text.isEmpty()) {
            return;
        }
        try {
            client.execute(() -> {
                try {
                    Component c = Component.literal(text);
                    if (client.player != null) {
                        client.player.sendSystemMessage(c);
                    } else if (client.gui != null && client.gui.hud != null) {
                        client.gui.hud.getChat().addClientSystemMessage(c);
                    }
                } catch (Throwable ignored) {
                    // HUD 未就绪时不影响翻译本身
                }
            });
        } catch (Throwable ignored) {
            // 客户端已关闭
        }
    }

    /** 鼠标在当前 GUI 缩放坐标下的位置；拿不到就返回 null（退回批量扫描） */
    private static double[] mouseGuiPos(Minecraft client) {
        try {
            if (client == null || client.getWindow() == null || client.mouseHandler == null) {
                return null;
            }
            return new double[]{
                    client.mouseHandler.getScaledXPos(client.getWindow()),
                    client.mouseHandler.getScaledYPos(client.getWindow())
            };
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 真正执行：鼠标指着文字就重译那一条，否则退回批量扫描 */
    private static void applyRescan(Minecraft client) {
        TongyongfanyiClient mod = getInstance();
        if (mod == null || mod.translationManager == null) {
            return;
        }
        // 没有打开界面时鼠标被锁定在准星上，坐标没有「指向」意义，直接走批量扫描
        double[] pos = (client != null && client.gui != null && client.gui.screen() != null)
                ? mouseGuiPos(client) : null;
        // 按「块」扫，而不是只挑一行：tooltip 有七八行，玩家点不准唯一那条漏翻的行，
        // 只挑一行就总会反馈「这一条已经全是中文」，可屏幕上明明还有英文。
        List<String> group = pos == null ? List.of()
                : HoverTextTracker.pickResidualGroup(pos[0], pos[1]);
        if (!group.isEmpty()) {
            boolean single = group.size() == 1;
            int submitted = 0;
            for (String line : group) {
                // 单条时把每步结果发到聊天栏；整块批量时只发汇总，免得刷屏
                int state = mod.translationManager.retranslateHovered(
                        line, single ? msg -> chatFeedback(client, msg) : null);
                if (state < 0) {
                    chatFeedback(client, I18n.t("key.rescan.busy"));
                    return;
                }
                submitted += state;
            }
            if (submitted == 0) {
                chatFeedback(client, I18n.t("key.rescan.hover_clean"));
                return;
            }
            chatFeedback(client, single
                    ? I18n.t("key.rescan.hover_start", group.get(0))
                    : I18n.t("key.rescan.hover_batch", submitted));
            return;
        }
        int submitted = mod.translationManager.rescanResidualEnglish();
        // -1 = 模型正在自愈/重新加载，这一批没有入队，别误报「没发现残留英文」
        chatFeedback(client, submitted < 0
                ? I18n.t("key.rescan.busy")
                : I18n.t(submitted > 0 ? "key.rescan.feedback" : "key.rescan.clean", submitted));
    }

    /**
     * 强制重译：鼠标压在文字上只重译那一行；悬停在物品槽上时把整块 tooltip
     * 的可见行都收回来一起重译。译文→原文反查由绘制层记录，中文也能重译。
     */
    private static void applyForce(Minecraft client) {
        TongyongfanyiClient mod = getInstance();
        if (mod == null || mod.translationManager == null) {
            return;
        }
        double[] pos = (client != null && client.gui != null && client.gui.screen() != null)
                ? mouseGuiPos(client) : null;
        List<String> group = pos == null ? List.of()
                : HoverTextTracker.pickGroup(pos[0], pos[1]);
        if (group.isEmpty()) {
            chatFeedback(client, I18n.t("key.force.miss"));
            return;
        }
        int submitted = mod.translationManager.forceRetranslateHovered(
                group, msg -> chatFeedback(client, msg));
        chatFeedback(client, submitted < 0
                ? I18n.t("key.rescan.busy")
                : submitted == 0 ? I18n.t("key.force.miss")
                : I18n.t("key.force.start", submitted));
    }

    /**
     * 切换原文/译文后让聊天栏重新拆行。
     * 聊天历史存的是拆好的固定行，只有重新排版才会重新读取 BilingualComponent 的当前文本；
     * 界面/物品/计分板这些每帧重绘的地方本来就会立刻跟着变。
     * 必须在主线程调用（按键回调与设置页都在主线程）。
     */
    /**
     * 模组总开关：关闭时把本地模型进程一起停掉（连带 llama-server 子进程），
     * 显存/内存立即还回去；打开时重新拉起服务。
     *
     * <p>以前这个开关只改「要不要翻译」，模型照样常驻占着几 GB 显存，
     * 玩家关了它也没有任何收益，所以现在做成真正的开关。
     */
    public static void setEnabled(boolean value) {
        TongyongfanyiClient mod = getInstance();
        if (mod == null) {
            return;
        }
        boolean changed = mod.config.enabled != value;
        mod.config.enabled = value;
        mod.configManager.save(mod.config);
        if (value) {
            // restart() 会先 shutdown 再 boot，串行执行，不会和别的重启撞车
            BundledRuntime.restart(mod.config);
        } else {
            BundledRuntime.shutdown();
        }
        if (changed) {
            // 关掉时把已经翻好的聊天内容切回原文，别留在屏幕上
            refreshChatLayout();
        }
    }

    public static void refreshChatLayout() {
        try {
            Minecraft client = Minecraft.getInstance();
            if (client != null && client.gui != null && client.gui.hud != null) {
                var chat = client.gui.hud.getChat();
                if (chat instanceof ChatComponentAccessor accessor) {
                    // rescaleChat() 会 resetChatScroll()，保存/恢复以免把翻历史的人拉回底部
                    int scroll = accessor.tyfy$scrollPos();
                    chat.rescaleChat();
                    accessor.tyfy$setScrollPos(scroll);
                } else {
                    chat.rescaleChat();
                }
            }
        } catch (Throwable ignored) {
            // 还没进世界时 HUD 可能未就绪；状态本身已经切换，进世界后自然生效。
        }
    }

    /** 有译文写完、还没回填到聊天栏 */
    private static final AtomicBoolean chatRefreshDirty = new AtomicBoolean(false);

    /**
     * 后台翻完一条只打脏标记，真正的重排版放到 END_CLIENT_TICK。
     *
     * <p>旧版用 AtomicBoolean 合并刷新请求：刷新正在执行那一瞬间写好的译文会被
     * 直接丢掉，而这次刷新已经读不到它 —— 表现就是「三句对话只翻了最新两条」；
     * 之后若没有新译文来触发刷新，那一条会一直停在英文。
     * 现在由 tick 每帧检查脏标记，译文无论在什么时刻写完，下一帧必定重排一次。
     */
    public static void scheduleChatRefresh() {
        chatRefreshDirty.set(true);
    }

    /** END_CLIENT_TICK 调用：一帧最多重排一次，且绝不漏掉最后一次译文 */
    static void tickChatRefresh() {
        if (chatRefreshDirty.getAndSet(false)) {
            refreshChatLayout();
        }
    }

    private static KeyMapping toggleOriginalKey;

    /**
     * /tyfy board：把当前侧边栏逐行打印出来（原文 / 译文 / 没翻的原因）。
     *
     * <p>计分板只有客户端内存里有，日志里看不到，所以漏翻时得当场把这几行导出来：
     * 每一行都标注「被哪条规则拦下」还是「只是还没翻出来」，不用再靠猜。
     */
    private void dumpScoreboard(FabricClientCommandSource source) {
        Minecraft client = Minecraft.getInstance();
        if (client.level == null) {
            feedback(source, "还没进世界，看不到计分板");
            return;
        }
        var scoreboard = client.level.getScoreboard();
        var objective = scoreboard.getDisplayObjective(net.minecraft.world.scores.DisplaySlot.SIDEBAR);
        if (objective == null) {
            feedback(source, "当前没有侧边栏计分板");
            return;
        }
        feedback(source, "§e—— 侧边栏 ——");
        String titleRaw = plain(objective.getDisplayName());
        String titleTr = plain(com.tongyongfanyi.translate.TranslationHelper
                .translateScoreboard(objective.getDisplayName()));
        feedback(source, "标题: " + titleRaw + (titleRaw.equals(titleTr) ? " §c(未翻译)" : " §7→ §f" + titleTr));

        var entries = new java.util.ArrayList<>(scoreboard.listPlayerScores(objective));
        entries.sort((a, b) -> Integer.compare(b.value(), a.value()));
        int bad = 0;
        for (var entry : entries) {
            var team = scoreboard.getPlayersTeam(entry.owner());
            String raw = plain(entry.display() != null ? entry.display()
                    : Component.literal(entry.owner()));
            if (raw.isBlank()) {
                continue;
            }
            if (team != null) {
                // 侧边栏整行 = 前缀 + 名字 + 后缀，前缀里常有主要文字，一起打出来
                String pre = plain(team.getPlayerPrefix());
                String suf = plain(team.getPlayerSuffix());
                if (!pre.isBlank() || !suf.isBlank()) {
                    raw = pre + raw + suf;
                }
            }
            String tr = plain(com.tongyongfanyi.translate.TranslationHelper
                    .translateScoreboard(Component.literal(raw)));
            String why = com.tongyongfanyi.translate.TranslationHelper.whySkipped(raw);
            boolean done = !tr.equals(raw);
            if (!done) {
                bad++;
            }
            feedback(source, (done ? "§a✔ §f" : "§c✘ §f") + raw
                    + (done ? " §7→ §f" + tr
                    : " §7[" + (why != null ? why : "未命中缓存，应在排队/已放弃") + "]"));
        }
        feedback(source, "共 " + entries.size() + " 行，其中 §c" + bad + " §f行没翻");
    }

    /** 打印用：把 § 换成 &，避免聊天栏直接染色看不清原文 */
    private static String plain(Component component) {
        return component == null ? "" : component.getString().replace('\u00a7', '&');
    }

    private void feedback(FabricClientCommandSource source, String msg) {
        source.sendFeedback(Component.literal("§a[通用翻译]§f " + msg));
    }

    /** /tyfy keys 的一行：绑定值 + KeyMapping 自己记的按下状态 + 物理读取结果 */
    private void keyLine(FabricClientCommandSource source, KeyMapping mapping, String label) {
        if (mapping == null) {
            feedback(source, "§c" + label + "§f: 未注册");
            return;
        }
        String bound = mapping.isUnbound() ? "未绑定" : mapping.saveString();
        String physical = "—";
        try {
            InputConstants.Key k = InputConstants.getKey(mapping.saveString());
            if (k.getType() == keyboardType()) {
                physical = String.valueOf(isPhysicallyKeyDown(k.getValue()));
            }
        } catch (Throwable t) {
            physical = "解析失败";
        }
        feedback(source, "§e" + label + "§f: " + bound
                + " ｜ 按键分发: " + mapping.isDown()
                + " ｜ 物理读: " + physical);
    }
}
