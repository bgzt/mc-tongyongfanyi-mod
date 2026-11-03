package com.tongyongfanyi.mixin;

import com.tongyongfanyi.translate.TranslationHelper;
import com.tongyongfanyi.translate.TranslationManager;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;

/**
 * 物品悬停提示 (tooltip) 翻译。
 * 只影响客户端显示，不修改物品栈、不发任何包，传回服务器的内容完全不变。
 */
@Mixin(ItemStack.class)
public abstract class ItemStackMixin {
    @Inject(method = "getTooltipLines", at = @At("RETURN"), cancellable = true)
    private void tyfy$translateTooltip(Item.TooltipContext context, Player player, TooltipFlag flag,
                                       CallbackInfoReturnable<List<Component>> cir) {
        TranslationManager manager = TranslationHelper.manager();
        if (manager == null || !manager.config().enabled || !manager.config().translateItemTooltips) {
            return;
        }
        List<Component> lines = cir.getReturnValue();
        if (lines == null || lines.isEmpty()) {
            return;
        }

        boolean changed = false;
        List<Component> result = new ArrayList<>(lines.size());
        for (Component line : lines) {
            Component translated = TranslationHelper.translate(line);
            if (translated != line) {
                changed = true;
                // 长译文自动换行：拆成多行，避免一行过长顶出提示框/被截断
                String text = translated.getString();
                if (text.length() > 30) {
                    for (String seg : wrapText(text, 30)) {
                        result.add(Component.literal(seg).withStyle(translated.getStyle()));
                    }
                    continue;
                }
            }
            result.add(translated);
        }
        if (changed) {
            cir.setReturnValue(result);
        }
    }

    /** 按单词/字符把长文本拆成不超过 maxLen 的多段（优先在空格处断行） */
    private static List<String> wrapText(String text, int maxLen) {
        List<String> lines = new ArrayList<>();
        String[] words = text.split(" ");
        StringBuilder cur = new StringBuilder();
        for (String word : words) {
            if (cur.length() > 0 && cur.length() + 1 + word.length() > maxLen) {
                lines.add(cur.toString());
                cur.setLength(0);
            }
            if (cur.length() > 0) {
                cur.append(' ');
            }
            cur.append(word);
            // 单个超长词（如长链接/长ID）按字符硬切
            while (cur.length() > maxLen) {
                lines.add(cur.substring(0, maxLen));
                cur.delete(0, maxLen);
            }
        }
        if (cur.length() > 0) {
            lines.add(cur.toString());
        }
        return lines;
    }
}
