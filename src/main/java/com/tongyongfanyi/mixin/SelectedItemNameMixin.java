package com.tongyongfanyi.mixin;

import com.tongyongfanyi.translate.TranslationHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 物品栏上方切换物品时弹出的物品名提示翻译。
 * 只改客户端显示，不影响物品栈、不修改任何发往服务器的内容。
 */
@Mixin(net.minecraft.client.gui.Hud.class)
public abstract class SelectedItemNameMixin {
    @Redirect(method = "extractSelectedItemName",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/item/ItemStack;getHoverName()Lnet/minecraft/network/chat/Component;"))
    private Component tyfy$translateSelectedItemName(ItemStack stack) {
        return TranslationHelper.translate(stack.getHoverName());
    }
}
