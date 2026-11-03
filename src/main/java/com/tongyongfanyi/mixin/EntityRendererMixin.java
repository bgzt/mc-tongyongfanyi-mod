package com.tongyongfanyi.mixin;

import com.tongyongfanyi.translate.TranslationHelper;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** 实体/玩家头顶名牌翻译（含悬空文字、生物名字、玩家名字） */
@Mixin(EntityRenderer.class)
public abstract class EntityRendererMixin {
    @Inject(method = "getNameTag", at = @At("RETURN"), cancellable = true)
    private void tyfy$translateNameTag(CallbackInfoReturnable<Component> cir) {
        cir.setReturnValue(TranslationHelper.translate(cir.getReturnValue()));
    }
}
