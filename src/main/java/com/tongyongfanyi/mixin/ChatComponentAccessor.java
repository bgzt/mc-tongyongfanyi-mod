package com.tongyongfanyi.mixin;

import net.minecraft.client.gui.components.ChatComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 读写聊天栏滚动位置。
 *
 * <p>{@code ChatComponent#rescaleChat()} 内部会先 {@code resetChatScroll()}，
 * 译文写进缓存后每次重排版都会把正在翻历史记录的玩家拉回最底部。
 * 有了这个 accessor 就能在重排版前后保存/恢复滚动位置。
 * 调用处用 {@code instanceof} 判断，万一注入失败也不影响刷新本身。
 */
@Mixin(ChatComponent.class)
public interface ChatComponentAccessor {
    @Accessor("chatScrollbarPos")
    int tyfy$scrollPos();

    @Accessor("chatScrollbarPos")
    void tyfy$setScrollPos(int value);
}
