package com.tongyongfanyi.mixin;

import com.tongyongfanyi.translate.TranslationHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.BossEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** 顶部 boss 栏文字翻译 */
@Mixin(BossEvent.class)
public abstract class BossEventMixin {
    @Inject(method = "getName", at = @At("RETURN"), cancellable = true)
    private void tyfy$translateName(CallbackInfoReturnable<Component> cir) {
        cir.setReturnValue(TranslationHelper.translate(cir.getReturnValue()));
    }
}
