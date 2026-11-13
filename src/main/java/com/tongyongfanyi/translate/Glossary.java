package com.tongyongfanyi.translate;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.tongyongfanyi.config.Languages;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStreamReader;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 术语表：把 SkyBlock 的高频专有名词固定成中文，不交给模型自由发挥。
 *
 * <p>做法是「占位符保护」：翻译前把命中的术语换成 {@code <t0>}、{@code <t1>}，
 * 译完再换回中文。这样 {@code Minion} 不会被翻成「小传」、{@code Dungeon} 不会变「地下城」。
 * 实测 qwen3.5 对 {@code <tN>} 形式保留得很好，而 {@code ⟦1⟧}、{@code 【1】} 会被改掉。
 *
 * <p>另外两个便宜的好处：
 * <ul>
 *   <li>整句就是术语（如 {@code Minion}、{@code Auction House}）时直接返回译文，一次模型调用都省了；</li>
 *   <li>模型把术语原样留在译文里时，收尾再兜一次底（{@link #postfix}）。</li>
 * </ul>
 *
 * <p>内置表在 jar 里（{@code tongyongfanyi/glossary.json}），用户可以在
 * {@code config/tongyongfanyi_glossary.json} 里追加/覆盖，改完 {@code /tyfy reload} 生效。
 */
public final class Glossary {
    private static final Logger LOGGER = LoggerFactory.getLogger("tongyongfanyi");
    private static final String BUILTIN = "tongyongfanyi/glossary.json";
    /**
     * 内置物品名表（NEU 仓库全量）。
     *
     * <p>只参与 {@link #exact(String)} 的整句直出，<b>不进匹配正则</b>：
     * 这张表有三千多条长短语，并进 {@code pattern} 会让那个「长词优先」的
     * 巨型 OR 正则再膨胀一倍，而它每帧都在渲染线程上跑。物品名在 tooltip 里
     * 本来就是完整一行，走 O(1) 的整句查表命中率一样、开销为零。
     */
    private static final String ITEMS = "tongyongfanyi/item_names.json";
    /** 地点白名单：这些名字不做占位保护，原文直接交给模型翻译 */
    private static final String LOCATIONS = "tongyongfanyi/locations.json";
    private static final Type MAP_TYPE = new TypeToken<LinkedHashMap<String, String>>() {}.getType();
    private static final Type LIST_TYPE = new TypeToken<ArrayList<String>>() {}.getType();
    private static final Gson GSON = new Gson();

    private static volatile Map<String, String> entries = Map.of();
    /** 只含内置表的那一份（归一化后）：用来告诉玩家「你这条覆盖了内置的什么」 */
    private static volatile Map<String, String> builtinMap = Map.of();
    private static volatile Pattern pattern = Pattern.compile("(?!)");
    private static volatile Map<String, String> exactMap = Map.of();
    /** 内置 NEU 物品名（小写键），是否并入 {@link #exactMap} 由设置页开关控制 */
    private static volatile Map<String, String> itemNames = Map.of();
    private static volatile boolean itemsEnabled = true;
    private static volatile Set<String> locations = Set.of();

    private Glossary() {
    }

    /**
     * 把「鼠标指向重译」逐词翻出来的新词自动写进玩家自定义词表。
     *
     * <p>只<b>新增</b>，绝不覆盖玩家在自定义翻译页里手写的词条；写进去后玩家能在那个页面
     * 看到、修改、移除，删掉就自动回退到内置译名（和手动添加走完全同一套优先级）。
     * 这样按一次键补上的词，下次遇到同类词直接命中，不用再来报漏词。
     *
     * @return 实际新增的词条数
     */
    public static synchronized int learn(Map<String, String> words) {
        if (words == null || words.isEmpty()) {
            return 0;
        }
        Path path = FabricLoader.getInstance().getConfigDir().resolve("tongyongfanyi_glossary.json");
        Map<String, String> user = new LinkedHashMap<>();
        if (Files.isRegularFile(path)) {
            try (Reader r = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                Map<String, String> loaded = GSON.fromJson(r, MAP_TYPE);
                if (loaded != null) {
                    user.putAll(loaded);
                }
            } catch (Exception ignored) {
                // 用户表损坏时不覆盖它，本次干脆不学词
                return 0;
            }
        }
        int added = 0;
        for (Map.Entry<String, String> e : words.entrySet()) {
            String key = e.getKey() == null ? "" : e.getKey().trim().toLowerCase(Locale.ROOT);
            String value = e.getValue() == null ? "" : e.getValue().trim();
            if (key.length() < 3 || key.length() > 40 || value.isEmpty()
                    || !key.matches("[a-z][a-z' -]*") || value.equals(key)) {
                continue;
            }
            if (!user.containsKey(key) && !exactMap.containsKey(key)) {
                user.put(key, value);
                added++;
            }
        }
        if (added == 0) {
            return 0;
        }
        try {
            Files.createDirectories(path.getParent());
            try (Writer w = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                GSON.toJson(user, MAP_TYPE, w);
            }
        } catch (Exception e) {
            LOGGER.warn("自动学词写盘失败: {}", e.toString());
            return 0;
        }
        reload();
        LOGGER.info("鼠标指向重译自动补了 {} 个词条到自定义词表", added);
        return added;
    }

    /**
     * 读取内置表 + 用户表。
     *
     * <p>优先级：<b>用户表覆盖内置表的同名项</b>（{@code merged} 先内置、后用户 putAll）。
     * 玩家在「自定义翻译」里删掉某条后，下一次 reload 时用户表里已经没有它，
     * 合并结果自然回退成内置译名 —— 删掉自定义就等于「恢复默认」，不需要额外标记。
     */
    public static synchronized void reload() {
        LOCALIZE_CACHE.clear();   // 词表变了，短名称的本地拆分结果必须重算
        Map<String, String> builtinRaw = new LinkedHashMap<>();
        loadInto(builtinRaw, null, BUILTIN);
        builtinCount = builtinRaw.size();
        Map<String, String> builtin = new LinkedHashMap<>();
        normalizeInto(builtin, builtinRaw);

        Map<String, String> merged = new LinkedHashMap<>(builtinRaw);
        // 远程数据表（GitHub 下载的）覆盖 jar 内置的：内置那份是打版时的快照，
        // 远程那份才是最新的。没下载过就跳过，等于用内置。
        Path remoteGlossary = RemoteTables.glossaryFile();
        if (remoteGlossary != null) {
            loadInto(merged, remoteGlossary, null);
        }
        Path user = FabricLoader.getInstance().getConfigDir().resolve("tongyongfanyi_glossary.json");
        if (Files.isRegularFile(user)) {
            loadInto(merged, user, null);   // 玩家自己写的优先级最高
        }
        // 统一小写做键（用户写 Book、内置写 book 也要算同一条）
        Map<String, String> normalized = new LinkedHashMap<>();
        normalizeInto(normalized, merged);
        // 自动补复数：表里历来只有单数（collection 有、collections 没有），
        // 于是 "Foraging Collections" 预替换成「采集 Collections」，模型把剩下的复数
        // 当成专名保留，屏幕上就是中英混排。这里给每个单词词条补一份复数形态，
        // 已有复数词条（如 bits → 比特）不覆盖。
        normalized = withPlurals(normalized);
        LOGGER.info("术语表含复数变体共 {} 条", normalized.size());

        // 长词优先：先匹配 "attack speed" 再匹配 "speed"
        List<String> keys = new ArrayList<>(normalized.keySet());
        keys.sort(Comparator.comparingInt(String::length).reversed());
        StringBuilder sb = new StringBuilder("(?<![A-Za-z])(");
        for (int i = 0; i < keys.size(); i++) {
            if (i > 0) {
                sb.append('|');
            }
            sb.append(Pattern.quote(keys.get(i)));
        }
        sb.append(")(?![A-Za-z])");

        entries = normalized;
        // 整句直出用的表 = 术语表 + 物品名。putIfAbsent 保证术语表里的
        // 老译名优先（「之前爬到有中文名的就用原来的」），物品名不会覆盖它。
        Map<String, String> loaded = new LinkedHashMap<>();
        if (itemsEnabled) {
            // 关掉开关时连文件都不读：288KB JSON 解析出来的 7000 条 HashMap
            // 玩家既然不用，就没必要占着内存
            normalizeInto(loaded, loadItemNamesRaw());
        }
        itemNames = loaded;
        rebuildExact();
        builtinMap = builtin;
        pattern = Pattern.compile(sb.toString(), Pattern.CASE_INSENSITIVE);
        locations = loadLocations();
        LOGGER.info("术语表已加载 {} 条（物品名 {} 条，来源 {}，{}，整句直出不进正则），地点放行 {} 个",
                normalized.size(), itemNames.size(), RemoteTables.sourceLabel(),
                itemsEnabled ? "已启用" : "已关闭", locations.size());
    }

    /**
     * 物品名表的数据来源：优先 GitHub 下载的最新版，没下载过或文件坏了就回退 jar 内置。
     *
     * <p>和术语表的合并方式不同：术语表是「内置 → 远程 → 用户」层层覆盖，
     * 物品名表则是<b>整份替换</b>。远程那份本来就是内置表的超集，叠加没有意义；
     * 而且以后从远端删掉一条错误译名时，叠加会让它在本机永远删不掉。
     */
    private static Map<String, String> loadItemNamesRaw() {
        Map<String, String> raw = new LinkedHashMap<>();
        Path remote = RemoteTables.itemNamesFile();
        if (remote != null) {
            loadInto(raw, remote, null);
            if (!raw.isEmpty()) {
                return raw;
            }
            // 文件存在但解析不出内容（下载到一半、被手工清空）：当它不存在，用内置的
        }
        loadInto(raw, null, ITEMS);
        return raw;
    }

    /**
     * 整句直出表的重新拼装：术语表（含复数变体）+ 可选的内置物品名。
     *
     * <p>不重新编译 {@code pattern}。玩家在设置里开关物品名表时只动这张 HashMap，
     * 编译那个几千分支的正则要几百毫秒，没必要为一次开关付这个代价。
     */
    private static synchronized void rebuildExact() {
        Map<String, String> exact = new LinkedHashMap<>(entries);
        if (itemsEnabled) {
            itemNames.forEach(exact::putIfAbsent);
        }
        exactMap = exact;
    }

    /** 设置页的「内置物品名表」开关：立即生效，不需要重启或重新加载模型 */
    public static synchronized void setItemNamesEnabled(boolean on) {
        if (itemsEnabled == on) {
            return;
        }
        itemsEnabled = on;
        if (on && itemNames.isEmpty()) {
            // 关着的时候 reload 根本没读文件，重新打开时补读一次（同样优先远程表）
            Map<String, String> loaded = new LinkedHashMap<>();
            normalizeInto(loaded, loadItemNamesRaw());
            itemNames = loaded;
        }
        rebuildExact();
        LOGGER.info("内置物品名表已{}（整句直出 {} 条）", on ? "启用" : "关闭", exactMap.size());
    }

    public static boolean itemNamesEnabled() {
        return itemsEnabled;
    }

    /** 不规则复数（只列游戏里真会出现的） */
    private static final Map<String, String> IRREGULAR = Map.ofEntries(
            Map.entry("man", "men"), Map.entry("woman", "women"), Map.entry("child", "children"),
            Map.entry("foot", "feet"), Map.entry("tooth", "teeth"), Map.entry("mouse", "mice"),
            Map.entry("goose", "geese"), Map.entry("wolf", "wolves"), Map.entry("leaf", "leaves"),
            Map.entry("knife", "knives"), Map.entry("life", "lives"), Map.entry("wife", "wives"),
            Map.entry("shelf", "shelves"), Map.entry("half", "halves"), Map.entry("self", "selves"),
            Map.entry("elf", "elves"), Map.entry("loaf", "loaves"), Map.entry("thief", "thieves"),
            Map.entry("person", "people"), Map.entry("staff", "staves"));

    /**
     * 给单数词条补一份复数形态。
     *
     * <p>已经存在的复数（{@code bits → 比特}）保持原样，不会被 {@code bit → 位} 覆盖。
     * 带空格的词组也要补最后一个词的复数：{@code God Potion} 在物品描述里几乎总写成
     * {@code God Potions}，旧版只名单数，屏幕上就留着「God药水」这种半英文。
     */
    private static Map<String, String> withPlurals(Map<String, String> src) {
        Map<String, String> out = new LinkedHashMap<>(src);
        for (Map.Entry<String, String> e : src.entrySet()) {
            String k = e.getKey();
            String v = e.getValue();
            if (k.indexOf('-') >= 0 || k.length() < 3) {
                continue;
            }
            String plural;
            int sp = k.lastIndexOf(' ');
            if (sp > 0 && sp < k.length() - 1) {
                String tail = k.substring(sp + 1);
                if (tail.length() < 3) {
                    continue;
                }
                String p = pluralOf(tail);
                plural = p == null ? null : k.substring(0, sp + 1) + p;
            } else {
                plural = pluralOf(k);
            }
            if (plural != null && !out.containsKey(plural)) {
                out.put(plural, v);
            }
        }
        return out;
    }

    /** 单数 → 复数；已经是复数或不适用时返回 null */
    private static String pluralOf(String k) {
        String irregular = IRREGULAR.get(k);
        if (irregular != null) {
            return irregular;
        }
        char last = k.charAt(k.length() - 1);
        if (k.endsWith("s") || k.endsWith("x") || k.endsWith("z")
                || k.endsWith("ch") || k.endsWith("sh")) {
            return k + "es";
        }
        if (last == 'y' && k.length() >= 3) {
            char prev = k.charAt(k.length() - 2);
            if ("aeiou".indexOf(prev) < 0) {
                return k.substring(0, k.length() - 1) + "ies";
            }
        }
        return k + "s";
    }

    /** 把一张原始表归一化进 {@code dst}：去注释键、去省略号、键小写 */
    private static void normalizeInto(Map<String, String> dst, Map<String, String> src) {
        src.forEach((k, v) -> {
            if (k == null || v == null || k.isBlank() || v.isBlank() || k.startsWith("_")) {
                return;
            }
            // 固定译名里不该出现省略号：之前 "talk to → 与…交谈" 会让计分板
            // 直接显示「与点点点对话」。术语直出不经过 cleanTranslation，
            // 所以必须在加载这一层就清掉（用户自定义覆盖表同样生效）。
            String cn = v.replace("…", "").replace("...", "").trim();
            if (cn.isEmpty()) {
                return;
            }
            dst.put(k.trim().toLowerCase(Locale.ROOT), cn);
        });
    }

    /**
     * 内置表里这个词的译名（不含玩家覆盖）。
     *
     * <p>「自定义翻译」列表用它标注「你这行覆盖了内置的 xxx」，
     * 玩家删掉这条时就知道会回退成什么。
     */
    public static String builtin(String key) {
        return key == null ? null : builtinMap.get(key.trim().toLowerCase(Locale.ROOT));
    }

    /** jar 内置术语表条数（不含远程/用户/复数变体），用于拒绝比内置还旧的远程表 */
    private static volatile int builtinCount;

    public static int builtinCount() {
        return builtinCount;
    }

    /**
     * 读取地点白名单。优先用 GitHub 下载的最新版，没下载过或文件坏了回退 jar 内置；
     * 缺文件不算错误（地点表只用于「单专名放行」，没有也能正常翻译）。
     */
    private static Set<String> loadLocations() {
        try {
            Path remote = RemoteTables.locationsFile();
            java.io.InputStream in = remote != null
                    ? Files.newInputStream(remote)
                    : Glossary.class.getClassLoader().getResourceAsStream(LOCATIONS);
            if (in == null) {
                return Set.of();
            }
            try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                List<String> list = GSON.fromJson(reader, LIST_TYPE);
                if (list == null || list.isEmpty()) {
                    return Set.of();
                }
                Set<String> set = new HashSet<>();
                for (String s : list) {
                    if (s != null && !s.isBlank()) {
                        set.add(s.trim().toLowerCase(Locale.ROOT));
                    }
                }
                return set;
            }
        } catch (Exception e) {
            LOGGER.warn("读取地点白名单失败: {}", e.toString());
            return Set.of();
        }
    }

    /**
     * 整段文本是不是一个地点名。允许 {@code ⏣} 前缀、§颜色码和首尾标点，
     * 用于放行那些「单个专有名词」——它们原本会被当成玩家 ID 过滤掉。
     */
    public static boolean isLocation(String text) {
        if (text == null || locations.isEmpty()) {
            return false;
        }
        String key = text.replaceAll("§[0-9a-fA-Fk-oK-OrR]", " ")
                .replaceAll("[⏣◇■▪›»]+", " ")
                .trim()
                .replaceAll("^[.:：,，\\-]+|[.。!！,，;；:：]+$", "")
                .trim().toLowerCase(Locale.ROOT);
        return locations.contains(key);
    }

    private static void loadInto(Map<String, String> target, Path file, String resource) {
        try (Reader reader = file != null
                ? Files.newBufferedReader(file, StandardCharsets.UTF_8)
                : new InputStreamReader(Glossary.class.getClassLoader().getResourceAsStream(resource),
                StandardCharsets.UTF_8)) {
            Map<String, String> loaded = GSON.fromJson(reader, MAP_TYPE);
            if (loaded != null) {
                target.putAll(loaded);
            }
        } catch (Exception e) {
            LOGGER.warn("读取术语表失败({}): {}", file != null ? file : resource, e.toString());
        }
    }

    public static int size() {
        return entries.size();
    }

    private static final Pattern TRAILING_PUNCT = Pattern.compile("([.。!！,，;；:：]+)$");
    /** 表里最长的键是 39 字符；超过这个长度不可能是整句术语，先早退再谈匹配 */
    private static final int MAX_TERM_LEN = 64;
    /** 「术语 + 等级」：Fortune III / Growth V / Protection V / Experience III */
    private static final Pattern ROMAN_SUFFIX = Pattern.compile("^(.*?)\\s+([IVXLCDM]{1,5})$");

    /**
     * 整句就是术语时直接给译文（省一次模型调用）。
     *
     * <p>计分板/属性栏是 {@code Purse:}、{@code Bits:}、{@code Health:} 这种带尾部冒号的写法，
     * 命中时要<b>把冒号补回去</b>，否则会显示成「比特 1,253」少了分隔符；
     * 半角冒号统一成中文全角，和其它译文保持一致。
     */
    /**
     * 术语表里「刻意保留英文」的词条：模组名、品牌、作曲家/主播名。
     *
     * <p>这些词写进内置表是为了用 {@code <tN>} 占位符保护它们，别让 0.8B 小模型
     * 把 {@code SkyHanni} 翻成「天空汉尼」、把 {@code JEI} 翻成奇怪的中文；
     * 但残留判定原先只有一份硬编码 {@code KEEP_WORDS}，会把它们算成「没翻干净」，
     * 于是译文判失败、不写缓存、missing.txt 里刷一堆。这里让判定直接复用术语表，
     * 以后往表里加品牌不用再改两份代码。
     */
    public static boolean isPreservedEnglish(String word) {
        if (word == null || word.isEmpty()) {
            return false;
        }
        String zh = exactMap.get(word.toLowerCase(Locale.ROOT));
        return zh != null && zh.equalsIgnoreCase(word);
    }

    public static String exact(String text) {
        if (text == null || text.isEmpty() || text.length() > MAX_TERM_LEN) {
            return null;
        }
        String trimmed = text.trim();
        // 这个方法在渲染层每帧被调用几百次，长度早退 + 预编译正则，
        // 不再用 String.replaceAll（每次都会重新编译 Pattern，是掉帧的隐形来源）。
        Matcher tail = TRAILING_PUNCT.matcher(trimmed);
        boolean hasTail = tail.find();
        String core = hasTail ? trimmed.substring(0, tail.start()) : trimmed;
        if (core.isEmpty()) {
            return null;
        }
        // 词条本身就带尾部标点（物品名「… of Love!」「…, 2022 Edition.」）：
        // 原样查得到就直出，不能先剥标点（剥了就永远查不到）。
        if (hasTail) {
            String raw = exactMap.get(trimmed.toLowerCase(Locale.ROOT));
            if (raw != null) {
                return raw;
            }
        }
        String hit = exactCore(core);
        if (hit == null) {
            return null;
        }
        return hasTail ? hit + tail.group(1).replace(':', '：') : hit;
    }

    /**
     * 纯数字价格行：{@code 81,920.0 Coins (1,280.0 each)} → {@code 81,920.0 硬币（单价 1,280.0）}。
     *
     * <p>这类行是 skyblocker / SkyHanni 塞进 tooltip 的，数字格式千变万化，
     * 交给模型只会被原样吐回来（或把千分位改掉），然后判失败、重试、进放弃名单。
     * 本地直接按模板出，零请求、格式绝对不变。
     */
    private static final Pattern PRICE_EACH = Pattern.compile(
            "^([\\d,.]+[kKmMbB]?)\\s+[Cc]oins?\\s*[（(]\\s*([\\d,.]+[kKmMbB]?)\\s+each\\s*[)）]$");
    private static final Pattern PRICE_PLAIN = Pattern.compile(
            "^([\\d,.]+[kKmMbB]?)\\s+[Cc]oins?$");

    public static String priceLine(String text) {
        if (text == null) {
            return null;
        }
        String t = text.trim();
        // 先按长度和「有没有 o/O」快速排除，绝大部分行在这里就返回了
        if (t.length() < 8 || t.length() > 48 || (t.indexOf('o') < 0 && t.indexOf('O') < 0)) {
            return null;
        }
        Matcher m = PRICE_EACH.matcher(t);
        if (m.matches()) {
            return m.group(1) + " 硬币（单价 " + m.group(2) + "）";
        }
        m = PRICE_PLAIN.matcher(t);
        if (m.matches()) {
            return m.group(1) + " 硬币";
        }
        return null;
    }

    // ---- Hypixel / SkyHanni 周期性固定提示（本地直出，不送小模型） ----
    // 这几行每 60 秒重发一次，0.8B 模型对它们经常输出乱码或被判坏，
    // 结果就是「一直不写缓存、聊天栏一直英文」。数字用捕获组原样回填。
    private static final Pattern SEG_MARK_CHAT = Pattern.compile("\\[\\d+\\]");
    /** Hypixel 一行里常把数字/动词拆成不同颜色段，颜色码不能挡住本地模板匹配 */
    private static final Pattern CHAT_COLOR_CODE = Pattern.compile("§[0-9A-Za-z]");
    private static final Pattern STASH_HAVE = Pattern.compile(
            "You have\\s+([\\d,.]+[kKmM]?)\\s+materials stashed away!");
    private static final Pattern STASH_TOTAL = Pattern.compile(
            "This totals\\s+([\\d,.]+[kKmM]?)\\s+types of materials stashed!");
    private static final Pattern STASH_CLICK = Pattern.compile(
            "CLICK HERE\\s+to pick them up!");
    private static final Pattern SKY_STASH = Pattern.compile(
            "You have\\s+([\\d,.]+[kKmM]?)\\s+\\S*\\s*in stash,\\s*totalling\\s+([\\d,.]+[kKmM]?)\\s+types");
    private static final Pattern SKY_EXTRA = Pattern.compile(
            "Extra Information from Tab list not found!");
    private static final Pattern SKY_CARNIVAL = Pattern.compile(
            "Carnival Tickets are ready to be claimed!");

    /**
     * 聊天里反复出现的固定提示，本地拼出整句中文。
     * 只认很特定的关键词，物品提示 / 计分板不会误命中；带 {@code [N]} 分段标记的
     * 键先去掉标记再匹配，译文由渲染层按原段比例切回，颜色分布保持不变。
     */
    public static String chatNotice(String text) {
        if (text == null || text.length() < 16 || text.length() > 320) {
            return null;
        }
        // 先剥掉 [N] 分段标记和 § 颜色码再判关键词与正则：
        // 日志里这几行长这样「§7You have §33,752 §7materials stashed away!」，
        // 不剥码 STASH_HAVE 永远匹配不到，模板等于白加。
        String flat = CHAT_COLOR_CODE.matcher(
                SEG_MARK_CHAT.matcher(text).replaceAll("")).replaceAll("").trim();
        if (flat.indexOf("stash") < 0 && flat.indexOf("Carnival Tickets") < 0
                && flat.indexOf("Tab list") < 0 && flat.indexOf("pick them up") < 0) {
            return null;
        }
        Matcher m;
        if ((m = STASH_HAVE.matcher(flat)).find()) {
            return "你有 " + m.group(1) + " 个材料存放在仓库！";
        }
        if ((m = STASH_TOTAL.matcher(flat)).find()) {
            return "（共 " + m.group(1) + " 种材料存入仓库！）";
        }
        if (STASH_CLICK.matcher(flat).find()) {
            return ">>> 点击这里拾取！ <<<";
        }
        if ((m = SKY_STASH.matcher(flat)).find()) {
            return "[SkyHanni] 你有 " + m.group(1) + " 个材料存放在仓库，共 "
                    + m.group(2) + " 种。点击以拾取你的仓库！";
        }
        if (SKY_EXTRA.matcher(flat).find()) {
            return "[SkyHanni] 未找到 Tab 列表的额外信息！请在 天空方块菜单 ➜ 设置 ➜ 个人 ➜ 用户界面 ➜ 玩家列表信息 中启用";
        }
        if (SKY_CARNIVAL.matcher(flat).find()) {
            return "[SkyHanni] 嘉年华票券可以领取了！[点击传送到嘉年华，或关闭此功能]";
        }
        return null;
    }

    /**
     * 「标签 + 金额」价格行：{@code NPC Sell Price: 500.0 Coins} →
     * {@code NPC 出售价格：500.0 硬币}。
     *
     * <p>skyblocker / SkyHanni 的价格区是「白色标签 + 彩色数字」两段，
     * 整句送模型时模型会把千分位改掉或者干脆原样返回；标签本身在术语表里，
     * 金额又有固定格式，本地拼出来既准又快。
     */
    private static final Pattern PRICE_ROW = Pattern.compile(
            "^(.+?)\\s*[:：]\\s*([\\d,.]+[kKmMbB]?)\\s+[Cc]oins?(.*)$");
    private static final Pattern EACH_TAIL = Pattern.compile(
            "^[（(]\\s*([\\d,.]+[kKmMbB]?)\\s+each\\s*[)）]$");

    public static String priceRow(String text) {
        if (text == null) {
            return null;
        }
        String t = text.trim();
        if (t.length() < 8 || t.length() > 72 || (t.indexOf('o') < 0 && t.indexOf('O') < 0)) {
            return null;
        }
        Matcher m = PRICE_ROW.matcher(t);
        if (!m.matches()) {
            return null;
        }
        String label = exact(m.group(1));
        if (label == null) {
            return null;   // 标签不在术语表里就别硬拼，交给模型
        }
        StringBuilder sb = new StringBuilder(label).append("：")
                .append(m.group(2)).append(" 硬币");
        String tail = m.group(3);
        if (tail != null && !tail.isBlank()) {
            Matcher each = EACH_TAIL.matcher(tail.trim());
            if (each.matches()) {
                sb.append("（单价 ").append(each.group(1)).append("）");
            }
        }
        return sb.toString();
    }

    /**
     * 整行由术语表里的词拼成的大写标签：{@code UNCOMMON ORE} → {@code 罕见矿石}。
     *
     * <p>Hypixel 的物品分类行（品质 + 类别）是全大写、2~4 个词，而且每个词都在术语表里
     * （uncommon→罕见、ore→矿石）。整句查不到就送模型的话，模型会原样返回全大写，
     * 判失败 3 次进放弃名单 —— 屏幕上就永远停在 {@code UNCOMMON ORE}。
     *
     * <p>限制条件故意收得很紧：必须 <b>全大写</b>、2~4 个词、每个词都命中，
     * 才拼出来，避免把普通句子（{@code The End} 之类）误当标签。
     */
    public static String composedLabel(String text) {
        if (text == null) {
            return null;
        }
        String t = text.trim();
        if (t.length() < 6 || t.length() > 40 || !t.equals(t.toUpperCase(Locale.ROOT))) {
            return null;
        }
        String[] words = t.split("\\s+");
        if (words.length < 2 || words.length > 4) {
            return null;
        }
        StringBuilder sb = new StringBuilder(t.length());
        for (String w : words) {
            String cn = exactMap.get(w.toLowerCase(Locale.ROOT));
            if (cn == null) {
                return null;
            }
            sb.append(cn);
        }
        return sb.toString();
    }

    /**
     * 单个片段命中：整词 → 「术语 + 罗马数字等级」→ 逗号分隔的列表，
     * 三种都命中不了才算失败。
     */
    private static String exactCore(String core) {
        if (core == null || core.isEmpty()) {
            return null;
        }
        String hit = exactMap.get(core.toLowerCase(Locale.ROOT));
        if (hit != null) {
            return hit;
        }
        // 「Fortune III」「Growth V」「Experience III」：附魔名 + 等级。
        // 不在表里就交给模型的话，会翻成「命运III」「体验 III」这种。
        Matcher roman = ROMAN_SUFFIX.matcher(core);
        if (roman.matches()) {
            String base = exactMap.get(roman.group(1).trim().toLowerCase(Locale.ROOT));
            if (base != null) {
                return base + " " + roman.group(2);
            }
        }
        // 「Giant Killer V, Life Steal III, Looting III」：附魔 lore 常见格式。
        // 以前整行不在表里 → 交给模型 → 模型照抄 → 判失败 5 次 → 屏幕上永远是英文。
        // 每一段都命中就整行直出，既不排队也不会翻错。
        if (core.indexOf(',') > 0) {
            String[] parts = core.split(",");
            StringBuilder sb = new StringBuilder(core.length() + 8);
            for (int i = 0; i < parts.length; i++) {
                String one = exactCore(parts[i].trim());
                if (one == null) {
                    return null;
                }
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(one);
            }
            return sb.toString();
        }
        return null;
    }

    /** 保护结果：占位后的文本 + 每个占位对应的中文 */
    public record Guard(String text, List<String> terms) {
        public boolean changed() {
            return !terms.isEmpty();
        }
    }

    /** 把命中的术语替换成 {@code <tN>} */
    public static Guard protect(String text) {
        if (text == null || entries.isEmpty()) {
            return new Guard(text, List.of());
        }
        Matcher m = pattern.matcher(text);
        StringBuilder sb = new StringBuilder(text.length() + 16);
        List<String> terms = new ArrayList<>();
        while (m.find()) {
            String key = m.group().toLowerCase(Locale.ROOT);
            String cn = entries.get(key);
            if (cn == null) {
                continue;
            }
            // 地点放行：不要用 <tN> 把地名藏起来，原文交给模型翻（提示词要求地名必须译出）。
            // 模型若照抄英文，postfix() 会用固定译名兜底；整句就是地名时 exact() 直接给译名。
            // 这样也避免了小模型改坏占位符导致整句回退、地名反而漏翻。
            if (locations.contains(key)) {
                continue;
            }
            terms.add(cn);
            m.appendReplacement(sb, Matcher.quoteReplacement("<t" + (terms.size() - 1) + ">"));
        }
        m.appendTail(sb);
        return new Guard(sb.toString(), terms);
    }

    /** 把占位符换回中文；模型偶尔会写成 {@code <T0>}、{@code < t0 >}，一并兜住 */
    public static String restore(String translated, Guard guard) {
        if (guard == null || !guard.changed() || translated == null) {
            return translated;
        }
        Matcher m = Pattern.compile("<\\s*[tT]\\s*(\\d+)\\s*>").matcher(translated);
        StringBuilder sb = new StringBuilder(translated.length());
        while (m.find()) {
            int idx;
            try {
                idx = Integer.parseInt(m.group(1));
            } catch (NumberFormatException e) {
                continue;
            }
            String cn = idx >= 0 && idx < guard.terms().size() ? guard.terms().get(idx) : m.group();
            m.appendReplacement(sb, Matcher.quoteReplacement(cn));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** 保护标记用的圈码：①..⑳（非 ASCII 数字，不会被数字归一化/回填动到） */
    private static final char[] CIRCLED = {
            '\u2460', '\u2461', '\u2462', '\u2463', '\u2464', '\u2465', '\u2466', '\u2467',
            '\u2468', '\u2469', '\u246a', '\u246b', '\u246c', '\u246d', '\u246e', '\u246f',
            '\u2470', '\u2471', '\u2472', '\u2473'};

    /**
     * 预替换结果：已替换好的文本 + 被保护起来的名词（按出现顺序）+ 这些名词的原文键。
     *
     * <p>{@code keys} 用于译后的 postfix 豁免：玩家指定 {@code hyperion → Hyperion}
     * 保留原名时，内置表的「海伯利安」不能再把它覆盖一次。
     */
    public record Pre(String text, List<String> hidden, Set<String> keys) {
        public boolean changed() {
            return !hidden.isEmpty();
        }

        public static Pre none(String text) {
            return new Pre(text, List.of(), Set.of());
        }

        /**
         * 译后 postfix 要跳过的词：原文键 + 译名里出现的英文单词。
         *
         * <p>后者是必要的：玩家指定 {@code book → Apple} 时，如果内置表里恰好有
         * {@code apple → 苹果}，不动手拦住就会把刚还原的 {@code Apple} 又换成「苹果」，
         * 玩家指定什么就显示什么这条约定就破了。
         */
        public Set<String> skipKeys() {
            if (hidden.isEmpty()) {
                return keys;
            }
            Set<String> all = new HashSet<>(keys);
            java.util.regex.Matcher m = Pattern.compile("[A-Za-z][A-Za-z'\\-]*").matcher(String.join(" ", hidden));
            while (m.find()) {
                all.add(m.group().toLowerCase(Locale.ROOT));
            }
            return all;
        }
    }

    /**
     * 送模型前的术语预替换。
     *
     * <p>「先把术语换成中文再送模型」实测比 {@code <tN>} 占位符稳得多，因为 1.8B
     * 对「已经是中文的部分」天然保持原样。但有一类译名本身<b>还含英文</b>
     * （{@code Hyperion}、{@code Skeletor 盔甲}、{@code 钛钻头 DR-X355}），
     * 直接写进去模型会再翻一遍，把玩家指定的 {@code book → Apple} 变成「苹果」。
     *
     * <p>所以这里分两条路：纯中文译名直接写入；<b>含拉丁字母的译名用 {@code 〖①〗} 占位</b>，
     * 译完由 {@link #restorePre} 换回。选圈码是因为它不属于 ASCII 数字，
     * 不会被数字归一化（{@code \d+ → #}）和回填打乱，也不会被「尖括号乱码」检测误杀。
     */
    public static Pre preTranslate(String text) {
        if (text == null || entries.isEmpty()) {
            return Pre.none(text);
        }
        Matcher m = pattern.matcher(text);
        StringBuilder sb = new StringBuilder(text.length() + 16);
        List<String> hidden = new ArrayList<>();
        Set<String> keys = new HashSet<>();
        while (m.find()) {
            String key = m.group().toLowerCase(Locale.ROOT);
            String cn = entries.get(key);
            if (cn == null) {
                continue;
            }
            // 地点放行：地名交给模型译（提示词要求地名必须译出），
            // 模型照抄时由 postfix 兜底，别用占位符藏起来。
            if (locations.contains(key)) {
                continue;
            }
            if (hasLatin(cn) && hidden.size() < CIRCLED.length) {
                // 含英文的译名：藏起来，避免模型二次翻译。
                // 注意只能走 appendReplacement（它会自己补上匹配前的文本），
                // 手动 sb.append 会打乱顺序。
                String mark = "\u3016" + CIRCLED[hidden.size()] + "\u3017";
                hidden.add(cn);
                keys.add(key);
                m.appendReplacement(sb, Matcher.quoteReplacement(mark));
                continue;
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(cn));
        }
        m.appendTail(sb);
        return new Pre(sb.toString(), hidden, keys);
    }

    /** 译名里还有英文字母吗（这种必须先藏起来，否则会被模型再翻一遍） */
    private static boolean hasLatin(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')) {
                return true;
            }
        }
        return false;
    }

    /**
     * 还原时容忍模型把 {@code 〖①〗} 写成 {@code 〖1〗}、{@code ［①］}、{@code 【①】} 等。
     * 左右括号必须把 {@code 〖〗}(U+3016/3017) 也列进去 —— 那正是我们生成占位时用的那对，
     * 漏掉它会导致还原完全失效（屏幕上留下半个「〖」）。
     */
    private static final Pattern PRE_MARK = Pattern.compile(
            "[\\[\uFF3B\u3010\u3014\u3016\uFF08(]\\s*([0-9\u2460-\u2473]{1,2})\\s*"
                    + "[\\]\uFF3D\u3011\u3015\u3017\uFF09)]");

    /**
     * 把占位标记换回玩家/内置表指定的译名。
     *
     * <p>先按标记里的序号取，取不到（模型把 ① 改成了别的字符）就按出现顺序取；
     * 还剩没配上的标记就直接删掉括号，至少不把 {@code 〖①〗} 显示到屏幕上。
     */
    public static String restorePre(String translated, Pre pre) {
        if (pre == null || !pre.changed() || translated == null) {
            return translated;
        }
        Matcher m = PRE_MARK.matcher(translated);
        StringBuilder sb = new StringBuilder(translated.length());
        int seq = 0;
        while (m.find()) {
            int idx = circledIndex(m.group(1));
            if (idx < 0) {
                idx = seq;
            }
            seq++;
            String term = idx >= 0 && idx < pre.hidden().size() ? pre.hidden().get(idx) : null;
            m.appendReplacement(sb, Matcher.quoteReplacement(term == null ? "" : term));
        }
        m.appendTail(sb);
        // 标记被改得认不出来（只剩括号没有数字）时，清掉括号本身
        return sb.toString().replaceAll("[\u3016\u3017]", "");
    }

    /** ① → 0，1 → 0，② → 1…；解析不了返回 -1 */
    private static int circledIndex(String mark) {
        if (mark == null || mark.isEmpty()) {
            return -1;
        }
        char c = mark.charAt(0);
        if (c >= '\u2460' && c <= '\u2473') {
            return c - '\u2460';
        }
        if (c >= '0' && c <= '9') {
            try {
                return Integer.parseInt(mark.trim()) - 1;
            } catch (NumberFormatException e) {
                return -1;
            }
        }
        return -1;
    }

    /** 收尾兜底：译文里还残留英文术语就换掉（模型有时会自作主张保留原文） */
    public static String postfix(String translated) {
        return postfix(translated, Set.of());
    }

    /**
     * 不能被术语表碰的片段：命令（{@code /storage}、{@code /craft}）和网址。
     *
     * <p>表里收了 {@code craft → 合成}、{@code storage → 储物} 这类通用词之后，
     * 如果不过这一层，{@code Also accessible via /craft} 会显示成「也可以通过 /合成 访问」，
     * 命令反而看不懂了。
     */
    private static final Pattern PROTECTED_TOKEN = Pattern.compile(
            "(?i)(?:https?://|www\\.)\\S+|/[A-Za-z][A-Za-z0-9_]{1,30}");

    private static boolean insideProtected(List<int[]> ranges, int start) {
        for (int[] r : ranges) {
            if (start >= r[0] && start < r[1]) {
                return true;
            }
        }
        return false;
    }

    /**
     * 收尾兜底，但跳过 {@code skip} 指定的词（小写）。
     *
     * <p>用在预替换之后：玩家自定义 {@code Hyperion → Hyperion}（要求保留原名）时，
     * 刚还原出来的 {@code Hyperion} 不能被内置表的「海伯利安」再覆盖一次。
     */
    public static String postfix(String translated, Set<String> skip) {
        if (translated == null || entries.isEmpty()) {
            return translated;
        }
        // 纯中文/数字/符号（没有任何拉丁字母）不需要跑那条 3363 分支的大正则
        if (!LATIN_HERE.matcher(translated).find()) {
            return translated;
        }
        // 同一个字符串 + 同一份 skip 的结果是确定的，缓存下来。
        // 计分板/tooltip 是每帧重画：命中缓存后 translateWholeLine 仍会调一次
        // postfix 做「残留英文兜底」，而这一步是整条渲染链上最贵的一处
        // （jstack 采样能在渲染线程里直接抓到 Glossary.postfix ← translateLine）。
        boolean plain = skip == null || skip.isEmpty();
        String cacheKey = plain ? translated : skip.hashCode() + "\u0000" + translated;
        synchronized (POSTFIX_CACHE) {
            String hit = POSTFIX_CACHE.get(cacheKey);
            if (hit != null) {
                return hit;
            }
        }
        List<int[]> protectedRanges = new ArrayList<>();
        // 只有真的可能出现 URL / 命令时才扫保护区，省掉每条字符串一次全量匹配
        if (translated.indexOf('/') >= 0 || translated.indexOf("ttp") >= 0
                || translated.indexOf("ww.") >= 0) {
            Matcher pm = PROTECTED_TOKEN.matcher(translated);
            while (pm.find()) {
                protectedRanges.add(new int[]{pm.start(), pm.end()});
            }
        }
        Matcher m = pattern.matcher(translated);
        StringBuilder sb = new StringBuilder(translated.length());
        while (m.find()) {
            String key = m.group().toLowerCase(Locale.ROOT);
            if (skip.contains(key) || insideProtected(protectedRanges, m.start())) {
                m.appendReplacement(sb, Matcher.quoteReplacement(m.group()));
                continue;
            }
            String cn = entries.get(key);
            m.appendReplacement(sb, Matcher.quoteReplacement(cn == null ? m.group() : cn));
        }
        m.appendTail(sb);
        String out = sb.toString();
        synchronized (POSTFIX_CACHE) {
            POSTFIX_CACHE.put(cacheKey, out);
        }
        return out;
    }

    /** postfix 结果缓存（访问顺序 LRU）：渲染线程每帧都拿同一批字符串来问 */
    private static final int POSTFIX_CACHE_MAX = 2048;
    private static final Map<String, String> POSTFIX_CACHE =
            new LinkedHashMap<>(512, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                    return size() > POSTFIX_CACHE_MAX;
                }
            };

    /** 中文译文里夹着的孤立英文功能词：{@code 右键点击 to 打开袋子} */
    private static final Pattern DANGLING_WORD = Pattern.compile(
            "(?<![A-Za-z])(?:of|the|into|and|to|for|from|with|in|on|by|a|an)(?![A-Za-z])",
            Pattern.CASE_INSENSITIVE);

    /**
     * 小模型整句只翻一半时照抄下来的小写功能词：
     * {@code Foraging! Items you pickup go → 采集！物品 you 拾取 go}。
     *
     * <p>只匹配<b>小写</b>：{@code Go}/{@code Will} 可能是玩家 ID 或专名，不能误删；
     * 术语表里已有固定译名的词（{@code not → 未}、{@code per → 每}）也不列进来，
     * 免得把该有的字删掉。这层只是译后兜底，顺便让旧的半翻缓存自愈。
     */
    private static final Pattern DANGLING_FUNC = Pattern.compile(
            "(?<![A-Za-z])(?:you|your|go|up|out|this|that|these|those"
                    + "|we|they|he|she|has|have|had|do|does|did"
                    + "|is|are|was|were|been|will|would|can|could)(?![A-Za-z])");
    private static final Pattern HAN_HERE = Pattern.compile("[\\u4e00-\\u9fa5]");
    private static final Pattern LATIN_HERE = Pattern.compile("[A-Za-z]{2,}");
    private static final Pattern EMPTY_QUOTES = Pattern.compile("《[\\s\\u3000]*》");
    private static final Pattern PAIRED_QUOTES = Pattern.compile("《([^《》]*)》");

    /**
     * 原文里没有书名号时，擦掉小模型自己给专名补的 {@code 《…》}。
     *
     * <p>1.8B 看到残缺句尾的 {@code The} 会当成作品名开头，输出一对空书名号；
     * 也会把 {@code BUY Enchanted Hopper} 翻成 {@code 购买《附魔漏斗》}。
     * 这两种都是译文凭空多出来的排版，属于「不该改原文格式」的范畴。
     * 成对书名号只去掉符号、保留里面的中文；空书名号整对删掉；
     * 原文真带书名号时原样返回（玩家自定义词条、成书正文不受影响）。
     *
     * @param original 原文，可以为 null（缓存自愈时拿不到原文，按「没有书名号」处理）
     */
    public static String stripBogusQuotes(String original, String translated) {
        if (translated == null || translated.isBlank()) {
            return translated;
        }
        if (original != null && (original.indexOf('《') >= 0 || original.indexOf('》') >= 0)) {
            return translated;
        }
        String out = EMPTY_QUOTES.matcher(translated).replaceAll("");
        out = PAIRED_QUOTES.matcher(out).replaceAll("$1");
        if (out.indexOf('《') >= 0 || out.indexOf('》') >= 0) {
            out = out.replace("《", "").replace("》", "");
        }
        return out;
    }

    /**
     * 译后残留英文本地修补：模型把 {@code Rose / Boost / Luck} 当专名照抄时，
     * 用术语表把它们换掉；中文里夹着的孤立功能词（{@code to / of / the}）直接删。
     *
     * <p>这一层不调用模型，所以旧的半翻缓存、模型只翻一半的新结果都能当场修好，
     * 不必等下一次翻译、也不必让玩家一个个报漏翻的词。
     * 整句英文（模型照抄原文）不处理，避免把没翻的句子改得更怪。
     */
    public static String repair(String translated) {
        return repair(translated, Set.of(), true);
    }

    public static String repair(String translated, Set<String> skip) {
        return repair(translated, skip, true);
    }

    /**
     * @param useGlossary 是否做术语表补全。设置里关掉「术语固定译名」时不补，
     *                    但悬空功能词清理照做（那一层跟术语表无关）。
     */
    public static String repair(String translated, Set<String> skip, boolean useGlossary) {
        if (translated == null) {
            return null;
        }
        // 空书名号一定是模型补出来的垃圾，先无条件删。不能等下面的早退判断：
        // 「持有物品常见于《》中」一个拉丁字母都没有，会被判成不用修而原样留下。
        translated = EMPTY_QUOTES.matcher(translated).replaceAll("");
        if (!HAN_HERE.matcher(translated).find()
                || !LATIN_HERE.matcher(translated).find()) {
            return translated;
        }
        String out = useGlossary ? postfix(translated, skip) : translated;
        if (HAN_HERE.matcher(out).find()) {
            out = DANGLING_WORD.matcher(out).replaceAll("");
            out = DANGLING_FUNC.matcher(out).replaceAll("");
            out = out.replaceAll("[ \\t]{2,}", " ").trim();
        }
        return out;
    }

    /**
     * 模型不可用时的本地兜底：直接对原文做术语表替换，认识多少翻多少。
     *
     * <p>跟 {@link #repair} 的区别：repair 只在「译文里已经有汉字」时动手，
     * 而请求超时 / 服务卡死时手上只有原文，整句都还是英文，repair 会直接早退。
     * 这时用术语表把认识的词换掉，至少不会出现整屏英文（例如
     * {@code +Luxurious Bank Upgrade → +奢华银行升级}）。
     * 结果只用于显示、不写缓存，模型恢复后仍会重新翻译整句。
     *
     * <p>只做替换，不删功能词：原文里 {@code Trick or Treat}、{@code Go} 这类
     * 专名首字母大写，贸然删词会把它改坏。
     */
    public static String localize(String original) {
        if (original == null || original.isBlank() || entries.isEmpty()) {
            return original;
        }
        String memo = LOCALIZE_CACHE.get(original);
        if (memo != null) {
            return memo.isEmpty() ? original : memo;
        }
        String out = postfix(original, Set.of());
        // 短物品名里剩下的生造复合词（Figstone / Moonglade）再按已知词根拆一层。
        // 只在这里做：长句随便拆词根会把正常英文句子改得面目全非。
        if (original.length() <= 48 && original.trim().split("\\s+").length <= 5) {
            out = localizeCompound(out);
        }
        out = out.replaceAll("[ \\t]{2,}", " ").trim();
        String result = out.equals(original) ? "" : out;
        if (LOCALIZE_CACHE.size() > 4096) {
            LOCALIZE_CACHE.clear();
        }
        LOCALIZE_CACHE.put(original, result);
        return result.isEmpty() ? original : result;
    }

    /** 拉丁词片段；本地拆分只在这些片段上做，中文部分原样保留 */
    private static final Pattern LATIN_TOKEN = Pattern.compile("[A-Za-z][A-Za-z']*");
    /** 玩家 ID 形态（驼峰中间还有大写、下划线、#、数字）：一律不拆，免得把名字翻坏 */
    private static final Pattern ID_LIKE = Pattern.compile(".*(?:[a-z][A-Z]|_|#|\\d).*");
    /** localize 每帧都会被渲染层调用，短名称拆分结果缓存起来别反复算 */
    private static final Map<String, String> LOCALIZE_CACHE = new ConcurrentHashMap<>();

    /**
     * 把 {@code Figstone}、{@code Moonglade} 这类 Wiki 生造专名按词根拆开拼成中文。
     *
     * <p>1.8B 小模型对不认识的专名一律照抄，实测普通提示词、「不许输出英文」的
     * 术语提示词、整句强约束重译都原样返回 {@code Figstone Splitter}。
     * 但拆成 {@code fig+stone}、{@code moon+glade} 后两半都在术语表里，
     * 纯本地就能拼出「无花果石头分离器」，模型卡住时也不会再显示整行英文。
     *
     * <p>两半都必须 ≥3 字符且都有译名才拆，避免把 {@code Museum} 切成 Muse+um。
     */
    /**
     * 整体就是专名，绝不能按词根拆开。
     * 实测日志里 {@code Minecraft 26.3} 被拆成 {@code Mine+craft} 翻成「矿井合成 26.3」，
     * 这类词要么在术语表里有整词译名，要么应当原样保留，拆两半只会出错。
     */
    private static final java.util.Set<String> NO_SPLIT = java.util.Set.of(
            "minecraft", "mojang", "hypixel", "skyblock", "skyblocks", "supercraft",
            "netherite", "redstone", "bedwars", "enderman", "enderchest", "creeper",
            "wither", "minions", "collections", "crafting");

    private static String splitCompound(String word) {
        if (word.length() < 7 || word.length() > 22 || ID_LIKE.matcher(word).matches()) {
            return null;
        }
        String low = word.toLowerCase(Locale.ROOT);
        if (NO_SPLIT.contains(low)) {
            return null;
        }
        Map<String, String> dict = entries;
        for (int i = 3; i <= low.length() - 3; i++) {
            String a = low.substring(0, i);
            String b = low.substring(i);
            String ca = dict.get(a);
            String cb = dict.get(b);
            // 译名等于原词说明玩家在自定义表里要求保留原文，不能拿它当词根
            if (ca != null && cb != null
                    && !ca.equalsIgnoreCase(a) && !cb.equalsIgnoreCase(b)) {
                return ca + cb;
            }
        }
        return null;
    }

    private static String localizeCompound(String text) {
        Matcher m = LATIN_TOKEN.matcher(text);
        List<int[]> spans = new ArrayList<>();
        while (m.find()) {
            if (m.end() - m.start() >= 7) {
                spans.add(new int[]{m.start(), m.end()});
            }
        }
        if (spans.isEmpty()) {
            return text;
        }
        StringBuilder sb = new StringBuilder(text);
        for (int i = spans.size() - 1; i >= 0; i--) {
            int[] s = spans.get(i);
            String cn = splitCompound(text.substring(s[0], s[1]));
            if (cn != null) {
                sb.replace(s[0], s[1], cn);
            }
        }
        return sb.toString();
    }

    /**
     * 箭头 / 图标字体字符。小模型常把 {@code ➜ Bazaar}、{@code Next Page →}
     * 里的这类符号当成可丢弃的标点，译文出来箭头就消失了。
     *
     * <p>覆盖范围：常用箭头、几何图形、杂项符号（⏣ ⛏ ◆ ★）、
     * Minecraft 资源包图标常用的私用区，以及书名号式尖引号。
     * 不含 {@code §} 颜色码、ASCII 标点和 CJK 汉字。
     */
    private static final Pattern DECOR_MARK = Pattern.compile(
            "[\\u2190-\\u21FF\\u2300-\\u23FF\\u25A0-\\u25FF\\u2600-\\u26FF"
                    + "\\u2794-\\u27FF\\u2B00-\\u2BFF\\uE000-\\uF8FF"
                    + "\\u00AB\\u00BB\\u2039\\u203A]");

    /** 方向性箭头：即使在句子中间丢了也要补回来（{@code Bazaar ➜ Mining}） */
    private static final Pattern ARROW_MARK = Pattern.compile(
            "[\\u2190-\\u21FF\\u2794-\\u27FF\\u2B00-\\u2BFF"
                    + "\\u00AB\\u00BB\\u2039\\u203A]");

    /**
     * 把译文里被模型吃掉的装饰符号按原相对位置补回去。
     *
     * <p>只在「译文确实不是原文」且「符号数量不够」时才动：
     * {@code localQuickLine} 已经拼好图标的行不会被重复添加，
     * 原文里没有、模型自己加的符号也不会被删。补好的结果由调用方回写缓存，
     * 所以旧缓存也能自愈，不用清文件。
     */
    public static String restoreMarks(String original, String translated) {
        if (original == null || translated == null || translated.isEmpty()
                || translated.equals(original)) {
            return translated;
        }
        // 只保护「行首 / 行尾图标」和「箭头」：句子中间的花体装饰（❈ Defense）
        // 模型省略掉无伤大雅，按比例硬插回去反而更突兀。
        List<int[]> positions = new ArrayList<>();   // {原文位置, 符号字符}
        Matcher om = DECOR_MARK.matcher(original);
        while (om.find()) {
            String mark = om.group();
            boolean atStart = original.substring(0, om.start()).trim().isEmpty();
            boolean atEnd = original.substring(om.end()).trim().isEmpty();
            if (!atStart && !atEnd && !ARROW_MARK.matcher(mark).matches()) {
                continue;
            }
            positions.add(new int[]{om.start(), mark.charAt(0)});
        }
        if (positions.isEmpty()) {
            return translated;   // 绝大多数文本没有这类符号，这里早退
        }
        Map<Character, Integer> missing = new LinkedHashMap<>();
        for (int[] p : positions) {
            missing.merge((char) p[1], 1, Integer::sum);
        }
        Matcher tm = DECOR_MARK.matcher(translated);
        while (tm.find()) {
            char c = tm.group().charAt(0);
            Integer n = missing.get(c);
            if (n == null) {
                continue;
            }
            if (n > 1) {
                missing.put(c, n - 1);
            } else {
                missing.remove(c);
            }
        }
        if (missing.isEmpty()) {
            return translated;
        }
        int len = translated.length();
        List<int[]> inserts = new ArrayList<>();   // {译文插入点, 符号字符}
        for (int[] p : positions) {
            char c = (char) p[1];
            Integer n = missing.get(c);
            if (n == null || n <= 0) {
                continue;
            }
            missing.put(c, n - 1);
            int at = (int) Math.round((double) p[0] / original.length() * len);
            inserts.add(new int[]{Math.max(0, Math.min(at, len)), c});
        }
        inserts.sort(Comparator.comparingInt(a -> a[0]));
        StringBuilder sb = new StringBuilder(len + inserts.size() * 3 + 4);
        int cursor = 0;
        for (int[] ins : inserts) {
            int at = Math.max(cursor, Math.min(ins[0], len));
            sb.append(translated, cursor, at);
            char mark = (char) ins[1];
            // 按原文习惯带上空格，别把箭头直接粘在汉字上（➜集市 → ➜ 集市）
            if (at == 0) {
                sb.append(mark).append(' ');
            } else if (at >= len) {
                sb.append(' ').append(mark);
            } else if (Character.isWhitespace(translated.charAt(at - 1))) {
                sb.append(mark);
                if (at >= len || !Character.isWhitespace(translated.charAt(at))) {
                    sb.append(' ');
                }
            } else {
                sb.append(' ').append(mark);
                if (at >= len || !Character.isWhitespace(translated.charAt(at))) {
                    sb.append(' ');
                }
            }
            cursor = at;
        }
        sb.append(translated, cursor, len);
        return sb.toString();
    }

    /** 目标语言是否中文：只有中文才值得做 postfix */
    public static boolean appliesTo(String targetLang) {
        return targetLang != null && Languages.byId(Languages.TARGETS, targetLang).id().startsWith("zh");
    }
}
