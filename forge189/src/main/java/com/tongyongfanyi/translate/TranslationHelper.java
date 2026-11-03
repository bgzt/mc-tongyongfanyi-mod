package com.tongyongfanyi.translate;

import com.tongyongfanyi.TongyongfanyiMod;
import com.tongyongfanyi.config.ModConfig;

import java.util.regex.Pattern;

/**
 * 翻译辅助：格式码剥离、是否可译判断、同步缓存查询 + 后台翻译。
 */
public final class TranslationHelper {
    private static final Pattern LATIN = Pattern.compile("[A-Za-z]");
    private static final Pattern CJK = Pattern.compile("[\\u4e00-\\u9fa5]");
    private static final Pattern SECTION = Pattern.compile("\\u00a7.");
    private static final Pattern URL = Pattern.compile("(?i)(https?://|www\\.|\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b)");

    private TranslationHelper() {
    }

    public static TranslationManager manager() {
        return TongyongfanyiMod.translationManager;
    }

    public static ModConfig config() {
        return TongyongfanyiMod.config;
    }

    public static boolean enabled() {
        ModConfig c = config();
        return c != null && c.enabled && manager() != null;
    }

    /**
     * 翻译一段纯文本：缓存命中返回译文（并做长度/省略号清理），未命中触发后台翻译并返回原文。
     */
    public static String translateText(String raw) {
        if (raw == null || raw.isEmpty() || !enabled()) {
            return raw;
        }
        String clean = SECTION.matcher(raw).replaceAll("");
        if (clean.indexOf('\u00a7') >= 0 || !shouldTranslate(clean)) {
            return raw;
        }
        TranslationManager manager = manager();
        String cached = manager.cached(clean);
        if (cached != null) {
            return cleanTranslation(clean, cached);
        }
        manager.translateInBackground(clean);
        return raw;
    }

    /**
     * 翻译一行可能带前缀格式码的文本（物品提示等），保留前缀 § 颜色码。
     */
    public static String translateLineWithCodes(String raw) {
        if (raw == null || raw.isEmpty() || !enabled()) {
            return raw;
        }
        StringBuilder codes = new StringBuilder();
        int i = 0;
        while (i + 1 < raw.length() && raw.charAt(i) == '\u00a7') {
            codes.append(raw.charAt(i)).append(raw.charAt(i + 1));
            i += 2;
        }
        String body = raw.substring(i);
        String translated = translateText(body);
        if (translated == null || translated.equals(body)) {
            return raw;
        }
        return codes.toString() + translated;
    }

    public static boolean shouldTranslate(String plain) {
        if (plain == null || plain.isEmpty()) {
            return false;
        }
        ModConfig c = config();
        if (plain.length() < c.minLength) {
            return false;
        }
        if (!LATIN.matcher(plain).find()) {
            return false;
        }
        if (URL.matcher(plain).find()) {
            return false;
        }
        if (c.skipIfContainsChinese && CJK.matcher(plain).find()) {
            return false;
        }
        return !plain.startsWith("/");
    }

    /** 去掉模型自行添加的省略号（原文本身没有省略号时） */
    private static String cleanTranslation(String original, String translated) {
        if (translated == null || translated.trim().isEmpty()) {
            return translated;
        }
        if (original.contains("...") || original.contains("\u2026")) {
            return translated;
        }
        if (!translated.contains("...") && !translated.contains("\u2026")) {
            return translated;
        }
        String cleaned = translated.replace("\u2026", "").replace("...", "").trim();
        return cleaned.isEmpty() ? translated : cleaned;
    }
}
