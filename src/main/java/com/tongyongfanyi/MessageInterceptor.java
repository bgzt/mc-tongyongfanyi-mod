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
 * 拦截服务器发来的消息进行翻译。
 * 按"样式+文本"逐段翻译，保留原文的颜色、点击事件与悬停信息；
 * 未命中缓存时放行原文（保证可点击），后台翻译完成后下一次直接命中。
 * 只处理「收到」的消息，玩家自己发出的消息不受任何影响。
 *
 * <p>两条路径分开处理：
 * <ul>
 *   <li>游戏消息（含动作栏）：走 {@code MODIFY_GAME}。该事件的返回值就是最终显示内容，
 *       直接返回译文即可，不需要取消原消息再补发，因此不会丢消息，也不会改变
 *       消息时序、聊天记录与动作栏（overlay）语义。</li>
 *   <li>玩家聊天：Fabric 只提供 {@code ALLOW_CHAT}（没有 MODIFY_CHAT），
 *       命中缓存时取消原消息并立刻补发一条带译文的同款消息。</li>
 * </ul>
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
     * 游戏消息 / 动作栏：命中缓存时替换为译文，否则原样返回并触发后台翻译。
     * 返回值由 Fabric 写回消息本体，因此无需取消原消息。
     */
    public Component modifyGameMessage(Component message, boolean overlay) {
        ModConfig config = manager.config();
        if (!config.enabled || !config.translateGameMessages) {
            return message;
        }
        return TranslationHelper.translateChat(message);
    }

    /** 聊天消息：只翻译内容本体，保留样式/点击事件；未命中放行原文 */
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
        Component translated = TranslationHelper.translateChat(content);
        if (translated == content) {
            return true; // 未命中缓存：原文放行（可点击），后台已开始翻译
        }
        reAddChatMessage(translated, boundChatType);
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
