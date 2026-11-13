package com.tongyongfanyi;

import com.tongyongfanyi.config.ModConfig;
import com.tongyongfanyi.translate.TranslationHelper;
import com.tongyongfanyi.translate.TranslationManager;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.PlayerChatMessage;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 拦截服务器发来的消息进行翻译。只处理「收到」的消息，不改任何发包。
 *
 * <p><b>关键背景（这是聊天栏反复漏翻的根因）</b>：Hypixel 的玩家聊天、NPC 对话、
 * 区域提示几乎全部是 system message（日志里 {@code [System] [CHAT]}），走的是
 * {@code MODIFY_GAME}，而不是 {@code ALLOW_CHAT}。旧版 {@code MODIFY_GAME} 只在缓存
 * 命中时替换、未命中就放行原文，于是只有消息重复出现时才碰巧翻出来；
 * 给 {@code ALLOW_CHAT} 加补发机制对 Hypixel 完全无效。
 *
 * <p>现在两条路径统一：把消息包成 {@link com.tongyongfanyi.translate.BilingualComponent}，
 * 整句一次送模型；首次显示原文，译文写进缓存后由客户端通知聊天栏重新排版，
 * 同一条消息原地变成中文 —— 不新增消息、不重复刷屏、不丢消息时序。
 * 按 O 键可随时切回完全原始的组件（带可点击的 NPC 选项按钮）。
 */
public class MessageInterceptor {
    private final TranslationManager manager;
    /** 防止补发的译文再次触发事件形成死循环（只有玩家聊天补发需要） */
    private final AtomicBoolean reentrant = new AtomicBoolean(false);

    public MessageInterceptor(TranslationManager manager) {
        this.manager = manager;
    }

    public void register() {
        ClientReceiveMessageEvents.MODIFY_GAME.register(this::modifyGameMessage);
        ClientReceiveMessageEvents.ALLOW_CHAT.register(this::onChatMessage);
    }

    /**
     * 游戏消息（Hypixel 聊天/NPC/系统提示的主力通道）。
     * 返回值由 Fabric 写回消息本体，因此不需要取消再补发，消息时序与聊天记录都不变。
     */
    public Component modifyGameMessage(Component message, boolean overlay) {
        ModConfig config = manager.config();
        // reentrant：补发的玩家聊天会再走一次 MODIFY_GAME，别套娃包装。
        if (!config.enabled || !config.translateGameMessages || reentrant.get()) {
            return message;
        }
        // 动作栏每 tick 重发同一条，包装后靠重复刷新自然命中缓存，不会漏翻。
        return TranslationHelper.wrapChatLine(message);
    }

    /** 原版玩家聊天：Fabric 没有 MODIFY_CHAT，只能取消原消息后立刻补发包装过的同款消息 */
    private boolean onChatMessage(Component message,
                                  PlayerChatMessage playerChatMessage,
                                  com.mojang.authlib.GameProfile sender,
                                  ChatType.Bound boundChatType,
                                  Instant timeStamp) {
        ModConfig config = manager.config();
        if (!config.enabled || !config.translateChatMessages || reentrant.get()) {
            return true;
        }
        Component content = contentComponent(message, playerChatMessage);
        Component wrapped = TranslationHelper.wrapChatLine(content);
        if (wrapped == content) {
            // 纯中文 / 纯符号 / 不值得翻：直接放行，别无谓地压住消息
            return true;
        }
        reAddChatMessage(wrapped, boundChatType);
        return false;
    }

    /** 提取聊天内容本体组件（保留样式/点击事件），不含发送者名字等装饰 */
    private static Component contentComponent(Component message, PlayerChatMessage playerChatMessage) {
        if (playerChatMessage != null) {
            Component unsigned = playerChatMessage.unsignedContent();
            if (unsigned != null) {
                return unsigned;
            }
            return Component.literal(playerChatMessage.signedContent());
        }
        return message;
    }

    private void reAddChatMessage(Component content, ChatType.Bound boundChatType) {
        Minecraft client = Minecraft.getInstance();
        client.execute(() -> {
            if (client.player == null) return;
            if (!reentrant.compareAndSet(false, true)) return;
            try {
                Component component = boundChatType != null ? boundChatType.decorate(content) : content;
                client.player.sendSystemMessage(component);
            } finally {
                reentrant.set(false);
            }
        });
    }
}
