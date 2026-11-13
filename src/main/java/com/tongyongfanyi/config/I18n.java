package com.tongyongfanyi.config;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import net.minecraft.network.chat.Component;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;

/**
 * 模组界面文案：自己读 {@code assets/tongyongfanyi/lang/*.json}。
 *
 * <p>不走 {@code Component.translatable} 的游戏语言系统 —— 26.3 的
 * {@code ClientLanguage.loadFrom} 只把 {@code en_us} + 当前语言两份资源并进翻译表，
 * 语言代码能否进入列表还取决于资源包 {@code pack.mcmeta} 的声明；
 * 实测游戏设成 zh_cn 时模组自带的 zh_cn.json 没被带上，界面直接回退成英文。
 * 自己加载完全可控，还能让「界面语言」设置强制指定，不受游戏资源包影响。
 *
 * <p>默认 {@code auto} 跟随 {@code Options.languageCode}；找不到对应文件按语言前缀
 * 就近匹配，最后回退 en_us。表按语言代码缓存，切换时不重复读文件。
 */
public final class I18n {
    private static final String RES = "assets/tongyongfanyi/lang/";
    private static final Type MAP_TYPE = new TypeToken<Map<String, String>>() {}.getType();
    private static final Gson GSON = new Gson();
    private static final Map<String, Map<String, String>> LOADED = new ConcurrentHashMap<>();

    /** 设置页可选的界面语言；auto = 跟随游戏语言 */
    public static final List<String> UI_LANGUAGES = List.of(
            "auto", "zh_cn", "zh_tw", "en_us", "ja_jp", "ko_kr", "de_de", "fr_fr", "es_es", "ru_ru");

    /** 下拉里显示的原生语言名 */
    private static final Map<String, String> NATIVE_NAMES = Map.ofEntries(
            Map.entry("zh_cn", "简体中文"), Map.entry("zh_tw", "繁體中文"),
            Map.entry("en_us", "English"), Map.entry("ja_jp", "日本語"),
            Map.entry("ko_kr", "한국어"), Map.entry("de_de", "Deutsch"),
            Map.entry("fr_fr", "Français"), Map.entry("es_es", "Español"),
            Map.entry("ru_ru", "Русский"));

    private static volatile String forced = "auto";

    private I18n() {
    }

    public static void setUiLanguage(String value) {
        forced = value == null || value.isBlank() ? "auto" : value;
    }

    public static String uiLanguage() {
        return forced;
    }

    /** 界面语言下拉里显示的文字 */
    public static String uiName(String code) {
        if ("auto".equals(code)) {
            return t("lang.game");   // 跟随游戏语言 / Match Game Language
        }
        return NATIVE_NAMES.getOrDefault(code, code);
    }

    private static String effectiveCode() {
        String code = forced;
        if ("auto".equals(code) || code == null || code.isBlank()) {
            code = Languages.gameCode();
        }
        return normalize(code);
    }

    private static String normalize(String code) {
        String c = code.toLowerCase(Locale.ROOT).replace('-', '_');
        if (UI_LANGUAGES.contains(c)) {
            return c;
        }
        // 没收录的地区变体（en_gb、zh_hk、pt_br …）按语言前缀就近匹配
        if (c.startsWith("zh")) {
            return c.contains("tw") || c.contains("hk") || c.contains("hant") ? "zh_tw" : "zh_cn";
        }
        if (c.startsWith("ja")) return "ja_jp";
        if (c.startsWith("ko")) return "ko_kr";
        if (c.startsWith("de")) return "de_de";
        if (c.startsWith("fr")) return "fr_fr";
        if (c.startsWith("es")) return "es_es";
        if (c.startsWith("ru")) return "ru_ru";
        return "en_us";
    }

    private static Map<String, String> table() {
        return LOADED.computeIfAbsent(effectiveCode(), I18n::load);
    }

    private static Map<String, String> load(String code) {
        Map<String, String> merged = new HashMap<>();
        loadInto(merged, "en_us");                    // 缺 key 时用英文兜底
        if (!"en_us".equals(code)) {
            loadInto(merged, code);
        }
        return merged;
    }

    private static void loadInto(Map<String, String> target, String code) {
        InputStream in = I18n.class.getClassLoader().getResourceAsStream(RES + code + ".json");
        if (in == null) {
            return;
        }
        try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            Map<String, String> loaded = GSON.fromJson(reader, MAP_TYPE);
            if (loaded != null) {
                target.putAll(loaded);
            }
        } catch (Exception ignored) {
            // 资源缺失时用英文表兜底，不让设置页打不开
        }
    }

    /** 取一句当前界面语言的纯文本，支持 %s 占位符 */
    public static String t(String key, Object... args) {
        String value = table().get(key);
        if (value == null) {
            value = key;
        }
        for (Object arg : args) {
            value = value.replaceFirst("%s", Matcher.quoteReplacement(String.valueOf(arg)));
        }
        return value;
    }

    /** 返回已解析好的组件（不走 Component.translatable，避免再被游戏语言系统改写） */
    public static Component c(String key, Object... args) {
        return Component.literal(t(key, args));
    }
}
