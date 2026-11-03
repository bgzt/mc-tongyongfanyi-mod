package com.tongyongfanyi.config;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import com.tongyongfanyi.TongyongfanyiClient;

/**
 * ModMenu 集成：在模组列表里给本 mod 添加"设置"按钮，点击打开设置界面。
 * ModMenu 为可选依赖，未安装时此 entrypoint 不会被调用，不影响 mod 正常运行。
 */
public class ModMenuIntegration implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return parent -> {
            TongyongfanyiClient mod = TongyongfanyiClient.getInstance();
            return new SettingsScreen(mod.getConfig(), mod.getConfigManager(), mod.getTranslationManager());
        };
    }
}
