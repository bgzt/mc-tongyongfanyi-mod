package com.tongyongfanyi.mixin;

import com.tongyongfanyi.translate.TranslationHelper;
import com.tongyongfanyi.translate.TranslationManager;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
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
import java.util.regex.Pattern;

/**
 * 物品悬停提示 (tooltip) 翻译。
 * 只影响客户端显示，不修改物品栈、不发任何包，传回服务器的内容完全不变。
 *
 * <p>关键点：Hypixel 的物品描述常常是一整段话（flavor text）被折成 3~4 行。
 * 老写法逐行送模型，每行都只有半句话，模型只能逐词直译 ——
 * 成品就是「这把剑是 由 火焰 锻造」这种断句。所以这里先把
 * <b>明显是同一段话的连续行拼回一句</b>再翻，译完按宽度重新折行。
 *
 * <p>判定「是同一段话」只认强信号：上一行没结束标点 + 下一行小写开头
 * （英文折行断在词边界，续行基本是小写），或者上一行以逗号/连字符结尾。
 * 同色、无点击/悬停事件才合并，属性行（{@code Damage: +100}）一律排除。
 */
@Mixin(ItemStack.class)
public abstract class ItemStackMixin {

    /** 一段话最多合并几行，防止把整屏文字拼成一个巨型请求 */
    private static final int MAX_PARAGRAPH_LINES = 6;
    /** 合并后的最大字符数：再长就会被请求层切块，切在句中间反而更乱 */
    private static final int MAX_PARAGRAPH_CHARS = 320;
    /** 合并后重新折行的宽度：英文=1 单位、中日韩=2 单位，44 ≈ 22 个汉字 ≈ 200px */
    private static final int PARAGRAPH_LINE_UNITS = 44;
    /** 老规则：单行译文超过这个字符数才折行 */
    private static final int SINGLE_LINE_MAX = 30;

    /**
     * 上一行停在一个「不可能收尾」的功能词上：说明折行把词组/句子从中间劈开了。
     *
     * <p>Hypixel 的紫色专名 {@code The End} 常被折成 {@code "... found on The"}
     * + {@code "End! Items you pickup ..."}，续行是大写开头，老规则只认小写开头，
     * 于是第一行被单独送模型。1.8B 看到句尾孤零零的 {@code The} 就会当成
     * 书名/作品名的开头，补出一对空书名号 {@code 《》}（实测缓存里有两条）。
     */
    private static final Pattern DANGLING_TAIL = Pattern.compile(
            "(?i).*\\b(?:the|a|an|of|to|in|on|into|onto|for|from|with|and|or|by|at"
                    + "|is|are|your|my|their|its|per|via|than|that|which|who)\\s*$");

    /**
     * 1 秒内出现了多少个「不同的物品栈」：超过阈值就说明是模组在批量扫描，
     * 不是玩家在悬停。
     *
     * <p>为什么光靠线程判断挡不住：SkyHanni / skyblocker 进服时会把 NEU 仓库
     * 几千件物品各取一次 tooltip 解析数据（价格、属性、搜索索引），这些调用
     * <b>就发生在渲染线程上</b>，所以线程判断对它们无效，一次登录照样刷出
     * 五千多条 lore，把队列占满十几分钟。
     *
     * <p>用「不同物品栈的个数」区分：玩家悬停时每帧重复同一个栈，1 秒内只有一两个；
     * 批量扫描时每件都不一样，瞬间就累积到几十个。
     *
     * <p>被跳过的文本不会永久丢失 —— 玩家真正悬停时会重新走这里（缓存没命中），
     * 那时每秒只有一两件，正常翻。
     */
    private static final Object SCAN_LOCK = new Object();
    private static long scanWindowStart;
    private static final java.util.Set<Integer> scanSeen = new java.util.HashSet<>();
    private static boolean scanBatching;

    private static boolean tyfy$isBulkScan(ItemStack stack) {
        long now = System.currentTimeMillis();
        synchronized (SCAN_LOCK) {
            if (now - scanWindowStart >= 1000L) {
                scanWindowStart = now;
                scanSeen.clear();
                scanBatching = false;
            }
            if (scanBatching) {
                return true;
            }
            scanSeen.add(System.identityHashCode(stack));
            if (scanSeen.size() > 40) {
                scanBatching = true;
                return true;
            }
            return false;
        }
    }

    /**
     * 进入 tooltip 构建：这期间提交的翻译全部插到队头。
     *
     * <p>{@code getTooltipLines} 只在「真的要显示提示框」时才被调用（原版
     * {@code Gui.extractTooltip} 和第三方模组的悬停渲染都会走它），所以这是
     * 「玩家鼠标正指着这件物品」最可靠的信号。计分板和背包格子里的文字虽然
     * 也在排队，但玩家此刻没在看它们。
     */
    @Inject(method = "getTooltipLines", at = @At("HEAD"))
    private void tyfy$hoverBegin(Item.TooltipContext context, Player player, TooltipFlag flag,
                                 CallbackInfoReturnable<List<Component>> cir) {
        TranslationHelper.beginHoverTooltip();
    }

    /**
     * 离开 tooltip 构建：恢复正常优先级。
     *
     * <p>挂在 RETURN 上会覆盖方法里所有 return 指令（包括「开关关掉」那几条早退），
     * 所以标志不会漏关；万一有异常路径，{@link TranslationHelper#hoverBoost()}
     * 还有 300ms 超时兜底。
     */
    @Inject(method = "getTooltipLines", at = @At("RETURN"))
    private void tyfy$hoverEnd(Item.TooltipContext context, Player player, TooltipFlag flag,
                               CallbackInfoReturnable<List<Component>> cir) {
        TranslationHelper.endHoverTooltip();
    }

    @Inject(method = "getTooltipLines", at = @At("RETURN"), cancellable = true)
    private void tyfy$translateTooltip(Item.TooltipContext context, Player player, TooltipFlag flag,
                                       CallbackInfoReturnable<List<Component>> cir) {
        /*
         * 只在渲染线程翻。
         *
         * SkyHanni / skyblocker 启动时会在自己的线程里把 NEU 仓库的几千件物品
         * 各取一次 tooltip，用来解析价格、属性、搜索索引 —— 这些结果不会显示给玩家。
         * 以前它们照样触发翻译，一次登录能刷出九千多条 lore（实测堆里 9238 个缓存键），
         * 单路串行要翻十几分钟，玩家真正悬停的那几行反而排在后面。
         *
         * 渲染线程才是「玩家要看这个 tooltip」的唯一场景：原版悬停渲染、REI 悬停预览
         * 都在渲染线程。后台读数据只需要英文字符串，跳过翻译不影响它们解析。
         */
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc != null && Thread.currentThread() != mc.getRunningThread()) {
            return;
        }
        if (tyfy$isBulkScan((ItemStack) (Object) this)) {
            return;   // 模组在批量扫描物品，不是在悬停
        }
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
        int i = 0;
        while (i < lines.size()) {
            // 先试着往后找「同一段话」：找到就整段一次翻，找不到就走逐行老逻辑
            int end = i;
            if (manager.config().mergeWholeLine) {
                int acc = lines.get(i).getString().trim().length();
                while (end + 1 < lines.size() && end + 1 - i < MAX_PARAGRAPH_LINES) {
                    String next = lines.get(end + 1).getString().trim();
                    // 超过 320 字就不合了：请求本身会再被切块，切在句中间语序照样会乱
                    if (acc + 1 + next.length() > MAX_PARAGRAPH_CHARS) {
                        break;
                    }
                    if (!tyfy$continues(lines.get(end), lines.get(end + 1))) {
                        break;
                    }
                    end++;
                    acc += 1 + next.length();
                }
            }
            if (end > i) {
                List<Component> merged = tyfy$translateParagraph(lines, i, end);
                if (merged != null) {
                    result.addAll(merged);
                    changed = true;
                    i = end + 1;
                    continue;
                }
                // 译文还没到：整段原样显示，等下一帧缓存命中再换，不要在中间夹半句中文
                for (int k = i; k <= end; k++) {
                    result.add(lines.get(k));
                }
                i = end + 1;
                continue;
            }

            Component translated = TranslationHelper.translateTooltip(lines.get(i));
            if (translated != lines.get(i)) {
                changed = true;
                // 长译文自动换行：拆成多行，避免一行过长顶出提示框/被截断
                String text = translated.getString();
                if (text.length() > SINGLE_LINE_MAX) {
                    for (String seg : wrapText(text, SINGLE_LINE_MAX)) {
                        result.add(Component.literal(seg).withStyle(translated.getStyle()));
                    }
                    i++;
                    continue;
                }
            }
            result.add(translated);
            i++;
        }
        if (changed) {
            cir.setReturnValue(result);
        }
    }

    /**
     * 把 [from, to] 这几行拼成一句话翻译，成功则返回折好的行。
     * 返回 null 表示「还不能合并」——译文未命中、或有行是空的，交给调用方原样显示。
     */
    private static List<Component> tyfy$translateParagraph(List<Component> lines, int from, int to) {
        StringBuilder sb = new StringBuilder(160);
        for (int k = from; k <= to; k++) {
            String t = lines.get(k).getString().trim();
            if (t.isEmpty()) {
                return null;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(t);
        }
        String text = sb.toString();
        Component probe = Component.literal(text);
        Component translated = TranslationHelper.translateTooltip(probe);
        // 未命中时 translateLine 原样返回入参，这一帧先按原文排，后台翻完自然就换上了
        if (translated == null || translated == probe) {
            return null;
        }
        String out = translated.getString();
        if (out.equals(text)) {
            return null;
        }
        Style style = tyfy$dominantStyle(lines, from, to);
        List<Component> wrapped = new ArrayList<>();
        for (String seg : tyfy$wrapCjk(out, PARAGRAPH_LINE_UNITS)) {
            wrapped.add(Component.literal(seg).withStyle(style));
        }
        return wrapped.isEmpty() ? null : wrapped;
    }

    /**
     * 取这几行里「字数最多的那一截」的样式：段首的彩色标签（{@code Foraging!}）
     * 不该把整段译文染成亮色，正文的灰色才是主体颜色。
     */
    private static Style tyfy$dominantStyle(List<Component> lines, int from, int to) {
        Style[] best = {lines.get(from).getStyle()};
        int[] bestLen = {-1};
        for (int k = from; k <= to; k++) {
            lines.get(k).<Object>visit((style, text) -> {
                if (text.length() > bestLen[0]) {
                    bestLen[0] = text.length();
                    best[0] = style;
                }
                return java.util.Optional.empty();
            }, Style.EMPTY);
        }
        return best[0];
    }

    /** 两行是不是同一段话被折开的两截（只认强信号，宁可不合并也别错合并） */
    private static boolean tyfy$continues(Component prev, Component next) {
        String p = prev.getString().trim();
        String n = next.getString().trim();
        if (p.isEmpty() || n.isEmpty()) {
            return false;
        }
        // 已经是中文了，说明这行翻过或本来就是中文，别再拼
        if (tyfy$hasCjk(p) || tyfy$hasCjk(n)) {
            return false;
        }
        Style ps = prev.getStyle();
        Style ns = next.getStyle();
        if (ps.getClickEvent() != null || ps.getHoverEvent() != null
                || ns.getClickEvent() != null || ns.getHoverEvent() != null) {
            return false;   // 带按钮/提示的行不碰，免得把可点区域合并掉
        }
        // 不再要求两行颜色完全一致：Hypixel 爱在段首贴一个彩色标签
        // （「Foraging!」「Mining!」是绿色，后面的正文是灰色），旧规则一比样式就把
        // 同一句话的续行拆成两截。模型只看到「Items you pickup go」半句，
        // you / go 这种功能词便直接照抄回来了 —— 截图里那两个残留英文就是这么来的。
        // 合并后的译文另用「字数最多的那段」的样式（见 tyfy$dominantStyle），
        // 不会被段首标签染成整段亮色。
        if (p.endsWith(":") || p.endsWith("：")) {
            return false;   // 「Damage:」「Strength:」属性行
        }
        if (tyfy$wordCount(p) < 2) {
            return false;   // 「+100」「[+]」这种不是句子
        }
        // 被折行劈在冠词/介词上：下一行即使大写开头也是同一句的下一截
        // （The End / The Nether / The Catacombs 这类专名尤其常见）。
        if (DANGLING_TAIL.matcher(p).matches()) {
            return true;
        }
        char c0 = n.charAt(0);
        boolean lowerStart = c0 >= 'a' && c0 <= 'z';
        boolean punctTail = p.endsWith(",") || p.endsWith(";") || p.endsWith("-");
        return lowerStart || punctTail;
    }

    private static boolean tyfy$hasCjk(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) >= 0x2E80) {
                return true;
            }
        }
        return false;
    }

    private static int tyfy$wordCount(String s) {
        int count = 0;
        boolean inWord = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean letter = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
            if (letter && !inWord) {
                count++;
            }
            inWord = letter;
        }
        return count;
    }

    /** 按显示宽度折行：中文算 2、英文算 1；行首不放中文标点，英文单词不拦腰截断 */
    private static List<String> tyfy$wrapCjk(String text, int maxUnits) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        int units = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n') {
                out.add(cur.toString());
                cur.setLength(0);
                units = 0;
                continue;
            }
            int w = c >= 0x2E80 ? 2 : 1;
            boolean cjkPunct = "，。、；：！？）」』”’,.;:!?)]".indexOf(c) >= 0;
            if (units + w > maxUnits && cur.length() > 0 && !cjkPunct) {
                int cut = tyfy$breakIndex(cur);
                out.add(cur.substring(0, cut).stripTrailing());
                String rest = cur.substring(cut).stripLeading();
                cur.setLength(0);
                cur.append(rest);
                units = tyfy$units(rest);
            }
            cur.append(c);
            units += w;
        }
        if (cur.length() > 0) {
            out.add(cur.toString());
        }
        return out;
    }

    /** 折行位置：默认整行；如果尾巴上有个没写完的英文单词，就把整个词挪到下一行 */
    private static int tyfy$breakIndex(StringBuilder sb) {
        int len = sb.length();
        if (len < 2) {
            return len;
        }
        char last = sb.charAt(len - 1);
        boolean lastLetter = (last >= 'a' && last <= 'z') || (last >= 'A' && last <= 'Z');
        if (!lastLetter) {
            return len;
        }
        for (int i = len - 1; i >= 0 && i >= len - 14; i--) {
            if (sb.charAt(i) == ' ') {
                return i + 1;
            }
        }
        return len;
    }

    private static int tyfy$units(String s) {
        int units = 0;
        for (int i = 0; i < s.length(); i++) {
            units += s.charAt(i) >= 0x2E80 ? 2 : 1;
        }
        return units;
    }

    /** 单行译文折行：按空格断，超长词硬切（老逻辑，保持原样） */
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
