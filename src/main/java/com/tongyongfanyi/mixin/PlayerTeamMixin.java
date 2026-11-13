package com.tongyongfanyi.mixin;

import com.tongyongfanyi.translate.TranslationHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Team;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 计分板 / 玩家列表「整行」翻译。
 *
 * <p>侧边栏一行在 26.3 里是这样拼出来的：
 * {@code PlayerTeam.formatNameForTeam(team, entry.ownerName())} = 前缀 + 名字 + 后缀。
 * Hypixel 会把一串文字按队伍前缀（最多 16 字）和后缀切开，于是
 * {@code Lava Springs} 变成前缀 {@code Lava Spri} + 后缀 {@code ngs}，
 * {@code Spring} 也就被劈成 {@code Spri} + {@code ng}。
 *
 * <p>旧写法分别翻译前缀和后缀：前半段命中术语表变成「熔岩泉」，后半段 {@code ngs}
 * 只有 3 个字母、按「太短」被丢掉，屏幕上就成了「熔岩泉ngs」。
 * 这不是模型截断，是我们自己把词劈开了再分别送去翻。
 *
 * <p>所以改为在<b>拼好的整行</b>上翻译：前缀 + 名字 + 后缀先合成一句，
 * 再查术语表 / 送模型。原来分别钩前缀、后缀两个字段的 {@code @Redirect}
 * 和 {@code PlayerScoreEntry#ownerName} 都已移除。
 */
@Mixin(PlayerTeam.class)
public abstract class PlayerTeamMixin {

    @Inject(method = "formatNameForTeam", at = @At("RETURN"), cancellable = true)
    private static void tyfy$translateWholeLine(Team team, Component name,
                                                CallbackInfoReturnable<MutableComponent> cir) {
        MutableComponent line = cir.getReturnValue();
        if (line == null) {
            return;
        }
        Component translated = TranslationHelper.translateScoreboard(line);
        if (translated instanceof MutableComponent mc && mc != line) {
            cir.setReturnValue(mc);
        }
    }
}
