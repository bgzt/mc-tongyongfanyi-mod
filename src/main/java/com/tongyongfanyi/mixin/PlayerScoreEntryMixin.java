package com.tongyongfanyi.mixin;

import com.tongyongfanyi.translate.TranslationHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.scores.PlayerScoreEntry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 计分板行（玩家名）翻译。
 * 26.2 的侧边栏用 {@code Hud#extractScoreboardSidebar} 里的
 * {@code score.ownerName()} 取行文字（display 组件存在时它就是行文字本体，
 * 否则回退到玩家名），因此这里只需要钩 ownerName。
 * 注意：{@code display()} 是 record 的原始访问器，渲染路径从不调用它，
 * 钩它属于无效注入，已移除。
 */
@Mixin(PlayerScoreEntry.class)
public abstract class PlayerScoreEntryMixin {
    @Inject(method = "ownerName", at = @At("RETURN"), cancellable = true)
    private void tyfy$translateOwnerName(CallbackInfoReturnable<Component> cir) {
        cir.setReturnValue(TranslationHelper.translate(cir.getReturnValue()));
    }
}
