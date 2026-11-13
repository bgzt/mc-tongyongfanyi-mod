package com.tongyongfanyi.mixin;

import com.tongyongfanyi.TongyongfanyiClient;
import com.tongyongfanyi.config.I18n;
import com.tongyongfanyi.config.SettingsScreen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 在游戏内暂停菜单左下角加一个「通用翻译」按钮。
 *
 * <p>这样不装 ModMenu 也能进设置，不用记 /tyfy 命令。
 * 按钮放在左下角而不是菜单网格里，是因为原版网格的行列会随玩法
 * （单人/多人、是否允许作弊）变化，硬插进去容易和原版按钮重叠。
 */
@Mixin(PauseScreen.class)
public abstract class PauseScreenMixin extends Screen {

    protected PauseScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void tyfy$addSettingsButton(CallbackInfo ci) {
        TongyongfanyiClient mod = TongyongfanyiClient.getInstance();
        if (mod == null) {
            return;
        }
        Button button = Button.builder(I18n.c("menu.button"), b -> {
                    if (this.minecraft != null) {
                        this.minecraft.setScreenAndShow(new SettingsScreen(
                                mod.getConfig(), mod.getConfigManager(), mod.getTranslationManager(), this));
                    }
                })
                .bounds(4, this.height - 24, 84, 20)
                .tooltip(Tooltip.create(I18n.c("menu.tooltip")))
                .build();
        this.addRenderableWidget(button);
    }
}
