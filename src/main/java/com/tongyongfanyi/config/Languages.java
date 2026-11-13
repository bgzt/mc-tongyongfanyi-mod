package com.tongyongfanyi.config;

import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 设置页可选的源语言 / 目标语言。
 *
 * <p>{@code id} 存进配置（也用作缓存键的一部分），{@code prompt} 是拼进提示词时用的中文名，
 * 两者分开是为了以后改提示词措辞不影响已有配置和缓存。
 *
 * <p>目标语言多了一个 {@code game}：跟随 Minecraft「选项 → 语言」里选的语言，
 * 由 {@link #resolveTarget(String)} 在运行时解析成真正的语言 id。
 */
public final class Languages {
    /** 一种语言 */
    public record Lang(String id, String label, String prompt) {
        @Override
        public String toString() {
            return label;
        }
    }

    private static final Lang AUTO = new Lang("auto", "自动检测", "自动检测到的语言");
    /** 只作为目标语言出现：跟随游戏语言 */
    private static final Lang GAME = new Lang("game", "跟随游戏语言", "跟随游戏语言");

    private static final List<Lang> COMMON = List.of(
            new Lang("en", "英语", "英语"),
            new Lang("zh-CN", "简体中文", "简体中文"),
            new Lang("zh-TW", "繁体中文", "繁体中文"),
            new Lang("ja", "日语", "日语"),
            new Lang("ko", "韩语", "韩语"),
            new Lang("fr", "法语", "法语"),
            new Lang("de", "德语", "德语"),
            new Lang("es", "西班牙语", "西班牙语"),
            new Lang("ru", "俄语", "俄语"),
            new Lang("pt", "葡萄牙语", "葡萄牙语"),
            new Lang("it", "意大利语", "意大利语"),
            // 新增语种
            new Lang("nl", "荷兰语", "荷兰语"),
            new Lang("pl", "波兰语", "波兰语"),
            new Lang("tr", "土耳其语", "土耳其语"),
            new Lang("vi", "越南语", "越南语"),
            new Lang("th", "泰语", "泰语"),
            new Lang("id", "印尼语", "印尼语"),
            new Lang("ar", "阿拉伯语", "阿拉伯语"),
            new Lang("uk", "乌克兰语", "乌克兰语"),
            new Lang("cs", "捷克语", "捷克语"),
            new Lang("sv", "瑞典语", "瑞典语"),
            new Lang("da", "丹麦语", "丹麦语"),
            new Lang("fi", "芬兰语", "芬兰语"),
            new Lang("el", "希腊语", "希腊语"),
            new Lang("hi", "印地语", "印地语"));

    /** 源语言：含“自动检测”，不含“跟随游戏语言” */
    public static final List<Lang> SOURCES =
            Stream.concat(Stream.of(AUTO), COMMON.stream()).toList();

    /** 目标语言：含“跟随游戏语言”，不含“自动检测” */
    public static final List<Lang> TARGETS =
            Stream.concat(Stream.of(GAME), COMMON.stream()).toList();

    /** Minecraft 语言代码（如 zh_cn / en_us）→ 本模组的语言 id */
    private static final Map<String, String> MC_TO_ID = Map.ofEntries(
            Map.entry("zh_cn", "zh-CN"), Map.entry("zh_tw", "zh-TW"), Map.entry("zh_hk", "zh-TW"),
            Map.entry("ja_jp", "ja"), Map.entry("ko_kr", "ko"),
            Map.entry("fr_fr", "fr"), Map.entry("de_de", "de"),
            Map.entry("es_es", "es"), Map.entry("es_mx", "es"),
            Map.entry("ru_ru", "ru"), Map.entry("pt_pt", "pt"), Map.entry("pt_br", "pt"),
            Map.entry("it_it", "it"), Map.entry("nl_nl", "nl"), Map.entry("nl_be", "nl"),
            Map.entry("pl_pl", "pl"), Map.entry("tr_tr", "tr"),
            Map.entry("vi_vn", "vi"), Map.entry("th_th", "th"), Map.entry("id_id", "id"),
            Map.entry("ar_sa", "ar"), Map.entry("uk_ua", "uk"),
            Map.entry("cs_cz", "cs"), Map.entry("sv_se", "sv"),
            Map.entry("da_dk", "da"), Map.entry("fi_fi", "fi"),
            Map.entry("el_gr", "el"), Map.entry("hi_in", "hi"));

    private Languages() {
    }

    /** 当前游戏里选的语言代码，取不到时按简体中文兜底 */
    public static String gameCode() {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc != null && mc.options != null && mc.options.languageCode != null) {
                return mc.options.languageCode.toLowerCase(Locale.ROOT);
            }
        } catch (Throwable ignored) {
            // 还没进游戏 / 无头环境
        }
        return "zh_cn";
    }

    /**
     * 把 {@code game} 解析成真正的目标语言 id；其余原样返回。
     * 提示词和缓存键都必须用解析后的值，否则玩家改了游戏语言却还在命中旧译文。
     */
    public static String resolveTarget(String id) {
        if (!GAME.id().equals(id)) {
            return id;
        }
        String code = gameCode();
        String mapped = MC_TO_ID.get(code);
        if (mapped != null) {
            return mapped;
        }
        // 没收录的地区变体（en_gb、de_at …）按语言前缀兜底
        String prefix = code.split("_", 2)[0];
        for (Lang l : COMMON) {
            if (l.id().equalsIgnoreCase(prefix)
                    || l.id().toLowerCase(Locale.ROOT).startsWith(prefix + "-")) {
                return l.id();
            }
        }
        return "en";
    }

    /**
     * 设置页里显示的语言名。语言名用<b>该语言自己的写法</b>（English / 日本語 / 한국어 …），
     * 这样无论游戏切成哪种语言，下拉里都能认出来；「自动检测」「跟随游戏语言」
     * 则走 I18n 按当前游戏语言翻译。
     */
    public static String uiLabel(String id) {
        if (AUTO.id().equals(id)) {
            return I18n.t("lang.auto");
        }
        if (GAME.id().equals(id)) {
            return I18n.t("lang.game");
        }
        return I18n.t("lang." + id);
    }

    /** 给界面显示用：跟随游戏语言时把当前实际语言也一起显示出来 */
    public static String displayTarget(String id) {
        if (!GAME.id().equals(id)) {
            return uiLabel(id);
        }
        return I18n.t("lang.game.with", uiLabel(resolveTarget(id)));
    }

    /** 按 id 找语言；找不到时按默认值处理，避免旧配置把界面搞崩 */
    public static Lang byId(List<Lang> pool, String id) {
        for (Lang l : pool) {
            if (l.id().equals(id)) {
                return l;
            }
        }
        return pool.get(0);
    }

    /** id 列表（给 CycleButton 用） */
    public static List<String> ids(List<Lang> pool) {
        List<String> ids = new ArrayList<>(pool.size());
        for (Lang l : pool) {
            ids.add(l.id());
        }
        return ids;
    }

    /** 把 id 换成提示词里的语言名（自动解析 game） */
    public static String promptName(List<Lang> pool, String id) {
        return byId(pool, resolveTarget(id)).prompt();
    }
}
