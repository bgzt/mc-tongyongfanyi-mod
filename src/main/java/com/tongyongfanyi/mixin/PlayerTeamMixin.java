package com.tongyongfanyi.mixin;

import com.tongyongfanyi.translate.TranslationHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.scores.PlayerTeam;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 计分板行文字翻译：很多服务器的计分板把可见文字放在队伍前缀/后缀里，
 * 玩家名只是占位符。
 *
 * <p>26.2 的侧边栏走 {@code PlayerTeam.formatNameForTeam(team, name)} →
 * {@code PlayerTeam#getFormattedName}，而 getFormattedName 是直接读
 * playerPrefix/playerSuffix 两个私有字段拼行的（不是走 getPlayerPrefix() 访问器），
 * 所以必须拦截字段读取。
 *
 * <p>注意：{@code getPlayerPrefix()} / {@code getPlayerSuffix()} 在客户端
 * 唯一的调用点是 {@code ClientboundSetPlayerTeamPacket.Parameters(PlayerTeam)}，
 * 那是**服务端构造队伍包**时用的；客户端渲染从不到那里去。所以给这两个访问器加钩子
 * 既翻不到任何东西（死代码），在单机（集成服务器与客户端同一个 JVM）下还有把译文
 * 写进回传队伍数据的风险，与「绝不修改发往服务器的内容」的承诺冲突，已移除。
 */
@Mixin(PlayerTeam.class)
public abstract class PlayerTeamMixin {
    @Shadow
    private Component playerPrefix;

    @Shadow
    private Component playerSuffix;

    /** getFormattedName 直接读字段拼接行，需拦截字段读取才能翻译前缀 */
    @Redirect(method = "getFormattedName",
            at = @At(value = "FIELD",
                    target = "Lnet/minecraft/world/scores/PlayerTeam;playerPrefix:Lnet/minecraft/network/chat/Component;"))
    private Component tyfy$translatePrefixField(PlayerTeam team) {
        return TranslationHelper.translate(this.playerPrefix);
    }

    /** 同上，拦截后缀字段读取 */
    @Redirect(method = "getFormattedName",
            at = @At(value = "FIELD",
                    target = "Lnet/minecraft/world/scores/PlayerTeam;playerSuffix:Lnet/minecraft/network/chat/Component;"))
    private Component tyfy$translateSuffixField(PlayerTeam team) {
        return TranslationHelper.translate(this.playerSuffix);
    }
}
