package com.tongyongfanyi.mixin;

import com.tongyongfanyi.translate.TranslationHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.scores.Objective;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** 计分板标题翻译 */
@Mixin(Objective.class)
public abstract class ObjectiveMixin {
    @Inject(method = "getDisplayName", at = @At("RETURN"), cancellable = true)
    private void tyfy$translateDisplayName(CallbackInfoReturnable<Component> cir) {
        cir.setReturnValue(TranslationHelper.translate(cir.getReturnValue()));
    }
}
