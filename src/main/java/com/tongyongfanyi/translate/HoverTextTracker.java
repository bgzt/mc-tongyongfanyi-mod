package com.tongyongfanyi.translate;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 记住「屏幕上刚画过的每一行文字」以及它在屏幕上的矩形，供「鼠标指向重译」按键做命中测试。
 *
 * <p>为什么要记录矩形：Minecraft 没有统一的「屏幕文字命中测试」接口，但所有界面（原版、
 * 其他模组的机器/商店面板、HUD、tooltip）最后都要走 {@code GuiGraphicsExtractor.text(...)}，
 * 而这几个方法签名里本来就带 x/y。在绘制时把矩形和文字一起记下来，按键时拿鼠标坐标一比即可。
 *
 * <p>绘制层拿到的经常<b>已经是译文</b>了，但重译必须把<b>原文</b>送模型，所以这里同时维护
 * 一份「译文 → 原文」反查表，由 {@link TranslationManager#cached(String)} 在命中缓存时喂进来。
 * 没反查到但文字本身还带英文/日文等外语字母时，就说明它压根没被翻过，直接当原文用。
 *
 * <p>全是静态内存数据，不落盘；条目按时间自然淘汰，界面不重绘时 800ms 后就失效。
 */
public final class HoverTextTracker {
    private HoverTextTracker() {
    }

    /** 只认「刚刚画过」的文字：鼠标移开后还留着会误命中上个界面的内容 */
    private static final long TTL_MS = 800L;
    private static final int MAX_DRAWN = 800;
    private static final int MAX_LINKS = 4000;
    private static final Pattern SECTION = Pattern.compile("§[0-9A-Za-z]");
    private static final Pattern SPACES = Pattern.compile("[\\s\u00a0]+");
    private static final Pattern FOREIGN = Pattern.compile(
            "[A-Za-z]{2,}|[\u3040-\u30FF\u31F0-\u31FF\uAC00-\uD7AF\u0400-\u04FF"
                    + "\u0590-\u05FF\u0600-\u06FF\u0E00-\u0E7F]");
    private static final Pattern SEGMENT = Pattern.compile("\\[\\d+\\]");
    /** 含非 ASCII 字符（汉字/假名/图标等）：才可能是译文，才值得做包含匹配反查 */
    private static final Pattern NON_LATIN = Pattern.compile("[^\\x00-\\x7F]");

    /**
     * @param text  原文（重译要送模型的那句）
     * @param shown 屏幕上实际画出来的那串字（可能是译文）
     */
    private record Entry(float x0, float y0, float x1, float y1, String text, String shown, long ts) {
    }

    /** text@坐标 → 记录；同一行每帧重绘会覆盖，不会无限堆积 */
    private static final Map<String, Entry> DRAWN = new LinkedHashMap<>();
    /** 归一化译文 → 原文 */
    private static final Map<String, String> RENDERED_TO_ORIGINAL = new LinkedHashMap<>();

    // ------------------------------------------------------------------
    // 译文 → 原文
    // ------------------------------------------------------------------

    /** 缓存命中时调用：把「显示出来的译文」和「送模型的原文」关联起来 */
    public static synchronized void link(String original, String translated) {
        if (original == null || translated == null) {
            return;
        }
        String key = normalize(translated);
        if (key.length() < 2 || key.length() > 400 || key.equals(normalize(original))) {
            return;
        }
        RENDERED_TO_ORIGINAL.remove(key);       // LinkedHashMap：移到末尾当「最近使用」
        RENDERED_TO_ORIGINAL.put(key, original);
        if (RENDERED_TO_ORIGINAL.size() > MAX_LINKS) {
            Iterator<String> it = RENDERED_TO_ORIGINAL.keySet().iterator();
            for (int i = 0; i < MAX_LINKS / 2 && it.hasNext(); i++) {
                it.next();
                it.remove();
            }
        }
    }

    public static synchronized String originalOf(String rendered) {
        if (rendered == null) {
            return null;
        }
        String key = normalize(rendered);
        String hit = RENDERED_TO_ORIGINAL.get(key);
        if (hit != null) {
            return hit;
        }
        // 纯 ASCII = 还没被翻过的英文原文，没必要做 O(n) 包含匹配
        // （一屏几十行 × 每帧，避免拖慢渲染）。
        if (!NON_LATIN.matcher(key).find()) {
            return null;
        }
        // 绘制时前后可能还带了色码/装饰符，精确 key 对不上就做一次包含匹配。
        // 限制长度，避免 "God" 这种短词把不相干的译句也匹配上。
        if (key.length() >= 6) {
            // 只扫最近的 256 条：这张表按 LRU 排序，越靠后越久没用上，
            // 全表遍历在渲染线程上是实打实的开销
            int checked = 0;
            for (Map.Entry<String, String> e : RENDERED_TO_ORIGINAL.entrySet()) {
                if (++checked > 256) {
                    break;
                }
                String k = e.getKey();
                if (k.length() >= 6 && (key.contains(k) || k.contains(key))) {
                    return e.getValue();
                }
            }
        }
        return null;
    }

    /**
     * 归一化缓存。
     *
     * <p>{@code normalize} 是纯函数（同样的输入永远同样输出），但里面套了三次正则，
     * 而它每帧要对屏幕上每一条文字各跑一次（JFR 采样里这块占了渲染线程约 6% 的样本）。
     * 文本内容高度重复（计分板、HUD 每帧都一样），缓存后几乎全部命中。
     */
    private static final Map<String, String> NORMALIZE_CACHE = new java.util.concurrent.ConcurrentHashMap<>();

    private static String normalize(String s) {
        if (s == null) {
            return "";
        }
        String hit = NORMALIZE_CACHE.get(s);
        if (hit != null) {
            return hit;
        }
        String out = SPACES.matcher(SEGMENT.matcher(SECTION.matcher(s).replaceAll(" "))
                .replaceAll(" ")).replaceAll(" ").trim();
        if (NORMALIZE_CACHE.size() > 2048) {
            NORMALIZE_CACHE.clear();   // 长局里文本无限增长，超限直接清空重来
        }
        NORMALIZE_CACHE.put(s, out);
        return out;
    }

    /** 给「绘制文本 → 真实原句」用的归一化：去色码 / [N] 标记 / 连续空白 */
    public static String normalizeForLookup(String s) {
        return s == null ? "" : normalize(s);
    }

    // ------------------------------------------------------------------
    // 绘制记录
    // ------------------------------------------------------------------

    /** 记下这行文字的屏幕矩形。x/y 是 GUI 逻辑坐标，和鼠标的 scaled 坐标同一套。 */
    public static void record(GuiGraphicsExtractor graphics, Font font, String text,
                              int x, int y, boolean centered) {
        if (graphics == null || font == null || text == null || text.isBlank()) {
            return;
        }
        String clean = SECTION.matcher(text).replaceAll("").trim();
        if (clean.length() < 2 || clean.length() > 400) {
            return;
        }
        recordImpl(graphics, clean, x, y, font.width(clean), Math.max(6f, font.lineHeight), centered);
    }

    private static void recordImpl(GuiGraphicsExtractor graphics, String clean,
                                   int x, int y, float width, float height, boolean centered) {
        // F3 调试屏的坐标/FPS 之类不该参与重译；模组自己的设置页也不能被重译
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc != null && mc.getDebugOverlay() != null && mc.getDebugOverlay().showDebugScreen()) {
                return;
            }
            if (mc != null && mc.gui != null && mc.gui.screen() != null
                    && mc.gui.screen().getClass().getName().startsWith("com.tongyongfanyi.")) {
                return;
            }
        } catch (Throwable ignored) {
            // 老版本没有 getDebugOverlay，忽略
        }
        float left = centered ? x - width / 2f : x;

        // 界面/容器可能整体平移或缩放过（pose 栈），矩形要跟着变换，否则命中会偏。
        float minX = left, minY = y, maxX = left + width, maxY = y + height;
        try {
            var p = graphics.pose();
            float m00 = p.m00, m01 = p.m01, m10 = p.m10, m11 = p.m11, m20 = p.m20, m21 = p.m21;
            float[] xs = {left, left + width, left, left + width};
            float[] ys = {y, y, y + height, y + height};
            minX = Float.POSITIVE_INFINITY;
            minY = Float.POSITIVE_INFINITY;
            maxX = Float.NEGATIVE_INFINITY;
            maxY = Float.NEGATIVE_INFINITY;
            for (int i = 0; i < 4; i++) {
                float tx = m00 * xs[i] + m10 * ys[i] + m20;
                float ty = m01 * xs[i] + m11 * ys[i] + m21;
                minX = Math.min(minX, tx);
                minY = Math.min(minY, ty);
                maxX = Math.max(maxX, tx);
                maxY = Math.max(maxY, ty);
            }
        } catch (Throwable ignored) {
            // 极端情况拿不到 pose，用未变换的矩形兜底
        }

        // 同一个方法可能同时被 5 参数版和 6 参数版注入，且不同注入点拿到的可能是
        // 原文、也可能是已被替换的译文。统一存「原文」，两条记录才会归到同一个 key。
        String canonical = originalOf(clean);
        if (canonical == null) {
            canonical = clean;
        }
        long now = System.currentTimeMillis();
        String key = canonical + "@" + Math.round(minX) + ":" + Math.round(minY);
        synchronized (DRAWN) {
            DRAWN.remove(key);
            DRAWN.put(key, new Entry(minX, minY, maxX, maxY, canonical, clean, now));
            if (DRAWN.size() > 128) {
                DRAWN.entrySet().removeIf(e -> now - e.getValue().ts() > TTL_MS);
            }
            while (DRAWN.size() > MAX_DRAWN) {
                Iterator<Map.Entry<String, Entry>> it = DRAWN.entrySet().iterator();
                it.next();
                it.remove();
            }
        }
    }

    /**
     * 26.3 里所有 text/centeredText/textWithBackdrop 重载最后都汇到
     * {@code text(Font, FormattedCharSequence, x, y, color, shadow)}，只拦这一个就能覆盖
     * 原版界面、tooltip、书页、计分板、HUD 和其他模组自己画的文字。
     */
    public static void recordSequence(GuiGraphicsExtractor graphics, Font font,
                                      FormattedCharSequence seq, int x, int y) {
        if (graphics == null || font == null || seq == null) {
            return;
        }
        // 模组自己画的状态行（翻译: HY-MT2… 正在翻译）不该进命中表，
        // 否则鼠标扫过左上角会把它当成待重译的句子
        if (TranslationHelper.isSelfDraw()) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        try {
            seq.accept((index, style, codePoint) -> {
                sb.appendCodePoint(codePoint);
                return true;
            });
        } catch (Throwable ignored) {
            return;
        }
        String clean = SECTION.matcher(sb.toString()).replaceAll("").trim();
        if (clean.length() < 2 || clean.length() > 400) {
            return;
        }
        float width;
        try {
            width = font.width(seq);
        } catch (Throwable t) {
            width = font.width(clean);
        }
        // x 已经是居中 subtract 之后的绘制起点，这里不再做居中换算
        recordImpl(graphics, clean, x, y, width, Math.max(6f, font.lineHeight), false);
    }

    // ------------------------------------------------------------------
    // 命中测试
    // ------------------------------------------------------------------

    /** 这一行里是否还有「本该翻掉」的英文实词 */
    private static final Pattern EN_TOKEN = Pattern.compile("[A-Za-z]{3,}");
    private static final Pattern URL_TOKEN = Pattern.compile(
            "(?i)https?://\\S+|www\\.\\S+|/[A-Za-z][A-Za-z0-9_]*|[\\w-]+(?:\\.[\\w-]+)+");
    private static final Pattern ROMAN_TOKEN = Pattern.compile("[IVXLCDM]{1,5}");
    private static final java.util.Set<String> KEEP_TOKEN = java.util.Set.of(
            "hypixel", "mojang", "minecraft", "pvp", "pve", "npc", "youtube", "twitch",
            "discord", "google", "java", "bedrock", "fabric", "forge", "sodium", "iris",
            "optifine", "modrinth", "curseforge", "api", "http", "https", "www", "com");

    /** 与 TranslationManager 的残留词判定一致：品牌名、缩写、罗马数字、玩家 ID 不算漏翻 */
    private static boolean hasResidualEnglish(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String body = URL_TOKEN.matcher(SECTION.matcher(text).replaceAll(" ")).replaceAll(" ");
        java.util.regex.Matcher m = EN_TOKEN.matcher(body);
        while (m.find()) {
            String w = m.group();
            String low = w.toLowerCase(java.util.Locale.ROOT);
            // 只把「4 个字母以内的全大写」当缩写放过（XP/HP/DPS/VIP/ATK）。
            // 以前所有全大写词都放过，于是 tooltip 里的「普通 PORTAL」被判成
            // 「已经全是中文」，按重译键毫无反应。
            if (KEEP_TOKEN.contains(low) || isUppercasedAbbrev(w)
                    || ROMAN_TOKEN.matcher(w).matches()) {
                continue;
            }
            // 驼峰/下划线才是玩家 ID；全大写词（PORTAL）属于漏翻，不能放过
            boolean idLike = w.indexOf('_') >= 0;
            if (!idLike) {
                boolean hasLower = false;
                boolean upperAfterFirst = false;
                for (int i = 0; i < w.length(); i++) {
                    char c = w.charAt(i);
                    if (Character.isLowerCase(c)) {
                        hasLower = true;
                    } else if (i > 0 && Character.isUpperCase(c)) {
                        upperAfterFirst = true;
                    }
                }
                idLike = hasLower && upperAfterFirst;
            }
            if (!idLike) {
                return true;
            }
        }
        return false;
    }

    /** 全大写且 4 个字母以内 = 缩写（VIP/DPS/NPC/XP）；再长的全大写词（PORTAL/NETHER）是漏翻 */
    private static boolean isUppercasedAbbrev(String w) {
        return w.length() <= 4 && w.equals(w.toUpperCase(java.util.Locale.ROOT));
    }

    /**
     * 返回鼠标所指那<b>一行</b>的原文，拿不到就返回 null。
     *
     * <p>两种情况都要覆盖：
     * <ol>
     *   <li>鼠标直接压在文字上（界面标签、书页、计分板行）：取那一行，把同一行被拆成
     *       多次 drawString 的片段按 x 拼回一句。</li>
     *   <li>鼠标放在物品上、tooltip 画在鼠标右下方（鼠标本身并不在文字上）：
     *       在鼠标右下方的邻近区域里找离鼠标最近的一行。</li>
     * </ol>
     */
    /** V 键：兜底时优先挑还带英文的行 */
    public static String pick(double mouseX, double mouseY) {
        return pick(mouseX, mouseY, true);
    }

    /**
     * @param preferEnglish 鼠标没压在文字上时，是否优先挑「还带英文」的行。
     *                      V 键只修漏翻，传 true；强制重译键传 false，尊重鼠标指向的那一行。
     */
    public static String pick(double mouseX, double mouseY, boolean preferEnglish) {
        List<Entry> live;
        long now = System.currentTimeMillis();
        synchronized (DRAWN) {
            live = DRAWN.values().stream().filter(e -> now - e.ts() <= TTL_MS).toList();
        }
        if (live.isEmpty()) {
            return null;
        }

        // 1) 严格命中：鼠标直接压在某段文字上
        List<Entry> hit = live.stream().filter(e ->
                mouseX >= e.x0() - 3 && mouseX <= e.x1() + 3
                        && mouseY >= e.y0() - 3 && mouseY <= e.y1() + 3).toList();
        // 2) 没压上（tooltip 画在鼠标右下方）：取鼠标附近、离它最近的一行
        List<Entry> candidates = hit.isEmpty() ? live.stream().filter(e ->
                e.x1() >= mouseX - 26 && e.x0() <= mouseX + 340
                        && e.y1() >= mouseY - 30 && e.y0() <= mouseY + 180).toList() : hit;
        if (candidates.isEmpty()) {
            return null;
        }
        // 鼠标放在物品槽上时，tooltip 里往往只有一两行还带英文（Logger XIII 之类）。
        // 只按「离鼠标最近」选行几乎总会选到已经翻成中文的标题/进度行，于是按键
        // 反馈「这一条已经全是中文」，可屏幕上明明还有英文。兜底时优先挑带英文的行。
        // 严格命中（鼠标真的压在文字上）时尊重玩家指向，不抢占。
        // 注意判的是 shown（屏幕上真正显示的字）—— text 是反查回去的原文，
        // 本来就整句英文，拿它判等于「每行都带英文」，筛选形同失效。
        if (preferEnglish && hit.isEmpty()) {
            List<Entry> withEnglish = candidates.stream()
                    .filter(e -> hasResidualEnglish(e.shown())).toList();
            if (!withEnglish.isEmpty()) {
                candidates = withEnglish;
            }
        }
        float rowY = candidates.stream().min(Comparator.comparingDouble(
                e -> Math.abs((e.y0() + e.y1()) / 2f - mouseY))).get().y0();
        float lineH = Math.max(6f, candidates.get(0).y1() - candidates.get(0).y0());
        // 只在候选区域内取同一行，别把屏幕另一头同一 y 的文字也拉进来
        List<Entry> sameRow = candidates.stream()
                .filter(e -> Math.abs(e.y0() - rowY) < lineH * 1.3f).toList();

        // 关键：26.3 的 GuiGraphicsTextMixin 是在 text(Font, Component/String, ...) 这一层
        // 把整段文本翻译好，之后才转成 FormattedCharSequence 绘制。也就是说**每一次
        // text() 调用就对应一条缓存查询**，绘制出来的字符串本身就是一个合法的缓存键/译文。
        // 因此只取单个条目；把同行多段拼起来反而对不上缓存，重译结果就显示不出来了。
        // 同一行有多段时挑最长的那条（通常是正文，而不是图标/编号）。
        Entry chosen = sameRow.stream()
                .max(Comparator.comparingInt((Entry e) -> e.text().length())
                        .thenComparingDouble(e -> -Math.abs((e.x0() + e.x1()) / 2f - mouseX)))
                .orElse(null);
        if (chosen == null) {
            return null;
        }
        String shown = chosen.text().trim();
        if (shown.length() < 2) {
            return null;
        }
        return originalForShown(shown);
    }

    /** 显示文本 → 原文：先查反查表；没翻过（本身是外文）就当原文；纯中文又查不到则放弃 */
    private static String originalForShown(String shown) {
        String original = originalOf(shown);
        if (original != null && !original.isBlank()) {
            return original;
        }
        return FOREIGN.matcher(shown).find() ? shown : null;
    }

    /** 强制重译一次最多处理几行：够覆盖一整块 tooltip，又不至于把整屏塞进队列 */
    private static final int FORCE_MAX_LINES = 8;

    /**
     * 「强制重译」用：鼠标压在文字上时只取那一行；鼠标放在物品槽上（tooltip 画在右下、
     * 鼠标本身并不在文字上）时，把 tooltip 里所有可见行都收回来，一次重译整件物品。
     *
     * <p>每行都先反查回原文，所以屏幕上已经是中文也照样能重译；纯中文且反查不到
     * 的行跳过（拿中文当原文送模型只会越翻越怪）。
     */
    public static List<String> pickGroup(double mouseX, double mouseY) {
        List<Entry> live;
        long now = System.currentTimeMillis();
        synchronized (DRAWN) {
            live = DRAWN.values().stream().filter(e -> now - e.ts() <= TTL_MS).toList();
        }
        if (live.isEmpty()) {
            return List.of();
        }
        List<Entry> hit = live.stream().filter(e ->
                mouseX >= e.x0() - 3 && mouseX <= e.x1() + 3
                        && mouseY >= e.y0() - 3 && mouseY <= e.y1() + 3).toList();
        if (!hit.isEmpty()) {
            // 尊重玩家指向：只重译这一行
            String one = pick(mouseX, mouseY, false);
            return one == null ? List.of() : List.of(one);
        }
        List<Entry> candidates = live.stream().filter(e ->
                e.x1() >= mouseX - 26 && e.x0() <= mouseX + 340
                        && e.y1() >= mouseY - 30 && e.y0() <= mouseY + 180).toList();
        if (candidates.isEmpty()) {
            return List.of();
        }
        List<Entry> sorted = new ArrayList<>(candidates);
        sorted.sort(Comparator.comparingDouble(Entry::y0));
        float lineH = Math.max(6f, sorted.get(0).y1() - sorted.get(0).y0());
        List<String> out = new ArrayList<>();
        List<Entry> row = new ArrayList<>();
        float rowY = Float.NEGATIVE_INFINITY;
        for (Entry e : sorted) {
            if (row.isEmpty() || Math.abs(e.y0() - rowY) < lineH * 1.3f) {
                if (row.isEmpty()) {
                    rowY = e.y0();
                }
                row.add(e);
            } else {
                addRowOriginal(row, out);
                row = new ArrayList<>();
                row.add(e);
                rowY = e.y0();
            }
            if (out.size() >= FORCE_MAX_LINES) {
                break;
            }
        }
        if (!row.isEmpty()) {
            addRowOriginal(row, out);
        }
        return out;
    }

    /**
     * 「重译残留英文」键（V）用：把鼠标这块区域内<b>屏幕上还夹着英文</b>的行全部收回来。
     *
     * <p>为什么不是「只挑一行」：tooltip 有七八行，玩家不可能精确点到唯一那条漏翻的行，
     * 而只要挑错一行就会反馈「这一条已经全是中文」，可眼睛明明看到英文。这里改成按块扫，
     * 一次把这一块里所有还带英文的句子都交给模型重译。
     *
     * <p>鼠标精确压在某一<b>带英文</b>的行上时仍只修那一行（尊重指向）；压到的是纯中文行
     * 则不抢占，继续把整块扫一遍。
     */
    public static List<String> pickResidualGroup(double mouseX, double mouseY) {
        List<Entry> live;
        long now = System.currentTimeMillis();
        synchronized (DRAWN) {
            live = DRAWN.values().stream().filter(e -> now - e.ts() <= TTL_MS).toList();
        }
        if (live.isEmpty()) {
            return List.of();
        }
        List<Entry> hit = live.stream().filter(e ->
                mouseX >= e.x0() - 3 && mouseX <= e.x1() + 3
                        && mouseY >= e.y0() - 3 && mouseY <= e.y1() + 3).toList();
        if (!hit.isEmpty()) {
            Entry pointed = hit.stream()
                    .max(Comparator.comparingInt((Entry e) -> e.shown().length())
                            .thenComparingDouble(e -> -Math.abs((e.x0() + e.x1()) / 2f - mouseX)))
                    .orElse(null);
            if (pointed != null && hasResidualEnglish(pointed.shown())) {
                String one = originalForShown(pointed.text().trim());
                if (one != null) {
                    return List.of(one);
                }
            }
        }
        List<Entry> candidates = live.stream().filter(e ->
                e.x1() >= mouseX - 26 && e.x0() <= mouseX + 340
                        && e.y1() >= mouseY - 30 && e.y0() <= mouseY + 180).toList();
        if (candidates.isEmpty()) {
            return List.of();
        }
        List<Entry> sorted = new ArrayList<>(candidates);
        sorted.sort(Comparator.comparingDouble(Entry::y0));
        float lineH = Math.max(6f, sorted.get(0).y1() - sorted.get(0).y0());
        List<String> out = new ArrayList<>();
        List<Entry> row = new ArrayList<>();
        float rowY = Float.NEGATIVE_INFINITY;
        for (Entry e : sorted) {
            if (row.isEmpty() || Math.abs(e.y0() - rowY) < lineH * 1.3f) {
                if (row.isEmpty()) {
                    rowY = e.y0();
                }
                row.add(e);
            } else {
                addRowIfResidual(row, out);
                row = new ArrayList<>();
                row.add(e);
                rowY = e.y0();
            }
            if (out.size() >= FORCE_MAX_LINES) {
                break;
            }
        }
        if (!row.isEmpty()) {
            addRowIfResidual(row, out);
        }
        return out;
    }

    /** 这一行只要有一段还带英文，就把该行最长的原文收进结果（重译以整句为单位） */
    private static void addRowIfResidual(List<Entry> row, List<String> out) {
        if (out.size() >= FORCE_MAX_LINES) {
            return;
        }
        boolean residual = row.stream().anyMatch(e -> hasResidualEnglish(e.shown()));
        if (!residual) {
            return;
        }
        addRowOriginal(row, out);
    }

    /** 同一行里取最长的那条（通常是正文而不是图标/编号），反查原文后去重收进结果 */
    private static void addRowOriginal(List<Entry> row, List<String> out) {        if (out.size() >= FORCE_MAX_LINES) {
            return;
        }
        Entry best = row.stream().max(Comparator.comparingInt(e -> e.text().length())).orElse(null);
        if (best == null) {
            return;
        }
        String shown = best.text().trim();
        if (shown.length() < 2) {
            return;
        }
        String original = originalForShown(shown);
        if (original != null && !out.contains(original)) {
            out.add(original);
        }
    }
}
