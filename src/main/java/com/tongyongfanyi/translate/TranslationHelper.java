package com.tongyongfanyi.translate;

import com.tongyongfanyi.TongyongfanyiClient;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 通用显示文本翻译：供物品提示、boss 栏、名牌、计分板、悬浮文字等
 * "屏幕上的显示文本"共用。只改客户端显示，不发包、不修改发往服务器的内容。
 */
public final class TranslationHelper {
    private static final Pattern LATIN = Pattern.compile("[A-Za-z]");
    private static final Pattern CJK = Pattern.compile("[\\u4e00-\\u9fa5]");
    /** 只认合法格式码：旧版 \u00a7. 会把 § 后的普通字母一起吞掉，把单词削掉一个字符 */
    /**
     * § 格式码。注意不能用「标准码白名单」：Hypixel 会在行里塞 {@code §u} 这类
     * 非标准码当隐形填充（用来让队伍名唯一），只认 0-9a-f/k-o/r 的话它会被当成正文留下，
     * 于是 {@code Dwarven M§u§2ines} 既拼不成 Dwarven Mines，也过不了「剥图标查术语表」。
     * 所以这里放宽成 {@code § + 任意字母数字}。
     */
    private static final Pattern SECTION = Pattern.compile("\\u00a7[0-9A-Za-z]");
    /** 网址/IP 不翻译，避免界面上的链接被翻译破坏 */
    private static final Pattern URL = Pattern.compile(
            "(?i)(https?://|www\\.|\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b|\\b(?:[\\w-]+\\.)+(?:net|com|org|io|gg|tv|me|co|dev|cn)\\b)");

    private TranslationHelper() {
    }


    /**
     * 「正在构建鼠标悬停物品的 tooltip」标记，由 {@code ItemStackMixin} 打开/关闭。
     *
     * <p>为什么要用它来提优先级：{@code getTooltipLines} 只在**真的要显示提示框**时被调用，
     * 所以这个窗口里提交的翻译，几乎都是玩家此刻正盯着的那几行。计分板、背包格子里的
     * 文字虽然也在排队，但玩家没在看它们 —— 让悬停物品插到队头，观感差别很明显。
     *
     * <p>用线程内的深度计数而不是布尔：{@code getTooltipLines} 在个别模组里会嵌套调用，
     * 布尔会被内层提前清掉。另加 300ms 超时兜底，万一某条路径抛异常没走到 RETURN，
     * 也不会让后面所有文本都永久享受最高优先级。
     */
    private static final ThreadLocal<long[]> HOVER_WINDOW =
            ThreadLocal.withInitial(() -> new long[2]);

    public static void beginHoverTooltip() {
        long[] w = HOVER_WINDOW.get();
        w[0]++;
        w[1] = System.nanoTime();
    }

    public static void endHoverTooltip() {
        long[] w = HOVER_WINDOW.get();
        if (w[0] > 0) {
            w[0]--;
        }
    }

    /** 当前是否处于「鼠标悬停物品的 tooltip」翻译窗口内 */
    public static boolean hoverBoost() {
        // 只有渲染线程上的 tooltip 构建才算「玩家真的在指这件物品」。
        // SkyHanni / skyblocker 会在自己的线程里批量取几千件物品的 tooltip 读数据，
        // 那些不该抢队头（它们甚至根本不显示），否则真悬停反而排在它们后面。
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc != null && Thread.currentThread() != mc.getRunningThread()) {
            return false;
        }
        long[] w = HOVER_WINDOW.get();
        if (w[0] <= 0) {
            return false;
        }
        if (System.nanoTime() - w[1] > 300_000_000L) {
            w[0] = 0;   // 超时兜底，见上面的说明
            return false;
        }
        return true;
    }

    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger("tongyongfanyi");

    public static TranslationManager manager() {
        try {
            TongyongfanyiClient mod = TongyongfanyiClient.getInstance();
            return mod != null ? mod.getTranslationManager() : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 当前是否显示原文：书页 / 告示牌有自维护的折行缓存，要把它算进指纹才能按键即切 */
    public static boolean showingOriginal() {
        TranslationManager manager = manager();
        return manager != null && manager.isShowingOriginal();
    }

    /** 翻译一段显示文本：缓存命中替换并保留格式，未命中触发后台翻译并返回原文 */
    public static Component translate(Component component) {
        return translate(component, TranslationManager.CAT_HUD);
    }

    /** 物品悬停提示（名称 + lore），优先级取设置里的「物品提示」档 */
    public static Component translateTooltip(Component component) {
        return translate(component, TranslationManager.CAT_TOOLTIP);
    }

    /**
     * tooltip 整块翻译，在 {@code setTooltipForNextFrame(List<Component>)} 入口调用。
     *
     * <p>为什么这里还要拦一次：skyblocker / SkyHanni 是在
     * {@code ItemStack.getTooltipLines} <b>返回之后</b>才往列表里追加价格行的
     * （NPC 出售价、集市买卖价）。那几行到渲染层时已经是被拆开的多色序列，
     * 各种「不像句子就早退」的判断很容易把它们挡掉 —— 实测它们从来没进过
     * 翻译缓存。在 tooltip 入口按行处理，能保证每行都过一遍本地模板。
     */
    public static java.util.List<Component> translateTooltipLines(java.util.List<Component> lines) {
        if (lines == null || lines.isEmpty()) {
            return lines;
        }
        TranslationManager manager = manager();
        if (manager == null || !manager.config().enabled
                || !manager.config().translateItemTooltips || manager.isShowingOriginal()
                || inOwnUi()) {
            return lines;
        }
        boolean changed = false;
        int replaced = 0;
        java.util.List<Component> out = new ArrayList<>(lines.size());
        for (Component line : lines) {
            Component t = translateTooltipLine(line);
            if (t != line) {
                changed = true;
                replaced++;
            }
            out.add(t);
        }
        // 诊断：确认入口被调用、以及自定义网格组件有没有被展开。
        // 每 5 秒最多一条，且不逐帧拼接字符串，正常游玩几乎看不到。
        long now = System.currentTimeMillis();
        if (now - tooltipLogAt > 5000L) {
            tooltipLogAt = now;
            StringBuilder detail = new StringBuilder(160);
            int n = Math.min(lines.size(), 10);
            for (int i = 0; i < n; i++) {
                Component c = lines.get(i);
                String s = c == null ? "" : SECTION.matcher(c.getString()).replaceAll("").trim();
                if (s.isEmpty()) {
                    Component ex = expandCustomContents(c);
                    s = ex == null ? "<空>"
                            : "[展开]" + SECTION.matcher(ex.getString()).replaceAll("").trim();
                }
                if (s.length() > 48) {
                    s = s.substring(0, 48);
                }
                detail.append(i).append('=').append(s).append(" | ");
            }
            LOG.info("tooltip 入口：{} 行，替换 {} 行，内容 {}", lines.size(), replaced, detail);
        }
        return changed ? out : lines;
    }

    private static long tooltipLogAt;

    /**
     * 展开「自定义 ComponentContents」里的隐藏文字。
     *
     * <p>skyblocker 6.11 的价格行不是普通的 {@code Component.literal}，而是
     * {@code GridComponent.of(标签, 金额)}：它把两个子 Component 塞进自己实现的
     * {@code ComponentContents}（{@code GridComponent$Contents}），用 {@code Font.width}
     * 和 {@code GridTooltipComponent} 自己按两列对齐绘制。这个 Contents <b>没有实现
     * {@code visit()}</b>，于是 {@code Component.getString()} 返回空字符串 ——
     * 这就是「NPC Sell Price / Bazaar Buy Price / Bazaar Sell Price」三行反复修不好的真正原因：
     * <ol>
     *   <li>tooltip 入口里 {@code plain.isEmpty()} 直接放行，本地价格模板根本没跑；</li>
     *   <li>渲染时由 skyblocker 自己画，也不经过 {@code GuiGraphics.text()} 的任何注入点。</li>
     * </ol>
     * 日志里的「tooltip 入口：7 行，本地命中 0 行」正是这个现象。
     *
     * <p>做法：反射读 {@code Component.getContents()}，再找它的无参 {@code components()}
     * （GridComponent$Contents 是个 record，自动生成这个访问器），把子 Component 的
     * {@code getString()} 用空格拼回普通文本。展开后的行换成普通的
     * {@code Component.literal}，渲染就回到原版 tooltip 路径，本地模板和模型都能接手。
     *
     * <p>全程反射、不 import skyblocker 的类：没装这个模组、或它改了内部实现时，
     * 静默返回 null，原行原样不动。
     */
    /** 按 contents 类型缓存 {@code components()} 访问器：tooltip 每帧都来，不能每次 getMethod */
    private static final java.util.Map<Class<?>, Optional<java.lang.reflect.Method>> PARTS_ACCESSOR =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static Component expandCustomContents(Component line) {
        if (line == null) {
            return null;
        }
        try {
            Object contents = line.getContents();
            if (contents == null) {
                return null;
            }
            java.lang.reflect.Method parts = PARTS_ACCESSOR.computeIfAbsent(contents.getClass(), k -> {
                try {
                    java.lang.reflect.Method m = k.getMethod("components");
                    return List.class.isAssignableFrom(m.getReturnType())
                            ? Optional.of(m) : Optional.empty();
                } catch (Throwable e) {
                    return Optional.empty();
                }
            }).orElse(null);
            if (parts == null) {
                return null;
            }
            Object value = parts.invoke(contents);
            if (!(value instanceof List<?> list) || list.isEmpty()) {
                return null;
            }
            StringBuilder sb = new StringBuilder(64);
            Style style = line.getStyle();
            boolean first = true;
            for (Object part : list) {
                if (!(part instanceof Component c)) {
                    continue;
                }
                String s = SECTION.matcher(c.getString()).replaceAll("").trim();
                if (s.isEmpty()) {
                    continue;
                }
                if (first) {
                    style = c.getStyle();   // 沿用标签那一段的颜色
                    first = false;
                }
                if (sb.length() > 0) {
                    sb.append(' ');         // 两列之间补一个空格，价格模板才认得出来
                }
                sb.append(s);
            }
            return sb.isEmpty() ? null : Component.literal(sb.toString()).withStyle(style);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 单行 tooltip：先本地模板（价格 / 术语 / 大写标签），都不命中才交给模型 */
    public static Component translateTooltipLine(Component line) {
        if (line == null) {
            return null;
        }
        String plain = SECTION.matcher(line.getString()).replaceAll("").trim();
        if (plain.isEmpty()) {
            // 自定义网格组件：getString() 是空的，但文字其实藏在 contents 里。
            // 展开成普通 Component 后继续走下面的本地模板；展开不了才放行。
            Component expanded = expandCustomContents(line);
            if (expanded == null) {
                return line;
            }
            line = expanded;
            plain = SECTION.matcher(line.getString()).replaceAll("").trim();
            if (plain.isEmpty()) {
                return line;
            }
        }
        String local = Glossary.priceRow(plain);
        if (local == null) {
            local = Glossary.priceLine(plain);
        }
        if (local == null) {
            local = Glossary.composedLabel(plain);
        }
        if (local == null) {
            local = lookupTerm(plain);
        }
        if (local != null) {
            return Component.literal(local).withStyle(line.getStyle());
        }
        return translateTooltip(line);
    }

    /**
     * REI / EMI 这类自己画 tooltip 的模组走的是
     * {@code GuiGraphicsExtractor.tooltip(Font, List<ClientTooltipComponent>, ...)}，
     * 完全不经过 {@code setTooltipForNextFrame}，所以 {@link #translateTooltipLines}
     * 一次都不会被调用 —— 背包里已经修好的价格行，在右侧物品列表里仍是英文。
     *
     * <p>skyblocker 在这种列表里塞的不是普通文字，而是
     * {@code GridTooltipComponent}：它自己按两列排版绘制，文字藏在
     * {@code sequence().gridContents().components()} 里，{@code getString()} 是空的，
     * 底层 {@code text(...)} 注入也拦不到。这里把组件里的文字翻出来，命中本地
     * 价格模板就换成普通文本组件，渲染回到原版路径。
     *
     * <p>全程反射 + 按类型缓存访问器，不依赖任何第三方类；没装这些模组、
     * 或它们改了内部结构时静默放行，不影响原来的渲染。
     */
    public static List<ClientTooltipComponent> translateTooltipComponents(
            List<ClientTooltipComponent> parts) {
        if (parts == null || parts.isEmpty()) {
            return parts;
        }
        TranslationManager manager = manager();
        if (manager == null || !manager.config().enabled
                || !manager.config().translateItemTooltips || manager.isShowingOriginal()
                || inOwnUi()) {
            return parts;
        }
        List<ClientTooltipComponent> out = null;
        for (int i = 0; i < parts.size(); i++) {
            ClientTooltipComponent part = parts.get(i);
            if (part == null) {
                continue;
            }
            String plain = tooltipComponentText(part);
            if (plain == null || plain.isBlank()) {
                continue;
            }
            // lookupTerm 内部已按 价格行 → 纯金额 → 大写标签 → 整句术语 依次查
            String local = lookupTerm(plain);
            if (local == null || local.equals(plain)) {
                continue;
            }
            if (out == null) {
                out = new ArrayList<>(parts);
            }
            out.set(i, ClientTooltipComponent.create(
                    Component.literal(local).withStyle(tooltipComponentStyle(part))
                            .getVisualOrderText()));
        }
        return out == null ? parts : out;
    }

    /** 按组件类型缓存「从组件走到 List&lt;Component&gt;」的方法链，别每帧 getMethods */
    private static final java.util.Map<Class<?>, Optional<List<java.lang.reflect.Method>>> COMPONENT_PATHS =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static List<java.lang.reflect.Method> buildComponentPath(Class<?> type, int depth) {
        if (depth <= 0) {
            return null;
        }
        for (java.lang.reflect.Method m : type.getMethods()) {
            if (m.getParameterCount() != 0 || m.getDeclaringClass() == Object.class) {
                continue;
            }
            Class<?> rt = m.getReturnType();
            if (List.class.isAssignableFrom(rt)) {
                return List.of(m);
            }
            if (depth > 1 && !rt.isPrimitive() && !rt.isArray() && rt != String.class
                    && !rt.getName().startsWith("java.")) {
                List<java.lang.reflect.Method> sub = buildComponentPath(rt, depth - 1);
                if (sub != null) {
                    List<java.lang.reflect.Method> path = new ArrayList<>();
                    path.add(m);
                    path.addAll(sub);
                    return path;
                }
            }
        }
        return null;
    }

    private static List<?> componentParts(Object part) {
        List<java.lang.reflect.Method> path = COMPONENT_PATHS.computeIfAbsent(part.getClass(),
                k -> Optional.ofNullable(buildComponentPath(k, 4))).orElse(null);
        if (path == null) {
            return null;
        }
        Object cur = part;
        try {
            for (java.lang.reflect.Method m : path) {
                cur = m.invoke(cur);
                if (cur == null) {
                    return null;
                }
            }
            return cur instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof Component
                    ? list : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 组件里实际显示的文字：普通文本组件读 FormattedCharSequence，网格组件展开子 Component */
    private static String tooltipComponentText(ClientTooltipComponent part) {
        for (java.lang.reflect.Field f : part.getClass().getDeclaredFields()) {
            if (!FormattedCharSequence.class.isAssignableFrom(f.getType())) {
                continue;
            }
            try {
                f.setAccessible(true);
                if (f.get(part) instanceof FormattedCharSequence seq) {
                    StringBuilder sb = new StringBuilder(64);
                    seq.accept((idx, style, cp) -> {
                        sb.appendCodePoint(cp);
                        return true;
                    });
                    String s = SECTION.matcher(sb.toString()).replaceAll("").trim();
                    if (!s.isEmpty()) {
                        return s;
                    }
                }
            } catch (Throwable ignored) {
                // 反射失败就继续走下面的网格组件分支
            }
        }
        List<?> list = componentParts(part);
        if (list == null) {
            return null;
        }
        StringBuilder b = new StringBuilder(64);
        for (Object o : list) {
            if (!(o instanceof Component c)) {
                continue;
            }
            String s = SECTION.matcher(c.getString()).replaceAll("").trim();
            if (s.isEmpty()) {
                continue;
            }
            if (b.length() > 0) {
                b.append(' ');      // 两列之间补空格，价格模板才认得出来
            }
            b.append(s);
        }
        return b.length() == 0 ? null : b.toString();
    }

    /** 沿用标签那一列的颜色，别把整行刷成白色 */
    private static Style tooltipComponentStyle(ClientTooltipComponent part) {
        List<?> list = componentParts(part);
        if (list != null) {
            for (Object o : list) {
                if (o instanceof Component c && !c.getString().isEmpty()) {
                    return c.getStyle();
                }
            }
        }
        return Style.EMPTY;
    }

    /** 右侧计分板，优先级取「计分板」档 */
    public static Component translateScoreboard(Component component) {
        return translate(component, TranslationManager.CAT_SCOREBOARD);
    }

    /** 标题 / 动作栏 / Boss 血条 / 头顶名，优先级取「HUD 与标题」档 */
    public static Component translateHud(Component component) {
        return translate(component, TranslationManager.CAT_HUD);
    }

    /**
     * 世界里的告示牌文字（含悬挂告示牌，正反面）。
     *
     * <p>告示牌每帧都会重新取一次文字，所以这里走「限速 + 只查缓存」的路子：
     * 有译文立刻换，没有就交给后台慢慢翻，绝不因为站在牌子前面就刷请求。
     * 目标语言就是中文时，牌子上那点英文（Welcome / Shop）本身也很短，
     * 会被 {@link #shouldTranslate} 之外的短词规则挡掉一部分，剩下的由术语表兜。
     */
    public static Component translateSign(Component component) {
        if (component == null) {
            return null;
        }
        TranslationManager manager = manager();
        if (manager == null || !manager.config().enabled || !manager.config().translateSignText
                || manager.isShowingOriginal()) {
            return component;
        }
        return translateLine(manager, component, true, false, TranslationManager.CAT_HUD);
    }

    /**
     * 成书阅读界面的一整页文字。
     *
     * <p>整页交给 {@link #translateLine} 走「整行合并」：书页一般整段同色，
     * 会拼成一句送模型（一份请求翻一整页），语序比逐段翻译好得多；
     * 多色页才退回逐段。页面没译文时原样返回，调用方按实例相等判断「要不要重建折行」。
     */
    public static Component translateBookPage(Component page) {
        if (page == null) {
            return null;
        }
        TranslationManager manager = manager();
        if (manager == null || !manager.config().enabled || !manager.config().translateBookText
                || manager.isShowingOriginal()) {
            return page;
        }
        return translateLine(manager, page, true, false, TranslationManager.CAT_SCREEN);
    }

    private static Component translate(Component component, int category) {
        if (component == null) {
            return null;
        }
        TranslationManager manager = manager();
        if (manager == null || !manager.config().enabled || manager.isShowingOriginal()) {
            return component;
        }
        // 单段短文本先查固定译名：InventoryProfilesNext 在物品栏底部画的红色 NONE，
        // 会被 shouldTranslate() / noTranslatableContent() 当成「无内容」直接放行，
        // 可术语表里明明有 none -> 无。只处理单段、24 字以内，
        // 多色行/长句完全不碰，不会破坏聊天的配色和可点区域。
        if (component.getSiblings().isEmpty()) {
            String whole = SECTION.matcher(component.getString()).replaceAll("").trim();
            if (!whole.isEmpty() && whole.length() <= 24) {
                String direct = glossaryExactOn(manager) ? lookupTerm(whole) : null;
                if (direct != null && !direct.equals(whole)) {
                    return Component.literal(direct).withStyle(component.getStyle());
                }
            }
        }
        return translateLine(manager, component, false, false, category);
    }

    /** 屏幕绘制层（String 重载）：很多模组界面直接用 String 画字，Component 版拦不到 */
    public static String translateRenderString(String text) {
        return translateRenderString(text, renderCategory());
    }

    /**
     * 同上，但指定队列优先级。
     *
     * <p>多色行基本只出现在 tooltip（第三方模组加的「标签 + 数值」两列），
     * 用 {@code CAT_SCREEN} 会被计分板、背包格子的刷新挤在后面；给它
     * {@code CAT_TOOLTIP} 才能跟悬停提示一起先出。
     */
    public static String translateRenderString(String text, int category) {
        TranslationManager manager = manager();
        if (manager == null || text == null || text.isEmpty()) {
            return text;
        }
        if (!manager.config().enabled || !manager.config().translateScreenText) {
            return text;
        }
        if (manager.isShowingOriginal()) {
            submitForLater(manager, text);   // 原文模式也预热，切回译文时立刻有中文
            return text;
        }
        // 模组自己的设置页由 I18n 提供文案（跟随游戏语言），不能被翻译器又翻回中文
        if (inOwnUi()) {
            return text;
        }
        // F3 调试屏也是走 String 绘制，不排除的话坐标/FPS 那些行会被翻掉
        var mc = net.minecraft.client.Minecraft.getInstance();
        if (mc != null && mc.getDebugOverlay() != null && mc.getDebugOverlay().showDebugScreen()) {
            return text;
        }
        // 把开头的 § 格式码摘出来，只把纯文本送模型。小模型会把 §f 改写成 §
        // （磁盘缓存里就有 "§fEntity Sorting" -> "§实体排序"），颜色码一旦被改坏
        // 就再也还原不回来，看起来也像「只翻了前几个字母」。
        int head = 0;
        while (head + 1 < text.length() && text.charAt(head) == '\u00a7'
                && isFormatCode(text.charAt(head + 1))) {
            head += 2;
        }
        String codes = text.substring(0, head);
        String body = text.substring(head);
        // 术语表整句直出必须排在 shouldTranslate() 前面：后者把 "none" / "N/A"
        // 这类「不值得送模型」的词直接放行，但术语表里明明有 none -> 无。
        // InventoryProfilesNext 在物品栏底部画的那条红色 NONE 就是这么停在英文的。
        // 有固定译名时根本不进后台队列，也不会撞上每秒限速。
        String direct = glossaryExactOn(manager) ? lookupTerm(body) : null;
        if (direct != null) {
            // 走一遍译文清洗：原文没有省略号时不该冒出「…」
            return codes + cleanTranslation(body, direct);
        }
        if (!shouldTranslate(manager, body)) {
            return text;
        }
        String cached = manager.cached(body);
        if (cached != null) {
            return codes + cached;
        }
        manager.translateInBackgroundRateLimited(body, category);
        return text;
    }

    /**
     * 最近由 {@code textWithWordWrap} 整段处理过的原文（500ms 内有效）。
     * 原版会立刻把这段话 split 成若干 FormattedCharSequence 逐行画出来；
     * 不去重的话底层注入会对每行再提交一次翻译，既是 4 倍请求，又会把一句拆成几句翻。
     */
    private static final java.util.Map<String, Long> RECENT_WRAP =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static void noteWrapSource(String plain) {
        long now = System.currentTimeMillis();
        RECENT_WRAP.entrySet().removeIf(e -> now - e.getValue() > 500L);
        if (plain.length() >= 8) {
            RECENT_WRAP.put(plain, now);
        }
    }

    /** 这一行是不是刚被整段翻译过的折行片段 */
    private static boolean isWrapFragment(String plain) {
        String t = plain == null ? "" : plain.trim();
        if (t.length() < 2) {
            return false;
        }
        long now = System.currentTimeMillis();
        for (java.util.Map.Entry<String, Long> e : RECENT_WRAP.entrySet()) {
            if (now - e.getValue() <= 500L && e.getKey().contains(t)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 折行绘制（{@code textWithWordWrap}）的整段入口：先整段翻译，再让原版按译文折行。
     *
     * <p>26.3 里 {@code textWithWordWrap} 会先 {@code font.split()} 把一段话拆成
     * {@link FormattedCharSequence} 列表，然后逐行调底层 {@code text(...)} —— 这条路径
     * <b>完全绕过</b>了 String / Component 的翻译注入点。Sodium / Iris 这类模组设置页
     * 右侧的英文说明就是这么漏掉的（日志、缓存里连一条记录都没有）。
     * 必须在拆行之前整段翻，否则 "Sets the maximum scale factor to be /
     * used for the user interface" 会被当成两句分别送模型，语序必然错乱。
     */
    public static FormattedText translateRenderFormattedText(FormattedText text) {
        TranslationManager manager = manager();
        if (text == null || manager == null || !manager.config().enabled
                || !manager.config().translateScreenText || manager.isShowingOriginal()
                || inOwnUi()) {
            return text;
        }
        String plain;
        try {
            plain = text.getString();
        } catch (Throwable ignored) {
            return text;
        }
        if (plain == null || plain.isBlank() || plain.length() > 400
                || !LATIN.matcher(plain).find()) {
            return text;
        }
        /*
         * 多色段落不做整段翻译。
         *
         * 整段翻只能返回纯文本（FormattedText.of），会把每种颜色抹成一个色；
         * 更要命的是它会调用 noteWrapSource() 登记「这段已经翻过」，
         * 之后原版把这个段落逐行切开画的时候，每一行都能被那条记录「包含」到，
         * 于是被 isWrapFragment 整体跳过 —— Hypixel 那种
         * 「[SkyHanni] 红色提示 + ➜ 路径」的长消息就是这么一直停在英文的。
         *
         * 交给底层逐行处理：每行按 Style 分段翻，颜色、点击事件全都原样保留。
         */
        if (isMultiStyle(text)) {
            return text;
        }
        String translated = translateRenderString(plain);
        if (translated == null || translated.equals(plain) || translated.isBlank()) {
            /*
             * 没译文时**不能**登记折行标记。
             *
             * 这是好几轮没修好的真正原因：原来先 noteWrapSource() 再翻译，
             * 一旦整段翻不出来（未命中/超时/被判定失败），登记还在，
             * 原版随后把这段逐行切开画的时候，每一行都被 isWrapFragment 判成
             * 「整段已经翻过」而跳过 —— 整段没翻、逐行也不翻，死锁。
             * 先翻译、成功了才登记，逐行才有机会用术语表/价格模板兜底。
             */
            return text;
        }
        noteWrapSource(plain);   // 只有真的翻成功了才标记，避免下面重复翻
        String clean = SECTION.matcher(translated).replaceAll("");
        clean = clean.replaceAll("\\[\\d{1,2}\\]", "");
        return clean.isBlank() ? text : FormattedText.of(clean);
    }

    /**
     * 底层 {@code text(Font, FormattedCharSequence, ...)} 的兜底：有些模组自己
     * {@code font.split()} 后逐行画字，不走 {@code textWithWordWrap}。
     *
     * <p>只在「译文与原文不同」时才替换序列；上层已经翻好的中文序列原样返回，
     * 不会二次翻译，也不会把多色文本重刷成单色。
     */
    public static FormattedCharSequence translateRenderSequence(FormattedCharSequence seq) {
        TranslationManager manager = manager();
        if (seq == null || manager == null || !manager.config().enabled
                || !manager.config().translateScreenText || inOwnUi()) {
            return seq;
        }
        if (manager.isShowingOriginal()) {
            // 原文模式：不替换显示，但把整行文本提交一次，切回译文就不用等
            StringBuilder all = new StringBuilder(64);
            try {
                seq.accept((i, style, cp) -> {
                    all.appendCodePoint(cp);
                    return true;
                });
            } catch (Throwable ignored) {
                return seq;
            }
            submitForLater(manager, SECTION.matcher(all.toString()).replaceAll(""));
            return seq;
        }
        var mc = net.minecraft.client.Minecraft.getInstance();
        if (mc != null && mc.getDebugOverlay() != null && mc.getDebugOverlay().showDebugScreen()) {
            return seq;
        }
        StringBuilder sb = new StringBuilder(64);
        final Style[] firstStyle = new Style[1];
        final boolean[] uniform = {true};
        // 逐段的样式与文字。多色行（第三方模组塞进 tooltip 的「标签 + 数值」两列）
        // 不能整行翻完套一个颜色，只能按段各翻各的、各留各的颜色。
        final List<Style> runStyles = new ArrayList<>(4);
        final List<StringBuilder> runTexts = new ArrayList<>(4);
        try {
            seq.accept((index, style, codePoint) -> {
                if (firstStyle[0] == null) {
                    firstStyle[0] = style;
                } else if (!style.equals(firstStyle[0])) {
                    uniform[0] = false;
                }
                int last = runStyles.size() - 1;
                if (last < 0 || !runStyles.get(last).equals(style)) {
                    runStyles.add(style);
                    runTexts.add(new StringBuilder());
                    last++;
                }
                runTexts.get(last).appendCodePoint(codePoint);
                sb.appendCodePoint(codePoint);
                return true;
            });
        } catch (Throwable ignored) {
            return seq;
        }
        String plain = sb.toString();
        if (plain.isBlank() || plain.length() > 400 || !LATIN.matcher(plain).find()) {
            return seq;   // 中文/符号占绝大多数帧，在这里退出几乎没有开销
        }
        if (uniform[0] && isWrapFragment(plain)) {
            return seq;   // 单色折行片段：整段已经在 textWithWordWrap 里翻过了
        }
        if (!uniform[0]) {
            // 多色行不看折行标记。tooltip 渲染会先用 textWithWordWrap 整段登记一次，
            // 之后逐行画时每一行都能被那个标记「包含」到，于是「标签 + 数字」这种
            // 两列价格行永远被跳过（实测 skyblocker 的 NPC Sell Price 一直不翻）。
            // 多色行走的是按段翻译，本来就不受整段标记影响。
            // 多色行：以前这里直接退回原文（怕把配色刷成单色），于是第三方模组
            // 自己加进 tooltip 的整块文字永远不翻 —— 典型是 skyblocker 的价格区
            // 「NPC Sell Price: 100,000.0 Coins」「Museum (Combat): ✖ Not Donated」，
            // 左边白色标签、右边绿色/红色数值，一行两三种颜色。
            // 现在按段分别翻，每段保留自己的 Style（颜色、点击、悬停事件全在 Style 里）。
            return translateStyledRuns(runStyles, runTexts, seq);
        }
        String translated = translateRenderString(plain);
        if (translated == null || translated.equals(plain) || translated.isBlank()) {
            return seq;   // 保持原序列，样式一点不动
        }
        String clean = SECTION.matcher(translated).replaceAll("");
        clean = clean.replaceAll("\\[\\d{1,2}\\]", "");
        if (clean.isBlank()) {
            return seq;
        }
        Component out = firstStyle[0] == null
                ? Component.literal(clean) : Component.literal(clean).withStyle(firstStyle[0]);
        return out.getVisualOrderText();
    }

    /**
     * 多色行逐段翻译：每个 Style 段单独查缓存/提交后台，命中就把那一段换成中文。
     *
     * <p>为什么要按段而不是整行：第三方模组往 tooltip 里加的「标签 + 数值」是
     * 一行两列，左边白色标签、右边绿色/红色数值（skyblocker 的价格区、博物馆、
     * 获取时间都是这样）。整行翻完套一个颜色会把数值的颜色吃掉；按比例切分又会
     * 把「100,000.0 Coins」切到标签那段里。只有按段翻才能既保住配色又保住右对齐。
     *
     * <p>段里首尾的空格是模组用来右对齐的填充，必须原样留着 —— 交给模型它会顺手删掉，
     * 表现为数值突然贴到标签后面。
     */
    private static FormattedCharSequence translateStyledRuns(List<Style> styles, List<StringBuilder> texts,
                                                             FormattedCharSequence original) {
        boolean changed = false;
        MutableComponent out = Component.empty();
        for (int i = 0; i < styles.size(); i++) {
            String raw = texts.get(i).toString();
            String text = raw;
            if (LATIN.matcher(raw).find()) {
                int lead = 0;
                while (lead < raw.length() && raw.charAt(lead) == ' ') {
                    lead++;
                }
                int tail = raw.length();
                while (tail > lead && raw.charAt(tail - 1) == ' ') {
                    tail--;
                }
                String core = raw.substring(lead, tail);
                // 纯数字/标点段（100,000.0、✖、0/4）交给模型只会被原样吐回来
                if (LATIN.matcher(core).find() && core.length() >= 2) {
                    // 多色行基本都是 tooltip（第三方模组加的标签/数值两列），
                    // 用 tooltip 档位排队，别被计分板刷新挤到后面
                    String tr = translateRenderString(core, TranslationManager.CAT_TOOLTIP);
                    if (tr != null && !tr.isBlank() && !tr.equals(core)) {
                        text = raw.substring(0, lead) + tr + raw.substring(tail);
                        changed = true;
                    }
                }
            }
            out.append(Component.literal(text).withStyle(styles.get(i)));
        }
        // 一段都没命中时保持原序列：连 Component 都不重建，避免每帧白白产生垃圾
        return changed ? out.getVisualOrderText() : original;
    }

    /**
     * 当前界面是不是「物品大列表」（REI / JEI / NEU 物品浏览器）。
     *
     * <p>这类界面一屏能画出上千件物品，每件都要翻名字和描述，量级远超其它来源。
     * 给它单独一档（默认最低 6），聊天、悬停提示、计分板就能排在它前面。
     *
     * <p>用「Screen 对象引用」做缓存：同一个界面每帧只判一次类名，
     * 切换界面才重新计算，不会给渲染线程添负担。
     */
    private static Object itemListScreenCache;
    private static boolean itemListScreenValue;

    public static boolean inItemListUi() {
        try {
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            if (mc == null || mc.gui == null) {
                return false;
            }
            Object screen = mc.gui.screen();
            if (screen == null) {
                return false;
            }
            if (screen == itemListScreenCache) {
                return itemListScreenValue;
            }
            String name = screen.getClass().getName();
            boolean itemList = name.startsWith("me.shedaniel.rei")
                    || name.startsWith("mezz.jei")
                    || name.contains("ItemList") || name.contains("ItemBrowser")
                    || name.contains("NEU");
            itemListScreenCache = screen;
            itemListScreenValue = itemList;
            return itemList;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 渲染层该用的优先级档 */
    private static int renderCategory() {
        return inItemListUi() ? TranslationManager.CAT_ITEM_LIST : TranslationManager.CAT_SCREEN;
    }

    /** 屏幕绘制层通用翻译：与 translate 相同，但未命中的后台提交按速率限制，避免刷屏 */
    public static Component translateRender(Component component) {
        if (component == null) {
            return null;
        }
        TranslationManager manager = manager();
        if (manager == null || !manager.config().enabled || !manager.config().translateScreenText
                || inOwnUi()) {
            return component;
        }
        if (manager.isShowingOriginal()) {
            // 「显示原文」期间也照常提交后台翻译。
            // 否则这个窗口里到达的消息会永远停在英文：模组自己发的本地消息
            // （{@code displayClientMessage}）不经过聊天事件，切回译文时也没有
            // resubmit 兜底，只能靠渲染层再提交一次。
            submitForLater(manager, component.getString());
            return component;
        }
        return translateLine(manager, component, true, false, renderCategory());
    }

    /** 这段 FormattedText 是否由多种样式拼成（多色聊天消息、带点击的提示） */
    private static boolean isMultiStyle(FormattedText text) {
        final Style[] first = new Style[1];
        final boolean[] multi = {false};
        try {
            text.visit((style, str) -> {
                if (first[0] == null) {
                    first[0] = style;
                } else if (!style.equals(first[0])) {
                    multi[0] = true;
                    return java.util.Optional.of(str);   // 非空 = 停止遍历
                }
                return java.util.Optional.empty();
            }, Style.EMPTY);
        } catch (Throwable ignored) {
            return false;
        }
        return multi[0];
    }

    /** 只提交翻译、不取结果：给「显示原文」模式预热缓存用 */
    private static void submitForLater(TranslationManager manager, String text) {
        if (text == null || text.isBlank() || text.length() > 400) {
            return;
        }
        manager.translateInBackgroundRateLimited(text, renderCategory());
    }

    /**
     * 非拉丁字母的文字：日文假名、韩文谚文、西里尔、希伯来、阿拉伯、泰文…
     *
     * <p>这些语言压根没有英文字母，原来「必须有两个连续英文字母」的判断会把它们全挡掉，
     * 服务器里的日语/韩语/俄语聊天一条都翻不出来。
     */
    private static final Pattern OTHER_SCRIPT = Pattern.compile(
            "[\\u3040-\\u30FF\\u31F0-\\u31FF\\uAC00-\\uD7AF\\u0400-\\u04FF"
                    + "\\u0590-\\u05FF\\u0600-\\u06FF\\u0E00-\\u0E7F]");

    /** 这段文字里有没有「需要翻的外语」：英文单词，或者假名/谚文/西里尔这类非拉丁文字 */
    private static boolean hasForeignScript(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        return LATIN_WORD_ANY.matcher(text).find() || OTHER_SCRIPT.matcher(text).find();
    }


    /**
     * 是不是玩家 ID / 用户名。这类字符串没有可译语义，送进模型只会照抄或挂到超时，
     * 然后重试 5 次把翻译线程全占住 —— 实测 156 次超时里 155 次都是这种。
     *
     * <p>优先查原版的在线玩家列表（最准），再用形态特征兜底（大厅里没列出的、
     * 或者服务端伪造的名字）。术语表和地点表收录的名字一律放行。
     */
    public static boolean isPlayerName(String text) {
        if (text == null) {
            return false;
        }
        String t = text.trim();
        if (t.length() < 3 || t.length() > 20 || !t.matches("[A-Za-z0-9_]+")) {
            return false;
        }
        if (Glossary.exact(t) != null || Glossary.isLocation(t)) {
            return false;
        }
        try {
            var mc = net.minecraft.client.Minecraft.getInstance();
            if (mc != null && mc.getConnection() != null
                    && mc.getConnection().getPlayerInfoIgnoreCase(t) != null) {
                return true;
            }
        } catch (Throwable ignored) {
            // 还没连上服务器 / 单人世界，走下面的形态判断
        }
        // 含数字、过长 → 基本都是 ID
        if (t.matches(".*\\d.*") || t.length() > 14) {
            return true;
        }
        // CamelCase（后面小写里又冒出大写）→ ID；全大写的 UI 词（CLICK/LOCK）不算
        boolean hasLower = false;
        boolean innerUpper = false;
        for (int i = 1; i < t.length(); i++) {
            char c = t.charAt(i);
            if (Character.isLowerCase(c)) {
                hasLower = true;
            } else if (Character.isUpperCase(c)) {
                innerUpper = true;
            }
        }
        // 长度限制到 9 以上，避免误伤 SkyBlock(8) 这类品牌词
        if (t.length() >= 9 && hasLower && innerUpper) {
            return true;
        }
        // 连续 5 个辅音 → 基本不是英语单词。阈值放宽到 5 是为了不误伤
        // Strength / Through 这类真词（它们有 4 连辅音）。
        int consonants = 0;
        for (int i = 0; i < t.length(); i++) {
            char c = Character.toLowerCase(t.charAt(i));
            if ("aeiou".indexOf(c) < 0) {
                if (++consonants >= 5) {
                    return true;
                }
            } else {
                consonants = 0;
            }
        }
        return false;
    }

    /**
     * 「这段字是模组自己画的，别翻」开关，由绘制方成对调用。
     *
     * <p>为什么需要：26.3 里 String / Component / centeredText / textWithWordWrap 最后都汇到
     * {@code text(Font, FormattedCharSequence, int, int, int, boolean)}，而这个重载本身挂了
     * 翻译钩子（{@code tyfy$translateSequence}）。左上角状态行改用
     * {@code getVisualOrderText()} 也没绕开它，于是只要在翻译，每帧都会把
     * 「翻译: HY-MT2 1.8B (Q4) 正在翻译 (2)…」送去模型，翻不出结果再重试，
     * 日志里就成了成片的「连续 5 次没翻出结果」，白占本来就只有 1 并发的队列。
     *
     * <p>用 ThreadLocal 而不是静态 boolean：绘制全在主线程，但后台线程也可能调
     * 翻译入口，简单布尔量会被串味。
     */
    private static final ThreadLocal<Boolean> SELF_DRAW = ThreadLocal.withInitial(() -> Boolean.FALSE);

    public static void beginSelfDraw() {
        SELF_DRAW.set(Boolean.TRUE);
    }

    public static void endSelfDraw() {
        SELF_DRAW.set(Boolean.FALSE);
    }

    public static boolean isSelfDraw() {
        return Boolean.TRUE.equals(SELF_DRAW.get());
    }

    /** 当前是否正打开模组自己的界面：它的文字由 I18n 控制语言，不该再被翻译器改写 */
    private static boolean inOwnUi() {
        if (isSelfDraw()) {
            return true;
        }
        try {
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            if (mc == null || mc.gui == null) {
                return false;
            }
            var screen = mc.gui.screen();
            return screen != null
                    && screen.getClass().getName().startsWith("com.tongyongfanyi.config.");
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 翻译一行：按"样式+文本"逐段分解（保留子组件样式与 § 格式码），
     * 只翻译可译片段，未命中触发后台翻译并原样保留。
     */
    private static Component translateLine(TranslationManager manager, Component line,
                                           boolean rateLimited, boolean chatPriority, int category) {
        // 一个拉丁字母都没有 = 既没英文可翻、也没有需要兜底的残留：
        // 直接原样返回，不拆段、不合并词、不查术语表、不重建组件。
        // 计分板/HUD 一屏十几行是每帧都走这里的，这条快捷路径省的是渲染线程上的实打实开销。
        String allText = line.getString();
        if (allText.indexOf('\u00a7') >= 0) {
            allText = SECTION.matcher(allText).replaceAll("");
        }
        if (!LATIN.matcher(allText).find()) {
            return line;
        }
        // 日期行、「图标 + 地点」这类短行本地直接拼，不送模型：
        // 小模型会把 2nd 啃成 2n、把 Dwarven Mines 劈成「矮人 M」，送进去纯属浪费还算错。
        Component quick = localQuickLine(manager, line);
        if (quick != null) {
            return quick;
        }
        // 与聊天路径一样：先收集所有段，把被 § 码 / 组件拆开的同一个词拼回去。
        // 计分板就是走这条路径，之前没做合并，所以 Village 被拆成 V + illage，
        // 屏幕上只翻出半截词。
        List<Seg> segs = new ArrayList<>();
        LegacyTracker legacy = new LegacyTracker();
        line.<Object>visit((style, text) -> {
            for (Run run : splitRuns(text)) {
                // 色码/格式码在同一个字符串里对后续文字持续生效，
                // 拆成兄弟组件后就不再跨段，必须给每段补齐当前状态
                legacy.apply(run.codes());
                Style runStyle = style;
                for (int i = 0; i + 1 < run.codes().length(); i += 2) {
                    ChatFormatting fmt = ChatFormatting.getByCode(run.codes().charAt(i + 1));
                    if (fmt != null) {
                        runStyle = runStyle.applyLegacyFormat(fmt);
                    }
                }
                String clean = SECTION.matcher(run.text()).replaceAll("");
                segs.add(new Seg(style, runStyle, legacy.codes(), clean, 0, null, -1));
            }
            return Optional.empty();
        }, Style.EMPTY);
        mergeWordGroups(segs, 0);

        // 一屏 lore 常被 Hypixel 按颜色拆成 4~6 段，逐段送模型 = 4~6 个请求，
        // 2 个并发槽来不及处理就只能排队，屏幕上是「一段段慢慢变中文」，还占满 GPU。
        // 同色行先走整行合并（最简单，样式不用重分配）。
        String whole = wholeLineText(manager, segs);
        if (whole != null) {
            return translateWholeLine(manager, line, segs, whole, rateLimited, chatPriority, category);
        }
        // 多色行：也整句翻译，译后把颜色分回去。
        // 以前多色行逐段翻，Hypixel 一句话里 "Bring 3,000 of these to 【Carolyn】
        // in 【Scarlet Isle】 to permanently gain +5 Health..."（紫色是 NPC/地点）
        // 会被拆成 6 段分别送模型，拼起来就是
        // 「带来 3,000 这些中的 Carolyn in 斯卡勒顿在上绯红岛 to 永久获得」——
        // to/in 这类功能词在碎片里根本翻不出来。
        Component colored = translateColoredLine(manager, segs, rateLimited, chatPriority, category);
        if (colored != null) {
            return colored;
        }

        MutableComponent result = Component.empty().withStyle(line.getStyle());
        boolean changed = false;
        int idx = 0;
        while (idx < segs.size()) {
            int end = idx;
            while (end + 1 < segs.size() && segs.get(end + 1).group() == segs.get(idx).group()) {
                end++;
            }
            Seg head = segs.get(idx);
            String clean = head.lookup() == null ? head.raw() : head.lookup();

            // 聊天里的「[MVP+] Steve: 正文」：只翻正文，名字保持原样
            String[] speaker = head.codes().isEmpty() && chatPriority && manager.config().smartTranslate
                    && manager.config().keepSpeakerNames
                    ? splitSpeaker(clean) : null;
            String subject = speaker != null ? speaker[1] : clean;
            String prefix = speaker != null ? speaker[0] : "";

            String output = null;
            String ordinal = localOrdinal(subject);
            // 整段就是术语：直接给固定译名，零延迟、不排队、不进模型
            String direct = glossaryExactOn(manager) ? lookupTerm(subject) : null;
            if (ordinal != null) {
                output = prefix + ordinal;
            } else if (direct != null) {
                output = prefix + cleanTranslation(subject, direct);
            } else if (subject.indexOf('\u00a7') >= 0 || !shouldTranslate(manager, subject)) {
                // 中英混排：不调模型翻整行，只把英文片段摘出来单独翻
                // 这里只做「把残留的英文术语补成中文」的兜底，不影响整句语序，
                // 所以跟「整句术语固定译名」那档开关走：玩家关掉句中占位替换后，
                // Bazaar / Hub / SkyBlock 这些模型爱保留的英文仍然会补成中文。
                String substituted = glossaryExactOn(manager) ? Glossary.postfix(clean) : clean;
                // 不再因为「这段带 § 格式码」就跳过英文片段抽取：
                // 颜色已经由 seg.codes() / runStyle 单独保留，文本本身是干净的。
                // 以前跳过会让带颜色的 lore 行里残留的英文永远翻不到。
                String mixed = translateLatinSpans(manager, substituted, rateLimited, chatPriority, category);
                if (!mixed.equals(clean)) {
                    output = prefix + mixed;
                }
            } else {
                String cached = manager.cached(subject);
                if (cached != null) {
                    output = prefix + cleanTranslation(subject, cached);
                    if (glossaryOn(manager)) {
                        // 模型偶尔会在译文里留下原文词（Mines → 「M弦」），术语表里有就换掉
                        output = Glossary.postfix(output);
                    }
                } else {
                    submitBackground(manager, subject, rateLimited, chatPriority, category);
                }
            }

            for (int k = idx; k <= end; k++) {
                Seg seg = segs.get(k);
                if (output != null) {
                    // 译文只放组内首段，后续段留空，避免把同一个词显示两遍
                    result.append(Component.literal(k == idx ? output : "")
                            .withStyle(seg.runStyle()));
                    changed = true;
                } else {
                    // 没译文时按段原样输出；用已套用 § 格式的 runStyle，颜色不丢
                    result.append(Component.literal(seg.raw()).withStyle(seg.runStyle()));
                }
            }
            idx = end + 1;
        }
        return changed ? result : line;
    }

    /**
     * 判断这一行能不能整句翻译，能就返回拼接后的纯文本。
     *
     * <p>必须同时满足：至少两段、所有段颜色相同且没有点击/悬停事件（整行换掉视觉上看不出差别）、
     * 不含中文（中英混排交给 {@code translateLatinSpans} 摘英文片段）、
     * 至少 4 个英文单词（属性行 {@code Health: +100} 只有 1 个词，不会被合并）。
     */
    private static String wholeLineText(TranslationManager manager, List<Seg> segs) {
        if (!manager.config().mergeWholeLine || segs.size() < 2) {
            return null;
        }
        StringBuilder sb = new StringBuilder(64);
        Object color = null;
        String format = null;
        for (int i = 0; i < segs.size(); i++) {
            Seg seg = segs.get(i);
            Style st = seg.runStyle();
            if (st.getClickEvent() != null || st.getHoverEvent() != null) {
                return null;   // 带按钮/提示的行保持逐段，别把可点击区域搞丢
            }
            Object c = st.getColor();
            // 粗体/斜体/下划线/删除线/乱码也要一致：整句译文只放在首段，
            // 中间那些加粗的词一旦被合并就只剩首段的样式了（表现为「失去格式」）。
            String f = (st.isBold() ? "b" : "") + (st.isItalic() ? "i" : "")
                    + (st.isUnderlined() ? "u" : "") + (st.isStrikethrough() ? "s" : "")
                    + (st.isObfuscated() ? "o" : "");
            if (i == 0) {
                color = c;
                format = f;
            } else if (!java.util.Objects.equals(color, c) || !format.equals(f)) {
                return null;   // 多色/多格式行：逐段替换才能保住颜色和格式
            }
            sb.append(seg.raw());
        }
        String whole = sb.toString();
        if (whole.length() < 16 || CJK.matcher(whole).find() || whole.indexOf('<') >= 0) {
            return null;
        }
        if (latinWordCount(whole) < 4) {
            return null;
        }
        return whole;
    }

    /** 整句翻译版：一个请求代替原来的 4~6 个，译文放在首段，其余段留空保留格式 */
    private static Component translateWholeLine(TranslationManager manager, Component line,
                                                List<Seg> segs, String whole,
                                                boolean rateLimited, boolean chatPriority, int category) {
        String output;
        String direct = glossaryExactOn(manager) ? lookupTerm(whole) : null;
        if (direct != null) {
            output = cleanTranslation(whole, direct);
        } else if (!shouldTranslate(manager, whole)) {
            return line;
        } else {
            String cached = manager.cached(whole);
            if (cached == null) {
                // 还没译文：先原样显示，缓存到位后下一帧整行替换（不闪烁、不丢字）
                submitBackground(manager, whole, rateLimited, chatPriority, category);
                return line;
            }
            output = cleanTranslation(whole, cached);
        }
        // 整句翻译时模型经常留下个别英文词（「接入 Mines」「在矮人村庄」），
        // 术语表里有就直接换掉，比再送一次模型稳得多。
        if (glossaryExactOn(manager)) {
            output = Glossary.postfix(output);
        }
        MutableComponent result = Component.empty().withStyle(line.getStyle());
        for (int i = 0; i < segs.size(); i++) {
            result.append(Component.literal(i == 0 ? output : "").withStyle(segs.get(i).runStyle()));
        }
        return result;
    }

    /** 去掉模型自行添加的省略号：仅当原文本身没有省略号时生效 */
    /** 英文序数后缀（30th / 1st / 2nd / 3rd），只在日期语境里转成「日」 */
    private static final Pattern EN_ORDINAL = Pattern.compile("(?<=\\d)(?:st|nd|rd|th)\\b",
            Pattern.CASE_INSENSITIVE);
    /**
     * 纯序号本地直接转（31st → 31日）：这类两三个字母的碎片送模型几乎必然原样吐回，
     * 判失败后还会重试 5 次、白占队列。计分板日期行「晚春 31st」就是这么漏的。
     */
    private static String localOrdinal(String text) {
        if (text == null) {
            return null;
        }
        String t = text.trim();
        if (!BARE_ORDINAL.matcher(t).matches()) {
            return null;
        }
        return t.replaceAll("(?i)(?:st|nd|rd|th)$", "日");
    }

    /** 整段就是一个英文序号（31st / 2nd / 3rd / 4th） */
    private static final Pattern BARE_ORDINAL = Pattern.compile("(?i)^\\d{1,2}(?:st|nd|rd|th)$");

    /** 日期语境的标志：季节 / 年月 / 日 */
    private static final Pattern DATE_CONTEXT = Pattern.compile("[春夏秋冬]|[年月日]");

    /** 中文之间夹的省略号（「与…交谈」→「与交谈」） */
    private static final Pattern CJK_ELLIPSIS = Pattern.compile(
            "(?<=[\\u4e00-\\u9fa5])(?:…|\\.\\.\\.)(?=[\\u4e00-\\u9fa5])");
    /**
     * 中文之间的多余空格。模型逐词直译会留下「非常 智者龙 胸甲」「抛光的 钛 镐」
     * 这种空隙，去掉之后才像正常中文；数字与中文之间的空格（{@code 1,253 法力}）不动。
     */
    private static final Pattern CJK_GAP = Pattern.compile(
            "(?<=[\\u4e00-\\u9fa5])[ \\t]+(?=[\\u4e00-\\u9fa5])");

    private static String cleanTranslation(String original, String translated) {
        if (translated == null || translated.isBlank()) {
            return translated;
        }
        String cleaned = translated;
        if (CJK_ELLIPSIS.matcher(cleaned).find()) {
            cleaned = CJK_ELLIPSIS.matcher(cleaned).replaceAll("");
        }
        if (original == null || !(original.contains("...") || original.contains("…"))) {
            if (cleaned.contains("...") || cleaned.contains("…")) {
                String trimmed = cleaned.replace("…", "").replace("...", "").trim();
                if (!trimmed.isBlank()) {
                    cleaned = trimmed;
                }
            }
        }
        if (CJK_GAP.matcher(cleaned).find()) {
            cleaned = CJK_GAP.matcher(cleaned).replaceAll("");
        }
        // 日期里的英文序数后缀：「晚春 30th」→「晚春 30日」。
        // 只在译文里出现季节/年月这些日期语境时才换，避免把「1st place」改坏。
        if (DATE_CONTEXT.matcher(cleaned).find()) {
            cleaned = EN_ORDINAL.matcher(cleaned).replaceAll("日");
        }
        // 计分板日期行常被拆成「晚春 」+「31st」两段，单独一段没有季节语境，
        // 这时只要整段就是一个序号，也按日期处理（31st → 31日）。
        if (BARE_ORDINAL.matcher(cleaned.trim()).matches()) {
            cleaned = cleaned.trim().replaceAll("(?i)(?:st|nd|rd|th)$", "日");
        }
        // 排版符号还原放在最后：前面几步可能刚插删过字符。
        // 半角括号、箭头两侧空格都属于原消息的格式，不该被模型改写掉。
        cleaned = restoreChatLayout(original, cleaned);
        // 原文没有书名号时，擦掉模型自己给专名/残句补出来的《…》
        return Glossary.stripBogusQuotes(original, cleaned);
    }

    private static void submitBackground(TranslationManager manager, String clean,
                                         boolean rateLimited, boolean chatPriority, int category) {
        if (chatPriority) {
            manager.translateChatInBackground(clean);
        } else if (rateLimited) {
            manager.translateInBackgroundRateLimited(clean, category);
        } else {
            manager.translateInBackground(clean, category);
        }
    }

    private record Run(String codes, String text) {
    }

    /**
     * § 格式状态累加器。
     *
     * <p>同一个字符串里的 {@code §6} 会对它后面的所有文字持续生效（包括被拆到
     * 下一个兄弟组件里的部分），但拆成多个 Component 之后原版就只认「本段自己的」
     * 样式了 —— 第二段既没有色码、Component style 又是空的，重建出来就变成白字、
     * 粗体斜体全丢。这就是「对话框翻译后有的失去颜色、有的失去格式」的根因。
     *
     * <p>这里按原版渲染器的规则累计：颜色码覆盖上一个颜色并清掉格式（和游戏里一样），
     * {@code §r} 全部清空，格式码累加，然后每段都带上当前完整状态。
     */
    private static final class LegacyTracker {
        private String color = "";
        private final StringBuilder formats = new StringBuilder();

        void apply(String codes) {
            for (int i = 0; i + 1 < codes.length(); i += 2) {
                char c = Character.toLowerCase(codes.charAt(i + 1));
                if (!isFormatCode(c)) {
                    continue;
                }
                if (c == 'r') {
                    color = "";
                    formats.setLength(0);
                } else if (isColorCode(c)) {
                    color = "\u00a7" + c;
                    formats.setLength(0);
                } else {
                    String pair = "\u00a7" + c;
                    if (formats.indexOf(pair) < 0) {
                        formats.append(pair);
                    }
                }
            }
        }

        String codes() {
            return color + formats;
        }
    }

    private static boolean isColorCode(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
    }

    /** § 后面跟的字符一律当格式码（含 Hypixel 自造的 §u 这类隐形填充码） */
    private static boolean isFormatCode(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    /** 按 § 格式码把一段文本拆成多段，格式码作为下一段的固定前缀 */
    private static List<Run> splitRuns(String s) {
        List<Run> runs = new ArrayList<>();
        StringBuilder codes = new StringBuilder();
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '\u00a7' && i + 1 < s.length() && isFormatCode(s.charAt(i + 1))) {
                codes.append(c).append(s.charAt(i + 1));
                i += 2;
            } else {
                int start = i;
                while (i < s.length()
                        && !(s.charAt(i) == '\u00a7' && i + 1 < s.length()
                        && isFormatCode(s.charAt(i + 1)))) {
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

    /**
     * 聊天里的「说话人前缀」：{@code [MVP+] Steve: 正文}、{@code [NPC] Lumber Jack: 正文}。
     *
     * <p>Hypixel 的聊天行样式分段很碎（一个名字能拆成三四个 Component），
     * 旧的逐段翻译会把 {@code Lumber Jack}、{@code Timber!} 分别送进模型，
     * 名字翻不出来还要重试 5 次，整句反而永远不翻。现在改为按整行的
     * 最后一个 {@code ": "} 切出正文，整句一次翻译。
     */
    private static String[] splitSpeaker(String text) {
        if (text == null || text.length() > 512) {
            return null;
        }
        int colon = text.lastIndexOf(": ");
        if (colon < 0 || colon > 64) {
            return null;
        }
        String head = text.substring(0, colon).trim();
        String prefix = text.substring(0, colon + 2);
        String body = text.substring(colon + 2);
        // 前缀里有句末标点说明这是句子里的冒号，不是说话人
        if (body.length() < 3 || prefix.indexOf('.') >= 0
                || prefix.indexOf('?') >= 0 || prefix.indexOf('!') >= 0) {
            return null;
        }
        // 「标签: 值」不是说话人。旧版把 "Profile ID: <UUID>"、"Latest update: 中文"、
        // "NPC Sell Price: 44 Coins" 一律切成前缀+正文，前缀被 keepSpeakerNames 原样保留，
        // 于是这些标签永远停在英文；正文若是 UUID 或纯中文，chatWorthTranslating 还会
        // 判整行「不值得翻」直接放行 —— 聊天栏反复漏翻有好几轮就是栽在这里。
        // 说话人前缀只可能是 "[MVP+] Steve" / "[NPC] Lumber Jack"（以 [ 开头）
        // 或单独一个玩家名（不含空格）。
        if (!head.startsWith("[") && head.indexOf(' ') >= 0) {
            return null;
        }
        return new String[]{prefix, body};
    }

    /** 术语表只对中文目标生效：目标设成英语/日语时不该把词替换成中文 */
    private static boolean glossaryOn(TranslationManager manager) {
        return manager.config().useGlossary
                && Glossary.appliesTo(manager.config().effectiveTarget());
    }

    /**
     * 整句就是术语时是否直接给固定译名。和 {@link #glossaryOn} 分开：
     * 玩家关掉「句中术语替换」（避免句子被占位符拆碎）后，计分板的
     * {@code Purse:}、{@code Bits:}、物品整名仍应出固定译名，
     * 否则模型会给出「位：」「魔法硬石」这类错译。
     */
    private static boolean glossaryExactOn(TranslationManager manager) {
        return manager.config().glossaryExact
                && Glossary.appliesTo(manager.config().effectiveTarget());
    }

    /** 拆出聊天行的 [说话人前缀, 待翻正文]，前缀保持原文 */
    private static String[] chatParts(TranslationManager manager, Component line) {
        String plain = SECTION.matcher(line.getString()).replaceAll("").trim();
        if (plain.isEmpty()) {
            return null;
        }
        String prefix = "";
        String body = plain;
        // 智能翻译关掉时连说话人名字一起翻（用户要的是「英文全翻」）
        if (manager.config().keepSpeakerNames && manager.config().smartTranslate) {
            String[] s = splitSpeaker(plain);
            if (s != null) {
                prefix = s[0];
                body = s[1];
            }
        }
        return new String[]{prefix, body};
    }

    /**
     * 聊天正文是否值得送模型。比通用 {@link #shouldTranslate} 宽松：
     * 中文 NPC 名 / 术语还原后的中文不能让整行被跳过，只看还有没有英文可翻。
     */
    private static boolean chatWorthTranslating(TranslationManager manager, String body) {
        // 智能翻译关掉：只要是英文就翻，玩家名、称号、时间、网址一律不再过滤
        if (!manager.config().smartTranslate) {
            return rawEnglishWanted(body);
        }
        if (body == null || body.isBlank() || body.length() < Math.max(3, manager.config().minLength)) {
            return false;
        }
        if (!hasForeignScript(body)) {
            return false;
        }
        // 纯字母数字的单个词多半是玩家 ID / 头顶名，只翻术语表收录的；
        // 带标点（Timber!）或含空格（Lumber Jack）的照样翻。
        String trimmed = body.trim();
        // 极短碎片（CH / gt / wCH / vk）：没有可译语义，送进去只会让小模型
        // 续写到 num_predict 上限，单个请求拖 3~6 秒。
        if (trimmed.length() < 4 && Glossary.exact(trimmed) == null
                && !Glossary.isLocation(trimmed)) {
            return false;
        }
        if (trimmed.indexOf(' ') < 0 && SINGLE_WORD.matcher(trimmed).matches()
                && Glossary.exact(trimmed) == null
                && !manager.config().translateSingleWords
                && isPlayerName(trimmed)) {
            // 默认只挡玩家 ID；普通单词（Purse/Bits/Objective/Skill/Level…）、
            // 术语和地名都照常翻。玩家 ID 已经在 submitTranslate 里用在线玩家列表
            // 精确过滤，不需要再靠「所有单个词一律不翻」来防队列堵塞 —— 那条老规则
            // 正是计分板大面积漏翻的原因。开关打开时连玩家 ID 也翻（标签即「含人名」）。
            return false;
        }
        return !body.startsWith("/");
    }

    /** 说话人前缀在整行纯文本里的长度；前缀跨多少个 Component 段都要原样保留 */
    private static int speakerPrefixLength(TranslationManager manager, String plain) {
        if (!manager.config().keepSpeakerNames || !manager.config().smartTranslate) {
            return 0;
        }
        String[] s = splitSpeaker(plain);
        return s == null ? 0 : s[0].length();
    }

    /**
     * 聊天消息逐段替换 —— 沿用 1.2/1.3 早期版本的 {@link #splitRuns} 思路。
     *
     * <p>按「§ 格式码 + Component 样式」把一行拆成若干段，每段单独查缓存；
     * 命中后用<b>这一段自己的 Style</b> 输出译文，因此颜色、点击、悬停事件
     * 全部原样保留（NPC 的 {@code [Sure]} 翻成「当然」后依然可点）。
     * 这正是旧版"不丢颜色"的做法，只是旧版没有异步刷新，首次消息永远翻不出来。
     *
     * <p>{@code submit=true} 时未命中的段会提交后台翻译（只在收到消息时调一次）；
     * {@code submit=false} 时只查缓存，供 BilingualComponent 每次重排版时调用。
     */
    /** 逐段替换的中间结构：lookup = 该段所属「完整词」的文本，group = 同词分组号 */
    private record Seg(Style style, Style runStyle, String codes, String raw,
                       int start, String lookup, int group) {
    }

    /** 两段之间是不是同一个词被硬拆开：紧贴的两侧都是字母/数字，中间没有空格或标点 */
    /** 不是单词的短字母组合？反过来说：这些是真词，遇到它们就别把上一段和它粘起来 */
    private static final java.util.Set<String> SHORT_WORDS = java.util.Set.of(
            "a", "i", "of", "to", "in", "is", "it", "at", "on", "we", "he", "do", "if",
            "as", "be", "by", "or", "an", "my", "up", "no", "so", "us", "me", "am", "go");

    private static boolean joinsWord(String a, String b) {
        if (a.isEmpty() || b.isEmpty()) {
            return false;
        }
        if (Character.isLetterOrDigit(a.charAt(a.length() - 1))
                && Character.isLetterOrDigit(b.charAt(0))) {
            return true;   // 同一个词被硬拆开（V + illage）
        }
        // 冠词 / 介词被颜色码单独拆成一段时也要拼回去："A " + "book" → "A book"。
        // 否则单字母 "A" 会因为 minLength=3 和「至少两个连续字母」的规则被丢掉，
        // 聊天里就一直显示 "A 书"（用户反馈的 a book 那个 A 不翻译）。
        String ta = a.trim();
        String tb = b.trim();
        if (a.endsWith(" ") && ta.length() <= 3 && ta.matches("[A-Za-z]+")
                && !tb.isEmpty() && Character.isLetter(tb.charAt(0))) {
            return true;
        }
        // 切点落在单词内部且紧跟空格：「Mine 」+「s」、「Sprin」+「g 」这类，
        // 续段只剩一两个小写字母。这种碎片单独送模型会翻成「M弦」「正弦」，
        // 或者干脆留一个字母在屏幕上。只在一两个字母、且不是常见英语单词时才拼。
        return a.endsWith(" ") && tb.length() <= 2 && tb.matches("[a-z]+")
                && !SHORT_WORDS.contains(tb);
    }

    /**
     * 把被 § 颜色码 / 多个 Component 拆开的同一个词拼回完整词，一组共用一个查询键。
     * 聊天和计分板都要用 —— 不这么做的话 Village 会被拆成 V + illage 分别送模型，
     * 屏幕上就只翻出「伊兰吉」这种半截词。
     */
    private static void mergeWordGroups(List<Seg> segs, int prefixLen) {
        // 第一步：同一个词被颜色码/组件拆开时（V + illage）先拼回去
        List<int[]> ranges = new ArrayList<>();
        for (int i = 0; i < segs.size(); ) {
            boolean overlapsPrefix = prefixLen > 0 && segs.get(i).start() < prefixLen;
            int j = i;
            while (!overlapsPrefix) {
                // 中间夹着空段（只有颜色码的占位组件）时要能跨过去继续拼词：
                // 计分板前缀 "Lava Spri" 和后缀 "ngs" 之间就常夹着这种空段，
                // 老写法一遇到空段就断开，于是 Spring 被翻成「SPRi」+「ngs」。
                int next = j + 1;
                while (next < segs.size() && segs.get(next).raw().isBlank()) {
                    next++;
                }
                if (next >= segs.size()
                        || (prefixLen > 0 && segs.get(next).start() < prefixLen)
                        || !joinsWord(segs.get(j).raw(), segs.get(next).raw())) {
                    break;
                }
                j = next;
            }
            ranges.add(new int[]{i, j});
            i = j + 1;
        }

        // 第二步：多词术语跨词组的情况也要连起来。
        // 「Rampart's Quarry」被空格边界分成两个词组，词组各自查表都查不到，
        // 于是屏幕上出现「Rampart's采石场」—— 一个词翻了、另一个留着英文。
        // 这里只要「相邻两组的文本拼起来正好是术语表里的词条」就合并成一组。
        boolean merged = true;
        while (merged) {
            merged = false;
            for (int i = 0; i + 1 < ranges.size(); i++) {
                int[] left = ranges.get(i);
                int[] right = ranges.get(i + 1);
                // 说话人前缀不参与合并，名字得原样留着
                if (prefixLen > 0 && segs.get(right[0]).start() < prefixLen) {
                    continue;
                }
                String leftText = rangeText(segs, left[0], left[1]);
                String rightText = rangeText(segs, right[0], right[1]);
                // 居中的纯空白段是排版，不是词：别让它被并进正文词组，
                // 否则整句块的起点会被逐组循环吃掉，居中空格也保不住。
                if (leftText.isBlank() || rightText.isBlank()) {
                    continue;
                }
                String joined = leftText + rightText;
                if (joined.length() > MAX_GLOSSARY_JOIN) {
                    continue;
                }
                // 两种拼法都要试：Hypixel 按队伍前缀切行时，空格可能刚好落在切点上，
                // 「Mine 」+「s」这种拼起来带空格查不到，去掉空格才是 Mines。
                boolean hit = lookupTerm(joined) != null
                        || lookupTerm(joined.replace(" ", "")) != null;
                if (!hit) {
                    continue;
                }
                ranges.set(i, new int[]{left[0], right[1]});
                ranges.remove(i + 1);
                merged = true;
                break;
            }
        }

        // 第三步：写回分组号和查询文本
        int group = 0;
        for (int[] range : ranges) {
            String lookup = rangeText(segs, range[0], range[1]);
            for (int k = range[0]; k <= range[1]; k++) {
                Seg s = segs.get(k);
                segs.set(k, new Seg(s.style(), s.runStyle(), s.codes(), s.raw(),
                        s.start(), lookup, group));
            }
            group++;
        }
    }

    /** 合并词组时允许的最大长度（术语表里最长的地名也就二三十字符） */
    private static final int MAX_GLOSSARY_JOIN = 48;

    /** 把 [from, to] 这几段的原文拼起来 */
    private static String rangeText(List<Seg> segs, int from, int to) {
        StringBuilder sb = new StringBuilder(16);
        for (int k = from; k <= to; k++) {
            sb.append(segs.get(k).raw());
        }
        return sb.toString();
    }

    private static Component translateChatSegments(TranslationManager manager, Component line, boolean submit) {
        String plain = SECTION.matcher(line.getString()).replaceAll("");
        int prefixLen = speakerPrefixLength(manager, plain);
        List<Seg> segs = new ArrayList<>();
        int[] consumed = {0};

        LegacyTracker legacy = new LegacyTracker();
        line.<Object>visit((style, text) -> {
            for (Run run : splitRuns(text)) {
                legacy.apply(run.codes());
                Style runStyle = style;
                for (int i = 0; i + 1 < run.codes().length(); i += 2) {
                    ChatFormatting fmt = ChatFormatting.getByCode(run.codes().charAt(i + 1));
                    if (fmt != null) {
                        runStyle = runStyle.applyLegacyFormat(fmt);
                    }
                }
                String clean = SECTION.matcher(run.text()).replaceAll("");
                int start = consumed[0];
                consumed[0] += clean.length();
                segs.add(new Seg(style, runStyle, legacy.codes(), clean, start, null, -1));
            }
            return Optional.empty();
        }, Style.EMPTY);

        // 把被 § 码 / 组件分段拆开的同一个词拼回完整词（详见 mergeWordGroups）
        mergeWordGroups(segs, prefixLen);

        // ---- 整句块：整行当成一句话（说话人前缀除外）----
        // 以前为了「不破坏点击区域」，遇到带点击/悬停事件的段就断开，
        // 但 Hypixel 的聊天几乎每段都可点（地点能传送、物品能查看），
        // 一句话被切成几个小碎片，每块不到 3 个英文词 → 退回逐词翻译，
        // 屏幕上就成了「A Fallen Star has crashed at」+「附近 矿石 and 粉末」这种夹生。
        //
        // 现在渲染按 [N] 标记把译文切回各段，每段用的还是它自己原来的 Style
        // （点击/悬停事件都在 Style 里），整行一起翻不会丢任何可点区域。
        java.util.Map<Integer, Integer> blockEnds = new java.util.HashMap<>();
        int blockStart = 0;
        while (blockStart < segs.size() && prefixLen > 0 && segs.get(blockStart).start() < prefixLen) {
            blockStart++;
        }
        // Hypixel 用「§f + 十几个空格」把整条消息居中（日志里的
        // "§f                §7You have ..."）。这些纯空白段是排版、不是文字：
        // 以前被并进整句块，缓存键被 trim、模型也看不到，译文就变成左对齐。
        // 现在把首尾空白段留在块外，由逐组逻辑原样输出（连同 §f 颜色码）。
        while (blockStart < segs.size() && segs.get(blockStart).raw().isBlank()) {
            blockStart++;
        }
        int lastContentSeg = segs.size() - 1;
        while (lastContentSeg > blockStart && segs.get(lastContentSeg).raw().isBlank()) {
            lastContentSeg--;
        }
        if (blockStart <= lastContentSeg) {
            blockEnds.put(blockStart, lastContentSeg);
        }

        MutableComponent result = Component.empty().withStyle(line.getStyle());
        boolean changed = false;
        int idx = 0;
        while (idx < segs.size()) {
            Integer blockEnd = blockEnds.get(idx);
            if (blockEnd != null) {
                Component block = translateChatBlock(manager, segs, idx, blockEnd, submit,
                        blockStyleBoost(segs, idx, blockEnd));
                if (block != null) {
                    result.append(block);
                    changed = true;
                    idx = blockEnd + 1;
                    continue;
                }
            }
            int end = idx;
            int group = segs.get(idx).group();
            // 前面的居中空白段万一被合并进正文组，也不能让它的逐组处理一路
            // 延伸过 blockStart —— 否则会跳过整句块，译文又变回逐段夹生。
            while (end + 1 < segs.size()
                    && segs.get(end + 1).group() == group
                    && (idx >= blockStart || end + 1 < blockStart)) {
                end++;
            }
            Seg head = segs.get(idx);
            String groupText = head.lookup() == null ? head.raw() : head.lookup();
            // 与说话人前缀重叠的组只含一段：改用切掉前缀后的正文当查询键
            if (head.start() < prefixLen) {
                int cut0 = Math.min(prefixLen - head.start(), head.raw().length());
                groupText = head.raw().substring(cut0);
            }

            String replacement = localOrdinal(groupText);
            if (replacement == null && !groupText.isEmpty() && glossaryExactOn(manager)) {
                replacement = cleanTranslation(groupText, lookupTerm(groupText));
            }
            boolean worth = !groupText.isEmpty() && chatWorthTranslating(manager, groupText);
            String cached = (replacement == null && worth) ? manager.cached(groupText) : null;
            if (replacement == null && cached != null && !cached.isBlank() && !cached.equals(groupText)) {
                replacement = cleanTranslation(groupText, cached);
            }
            if (replacement == null && submit && worth && cached == null
                    && !manager.isShowingOriginal() && !groupText.isEmpty()) {
                manager.translateChatInBackground(groupText);
            }
            if (replacement == null && !groupText.isEmpty() && glossaryExactOn(manager)) {
                String post = Glossary.postfix(groupText);
                if (!post.equals(groupText)) {
                    replacement = post;
                }
            }

            for (int k = idx; k <= end; k++) {
                Seg seg = segs.get(k);
                String codes = seg.codes();
                String clean = seg.raw();
                if (clean.isEmpty()) {
                    result.append(Component.literal(codes).withStyle(seg.style()));
                    continue;
                }
                if (seg.start() < prefixLen) {
                    int cut = Math.min(prefixLen - seg.start(), clean.length());
                    result.append(Component.literal(codes + clean.substring(0, cut)).withStyle(seg.style()));
                    clean = clean.substring(cut);
                    codes = "";
                    if (clean.isEmpty()) {
                        continue;
                    }
                }
                boolean first = (k == idx);
                if (replacement != null) {
                    // 译文只放在组内第一段，后续段仅保留格式码，避免重复显示同一个词
                    result.append(Component.literal(codes + (first ? replacement : ""))
                            .withStyle(seg.runStyle()));
                    changed = true;
                } else {
                    result.append(Component.literal(codes + clean).withStyle(seg.style()));
                }
            }
            idx = end + 1;
        }
        return changed ? result : line;
    }

    /** 计分板日期行：Early/Late Spring 2nd, Year 526 */
    private static final Pattern DATE_LINE = Pattern.compile(
            "(?i)^(early|mid|late)?\\s*(spring|summer|autumn|fall|winter)\\s*(\\d{1,2})"
                    + "(?:st|nd|rd|th)?(?:,?\\s*year\\s*(\\d{1,4}))?$");

    private static final java.util.Map<String, String> SEASONS = java.util.Map.ofEntries(
            java.util.Map.entry("early spring", "早春"), java.util.Map.entry("spring", "春"),
            java.util.Map.entry("mid spring", "仲春"), java.util.Map.entry("late spring", "晚春"),
            java.util.Map.entry("early summer", "初夏"), java.util.Map.entry("summer", "夏"),
            java.util.Map.entry("mid summer", "仲夏"), java.util.Map.entry("late summer", "晚夏"),
            java.util.Map.entry("early autumn", "初秋"), java.util.Map.entry("autumn", "秋"),
            java.util.Map.entry("fall", "秋"), java.util.Map.entry("late autumn", "晚秋"),
            java.util.Map.entry("early winter", "初冬"), java.util.Map.entry("winter", "冬"),
            java.util.Map.entry("late winter", "晚冬"));

    /** 剥掉行首/行尾符号用（图标字体字符、标点） */
    private static final Pattern EDGE_STRIP = Pattern.compile(
            "^[^A-Za-z0-9\\u4e00-\\u9fa5]+|[^A-Za-z0-9\\u4e00-\\u9fa5]+$");

    /**
     * 术语查询：先按原样查，查不到再剥掉行首/行尾的图标与标点查一次。
     *
     * <p>计分板地点行是「⛏ Rampart's Quarry」这种「图标 + 地名」，
     * 带着图标直接查表永远查不到，模型又喜欢把 Rampart's 留着当专有名词。
     * 剥掉图标再查就能命中 {@code rampart's quarry → 城墙采石场}。
     */
    private static String lookupTerm(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String t = text.trim();
        // Hypixel / SkyHanni 周期性固定提示本地直出：小模型对这几行经常输出乱码，
        // 判坏后不写缓存，聊天栏就一直显示英文（19:44/19:45/19:46 那三批 stash 提示）。
        String notice = Glossary.chatNotice(t);
        if (notice != null) {
            return notice;
        }
        // 价格行先走本地模板，这类行送模型基本原样返回，还会把千分位改掉。
        // priceRow 处理「标签 + 金额」（NPC Sell Price: 500.0 Coins），
        // priceLine 处理纯金额（500.0 Coins），两个都要在整句查表之前试。
        String price = Glossary.priceRow(t);
        if (price != null) {
            return price;
        }
        price = Glossary.priceLine(t);
        if (price != null) {
            return price;
        }
        // 全大写的大写标签（UNCOMMON ORE）逐词拼，命中不了才继续往下查
        String composed = Glossary.composedLabel(t);
        if (composed != null) {
            return composed;
        }
        String hit = Glossary.exact(t);
        if (hit != null) {
            return hit;
        }
        // 分别取出行首 / 行尾的图标与箭头。计分板上「➜ Bazaar」「Mining ➜」
        // 要剥掉装饰符才能命中整句术语，但装饰符本身必须原样拼回译文 ——
        // 以前只返回纯译名，表现为「缓存没命中时原文有箭头，译文一到箭头就没了」。
        Matcher edge = EDGE_STRIP.matcher(t);
        String prefix = "";
        String suffix = "";
        while (edge.find()) {
            if (edge.start() == 0) {
                prefix = edge.group();
            } else {
                suffix = edge.group();
            }
        }
        if (prefix.length() + suffix.length() >= t.length()) {
            return null;   // 整行都是符号，没有可查的词
        }
        String core = t.substring(prefix.length(), t.length() - suffix.length()).trim();
        if (core.isEmpty() || core.equals(t)) {
            return null;
        }
        String term = Glossary.exact(core);
        if (term == null) {
            return null;
        }
        // 尾部冒号跟整句直出保持一致（「➜ Purse:」→「➜ 钱包：」）
        return prefix + term + suffix.replace(':', '：');
    }

    /** 行首/行尾的装饰符号（⛏ ⏣ 这类图标字体字符与标点） */
    private static final Pattern EDGE_SYMBOLS = Pattern.compile(
            "^[^A-Za-z0-9\\u4e00-\\u9fa5]+");

    /**
     * 能在本地确定译文的短行：日期行、以及「图标 + 术语」的地点行。
     * 命中就整行替换，零延迟、不进队列、也不会被模型啃成半截。
     */
    private static Component localQuickLine(TranslationManager manager, Component line) {
        String plain = SECTION.matcher(line.getString()).replaceAll("").trim();
        if (plain.isEmpty() || plain.length() > 40) {
            return null;
        }
        if (CJK.matcher(plain).find()) {
            return null;   // 已经是中文（含术语还原后的），不重复处理
        }
        // 1) 日期行
        Matcher d = DATE_LINE.matcher(plain);
        if (d.matches()) {
            String prefix = d.group(1) == null ? "" : d.group(1).toLowerCase(java.util.Locale.ROOT);
            String season = (prefix.isEmpty() ? "" : prefix + " ")
                    + d.group(2).toLowerCase(java.util.Locale.ROOT);
            String cn = SEASONS.get(season.trim());
            if (cn != null) {
                StringBuilder sb = new StringBuilder(cn).append(' ').append(d.group(3)).append('日');
                if (d.group(4) != null) {
                    sb.append("，第").append(d.group(4)).append('年');
                }
                return Component.literal(sb.toString()).withStyle(dominantStyle(line));
            }
        }
        // 2) 「⛏ Dwarven Mines」这种图标 + 术语
        if (!glossaryExactOn(manager)) {
            return null;
        }
        Matcher lead = EDGE_SYMBOLS.matcher(plain);
        String icon = lead.find() ? lead.group() : "";
        String core = plain.substring(icon.length()).trim();
        if (core.isEmpty()) {
            return null;
        }
        String term = Glossary.exact(core);
        if (term == null) {
            return null;
        }
        return Component.literal((icon.isEmpty() ? "" : icon + " ") + term).withStyle(dominantStyle(line));
    }

    /** 取「字最多的那段」的样式，整行替换时用它，避免颜色被拉成默认白 */
    private static Style dominantStyle(Component line) {
        Style[] best = {line.getStyle()};
        int[] bestLen = {-1};
        line.<Object>visit((style, text) -> {
            if (text.length() > bestLen[0]) {
                bestLen[0] = text.length();
                best[0] = style;
            }
            return Optional.empty();
        }, Style.EMPTY);
        return best[0];
    }

    private static boolean hasClickOrHover(Seg seg) {
        Style st = seg.style();
        return st.getClickEvent() != null || st.getHoverEvent() != null;
    }

    /**
     * 整句块译完重建组件时，原文行共有的「交互 + 字形」样式。
     *
     * <p>模型常把几个 {@code §l} 段合成一句译文，重建时若只落到首段样式，
     * 粗体就丢了（截图里 {@code >>> CLICK HERE <<<} 就是这么变细的）。
     * 点击/悬停事件也一样：Hypixel 的可点提示往往挂在其中一段上，
     * 译文按标记重新切段后，别的段就没事件了。这里把行级共有的样式
     * 补到每个输出段上，保证「原来整行能点，翻完还是整行能点」。
     */
    private static Style blockStyleBoost(List<Seg> segs, int from, int to) {
        Style acc = Style.EMPTY;
        int nonBlank = 0;
        int bold = 0;
        int italic = 0;
        for (int k = from; k <= to; k++) {
            Seg seg = segs.get(k);
            if (seg.raw().isBlank()) {
                continue;
            }
            nonBlank++;
            Style st = seg.runStyle();
            if (st.isBold()) {
                bold++;
            }
            if (st.isItalic()) {
                italic++;
            }
            if (st.getClickEvent() != null && acc.getClickEvent() == null) {
                acc = acc.withClickEvent(st.getClickEvent());
            }
            if (st.getHoverEvent() != null && acc.getHoverEvent() == null) {
                acc = acc.withHoverEvent(st.getHoverEvent());
            }
        }
        if (nonBlank == 0) {
            return Style.EMPTY;
        }
        if (bold * 2 >= nonBlank) {
            acc = acc.withBold(true);
        }
        if (italic * 2 >= nonBlank) {
            acc = acc.withItalic(true);
        }
        return acc;
    }

    /** 只补缺失的字段，不覆盖该段自己的颜色/事件 */
    private static Style boosted(Style base, Style boost) {
        Style out = base;
        if (boost.getClickEvent() != null && out.getClickEvent() == null) {
            out = out.withClickEvent(boost.getClickEvent());
        }
        if (boost.getHoverEvent() != null && out.getHoverEvent() == null) {
            out = out.withHoverEvent(boost.getHoverEvent());
        }
        if (boost.isBold() && !out.isBold()) {
            out = out.withBold(true);
        }
        if (boost.isItalic() && !out.isItalic()) {
            out = out.withItalic(true);
        }
        return out;
    }

    /**
     * 按原文段还原「前导空格」（{@code " <<<"} 这种）。
     * 尾随空格不在这里补：整句层的 {@link #restoreChatLayout} 已经负责箭头后的空格，
     * 两处都补会在 {@code ">>> "} 段上叠出双空格。
     */
    private static String edgeSpaces(String original, String translated) {
        if (original == null || original.isEmpty() || translated == null) {
            return translated;
        }
        int a = 0;
        while (a < original.length()
                && (original.charAt(a) == ' ' || original.charAt(a) == '\t')) {
            a++;
        }
        return a == 0 ? translated : original.substring(0, a) + translated;
    }

    /** 只压中间连续空格，首尾空白（居中/装饰排版）原样保留 */
    private static String squeezeSpaces(String s) {
        if (s == null) {
            return null;
        }
        int a = 0;
        int b = s.length();
        while (a < b && (s.charAt(a) == ' ' || s.charAt(a) == '\t')) {
            a++;
        }
        while (b > a && (s.charAt(b - 1) == ' ' || s.charAt(b - 1) == '\t')) {
            b--;
        }
        return s.substring(0, a) + s.substring(a, b).replaceAll("[ \\t]{2,}", " ")
                + s.substring(b);
    }

    /**
     * 中文（含全角标点，{@code ！？。，；：）】」}）后紧跟箭头时补回那一格空格。
     * 只写汉字范围会漏掉 {@code "！<<<"} —— 全角感叹号不在 CJK 统一表意文字里。
     */
    private static final Pattern DECOR_LEAD = Pattern.compile(
            "(?<=[\\u4e00-\\u9fa5\\u3001\\u3002\\u300d\\u3011"
                    + "\\uff01\\uff09\\uff0c\\uff1a\\uff1b\\uff1f])"
                    + "(<<<|>>>|<<|>>|«|»|➜|➤|⏣)");
    private static final Pattern DECOR_TAIL = Pattern.compile(
            "(<<<|>>>|<<|>>|«|»|➜|➤|⏣)(?=[\\u4e00-\\u9fa5])");

    /**
     * 还原聊天行的排版符号：模型爱把半角圆括号改成全角，也会把
     * {@code ">>> CLICK HERE <<<"} 挤成 {@code ">>>点击这里<<"}。
     * 只按原文的实际数量/符号补，不改动正常中文标点。
     */
    private static String restoreChatLayout(String original, String translated) {
        if (original == null || translated == null) {
            return translated;
        }
        String t = translated;
        int halfOpen = countChar(original, '(');
        int halfClose = countChar(original, ')');
        if (halfOpen > 0 && halfClose > 0
                && countChar(t, '（') == halfOpen && countChar(t, '）') == halfClose) {
            t = t.replace('（', '(').replace('）', ')');
        }
        t = DECOR_LEAD.matcher(t).replaceAll(" $1");
        t = DECOR_TAIL.matcher(t).replaceAll("$1 ");
        return squeezeSpaces(t);
    }

    private static int countChar(String s, char c) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == c) {
                n++;
            }
        }
        return n;
    }

    /** 分段标记：{@code [0]} {@code [1]} …（实测方括号数字最不容易被小模型改坏） */
    private static final Pattern SEG_MARK = Pattern.compile("\\[(\\d{1,2})\\]");
    /** 数字回填失败时标记会退化成本来的占位符 {@code [#]} */
    private static final Pattern SEG_MARK_BROKEN = Pattern.compile("\\[\\s*#\\s*\\]");

    /**
     * 多色行整句翻译：整句送给模型，译后按「术语/数字锚点 + 各段字数比例」把颜色分回去。
     *
     * <p>为什么不带 {@code [N]} 标记：实测这一行会被模型把标记吞掉几个
     * （{@code [1] of these to [2] Carolyn} → 只回 {@code [0]…[2]Carolyn}），
     * 而纯原文能给出通顺的整句。颜色靠 {@link #renderChatBlock} 重新分配，
     * 「绯红岛」这类术语中文名会按原段样式单独上色。
     *
     * @return 组好的组件；返回 null 表示这一行不该走整句（段太少 / 有可点击 / 太短太长），
     *         调用方继续走逐段逻辑
     */
    private static Component translateColoredLine(TranslationManager manager, List<Seg> segs,
                                                  boolean rateLimited, boolean chatPriority, int category) {
        if (segs.size() < 2) {
            return null;
        }
        // 以前这里有一条「有可点击/悬停的段就整行不翻」的早退，本意是保护传送链接的交互。
        // 但 Hypixel 的提示几乎每条都带点击事件（Carnival 的「[CLICK to warp…]」、
        // NPC 对话的可选项），结果整类消息永远停在英文。
        // 实际上点击/悬停事件挂在 Style 上，renderChatBlock 回填时按段原样保留，
        // 翻译文字不会让链接失效 —— 所以这里不再拦。
        StringBuilder sb = new StringBuilder(96);
        for (Seg seg : segs) {
            sb.append(seg.raw());
        }
        String text = sb.toString().trim();
        if (text.length() < 8 || text.length() > 600 || latinWordCount(text) < 2) {
            return null;
        }
        String translated = null;
        if (glossaryExactOn(manager)) {
            translated = lookupTerm(text);
        }
        if (translated == null) {
            translated = manager.cached(text);
        }
        if (translated == null || translated.isBlank() || translated.equals(text)) {
            // 还没译文：整行提交一次，并原样显示，避免下面逐段又各提交一遍
            if (translated == null && !manager.isShowingOriginal()) {
                submitBackground(manager, text, rateLimited, chatPriority, category);
                return renderRawBlock(segs, 0, segs.size() - 1);
            }
            return null;
        }
        String clean = cleanTranslation(text, translated);
        if (glossaryExactOn(manager)) {
            clean = Glossary.postfix(clean);
        }
        return renderChatBlock(manager, segs, 0, segs.size() - 1, clean);
    }

    /**
     * 把一个「整句块」当成一句话翻译，同时带上颜色分段标记。
     *
     * <p>整句送模型是为了语序正确；每段前面塞 {@code [数字]} 是为了让模型自己
     * 在原来的颜色边界断句，译文回来按标记切回各段，<b>每段还是原来的颜色</b>。
     * 实测 tyfy-hymt2:1.8b 对 {@code [0]} 这种方括号数字能 9/9 原样保留；
     * 万一标记被吃掉或数量不够，退回「按原段长度比例切分」的兜底（颜色分布仍是对的）。
     *
     * @return 译文组件；返回 null 表示这句还不值得/还没能整句翻
     */
    private static Component translateChatBlock(TranslationManager manager, List<Seg> segs,
                                                int from, int to, boolean submit, Style boost) {
        StringBuilder plainSb = new StringBuilder(96);
        for (int k = from; k <= to; k++) {
            plainSb.append(segs.get(k).raw());
        }
        String text = plainSb.toString().trim();
        // 放宽上限：以前 320 字 / 至少 3 个英文词的长段落会被挡回「逐组翻译」，
        // 每组又凑不够词数，最后整段只剩术语表换掉的几个中文词。请求层本来就会
        // 按 CHUNK_CHARS 切块送模型，这里不必再卡长度。
        if (text.length() < 12 || text.length() > 600 || latinWordCount(text) < 2) {
            return null;
        }
        if (!chatWorthTranslating(manager, text)) {
            return null;
        }

        // 带分段标记的请求文本：每段前面加 [序号]
        List<Integer> segIndex = new ArrayList<>();
        for (int k = from; k <= to; k++) {
            if (!segs.get(k).raw().isBlank()) {
                segIndex.add(k);
            }
        }
        // 逐字符着色（SkyHanni 的彩虹字/渐变色文本框）会把一句话切成几十个
        // 单字符段，每段挂一个 [n]：请求变成「[0][[1]S[2]k[3]y[4]H[5]a…」，
        // 光前缀就有 11 个标记，小模型必然把标记和内容搅乱、输出被截断，
        // 这条消息就永远翻不出来（日志里那几条全是这种）。
        // 碎段不加标记，直接送整句；译文回来后走 renderChatBlock 按原文长度
        // 比例回填，每段仍用自己原来的颜色，彩虹效果还在。
        int singleCharRuns = 0;
        for (int k : segIndex) {
            if (segs.get(k).raw().length() <= 1) {
                singleCharRuns++;
            }
        }
        // 段数多时同样不加标记：SkyHanni 的多色长提示常有 8~12 段，
        // "[0][1]…[9]" 这种请求会让 0.8B 小模型把标记和正文搅在一起，
        // 输出被 looksMangled 判坏 → 不写缓存 → 整行永远停在英文。
        // 送干净整句，译文回来按原段长度比例切回去，颜色分布仍保持原样。
        boolean tooFragmented = (singleCharRuns >= 6 && singleCharRuns * 2 >= segIndex.size())
                || segIndex.size() > 6;
        StringBuilder marked = new StringBuilder(text.length() + 24);
        int mark = 0;
        for (int k : segIndex) {
            String raw = segs.get(k).raw();
            marked.append(tooFragmented ? raw : "[" + (mark++) + "]" + raw);
        }

        String key = tooFragmented ? text : marked.toString();
        String translated = null;
        if (glossaryExactOn(manager)) {
            translated = lookupTerm(key);
        }
        if (translated == null) {
            translated = manager.cached(key);
        }
        // 没有带标记的译文时，退回不带标记的整句缓存（老数据）
        boolean fromMarked = translated != null;
        if (translated == null) {
            if (glossaryExactOn(manager)) {
                translated = lookupTerm(text);
            }
            if (translated == null) {
                translated = manager.cached(text);
            }
        }
        if (translated != null && !translated.isBlank()
                && !translated.equals(key) && !translated.equals(text)) {
            String clean = cleanTranslation(text, translated);
            if (glossaryExactOn(manager)) {
                // 同上：整句译完再把残留的英文术语换成固定译名
                clean = Glossary.postfix(clean);
            }
            if (fromMarked) {
                Component byMarkers = renderSegmented(segs, segIndex, clean, boost);
                if (byMarkers != null) {
                    return byMarkers;
                }
            }
            // 标记没保住（或命中老缓存）：按原段长度比例切分兜底，颜色分布还是原来的
            return renderChatBlock(manager, segs, from, to, clean, boost);
        }
        // 还没译文：整块提交一次，别再逐段各提交一次（那会翻成两截，屏幕上夹生）。
        // 渲染刷新（submit=false）以前只查缓存、绝不补提交：首次请求一旦因服务重启、
        // 超时或模型输出判坏而丢失，这一行就永远停在英文，按 V 也要等消息重复出现
        // 才碰巧重试 —— 聊天栏反复漏翻、修几次都只改链路内部，根子就在这里。
        // 现在给渲染路径留一条 8 秒节流的自救通道；submitTranslate 自带 pending
        // 去重与失败冷却，不会每帧刷请求。
        if (!manager.isShowingOriginal()) {
            if (submit) {
                manager.translateChatInBackground(key);
            } else {
                manager.resubmitChatIfDue(key);
            }
        }
        return submit ? renderRawBlock(segs, from, to) : null;
    }

    /**
     * 按译文里的 {@code [数字]} 标记把句子切回原来的各段，每段用它自己的样式。
     * 标记数不足 2 个时返回 null，调用方走比例切分兜底。
     */
    private static Component renderSegmented(List<Seg> segs, List<Integer> segIndex, String translated) {
        return renderSegmented(segs, segIndex, translated, Style.EMPTY);
    }

    private static Component renderSegmented(List<Seg> segs, List<Integer> segIndex,
                                             String translated, Style boost) {
        Matcher m = SEG_MARK.matcher(translated);
        List<int[]> marks = new ArrayList<>();   // {序号, 文本起点, 标记起点}
        while (m.find()) {
            marks.add(new int[]{Integer.parseInt(m.group(1)), m.end(), m.start()});
        }
        if (marks.size() < 2) {
            return null;
        }
        MutableComponent out = Component.empty();
        // 第一个标记之前若有多余文字，按第一段样式输出
        String head = translated.substring(0, marks.get(0)[2]).trim();
        if (!head.isEmpty()) {
            out.append(Component.literal(head)
                    .withStyle(boosted(styleOf(segs, segIndex, marks.get(0)[0]), boost)));
        }
        for (int i = 0; i < marks.size(); i++) {
            int textStart = marks.get(i)[1];
            int textEnd = (i + 1 < marks.size()) ? marks.get(i + 1)[2] : translated.length();
            if (textEnd <= textStart) {
                continue;
            }
            String piece = translated.substring(textStart, textEnd).trim();
            if (piece.isEmpty()) {
                continue;
            }
            int mark = marks.get(i)[0];
            // 按原文段还原首尾空格：{@code ">>> "} / {@code " <<<"} 里的那格空格
            // 是原消息的排版，之前被 trim 掉后箭头和中文就挤在一起了。
            int at = Math.max(0, Math.min(mark, segIndex.size() - 1));
            piece = squeezeSpaces(edgeSpaces(segs.get(segIndex.get(at)).raw(), piece));
            out.append(Component.literal(piece)
                    .withStyle(boosted(styleOf(segs, segIndex, mark), boost)));
        }
        return out;
    }

    /** 标记序号 → 原段的样式；越界时退回最后一段 */
    private static Style styleOf(List<Seg> segs, List<Integer> segIndex, int mark) {
        int pos = Math.max(0, Math.min(mark, segIndex.size() - 1));
        return segs.get(segIndex.get(pos)).runStyle();
    }

    /** 还没译文时：原样输出，但用整块替换掉逐组逻辑，避免重复提交 */
    private static Component renderRawBlock(List<Seg> segs, int from, int to) {
        MutableComponent out = Component.empty();
        for (int k = from; k <= to; k++) {
            Seg seg = segs.get(k);
            out.append(Component.literal(seg.codes() + seg.raw()).withStyle(seg.style()));
        }
        return out;
    }

    /**
     * 整句译文重新分段上色：<b>保持原来每段各自的颜色</b>，不做统一染色。
     *
     * <p>做法：先找出译文里能精确对上的「锚点」（术语的中文名、纯数字），
     * 锚点用原段的样式；锚点之间的文字按各原段字符数**按比例**切开，
     * 每一截用它自己那段的样式。这样原文是「白字 + 蓝物品名 + 白字 + 红数字」，
     * 译文就还是这个配色分布，只是句子连成通了。
     */
    private static Component renderChatBlock(TranslationManager manager, List<Seg> segs,
                                             int from, int to, String translated) {
        return renderChatBlock(manager, segs, from, to, translated, Style.EMPTY);
    }

    private static Component renderChatBlock(TranslationManager manager, List<Seg> segs,
                                             int from, int to, String translated, Style boost) {
        // 标记没保住时可能残留 [3] / [#] 这类碎片，先清掉再按比例切
        translated = SEG_MARK.matcher(translated).replaceAll("");
        translated = SEG_MARK_BROKEN.matcher(translated).replaceAll("");
        // 1) 找锚点：术语中文名 / 纯数字，按译文中的出现顺序排好
        List<Integer> anchorSegs = new ArrayList<>();
        List<Integer> anchorPos = new ArrayList<>();
        List<String> anchorText = new ArrayList<>();
        int cursor = 0;
        for (int k = from; k <= to; k++) {
            String needle = blockNeedle(manager, segs.get(k), translated);
            if (needle == null) {
                continue;
            }
            int at = translated.indexOf(needle, cursor);
            if (at < 0) {
                continue;
            }
            anchorSegs.add(k);
            anchorPos.add(at);
            anchorText.add(needle);
            cursor = at + needle.length();
        }

        MutableComponent out = Component.empty();
        int textPos = 0;
        int segFrom = from;
        for (int a = 0; a <= anchorSegs.size(); a++) {
            boolean hasAnchor = a < anchorSegs.size();
            int anchorSeg = hasAnchor ? anchorSegs.get(a) : -1;
            int anchorAt = hasAnchor ? anchorPos.get(a) : translated.length();
            int gapTo = hasAnchor ? anchorSeg - 1 : to;
            String gap = translated.substring(textPos, Math.max(textPos, anchorAt));
            appendProportional(out, segs, segFrom, gapTo, gap, boost);
            if (hasAnchor) {
                String needle = anchorText.get(a);
                out.append(Component.literal(needle)
                        .withStyle(boosted(segs.get(anchorSeg).runStyle(), boost)));
                textPos = anchorAt + needle.length();
                segFrom = anchorSeg + 1;
            }
        }
        return out;
    }

    /** 这段原文在译文里能精确对上的中文/数字；对不上返回 null */
    private static String blockNeedle(TranslationManager manager, Seg seg, String translated) {
        String raw = seg.raw().trim();
        if (raw.isEmpty()) {
            return null;
        }
        if (raw.matches("\\d[\\d,.]*")) {
            return translated.contains(raw) ? raw : null;
        }
        if (!glossaryExactOn(manager)) {
            return null;
        }
        String cn = Glossary.exact(raw);
        if (cn == null || cn.length() < 2) {
            return null;
        }
        return translated.contains(cn) ? cn : null;
    }

    /** 把一段文字按各原段的字符数比例切开，每截用它自己那段的样式 */
    private static void appendProportional(MutableComponent out, List<Seg> segs,
                                           int from, int to, String gap, Style boost) {
        if (gap.isEmpty() || from > to) {
            return;
        }
        int total = 0;
        for (int k = from; k <= to; k++) {
            total += segs.get(k).raw().length();
        }
        if (total <= 0) {
            out.append(Component.literal(gap).withStyle(boost));
            return;
        }
        int consumed = 0;
        int accRaw = 0;
        for (int k = from; k <= to; k++) {
            int len = segs.get(k).raw().length();
            if (len <= 0) {
                continue;
            }
            accRaw += len;
            int end = (int) Math.round((double) gap.length() * accRaw / total);
            end = Math.max(end, consumed);
            end = Math.min(end, gap.length());
            if (end > consumed) {
                out.append(Component.literal(gap.substring(consumed, end))
                        .withStyle(boosted(segs.get(k).runStyle(), boost)));
                consumed = end;
            }
        }
        if (consumed < gap.length()) {
            out.append(Component.literal(gap.substring(consumed))
                    .withStyle(boosted(segs.get(to).runStyle(), boost)));
        }
    }

    /**
     * 聊天消息统一入口：逐段提交后台翻译，并包成「原文 ↔ 译文」可切换组件。
     *
     * <p>首次收到时缓存里没译文，组件先显示原文；后台翻完写缓存后会通知聊天栏
     * 重新排版，同一条消息原地变成中文 —— 不新增消息、不重复刷屏、不丢颜色。
     * 早期版本缺的就是这一步，所以只能靠消息重复出现时碰巧命中缓存。
     */
    /** 最近收到的聊天行（原始组件），只在内存里留 24 条，供 /tyfy chat 诊断 */
    private static final java.util.ArrayDeque<Component> RECENT_CHAT = new java.util.ArrayDeque<>();

    private static void rememberChat(Component line) {
        synchronized (RECENT_CHAT) {
            RECENT_CHAT.addLast(line);
            while (RECENT_CHAT.size() > 24) {
                RECENT_CHAT.removeFirst();
            }
        }
    }

    /**
     * 诊断用：把最近收到的聊天行逐条摊开 —— 原文、译文、以及「为什么没翻」。
     * 计分板有 /tyfy board，聊天只能这样当场导出，否则只能靠猜。
     */
    public static List<String> dumpRecentChat() {
        List<String> out = new ArrayList<>();
        List<Component> snapshot;
        synchronized (RECENT_CHAT) {
            snapshot = new ArrayList<>(RECENT_CHAT);
        }
        TranslationManager manager = manager();
        int i = 0;
        for (Component raw : snapshot) {
            i++;
            String plain = raw.getString().replace('\u00a7', '&').replace('\n', ' ');
            String shown = manager == null ? plain
                    : translateChatSegments(manager, raw, false).getString().replace('\u00a7', '&');
            String why = whySkipped(raw.getString());
            boolean done = !plain.equals(shown);
            out.add((done ? "§a✔ §f" : "§c✘ §f") + (plain.length() > 90 ? plain.substring(0, 90) + "…" : plain)
                    + (done ? " §7→ §f" + (shown.length() > 90 ? shown.substring(0, 90) + "…" : shown)
                    : " §7[" + (why != null ? why : "未命中缓存/被判失败") + "]"));
        }
        return out;
    }

    private static long chatSkipLogAt;
    private static int chatSkipLogCount;

    /** 漏翻取证：被判「不值得翻」而原样放行的聊天行，10 秒最多记 5 条 */
    private static void logChatSkip(String body) {
        if (body == null || body.length() < 8 || !LATIN_WORD_ANY.matcher(body).find()) {
            return;
        }
        long now = System.currentTimeMillis();
        synchronized (TranslationHelper.class) {
            if (now - chatSkipLogAt > 10_000L) {
                chatSkipLogAt = now;
                chatSkipLogCount = 0;
            }
            if (chatSkipLogCount >= 5) {
                return;
            }
            chatSkipLogCount++;
            LOG.info("聊天行未包装（不会翻译）: {}",
                    body.length() > 120 ? body.substring(0, 120) + "…" : body);
        }
    }

    public static Component wrapChatLine(Component line) {
        if (line == null) {
            return null;
        }
        TranslationManager manager = manager();
        if (manager == null || !manager.config().enabled) {
            return line;
        }
        rememberChat(line);
        String[] parts = chatParts(manager, line);
        // 不值得翻的行直接原样返回：调用方据此放行原消息，避免无谓地压住/补发
        if (parts == null || !chatWorthTranslating(manager, parts[1])) {
            logChatSkip(parts == null ? "" : parts[1]);
            return line;
        }
        if (!manager.isShowingOriginal()) {
            // 逐段提交（段文本就是缓存键，与 translateChatSegments 查询时一致）
            translateChatSegments(manager, line, true);
        }
        return new BilingualComponent(line, () -> translateChatSegments(manager, line, false));
    }

    /**
     * 从「显示原文」切回译文时补提交一次。
     *
     * <p>原文模式期间到达的消息在 {@link #wrapChatLine} 里根本没提交过翻译，
     * 而 {@link BilingualComponent} 的 supplier 只查缓存（submit=false）。
     * 于是切回来之后这些消息会一直显示英文，看起来像「随机有的翻有的没翻」。
     * 这里把最近 24 条重新走一遍提交路径；缓存已命中的会被跳过，
     * 未命中的才入队，且 {@code translateChatAsync} 自带 pending 去重。
     */
    public static void resubmitRecentChat() {
        TranslationManager manager = manager();
        if (manager == null || !manager.config().enabled) {
            return;
        }
        List<Component> snapshot;
        synchronized (RECENT_CHAT) {
            snapshot = new ArrayList<>(RECENT_CHAT);
        }
        for (Component raw : snapshot) {
            String[] parts = chatParts(manager, raw);
            if (parts == null || !chatWorthTranslating(manager, parts[1])) {
                continue;
            }
            translateChatSegments(manager, raw, true);
        }
    }

    /**
     * 中英混排时用来找「连续英文片段」。以前要求至少两个英文词，
     * 结果 tooltip 里跨行断开的单个介词 / 冠词（into、of、the）永远抽不出来，
     * 译文里就一直留着英文。改成允许单个词，URL 与玩家 ID 由下游过滤。
     */
    private static final Pattern LATIN_SPAN = Pattern.compile(
            "[A-Za-z][A-Za-z'’,.!?-]*(?:\\s+[A-Za-z0-9'’,.!?-]+)*");

    /**
     * 只翻译文本里的英文片段，中文原样保留。
     * 命中的走缓存直接替换，未命中的提交后台、本次先留原文。
     */
    private static String translateLatinSpans(TranslationManager manager, String text,
                                              boolean rateLimited, boolean chatPriority, int category) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        Matcher m = LATIN_SPAN.matcher(text);
        StringBuilder sb = new StringBuilder(text.length());
        int last = 0;
        boolean changed = false;
        while (m.find()) {
            String span = m.group();
            // 单个介词 / 冠词也要翻，但这里必须重新过一遍完整过滤：
            // 上一轮放宽到「允许单个词」后，CH / gt / wCH / [VIP 这类装饰碎片
            // 绕过了 shouldTranslate 直接进队列，1.8B 会不停续写拖到 3~6 秒。
            if (span.length() < 4 || URL.matcher(span).find()
                    || !LATIN_WORD_ANY.matcher(span).find()
                    || span.matches(".*[\\[\\]§#/].*")
                    || !shouldTranslate(manager, span)
                    || (manager.config().smartTranslate && isPlayerName(span))) {
                continue;
            }
            sb.append(text, last, m.start());
            last = m.end();
            String cached = manager.cached(span);
            if (cached != null) {
                sb.append(cleanTranslation(span, cached));
                changed = true;
            } else {
                submitBackground(manager, span, rateLimited, chatPriority, category);
                sb.append(span);
            }
        }
        if (!changed) {
            return text;
        }
        sb.append(text.substring(last));
        return sb.toString();
    }

    /**
     * 诊断用：说明这段文字为什么不会显示译文（返回 null 表示「没被规则拦下」，
     * 只是还没翻出来 / 正在排队）。/tyfy board 用它给计分板每行标注原因。
     */
    public static String whySkipped(String text) {
        TranslationManager manager = manager();
        if (manager == null) {
            return "管理器未就绪";
        }
        if (!manager.config().enabled) {
            return "总开关关闭";
        }
        if (manager.isShowingOriginal()) {
            return "当前显示原文（按切换键可切回译文）";
        }
        if (text == null || text.isBlank()) {
            return "空行";
        }
        String t = SECTION.matcher(text).replaceAll("").trim();
        if (CJK.matcher(t).find()) {
            return "已含中文";
        }
        if (!hasForeignScript(t)) {
            return "没有可译的外语（既没有英文单词，也没有假名/谚文等）";
        }
        if (manager.config().smartTranslate) {
            if (isPlayerName(t)) {
                return "判定为玩家名";
            }
            if (isRankedName(t)) {
                return "判定为称号+玩家名";
            }
            if (URL_LIKE.matcher(t).matches()) {
                return "整行是网址";
            }
            if (TIME_ONLY.matcher(t).matches()) {
                return "只有游戏时间";
            }
            if (t.length() < Math.max(3, manager.config().minLength)) {
                return "太短";
            }
            if (t.startsWith("[") && !t.contains("]")) {
                return "残缺标签（[VIP 这种）";
            }
            if (t.startsWith("/")) {
                return "以 / 开头（命令）";
            }
        }
        return null;
    }

    /**
     * 「智能翻译」关掉时的唯一规则：文本里有连续两个以上英文字母就送模型，
     * 不再区分玩家名、称号、时间、网址、装饰符号。返回 false 表示交给后面的智能规则判断。
     */
    private static boolean rawEnglishWanted(String text) {
        if (text == null) {
            return false;
        }
        String t = text.trim();
        if (t.length() < 2) {
            return false;
        }
        return hasForeignScript(t);
    }

    private static boolean shouldTranslate(TranslationManager manager, String plain) {
        if (plain == null || plain.isBlank()) {
            return false;
        }
        // 智能翻译关掉：不算「是不是玩家名/时间/网址/装饰符号」，只要带英文就翻
        if (!manager.config().smartTranslate) {
            return rawEnglishWanted(plain);
        }
        // 整行就是网址/域名（含 Hypixel 折行截断的 "www.hypixel.ne"）：
        // 送模型只会照抄回来、判失败后重试到放弃，没有可译内容。
        if (URL_LIKE.matcher(plain.trim()).matches()) {
            return false;
        }
        // 含网址：把网址剥掉再看剩下的内容。剩下的够长、有两个以上英文词就照翻，
        // 网址本身由翻译器用 <uN> 占位符保护，模型改不到它。
        String body = plain;
        if (URL.matcher(plain).find()) {
            body = URL.matcher(plain).replaceAll(" ").trim();
            if (latinWordCount(body) < 2) {
                return false;
            }
        }
        if (body.length() < manager.config().minLength) {
            return false;
        }
        // 必须有可译内容：英文单词，或假名/谚文/西里尔这类非拉丁文字
        // （日语没有英文字母，原来这条把整行日语都挡掉了）
        if (!hasForeignScript(body)) {
            return false;
        }
        String trimmed = body.trim();
        // 游戏内时间（6:20am / 12:30pm）翻成「上午6:20」没有意义，而且每 10 分钟
        // 就是一个新字符串，白白占队列。
        if (TIME_ONLY.matcher(trimmed).matches()) {
            return false;
        }
        // "[VIP" "[MVP+" 这类被颜色码/宽度截断的残缺标签，没有可译语义
        if ((trimmed.startsWith("[") && !trimmed.contains("]"))
                || (trimmed.startsWith("(") && !trimmed.contains(")"))) {
            return false;
        }
        // Tab 列表/头顶的「称号 + 玩家名」（[VIP] EndPainting）：整行没有可译内容，
        // 送模型会把名字翻成「结束绘画」这种笑话。
        if (RANKED_NAME.matcher(trimmed).matches()) {
            return false;
        }
        // 单个词只翻术语表里有的（Dungeon / Minion …）。
        // 否则计分板、头顶名、队伍里的玩家 ID（CornyGoose、OYpotato）会全部被送去翻译，
        // 翻不出来还要重试 5 次，直接把队列塞满、互相超时。
        if (trimmed.indexOf(' ') < 0 && SINGLE_WORD.matcher(trimmed).matches()
                && Glossary.exact(trimmed) == null
                && !manager.config().translateSingleWords
                && isPlayerName(trimmed)) {
            // 默认只挡玩家 ID；普通单词（Purse/Bits/Objective/Skill/Level…）、
            // 术语和地名都照常翻。玩家 ID 已经在 submitTranslate 里用在线玩家列表
            // 精确过滤，不需要再靠「所有单个词一律不翻」来防队列堵塞 —— 那条老规则
            // 正是计分板大面积漏翻的原因。开关打开时连玩家 ID 也翻（标签即「含人名」）。
            return false;
        }
        // 日语/韩语里也大量用汉字（日本語、学校…），有汉字不等于就是中文
        if (manager.config().skipIfContainsChinese && CJK.matcher(body).find()
                && !OTHER_SCRIPT.matcher(body).find()) {
            // 「12 年 Hypixel 老兵」这种本来就基本是中文的句子，送模型只会被原样吐回，
            // 连续 5 次失败后进 30 分钟冷却，还白占队列。
            return false;
        }
        return !body.startsWith("/");
    }

    /** 纯游戏时间，如 6:20am / 12:30pm / 6:20 */
    private static final Pattern TIME_ONLY = Pattern.compile("\\d{1,2}:\\d{2}\\s*(?i:am|pm)?");
    /**
     * 「[VIP] Steve」「[MVP++] Alex」「[335] WIZARD_ALVIN」：称号/等级 + 玩家名，整行不该翻译。
     *
     * <p>括号里原来只认字母（VIP / MVP+），于是 Hypixel 计分板里更常见的
     * 「[335] 玩家名」全被漏掉 —— 一晚上能把同一行送进模型上百次，
     * 每次都原样返回、判失败、60 秒后再来一次。现在括号里允许数字等级，
     * 名字后面也允许跟一两个装饰符号（α / ☘♲ / 🐸）或队伍标签（[FLOOF] / [❤]）。
     */
    private static final Pattern RANKED_NAME = Pattern.compile(
            "^\\[[A-Za-z0-9+]{1,12}\\]\\s*[A-Za-z0-9_]{2,20}\\s*"
                    + "(\\[[^\\]]{1,16}\\]|[^\\p{L}\\p{Nd}]{1,3})?$");
    /**
     * 像网址/域名的片段（含被 Hypixel 折行截断的 {@code www.hypixel.ne}）。
     * 这些送模型只会照抄回来、判失败后重试到放弃，纯粹浪费队列。
     */
    private static final Pattern URL_LIKE = Pattern.compile(
            "(?i)(https?://|www\\.)[^\\s]*|[\\w-]+(\\.[\\w-]+)+[^\\s]*");

    /** 给缓存层用：这类整行译文（[VIP] 我在这里）是错的，读取时当未命中 */
    public static boolean isRankedName(String text) {
        return text != null && RANKED_NAME.matcher(text.trim()).matches();
    }
    /** 单个「词」：字母数字下划线撇号连字符，2-24 位 */
    private static final Pattern LATIN_WORD_ANY = Pattern.compile("[A-Za-z]{2,}");
    /** 单个「词」：字母数字下划线撇号连字符，2-24 位 */
    private static final Pattern SINGLE_WORD = Pattern.compile("[A-Za-z0-9_'’-]{2,24}");

    private static final Pattern LATIN_WORD = Pattern.compile("[A-Za-z]{2,}");

    private static int latinWordCount(String s) {
        Matcher m = LATIN_WORD.matcher(s);
        int n = 0;
        while (m.find()) {
            n++;
        }
        return n;
    }
}
