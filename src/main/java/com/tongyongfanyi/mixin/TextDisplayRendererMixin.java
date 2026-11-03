package com.tongyongfanyi.mixin;

import com.tongyongfanyi.translate.TranslationHelper;
import net.minecraft.client.renderer.entity.DisplayRenderer;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** 空中悬浮文字（text display 实体）翻译 */
@Mixin(DisplayRenderer.TextDisplayRenderer.class)
public abstract class TextDisplayRendererMixin {
    @ModifyVariable(method = "splitLines", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private Component tyfy$translateText(Component text) {
        return TranslationHelper.translate(text);
    }
}
