package com.tongyongfanyi.translate;

import com.tongyongfanyi.TongyongfanyiClient;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 通用显示文本翻译：供物品提示、boss 栏、名牌、计分板、悬浮文字等
 * "屏幕上的显示文本"共用。只改客户端显示，不发包、不修改发往服务器的内容。
 */
public final class TranslationHelper {
    private static final Pattern LATIN = Pattern.compile("[A-Za-z]");
    private static final Pattern CJK = Pattern.compile("[\\u4e00-\\u9fa5]");
    private static final Pattern SECTION = Pattern.compile("\\u00a7.");
    /** 网址/IP 不翻译，避免界面上的链接被翻译破坏 */
    private static final Pattern URL = Pattern.compile("(?i)(https?://|www\\.|\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b)");

    private TranslationHelper() {
    }

    public static TranslationManager manager() {
        try {
            TongyongfanyiClient mod = TongyongfanyiClient.getInstance();
            return mod != null ? mod.getTranslationManager() : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 翻译一段显示文本：缓存命中替换并保留格式，未命中触发后台翻译并返回原文 */
    public static Component translate(Component component) {
        if (component == null) {
            return null;
        }
        TranslationManager manager = manager();
        if (manager == null || !manager.config().enabled) {
            return component;
        }
        return translateLine(manager, component, false, false);
    }

    /** 聊天栏专用翻译：与 translate 相同，但后台请求走高优先级，聊天优先翻译 */
    public static Component translateChat(Component component) {
        if (component == null) {
            return null;
        }
        TranslationManager manager = manager();
        if (manager == null || !manager.config().enabled) {
            return component;
        }
        return translateLine(manager, component, false, true);
    }

    /** 屏幕绘制层通用翻译：与 translate 相同，但未命中的后台提交按速率限制，避免刷屏 */
    public static Component translateRender(Component component) {
        if (component == null) {
            return null;
        }
        TranslationManager manager = manager();
        if (manager == null || !manager.config().enabled || !manager.config().translateScreenText) {
            return component;
        }
        return translateLine(manager, component, true, false);
    }

    /**
     * 翻译一行：按"样式+文本"逐段分解（保留子组件样式与 § 格式码），
     * 只翻译可译片段，未命中触发后台翻译并原样保留。
     */
    private static Component translateLine(TranslationManager manager, Component line,
                                           boolean rateLimited, boolean chatPriority) {
        MutableComponent result = Component.empty().withStyle(line.getStyle());
        boolean[] changed = {false};
        line.<Object>visit((style, text) -> {
            for (Run run : splitRuns(text)) {
                Style runStyle = style;
                for (int i = 0; i + 1 < run.codes().length(); i += 2) {
                    ChatFormatting fmt = ChatFormatting.getByCode(run.codes().charAt(i + 1));
                    if (fmt != null) {
                        runStyle = runStyle.applyLegacyFormat(fmt);
                    }
                }
                String clean = SECTION.matcher(run.text()).replaceAll("");
                if (clean.indexOf('\u00a7') >= 0 || !shouldTranslate(manager, clean)) {
                    result.append(Component.literal(run.text()).withStyle(style));
                    continue;
                }
                String cached = manager.cached(clean);
                if (cached != null) {
                    // 模型有时会给不完整片段自行补"..."（如 "Talk to " -> "与...交谈"），
                    // 原文没有省略号时去掉译文里多余的省略号。
                    String cleaned = cleanTranslation(clean, cached);
                    result.append(Component.literal(cleaned).withStyle(runStyle));
                    changed[0] = true;
                } else {
                    submitBackground(manager, clean, rateLimited, chatPriority);
                    result.append(Component.literal(run.text()).withStyle(style));
                }
            }
            return Optional.empty();
        }, Style.EMPTY);
        return changed[0] ? result : line;
    }

    /** 去掉模型自行添加的省略号：仅当原文本身没有省略号时生效 */
    private static String cleanTranslation(String original, String translated) {
        if (translated == null || translated.isBlank()) {
            return translated;
        }
        if (original.contains("...") || original.contains("…")) {
            return translated;
        }
        if (!translated.contains("...") && !translated.contains("…")) {
            return translated;
        }
        String cleaned = translated.replace("…", "").replace("...", "").trim();
        return cleaned.isBlank() ? translated : cleaned;
    }

    private static void submitBackground(TranslationManager manager, String clean,
                                         boolean rateLimited, boolean chatPriority) {
        if (chatPriority) {
            manager.translateChatInBackground(clean);
        } else if (rateLimited) {
            manager.translateInBackgroundRateLimited(clean);
        } else {
            manager.translateInBackground(clean);
        }
    }

    private record Run(String codes, String text) {
    }

    /** 按 § 格式码把一段文本拆成多段，格式码作为下一段的固定前缀 */
    private static List<Run> splitRuns(String s) {
        List<Run> runs = new ArrayList<>();
        StringBuilder codes = new StringBuilder();
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '\u00a7' && i + 1 < s.length()) {
                codes.append(c).append(s.charAt(i + 1));
                i += 2;
            } else {
                int start = i;
                while (i < s.length() && s.charAt(i) != '\u00a7') {
                    i++;
                }
                runs.add(new Run(codes.toString(), s.substring(start, i)));
                codes.setLength(0);
            }
        }
        if (codes.length() > 0 && !runs.isEmpty()) {
            runs.add(new Run(codes.toString(), ""));
        }
        return runs;
    }

    private static boolean shouldTranslate(TranslationManager manager, String plain) {
        if (plain == null || plain.isBlank()) {
            return false;
        }
        if (plain.length() < manager.config().minLength) {
            return false;
        }
        if (!LATIN.matcher(plain).find()) {
            return false;
        }
        if (URL.matcher(plain).find()) {
            return false;
        }
        if (manager.config().skipIfContainsChinese && CJK.matcher(plain).find()) {
            return false;
        }
        return !plain.startsWith("/");
    }
}
