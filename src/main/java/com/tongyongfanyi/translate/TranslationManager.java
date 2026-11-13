package com.tongyongfanyi.translate;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.tongyongfanyi.config.I18n;
import com.tongyongfanyi.config.ModConfig;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 翻译管理器。负责选择后端、异步翻译，并维护两层缓存：
 * 1. 内存缓存(最近使用 LRU)
 * 2. 磁盘持久化缓存(存到 config/tongyongfanyi_cache.json)，下次启动/再次遇见相同文本直接命中。
 */
public class TranslationManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("tongyongfanyi");
    private static final Gson GSON = new GsonBuilder().create();
    private static final Type MAP_TYPE = new TypeToken<LinkedHashMap<String, String>>() {}.getType();
    /** 翻译来源类别（顺序对应设置页「优先级」子页面里的 5 行） */
    public static final int CAT_CHAT = 0;
    public static final int CAT_TOOLTIP = 1;
    public static final int CAT_SCOREBOARD = 2;
    public static final int CAT_SCREEN = 3;
    public static final int CAT_HUD = 4;
    /** 物品大列表界面（REI / JEI / NEU 物品浏览器），量级最大、默认优先级最低 */
    public static final int CAT_ITEM_LIST = 5;

    /**
     * 鼠标悬停物品的 tooltip 专用优先级：比任何用户档位都高
     * （{@link #queuePriority} 换算出来的最大值只有 5）。
     *
     * <p>只在 {@code ItemStack.getTooltipLines} 的翻译窗口里生效 ——
     * 那是「玩家真的把鼠标放在这件物品上」的唯一可靠信号。
     */
    public static final int HOVER_PRIORITY = 50;

    /**
     * 用户设定的「1 = 最优先」档位 → 队列里的 priority（越大越先出）。
     * 六档：1 → 6 … 6 → 1，保证 PriorityBlockingQueue 先取高优先级的文本。
     *
     * <p>插队是天然的：{@code PriorityBlockingQueue} 每次 take 都取当前最大 priority，
     * 所以哪怕队列里已经堆了几千条最低档，新来一条聊天/悬停文本也会立刻排到队头，
     * 下一个出队的就是它（只有「正在执行的那一条」无法打断）。
     */
    private int queuePriority(int category) {
        ModConfig c = config;
        int level = switch (category) {
            case CAT_CHAT -> c.priorityChat;
            case CAT_TOOLTIP -> c.priorityTooltip;
            case CAT_SCOREBOARD -> c.priorityScoreboard;
            case CAT_SCREEN -> c.priorityScreen;
            case CAT_ITEM_LIST -> c.priorityItemList;
            default -> c.priorityHud;
        };
        level = Math.max(1, Math.min(6, level));
        return 7 - level;
    }

    /** 当前配置。运行时会被整体替换（/tyfy reload），且翻译线程会读取，故非 final + volatile */
    private volatile ModConfig config;
    /** 优先级队列线程池：聊天(高优先级)先翻，其余批量文字并行处理 */
    private final ThreadPoolExecutor executor;
    private volatile OllamaNativeTranslator translator;
    private final Map<String, String> memoryCache;
    /** 磁盘缓存持久化路径 */
    private final Path diskCachePath;
    /** 一次性缓存净化标记：修复旧版本「翻译失败把原文当译文缓存」留下的污染 */
    private final Path purgeMarkerPath;
    /** 磁盘缓存: 原文 -> 译文 */
    private final Map<String, String> diskCache;
    /** 缓存刷盘线程（异步写文件，避免拖慢翻译线程） */
    private final ScheduledExecutorService saver = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "tongyongfanyi-cache-saver");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean saveScheduled = new AtomicBoolean(false);
    /** 磁盘缓存是否有未写入的改动 */
    private volatile boolean diskCacheDirty = false;

    // ---- 聊天译文缓存（单独文件，带自动过期） ----
    // 聊天句子一次性居多，但 SkyHanni / Hypixel 有些提示会周期性重复出现
    //（Profile ID、Carnival Tickets、stash 提醒）。以前聊天译文只在内存里，
    // 重启游戏后这些句子又要重新等模型，看起来就像「一直没翻」。
    // 现在单独落盘，并与主缓存隔离：不会挤掉界面/物品/计分板的长期译文。
    private final Path chatCachePath;
    private final Map<String, ChatEntry> chatCache = new ConcurrentHashMap<>();
    private final AtomicBoolean chatSaveScheduled = new AtomicBoolean(false);
    private volatile boolean chatCacheDirty = false;
    /** 3 天没被命中就自动删掉，避免缓存文件越用越大 */
    private static final long CHAT_CACHE_TTL_MS = 3L * 24 * 60 * 60 * 1000;
    /** 条数硬上限：超过就按「最久没用」淘汰 */
    private static final int CHAT_CACHE_MAX = 3000;
    private static final Type CHAT_MAP_TYPE =
            new TypeToken<LinkedHashMap<String, ChatEntry>>() {}.getType();

    /** 聊天缓存条目：at = 最后命中时间（命中即续期，冷句子才会过期） */
    private static final class ChatEntry {
        String value;
        long at;
        ChatEntry() { }
        ChatEntry(String value, long at) {
            this.value = value;
            this.at = at;
        }
    }

    /**
     * 最近查询过缓存的「原文 → 缓存键」。按键重译只扫描这批当前/刚才显示在屏幕上的句子，
     * 不会把磁盘里 2000 条历史缓存全部重翻；accessOrder=true 让遍历顺序按最近使用排序。
     */
    private final Map<String, String> recentTexts = Collections.synchronizedMap(
            new LinkedHashMap<String, String>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                    return size() > 600;
                }
            });

    /** 按键/设置页切换：true 时所有实时绘制层直接返回原文，聊天历史由 BilingualComponent 切回 */
    private volatile boolean showOriginal = false;

    public boolean isShowingOriginal() {
        return showOriginal;
    }

    /** 设置页开关用：直接指定显示原文还是译文 */
    public void setShowOriginal(boolean value) {
        boolean was = showOriginal;
        showOriginal = value;
        // 设置页点「完成」从原文切回译文时，同样要补提交原文模式期间的消息
        if (was && !value) {
            try {
                TranslationHelper.resubmitRecentChat();
            } catch (Throwable ignored) {
                // 补提交失败不能影响设置生效
            }
        }
    }

    /**
     * 译文写入缓存后的通知。聊天栏的消息在收到时就拆成固定行了，
     * 后台翻完必须让聊天栏重新排版，才能把已经显示出来的英文原地换成中文。
     */
    private volatile Runnable onCacheUpdated = null;

    public void setOnCacheUpdated(Runnable listener) {
        this.onCacheUpdated = listener;
    }

    private void notifyCacheUpdated() {
        Runnable r = onCacheUpdated;
        if (r != null) {
            try {
                r.run();
            } catch (Throwable ignored) {
                // 通知失败不能影响翻译主流程
            }
        }
    }

    /** 切换原文/译文显示，返回切换后的状态（true = 正在显示原文） */
    public boolean toggleShowOriginal() {
        showOriginal = !showOriginal;
        if (!showOriginal) {
            // 原文模式期间到达的消息在 wrapChatLine 里没提交过翻译，
            // BilingualComponent 的 supplier 又只查缓存。切回译文时补一次提交，
            // 否则这些消息会一直显示英文（表现为「随机有的翻有的没翻」）。
            try {
                TranslationHelper.resubmitRecentChat();
            } catch (Throwable ignored) {
                // 补提交失败不能影响按键切换本身
            }
        }
        return showOriginal;
    }
    /** 正在翻译中的文本 -> 等待回调，避免同一文本在短时间内被重复请求 */
    private final Map<String, List<TranslationCallback>> pending = new HashMap<>();
    /**
     * pending 里每个 key 的入队时刻。Ollama 服务崩溃/重启后，已经发出的请求不会
     * 有任何回调，旧逻辑会把 key 永远留在 pending 里，之后所有渲染帧都被
     * 「已经有请求在途」挡回去 —— 那一行聊天就永久停在英文上，按 V 重译也救不了。
     * 超过 STALE_PENDING_MS 没回来的就当作丢失，允许重新提交。
     */
    private final Map<String, Long> pendingAt = new ConcurrentHashMap<>();
    private static final long STALE_PENDING_MS = 45_000L;
    /** 手动批量重译（V 键）走独立 executor 任务，不进 pending，单独计数给状态行用 */
    private final java.util.concurrent.atomic.AtomicInteger batchInFlight = new java.util.concurrent.atomic.AtomicInteger();
    /** 聊天整句块首次提交失败后的低频补提交：同一句 8 秒最多一次 */
    private final Map<String, Long> chatResubmitAt = new ConcurrentHashMap<>();
    private static final long CHAT_RESUBMIT_MS = 8_000L;
    /** 渲染层批量翻译限速（每秒最多新提交条数），避免打开英文界面时把翻译队列打爆 */
    private final Object bgRateLock = new Object();
    private long bgRateWindowStart = 0L;
    private int bgRateCount = 0;
    /**
     * 渲染层每秒最多提交多少条新文本。
     * 整行合并后一屏文字的请求数降到原来的 1/4 左右，适当放宽到 15，
     * 让一屏内容能在一帧到两帧内全部提交完，而不是被限速摊到两三秒里慢慢出现。
     */
    private static final int BG_MAX_PER_SECOND = 15;
    /**
     * 物品大列表（REI / JEI / NEU）每秒最多提交多少条。
     *
     * <p>它和界面文字分开计数：一屏上千件物品，共用一个桶会把正常界面文字饿死。
     * 限得比界面文字更紧，是因为这类文本基本都排在玩家真正要看的内容后面。
     */
    private static final int ITEM_LIST_MAX_PER_SECOND = 8;
    private final Object itemListRateLock = new Object();
    private long itemListWindowStart = 0L;
    private int itemListCount = 0;

    public TranslationManager(ModConfig config) {
        this.config = config;
        this.memoryCache = Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                // 必须读字段而不是捕获构造参数：setConfig() 换过配置后，
                // 捕获的参数会永远停留在旧那份配置上。
                return size() > TranslationManager.this.config.cacheSize;
            }
        });
        this.diskCachePath = FabricLoader.getInstance().getConfigDir().resolve("tongyongfanyi_cache.json");
        this.purgeMarkerPath = FabricLoader.getInstance().getConfigDir().resolve("tongyongfanyi_cache.purged");
        this.chatCachePath = FabricLoader.getInstance().getConfigDir().resolve("tongyongfanyi_chat_cache.json");
        this.diskCache = Collections.synchronizedMap(new LinkedHashMap<>());
        loadDiskCache();
        loadChatCache();
        // 先按配置决定要不要并入内置物品名表，再加载术语表：
        // 关掉时那张 4800 条的表根本不会进内存。
        Glossary.setItemNamesEnabled(config.itemNameTable);
        Glossary.reload();
        repairDiskCacheAsync();
        this.translator = createTranslator();
        int threads = initialThreads(config);
        this.executor = new ThreadPoolExecutor(threads, threads, 60L, TimeUnit.SECONDS,
                new PriorityBlockingQueue<>(), r -> {
            Thread t = new Thread(r, "tongyongfanyi-translate");
            t.setDaemon(true);
            return t;
        });
    }

    /** 启动时的线程数：本地和在线 API 各用各的配置，互不影响 */
    private static int initialThreads(ModConfig cfg) {
        if (cfg == null) {
            return 1;
        }
        return clampThreads(threadsFor(cfg));
    }

    /**
     * 当前配置该用几路并发。
     *
     * <p>「在线 API 但地址是 127.0.0.1」要按**本地**算：那是玩家用 Termux 跑起来的
     * ollama serve（手机上唯一可行的本地推理方式），受手机 CPU 限制，
     * 开 6~8 路并发只会互相抢核、每条都更慢。
     */
    private static int threadsFor(ModConfig cfg) {
        boolean cloud = "api".equalsIgnoreCase(cfg.translateBackend)
                && !com.tongyongfanyi.config.ApiModels.isLoopback(cfg.apiBaseUrl);
        return cloud ? cfg.apiThreads : cfg.translationThreads;
    }

    private static int clampThreads(int threads) {
        return Math.max(1, Math.min(16, threads));
    }

    /**
     * 按当前后端重新套用线程数。切后端、改任一后端自己的线程数时都要调一次：
     * 本地用 {@code translationThreads}，在线 API 用 {@code apiThreads}。
     */
    public void applyThreadCounts() {
        ModConfig cfg = config();
        if (cfg == null) {
            return;
        }
        setThreadCount(threadsFor(cfg));
    }

    /** 运行时调整翻译线程数（设置页可选） */
    public void setThreadCount(int threads) {
        int t = clampThreads(threads);
        // ThreadPoolExecutor.setCorePoolSize 在 core > max 时会抛 IllegalArgumentException。
        // 从 1 调到 4 必须先抬 max 再抬 core；缩小时反过来。以前两行顺序写死，
        // 在设置页点「1 → 4」会直接抛异常，还会连带打断后面的 BundledRuntime.restart()，
        // 表现为「设置里写了 4，服务端却还在按旧并发跑」。
        try {
            if (t > executor.getMaximumPoolSize()) {
                executor.setMaximumPoolSize(t);
                executor.setCorePoolSize(t);
            } else {
                executor.setCorePoolSize(t);
                executor.setMaximumPoolSize(t);
            }
        } catch (IllegalArgumentException e) {
            LOGGER.warn("调整翻译线程数失败: {}", e.toString());
        }
    }

    @SuppressWarnings("unchecked")
    private void loadDiskCache() {
        if (Files.exists(diskCachePath)) {
            try {
                String json = Files.readString(diskCachePath);
                Map<String, String> loaded = GSON.fromJson(json, MAP_TYPE);
                if (loaded != null) {
                    synchronized (diskCache) {
                        diskCache.putAll(loaded);
                    }
                    LOGGER.info("已加载磁盘翻译缓存 {} 条", diskCache.size());
                }
            } catch (IOException e) {
                LOGGER.warn("读取磁盘缓存失败: {}", e.getMessage());
            }
        }
        purgeMangledCache();
        purgePoisonedCacheOnce();
    }

    /** 启动时读回聊天译文，顺手清掉过期/污染条目 */
    private void loadChatCache() {
        if (!Files.exists(chatCachePath)) {
            return;
        }
        try {
            Map<String, ChatEntry> loaded = GSON.fromJson(Files.readString(chatCachePath), CHAT_MAP_TYPE);
            if (loaded != null) {
                long now = System.currentTimeMillis();
                for (Map.Entry<String, ChatEntry> e : loaded.entrySet()) {
                    ChatEntry v = e.getValue();
                    if (v == null || v.value == null || v.value.isBlank()
                            || now - v.at > CHAT_CACHE_TTL_MS || looksMangled(v.value)) {
                        continue;
                    }
                    chatCache.put(e.getKey(), v);
                }
            }
            LOGGER.info("已加载聊天译文缓存 {} 条（3 天未用自动删除，上限 {} 条）",
                    chatCache.size(), CHAT_CACHE_MAX);
        } catch (Exception e) {
            LOGGER.warn("读取聊天译文缓存失败: {}", e.getMessage());
        }
        enforceChatCacheLimit();
    }

    /** 过期 + 超限清理：启动时和写入时各跑一次，老旧聊天句子不会一直占着文件 */
    private void enforceChatCacheLimit() {
        long now = System.currentTimeMillis();
        int removed = 0;
        for (Map.Entry<String, ChatEntry> e : chatCache.entrySet()) {
            ChatEntry v = e.getValue();
            if (v == null || now - v.at > CHAT_CACHE_TTL_MS) {
                chatCache.remove(e.getKey());
                removed++;
            }
        }
        int over = chatCache.size() - CHAT_CACHE_MAX;
        if (over > 0) {
            List<Map.Entry<String, ChatEntry>> sorted = new ArrayList<>(chatCache.entrySet());
            sorted.sort((a, b) -> Long.compare(a.getValue().at, b.getValue().at));
            for (int i = 0; i < over && i < sorted.size(); i++) {
                chatCache.remove(sorted.get(i).getKey());
                removed++;
            }
        }
        if (removed > 0) {
            chatCacheDirty = true;
            saveChatCache();
            LOGGER.info("聊天译文缓存自动清理 {} 条（过期或超限），剩余 {} 条",
                    removed, chatCache.size());
        }
    }

    private void saveChatCache() {
        if (!chatCacheDirty) {
            return;
        }
        try {
            Files.createDirectories(chatCachePath.getParent());
            String json = GSON.toJson(new LinkedHashMap<>(chatCache));
            Path tmp = chatCachePath.resolveSibling(chatCachePath.getFileName() + ".tmp");
            Files.writeString(tmp, json);
            try {
                Files.move(tmp, chatCachePath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, chatCachePath, StandardCopyOption.REPLACE_EXISTING);
            }
            chatCacheDirty = false;
        } catch (IOException e) {
            LOGGER.warn("保存聊天译文缓存失败: {}", e.getMessage());
        }
    }

    private void markChatCacheDirty() {
        chatCacheDirty = true;
        if (chatSaveScheduled.compareAndSet(false, true)) {
            saver.schedule(() -> {
                saveChatCache();
                chatSaveScheduled.set(false);
            }, 2, TimeUnit.SECONDS);
        }
    }

    /** 查聊天译文：命中即续期，过期就地删掉 */
    private String chatCacheHit(String key) {
        ChatEntry e = chatCache.get(key);
        if (e == null) {
            return null;
        }
        long now = System.currentTimeMillis();
        if (now - e.at > CHAT_CACHE_TTL_MS) {
            chatCache.remove(key);
            markChatCacheDirty();
            return null;
        }
        e.at = now;
        return e.value;
    }

    /**
     * 启动时把磁盘缓存整表修一遍。
     *
     * <p>原来的 {@link Glossary#repair} 是「懒自愈」：只有条目被读取（悬停物品、刷新
     * 计分板）时才补术语。于是 2859 条缓存里 150 条中英混排的旧译文
     * （{@code Dimensional}、{@code Legion}、{@code Helix}、{@code you}、{@code this}）
     * 只要玩家没再看到那件物品，就一直停在英文上，总感觉「还有没翻的」。
     * 术语表补词、悬空功能词清理规则升级后，也都得等玩家逐个撞上才生效。
     * 这里后台跑一次全量修复并回写，不需要手动清缓存、也不用逐个报词。
     */
    /** 缓存译文里的命令 / 网址：全量自愈时整个条目跳过 */
    private static final Pattern CACHE_PROTECTED_TOKEN = Pattern.compile(
            "(?i)(?:https?://|www\\.)\\S+|/[A-Za-z][A-Za-z0-9_]{1,30}");

    /** 磁盘缓存里「带 [N]/[#] 分段标记的聊天整句」这一类 key */
    private static final Pattern CHAT_BLOCK_KEY = Pattern.compile("\\[(?:\\d{1,2}|#)\\]");

    private void repairDiskCacheAsync() {
        Thread t = new Thread(() -> {
            try {
                List<String> keys;
                synchronized (diskCache) {
                    keys = List.copyOf(diskCache.keySet());
                }
                int fixed = 0;
                for (String key : keys) {
                    String value;
                    synchronized (diskCache) {
                        value = diskCache.get(key);
                    }
                    if (value == null || value.isBlank()) {
                        continue;
                    }
                    // 术语替换本身有命令保护，但 repair 里的悬空短词清理没有：
                    // 全量跑一遍可能把 /coopadd、链接里的 /a 之类删掉，索性整条跳过。
                    if (CACHE_PROTECTED_TOKEN.matcher(value).find()) {
                        continue;
                    }
                    // 早期版本的「本地兜底」把只换了术语的半英文聊天整句写进了磁盘：
                    // "[0][MVP..] xxx[3]joined 大厅!"。它每帧命中、再也不会送模型，
                    // repair 也补不掉 joined / killed 这类普通动词。清掉让新版本整句重翻。
                    if (CHAT_BLOCK_KEY.matcher(key).find() && CJK.matcher(value).find()
                            && !residualWords(value).isEmpty()) {
                        synchronized (diskCache) {
                            diskCache.remove(key);
                        }
                        fixed++;
                        continue;
                    }
                    // 折行残句（"... on The"）被小模型翻出一对空书名号并写进磁盘：
                    // 这种 key 本身就不该存在，删掉让合并后的完整原文重新翻译。
                    if (value.contains("《》") || value.contains("《 》")) {
                        synchronized (diskCache) {
                            diskCache.remove(key);
                        }
                        fixed++;
                        continue;
                    }
                    // 非空的凭空书名号（购买《附魔漏斗》）只去符号、保留中文，就地修好。
                    String repaired = Glossary.stripBogusQuotes(null, Glossary.repair(value));
                    if (repaired != null && !repaired.equals(value) && !repaired.isBlank()) {
                        synchronized (diskCache) {
                            diskCache.put(key, repaired);
                        }
                        fixed++;
                    }
                }
                if (fixed > 0) {
                    markDiskCacheDirty();
                    LOGGER.info("启动自愈：修正/清理磁盘缓存里 {} 条中英混排的旧译文", fixed);
                }
            } catch (Exception e) {
                LOGGER.warn("缓存自愈失败: {}", e.toString());
            }
        }, "tongyongfanyi-cache-repair");
        t.setDaemon(true);
        t.start();
    }

    /**
     * 每次启动都清掉「译文里残留编号标记」的缓存条目。
     * 小模型复读提示词示例时会产生一批 {@code 收集 20 个 <t0> 日志，见 <u0>} 之类的
     * 伪译文并写进磁盘；不用一次性标记文件，因为这类污染可能在任何版本再次出现。
     */
    private void purgeMangledCache() {
        int removed = 0;
        synchronized (diskCache) {
            Iterator<Map.Entry<String, String>> it = diskCache.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<String, String> e = it.next();
                if (e.getValue() == null || looksMangled(e.getValue())) {
                    it.remove();
                    removed++;
                }
            }
        }
        for (Map.Entry<String, String> e : new HashMap<>(memoryCache).entrySet()) {
            if (e.getValue() == null || looksMangled(e.getValue())) {
                memoryCache.remove(e.getKey());
            }
        }
        if (removed > 0) {
            LOGGER.info("已清理 {} 条含占位符残留的污染译文", removed);
            diskCacheDirty = true;
            saveDiskCache();
        }
    }

    /**
     * 一次性净化历史缓存。
     * 旧版本在翻译失败时会把原文当译文写进缓存（含磁盘），污染一旦形成，
     * 即使后来配好密钥或启动了本地模型，这些文本也永远不会再被翻译。
     * 这里把「译文 == 原文」的条目清掉（对英译中而言这类条目几乎必然是被污染的），
     * 并用一个标记文件保证只做一次，避免每次启动都重复丢弃合法的同形译文。
     */
    private void purgePoisonedCacheOnce() {
        if (Files.exists(purgeMarkerPath)) {
            return;
        }
        int removed = 0;
        synchronized (diskCache) {
            Iterator<Map.Entry<String, String>> it = diskCache.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<String, String> e = it.next();
                if (e.getKey().equals(e.getValue())) {
                    it.remove();
                    removed++;
                }
            }
        }
        if (removed > 0) {
            LOGGER.info("已清理 {} 条被旧版本污染的缓存条目（译文与原文相同）", removed);
            diskCacheDirty = true;
            saveDiskCache();
        }
        try {
            Files.createDirectories(purgeMarkerPath.getParent());
            Files.writeString(purgeMarkerPath, "purged " + removed + System.lineSeparator());
        } catch (IOException e) {
            LOGGER.warn("写入缓存净化标记失败: {}", e.getMessage());
        }
    }

    private void saveDiskCache() {
        if (!diskCacheDirty) {
            return;
        }
        try {
            Files.createDirectories(diskCachePath.getParent());
            String json;
            synchronized (diskCache) {
                json = GSON.toJson(diskCache);
            }
            // 先写临时文件再原子替换，避免游戏中途崩溃损坏缓存
            Path tmp = diskCachePath.resolveSibling(diskCachePath.getFileName() + ".tmp");
            Files.writeString(tmp, json);
            try {
                Files.move(tmp, diskCachePath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, diskCachePath, StandardCopyOption.REPLACE_EXISTING);
            }
            diskCacheDirty = false;
        } catch (IOException e) {
            LOGGER.warn("保存磁盘缓存失败: {}", e.getMessage());
        }
    }

    /** 标记磁盘缓存有改动，并安排稍后异步刷盘 */
    /**
     * 写缓存。聊天译文单独落到 {@code tongyongfanyi_chat_cache.json}：
     * 以前只留内存，重启后 SkyHanni / Hypixel 那些周期性重复的句子又要重等模型，
     * 看起来就像「一直没翻译」。现在落盘但<b>不进主缓存文件</b>，并用
     * 「3 天没命中自动删 + 最多 3000 条」控制体积，不会挤掉界面/物品名译文。
     */
    private void store(String key, String value, boolean chat) {
        memoryCache.put(key, value);
        if (chat) {
            chatCache.put(key, new ChatEntry(value, System.currentTimeMillis()));
            markChatCacheDirty();
            // 不必每写一条就排序清理，超出上限一截再整批淘汰
            if (chatCache.size() > CHAT_CACHE_MAX + 200) {
                enforceChatCacheLimit();
            }
            return;
        }
        synchronized (diskCache) {
            diskCache.put(key, value);
            trimDiskCacheLocked();
        }
        markDiskCacheDirty();
    }

    private void markDiskCacheDirty() {
        diskCacheDirty = true;
        if (saveScheduled.compareAndSet(false, true)) {
            saver.schedule(() -> {
                saveDiskCache();
                saveScheduled.set(false);
            }, 2, TimeUnit.SECONDS);
        }
    }

    /** 超限时只淘汰最旧条目，不清空全部，避免已缓存内容被整体丢弃 */
    private void trimDiskCacheLocked() {
        int max = Math.max(4096, config.cacheSize * 4);
        if (diskCache.size() <= max) {
            return;
        }
        Iterator<Map.Entry<String, String>> it = diskCache.entrySet().iterator();
        while (diskCache.size() > max && it.hasNext()) {
            it.next();
            it.remove();
        }
    }

    /** 重建翻译器。本地模型的服务地址、模型名、超时等参数变化后必须重建才生效 */
    private OllamaNativeTranslator createTranslator() {
        return OllamaNativeTranslator.create(config);
    }

    /**
     * 当前有多少段文本正在翻译，给左上角状态行用。
     * pending 是常规请求；V 键批量重译走独立任务，以前完全没算进来，
     * 所以「提交 12 句重译」时状态行一直写着「空闲」，看起来像模型没干活。
     */
    public int busyCount() {
        int normal;
        synchronized (pending) {
            // 服务重启后丢掉的请求不该让状态行永远显示「正在翻译」
            long now = System.currentTimeMillis();
            pendingAt.forEach((key, at) -> {
                if (now - at > STALE_PENDING_MS && pending.containsKey(key)) {
                    pending.remove(key);
                }
            });
            pendingAt.keySet().removeIf(key -> !pending.containsKey(key));
            normal = pending.size();
        }
        return normal + batchInFlight.get();
    }

    /**
     * 聊天整句块没有译文时的低频补提交。BilingualComponent 每帧只查缓存
     * （submit=false），首次请求一旦失败（服务重启、超时、模型输出判坏），
     * 这行就再也不会被重新提交，屏幕上永远是英文 —— 这正是聊天漏翻反复出现、
     * 修了几次都只在链路内部打转的原因。这里给渲染路径留一条自救通道，
     * 8 秒节流 + submitTranslate 自带的 pending/attempts 去重，不会刷请求。
     */
    public void resubmitChatIfDue(String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        long now = System.currentTimeMillis();
        Long last = chatResubmitAt.putIfAbsent(text, now);
        if (last != null) {
            if (now - last < CHAT_RESUBMIT_MS) {
                return;
            }
            chatResubmitAt.put(text, now);
        }
        if (chatResubmitAt.size() > 512) {
            chatResubmitAt.clear();
        }
        translateChatAsync(text, r -> { });
    }

    /**
     * 术语表只在「开关打开 且 目标语言是中文」时生效。
     * 目标选英语/日语时还把词换成中文占位符，译出来会是中英混杂。
     */
    private boolean glossaryOn() {
        return config.useGlossary && Glossary.appliesTo(config.effectiveTarget());
    }

    /** 整句就是术语时直接给固定译名（与「句中占位替换」开关分开，见 ModConfig#glossaryExact） */
    private boolean glossaryExactOn() {
        return config.glossaryExact && Glossary.appliesTo(config.effectiveTarget());
    }

    /** 同步查询缓存(内存 + 磁盘) */
    /** 已经尝试过「半翻修复」的缓存键：同一个 key 只重翻一次，避免每帧重复提交 */
    private final Set<String> repairedKeys = ConcurrentHashMap.newKeySet();

    /** 译文里允许保留的英文（品牌名、平台名、常规缩写） */
    private static final Set<String> KEEP_WORDS = Set.of(
            "hypixel", "mojang", "minecraft", "pvp", "pve", "npc", "youtube", "twitch",
            "discord", "google", "java", "bedrock", "fabric", "forge", "sodium", "iris",
            "optifine", "modrinth", "curseforge", "api", "http", "https", "www", "com",
            // 按键名、称号标签：中文玩家也习惯看原样，不算漏翻
            "shift", "ctrl", "alt", "admin", "mvp", "vip",
            // 常见模组 / 工具名：品牌保留，其余交给 Glossary.isPreservedEnglish() 动态判定
            "skyhanni", "skyblocker", "jei", "rei", "neu", "github", "apache",
            "ollama", "cloth", "skywars", "ipbr", "hymt", "qwen");
    /** 等级罗马数字（I / III / XIV）：本来就该保留，不算漏翻 */
    private static final Pattern ROMAN_ONLY = Pattern.compile("[IVXLCDM]{1,5}");

    public String cached(String text) {
        String key = text == null ? null : cacheKey(text);
        if (key != null && text.length() <= 400) {
            recentTexts.put(text, key);
        }
        // 旧版本把玩家 ID 翻成中文写进了缓存（karljyri → 卡里尔里）。
        // 这些键不用删文件，查询时直接当作未命中即可。
        // 「智能翻译」关掉时这里不能过滤 —— 玩家名、称号也要翻，否则缓存永远命中不了。
        if (config.smartTranslate) {
            if (!config.translateSingleWords && TranslationHelper.isPlayerName(text)) {
                return null;
            }
            // 「[VIP] EndPainting」被翻成「[VIP] 结束绘画」这种旧译文，读取时直接当未命中，
            // 不用手动清缓存；这类整行本来也不该翻。
            if (TranslationHelper.isRankedName(text)) {
                return null;
            }
        }
        String hit = memoryCache.get(key);
        if (hit == null) {
            hit = diskCache.get(key);
            if (hit != null && !hit.equals(text) && !looksMangled(hit)) {
                memoryCache.put(key, hit);
            }
        }
        boolean fromChat = false;
        if (hit == null) {
            // 聊天译文单独存，带过期时间；重启后重复出现的提示能直接命中
            hit = chatCacheHit(key);
            fromChat = hit != null;
        }
        // 旧版本把「原文」当译文写进磁盘缓存（Enchanted 一直显示英文就是这原因），
        // 或模型复读提示词示例留下带编号标记的坏译文 —— 两类都当作未命中。
        String clean = hit != null && !hit.equals(text) && !looksMangled(hit) ? hit : null;
        if (clean == null) {
            return null;
        }
        // 屏幕绘制层拿到的常常是译文，鼠标指向重译要把它反查回原文。
        // 每次缓存命中都顺手记一条，成本只有一次字符串归一化。
        HoverTextTracker.link(text, clean);
        // 磁盘缓存里那一大批「半翻」条目（兔子 Bro、Boost 没翻、右键点击 to 打开袋子）
        // 在这里当场补掉，并把补好的结果写回缓存 —— 不调用模型、不用等下一轮重翻。
        // 补表后这条路径能让旧缓存自愈，未翻词清单也不会反复报同一批词。
        String repaired = Glossary.repair(clean, Set.of(), glossaryExactOn());
        // 旧缓存里模型把 ➜ / → / ⏣ 当标点吃掉了：按原相对位置补回来并回写磁盘，
        // 让计分板、菜单的箭头不再「翻译完就消失」，也不必手动清缓存。
        repaired = Glossary.restoreMarks(text, repaired);
        if (!repaired.equals(clean)) {
            if (fromChat) {
                // 自愈后的聊天译文仍留在聊天缓存里，别回流到主磁盘缓存
                ChatEntry ce = chatCache.get(key);
                if (ce != null) {
                    ce.value = repaired;
                    ce.at = System.currentTimeMillis();
                    markChatCacheDirty();
                }
            } else {
                store(key, repaired, false);
            }
            clean = repaired;
        }
        // 旧版本逐行翻译写进来的半英文译文（of 地下墓穴、into 炖菜）当作未命中，重新翻
        if (hasDanglingEnglish(clean)) {
            return null;
        }
        // 旧缓存里 Piercing I → 穿透 我 这类等级误译，读取时就修正，不用手动清缓存
        String fixed = fixRomanI(text, clean);
        // 缓存键里的数字是归一化的（见 cacheKey），这里把当前真实的数值填回去：
        // 金币/血量/时间每变一次都算同一句话，避免同一条计分板被反复重翻。
        String result = fillNumbers(text, fixed);
        // 术语补全已经在上面的 repair 里做过一次；fillNumbers / fixRomanI 只动数字和
        // 单个罗马数字，不会带进新的英文单词，这里不再重复跑一遍大正则（每帧几百次，
        // 重复跑正是「翻译卡卡的」隐形开销）。
        // 旧缓存里的「半翻」条目（Foraging Collections → 采集 Collections）当作未命中重翻一次。
        // 历史数据是「先预替换、模型只翻一半」那版提示词产出的，读的时候修掉比让玩家
        // 手动清缓存好；但同一个 key 只修一次 —— 模型确实翻不出来的词（NPC 名之类）
        // 每帧重翻会把队列打爆。
        if (needsRepair(text, result)) {
            if (repairedKeys.size() > 4000) {
                repairedKeys.clear();
            }
            if (repairedKeys.add(key)) {
                return null;
            }
        }
        // 屏幕真正显示的是 result（可能已经过术语补全/箭头还原/数字回填），
        // 和上面 link 的原始 clean 可能不一样。再记一条，鼠标指向重译才能
        // 把「显示出来的中文」准确反查回原文。
        HoverTextTracker.link(text, result);
        return result;
    }

    /**
     * 译文里还留着「本该翻译的英文实词」吗？
     *
     * <p>用于识别旧的半翻缓存。三重过滤避免误伤：
     * 品牌/平台名（{@link #KEEP_WORDS}）、全大写缩写（VIP/DPS/NPC）、
     * 以及原文里根本没有的词（模型自己写的，重翻也没意义）。
     */
    private static boolean needsRepair(String source, String translated) {
        if (source == null || translated == null || translated.isEmpty()) {
            return false;
        }
        String body = translated.replaceAll("\u00a7[0-9A-Za-z]", "");
        if (!CJK.matcher(body).find()) {
            return false;   // 没有中文 = 完全没翻，另有判定
        }
        String srcLower = source.toLowerCase(java.util.Locale.ROOT);
        Matcher m = Pattern.compile("[A-Za-z]{3,}").matcher(body);
        while (m.find()) {
            String w = m.group();
            String low = w.toLowerCase(java.util.Locale.ROOT);
            if (KEEP_WORDS.contains(low) || Glossary.isPreservedEnglish(low)) {
                continue;
            }
            if (isUppercasedAbbrev(w)) {
                continue;   // VIP / DPS / NPC / XP 这类缩写是有意保留的
            }
            if (!srcLower.contains(low)) {
                continue;   // 原文里没有，不是「没翻」的残留
            }
            return true;
        }
        return false;
    }

    /** 原文没有省略号时，去掉译文多出来的省略号（模型爱给短句补「…」） */
    private static String stripEllipsis(String original, String translated) {
        if (original == null || translated == null) {
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

    /** 连续数字（含千分位、小数、时间 6:20、比例 1,253/1,253）当作一个可变化片段 */
    private static final Pattern NUM_RUN = Pattern.compile("\\d[\\d,.:/]*");

    private static String normalizeNumbers(String text) {
        return text == null ? "" : NUM_RUN.matcher(text).replaceAll("#");
    }

    /**
     * 把原文里当前的数字按顺序填回缓存译文。
     * 数量对不上（模型漏写/合并了数字）时原样返回译文 —— 至少还是中文，
     * 比重新排队再翻一遍要快，也比显示英文好。
     */
    private static String fillNumbers(String source, String translated) {
        if (source == null || translated == null) {
            return translated;
        }
        List<String> nums = new ArrayList<>();
        Matcher sm = NUM_RUN.matcher(source);
        while (sm.find()) {
            nums.add(sm.group());
        }
        if (nums.isEmpty()) {
            return translated;
        }
        Matcher tm = NUM_RUN.matcher(translated);
        StringBuilder sb = new StringBuilder(translated.length());
        int i = 0;
        while (tm.find()) {
            if (i >= nums.size()) {
                return translated;
            }
            tm.appendReplacement(sb, Matcher.quoteReplacement(nums.get(i++)));
        }
        if (i != nums.size()) {
            return translated;
        }
        tm.appendTail(sb);
        return sb.toString();
    }

    /**
     * 缓存键带上语言对：换了目标语言之后，旧语言的译文不能再命中，
     * 否则会出现「改成英文了但显示的还是中文」这种怪事。
     */
    /**
     * 缓存键里的数字归一化成 {@code #}。
     *
     * <p>计分板的 {@code Purse: 1,253,456}、{@code 1,253/1,253 Mana}、
     * 游戏时间 {@code 6:20am} 每变一次就是一个全新字符串，不归一化的话
     * 同一句话会被无限重翻（实测 9 分钟 3979 个请求、缓存只落 140 条）。
     * 命中后用 {@link #fillNumbers} 把当前数值填回译文。
     */
    private String cacheKey(String text) {
        return config.sourceLanguage + ">" + config.effectiveTarget() + "|" + normalizeNumbers(text);
    }

    private static final Pattern LATIN_WORD = Pattern.compile("[A-Za-z]{2,}");
    private static final Pattern CJK = Pattern.compile("[\\u4e00-\\u9fa5]");

    /** 这次结果算不算「没翻」：空、原样抄回、或者目标中文却一个汉字都没有 */
    /** 罗马数字等级：Growth I / Piercing I / ◆ Lava Rune I —— 前面是一个大写开头的英文词 */
    private static final Pattern ROMAN_I = Pattern.compile(
            "(?:[A-Z][A-Za-z'\\-]*\\s+)I(?![A-Za-z])");

    /**
     * 小模型常把等级里的罗马数字 I 当成英语第一人称代词译成「我」
     * （Lapidary I → 宝石匠 我）。只在原文确实是「大写词 + I」等级时，
     * 把译文里的「我」还原成 I；句首 / 小写字后的代词 I（I know、how much I care）
     * 都不匹配，不会误伤。
     */
    private static String fixRomanI(String input, String output) {
        if (output == null || input == null || !output.contains("我")) {
            return output;
        }
        Matcher m = ROMAN_I.matcher(input);
        String out = output;
        while (m.find()) {
            int i = out.indexOf("我");
            if (i < 0) {
                break;
            }
            out = out.substring(0, i) + "I" + out.substring(i + 1);
        }
        return out;
    }

    /** 中文译文里残留的悬空功能词：of 地下墓穴 / the 主城 村庄 / into 炖菜 */
    private static final Pattern DANGLING_EN = Pattern.compile(
            "(?<![A-Za-z])(?:of|the|into|and|to|for|from|with|in|on|by|a|an)(?![A-Za-z])",
            Pattern.CASE_INSENSITIVE);

    private static boolean hasDanglingEnglish(String s) {
        if (s == null) {
            return false;
        }
        // 颜色码必须先剥掉：§a / §c / §e 里的字母会被 DANGLING_EN 当成英文冠词 a，
        // 于是「显示模式 §a[」这种完全正确的译文被判成「中文里夹着英文虚词」，
        // 缓存读不出来、新译文也写不进去 —— SkyHanni 物品栏的文字几乎全都带 §a，
        // 这就是「模型明明翻了，界面却一直英文」的根源。
        // UUID / 长 ID 同理：d538a9b7-e96d 里被数字包夹的 a 也会被当成冠词。
        String body = stripIds(SECTION_CODE.matcher(s).replaceAll(""));
        return CJK.matcher(body).find() && DANGLING_EN.matcher(body).find();
    }

    /** 统计英文单词（≥3 个连续字母）个数 */
    private static int englishWordCount(String s) {
        if (s == null) {
            return 0;
        }
        Matcher m = Pattern.compile("[A-Za-z]{3,}").matcher(s);
        int n = 0;
        while (m.find()) {
            n++;
        }
        return n;
    }

    /**
     * 译文里还残留大片英文 = 模型只翻了一半（把 Year of the Witch! 之类照抄回来）。
     * 这种不能算成功、不能写缓存，要交给 strict 提示词重试。
     * 占位符已剥离，URL 在 <uN> 里不会计入；短句才检查，长段落不折腾。
     *
     * <p><b>两边必须用同一套「该翻的词」口径</b>：以前这里两边都用
     * {@link #englishWordCount} 裸数，
     * {@code [MVP+] Real_corni joined the lobby!} 翻成
     * {@code [MVP+] Real_corni 加入了大厅！} 后仍剩下 MVP / Real / corni
     * 三个「英文词」，{@code 3*3=9 > 5} 直接判「没翻完」。
     * 于是带玩家名字的聊天行**每一句**都被丢弃、永远进不了缓存，
     * 屏幕上只剩术语表换过的「joined the 大厅!」—— 这就是「翻译根本没进行」。
     */
    /**
     * 模型是不是进了「复读循环」/ 输出远超原文。
     *
     * <p>0.8B Q4 这类小模型在陌生长句上容易退化成把同一个短语反复吐出来，
     * 一次能吐几百字（日志里那条「狩猎该怪物…」重复 50 多次）。这跟
     * 「译得不好」不是一回事：它会写进缓存，之后每次渲染都命中，屏幕上
     * 永远是一大坨重复中文，连重翻的机会都没有。所以必须在落盘前拦掉。
     *
     * <p>两个信号都给得很宽松，正常译文不会碰到：
     * 英译中一般比原文短，超过 3 倍（或 80 字）基本就是复读；
     * 另外从尾部往前数同一个短单元连续出现 4 次以上也判退化。
     */
    public static boolean degenerateOutput(String source, String out) {
        if (source == null || out == null || out.isEmpty()) {
            return false;
        }
        int n = out.length();
        if (n > Math.max(80, source.length() * 3)) {
            return true;
        }
        if (n < 16) {
            return false;
        }
        for (int unit = 2; unit <= 12 && unit * 4 <= n; unit++) {
            String tail = out.substring(n - unit);
            int count = 0;
            int pos = n - unit;
            while (pos >= 0 && out.startsWith(tail, pos)) {
                count++;
                pos -= unit;
            }
            if (count >= 4) {
                return true;
            }
        }
        return false;
    }

    private static boolean residualTooHigh(String input, String output) {
        if (input == null || output == null || input.length() > 200) {
            return false;
        }
        // 颜色码不是英文：先剥掉再数残留词，否则 §a 里的 a 会被算成冠词，
        // SkyHanni / Skyblocker 带 §a 的正确译文会一直判失败。
        // UUID / 长十六进制 ID 也一样，它们本来就该原样保留。
        input = stripIds(SECTION_CODE.matcher(input).replaceAll(""));
        output = stripIds(SECTION_CODE.matcher(output).replaceAll(""));
        String strip = "<\\s*/?\\s*[A-Za-z]{1,12}\\s*\\d*\\s*>";
        if (hasDanglingEnglish(output)) {
            return true;   // 中文里夹着 of / the / into，一定是逐行翻译的残留
        }
        // 只数「本该翻掉」的英文实词：玩家 ID、全大写缩写、罗马数字、
        // URL、术语保留词都不算残留（见 residualWords）。
        String inBody = input.replaceAll(strip, " ");
        String outBody = output.replaceAll(strip, " ");
        int in = residualWords(inBody).size();
        // 专名豁免：译文里剩下的词，如果原文也有、且首字母大写，那是 NPC / 物品 /
        // 地名这类「该保留的专名」，不是漏翻。
        //   Rabbit Bro - [180] Director  → Rabbit Bro - [180] 导演
        //   This requires Chocolate Factory V!  → 这需要 Chocolate Factory V！
        // 以前这两条都判「没翻完」→ 不写缓存 → 每 60 秒重来一次，永远翻不出来。
        // 纯英文照抄不给豁免（否则整句英文会被当成「专名都保留」而写进缓存）。
        int out = CJK.matcher(outBody).find()
                ? residualWords(outBody, inBody).size()
                : residualWords(outBody).size();
        return in >= 2 && out >= 2 && out * 3 > in;
    }

    /** 字符串里的拉丁字母个数（用于判断译文是否还残留大片英文） */
    private static int latinCount(String s) {
        if (s == null) {
            return 0;
        }
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')) {
                n++;
            }
        }
        return n;
    }

    private boolean looksUntranslated(String input, String output) {
        if (output == null || output.isBlank()) {
            return true;
        }
        String in = input == null ? "" : input.trim();
        String out = output.trim();
        if (out.equalsIgnoreCase(in)) {
            return true;
        }
        if (config.effectiveTarget() != null && config.effectiveTarget().startsWith("zh")) {
            return !CJK.matcher(out).find() && LATIN_WORD.matcher(in).find();
        }
        return false;
    }

    /**
     * 送给模型前做一点归一化：
     * <ul>
     *   <li>去掉首尾空格（{@code "Warp To "} 这种界面残留会让模型不知道该不该补全）</li>
     *   <li>全大写多词短语转小写（{@code GHOST VISITORS} 容易被当成缩写原样保留）</li>
     * </ul>
     */
    private static String normalizeForModel(String text) {
        if (text == null) {
            return null;
        }
        String t = text.trim();
        if (t.indexOf(' ') > 0 && t.matches("[A-Z0-9 .,!?&'’\\-]{4,}") && t.matches(".*[A-Z].*[A-Z].*")) {
            t = t.toLowerCase(java.util.Locale.ROOT);
        }
        return t;
    }

    /** 单段最大字符数：中文 400 字约 400 token，加上提示词仍远低于 1536 */
    private static final int CHUNK_CHARS = 400;
    private static final Pattern SENTENCE_END = Pattern.compile("(?<=[。！？!?；;\\n])");

    /**
     * 把长文本切成若干段，尽量在句末断开。
     * 没有标点的超长句（物品 lore 常见）按 {@code max} 硬切，避免再次超上下文。
     */
    private static List<String> splitChunks(String text, int max) {
        if (text == null || text.length() <= max) {
            return List.of(text);
        }
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String sentence : SENTENCE_END.split(text)) {
            if (sentence.isEmpty()) {
                continue;
            }
            if (sentence.length() > max) {
                // 先冲掉手上的，再把这句硬切
                if (cur.length() > 0) {
                    out.add(cur.toString());
                    cur.setLength(0);
                }
                // 尽量在词边界切：从中间硬切会把从句/短语劈成两半，
                // 两段各自翻译再拼回去，语序就乱了。
                for (int i = 0; i < sentence.length(); ) {
                    int end = Math.min(sentence.length(), i + max);
                    if (end < sentence.length()) {
                        int space = sentence.lastIndexOf(' ', end);
                        if (space > i + max / 2) {
                            end = space + 1;
                        }
                    }
                    out.add(sentence.substring(i, end));
                    i = end;
                }
                continue;
            }
            if (cur.length() > 0 && cur.length() + sentence.length() > max) {
                out.add(cur.toString());
                cur.setLength(0);
            }
            cur.append(sentence);
        }
        if (cur.length() > 0) {
            out.add(cur.toString());
        }
        return out.isEmpty() ? List.of(text) : out;
    }

    /** 按尝试次数选提示词：第 2 次起强约束，单词术语到第 3 次用术语提示词 */
    private static final Pattern SEGMENT_MARK = Pattern.compile("^\\[\\d+\\]");

    private String callModel(String payload, int attemptNo, boolean singleWord) throws Exception {
        // 带 [数字] 分段标记的整句：走「保留颜色标记」的提示词，别让模型把标记吃掉
        if (SEGMENT_MARK.matcher(payload.trim()).find()) {
            return translator.translateSegmented(payload, config.sourceLanguage, config.effectiveTarget());
        }
        // 短物品名（≤4 词）也走术语专用提示词：小模型遇到 "Ender Belt" 这种
        // 会当成专有名词照抄，普通/强约束提示词重试 5 次都翻不出来。
        boolean shortPhrase = payload.length() <= 32
                && payload.trim().split("\\s+").length <= 4;
        if (attemptNo >= 2 && (singleWord || shortPhrase)) {
            return translator.translateTerm(payload, config.effectiveTarget());
        }
        if (attemptNo >= 1) {
            return translator.translateStrict(payload, config.sourceLanguage, config.effectiveTarget());
        }
        return translator.translate(payload, config.sourceLanguage, config.effectiveTarget());
    }

    /**
     * 网址 + Hypixel 图标字体字符（私用区 U+E000–U+F8FF）：模型最容易改坏的东西，
     * 翻译前必须先摘出来。图标丢了会变成「1,253/1,253 法力值」这种缺了心形/盾牌的样子。
     */
    private static final Pattern URL_FULL = Pattern.compile(
            "(?i)(https?://\\S+|www\\.[\\w.-]+\\S*|\\b(?:\\d{1,3}\\.){3}\\d{1,3}(?::\\d+)?\\S*"
                    + "|\\b(?:[\\w-]+\\.)+(?:net|com|org|io|gg|tv|me|co|dev|cn)\\b\\S*"
                    + "|[\uE000-\uF8FF]+)");

    private static String protectUrls(String text, List<String> urls) {
        Matcher m = URL_FULL.matcher(text);
        StringBuilder sb = new StringBuilder(text.length());
        while (m.find()) {
            urls.add(m.group());
            m.appendReplacement(sb, Matcher.quoteReplacement("<u" + (urls.size() - 1) + ">"));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String restoreUrls(String translated, List<String> urls) {
        if (urls.isEmpty() || translated == null) {
            return translated;
        }
        Matcher m = Pattern.compile("<\\s*[uU]\\s*(\\d+)\\s*>").matcher(translated);
        StringBuilder sb = new StringBuilder(translated.length());
        while (m.find()) {
            int idx;
            try {
                idx = Integer.parseInt(m.group(1));
            } catch (NumberFormatException e) {
                continue;
            }
            String url = idx >= 0 && idx < urls.size() ? urls.get(idx) : m.group();
            m.appendReplacement(sb, Matcher.quoteReplacement(url));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** 译文里是否残留占位符痕迹（模型吞掉/改写 <tN> 的迹象） */
    /**
     * 译文里残留的编号标记。只认我们自己的 {@code <tN>}/{@code <uN>} 和模型常见的
     * 乱码形态，不用宽泛的 {@code <[a-zA-Z]+>} —— 那会误伤 {@code <VIP>} 之类的正常文本。
     */
    /**
     * 任何「短尖括号字母标记」都算坏占位符。以前只认 t/u 开头，结果 1.8B 把
     * {@code <t0>} 改写成 {@code <a>} 就直接漏检，屏幕上出现「由 <a> 生产出来」。
     * 这个正则同时覆盖 &lt;t0&gt;、&lt;u0&gt;、&lt;bag&gt;、&lt;a&gt; 等所有变体；
     * 启动净化与 cached() 都用它，旧污染缓存会自动失效。
     */
    private static final Pattern MANGLED = Pattern.compile(
            "<\\s*/?\\s*[A-Za-z]{1,12}\\s*\\d*\\s*>",
            Pattern.CASE_INSENSITIVE);

    private static boolean looksMangled(String s) {
        return s != null && MANGLED.matcher(s).find();
    }

    /**
     * 值不值得重试：两个以上英文词的长句，或者单个「首字母大写」的词。
     *
     * <p>后者是游戏里最常见的漏翻形态——{@code Catacombs}、{@code Dungeon}、
     * {@code Auction} 这类术语会被模型当成专有名词照抄，但它其实该翻。
     * 全大写（{@code SKYBLOCK}）和带符号的（{@code MVP+}）不在这里折腾，那多半是 ID。
     */
    private static boolean worthRetry(String text) {
        if (text == null) {
            return false;
        }
        String t = text.trim();
        if (t.length() >= 8) {
            Matcher m = LATIN_WORD.matcher(t);
            int words = 0;
            while (m.find()) {
                if (++words >= 2) {
                    return true;
                }
            }
        }
        return isCapitalizedTerm(t);
    }

    private static boolean isCapitalizedTerm(String t) {
        if (t.length() < 4 || t.length() > 24 || !Character.isUpperCase(t.charAt(0))) {
            return false;
        }
        for (int i = 1; i < t.length(); i++) {
            char c = t.charAt(i);
            if (Character.isLowerCase(c)) {
                return true;   // 后面出现小写字母，说明是正常的词而不是全大写 ID
            }
            if (!Character.isLetter(c) && c != ' ' && c != '\'' && c != '-') {
                return false;  // 混了数字/符号（MVP+、ID_123），当 ID 处理
            }
        }
        return false;
    }

    /** 单个英文单词（不含空格、数字、符号），用于判断要不要走术语提示词 */
    private static boolean isSingleWord(String text) {
        if (text == null) {
            return false;
        }
        String t = text.trim();
        if (t.length() < 3 || t.length() > 24) {
            return false;
        }
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (!Character.isLetter(c) && c != '\'' && c != '-') {
                return false;
            }
        }
        return true;
    }

    /** 异步翻译：按来源类别取用户设定的优先级，结果经回调返回 */
    public void translateAsync(String text, TranslationCallback callback, int category) {
        submitTranslate(text, callback, queuePriority(category), category == CAT_CHAT);
    }

    /** 异步翻译（默认按 HUD 档位） */
    public void translateAsync(String text, TranslationCallback callback) {
        translateAsync(text, callback, CAT_HUD);
    }

    /** 聊天专用：优先级同样取设置里的「聊天栏」档位 */
    public void translateChatAsync(String text, TranslationCallback callback) {
        submitTranslate(text, callback, queuePriority(CAT_CHAT), true);
    }

    /** 同一段文本最多尝试次数：连续 5 次都翻不出目标语言就暂停重试 */
    private static final int MAX_ATTEMPTS = 5;
    /** 两次重试之间的最小间隔，避免短时间把 5 次预算全打光 */
    private static final long RETRY_INTERVAL_MS = 1500L;
    /**
     * 放弃多久之后给一次重来的机会。以前「5 次翻不出来」是永久的，
     * 于是计分板上 Give Rose Red Dye to Marco 这种行会一直显示英文，
     * 就算后来补了术语表也回不来，玩家只能手动清缓存。
     */
    private static final long ATTEMPT_COOLDOWN_MS = 60_000L;
    /**
     * 「已经确定翻不出来」的文本再次放行的间隔。
     *
     * <p>60 秒的冷却对真人对话够用，对计分板就不够：同一行「[335] WIZARD_ALVIN」
     * 每分钟刷新一次，于是每分钟都被重新提交、重新失败、重新打一行日志，
     * 实测一小时能刷出上千次请求，把真正要翻的文本挤在队列后面。
     * 攒满 {@link #MAX_ATTEMPTS} 之后改成 30 分钟放行一次：
     * 既不彻底封死（补了术语表还能自动回来），也不会一直在空转。
     * 手动按 R / V 强制重译会立刻清掉这条记录。
     */
    private static final long HOPELESS_COOLDOWN_MS = 30 * 60_000L;
    /** 尝试计数表；条目上限兜底，防止长局里无限增长 */
    private final Map<String, Attempt> attempts = new java.util.concurrent.ConcurrentHashMap<>();
    /** 攒满失败次数后放弃的文本 → 放弃时刻 */
    private final Map<String, Long> hopeless = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 在线 API 鉴权失败（401/403）的时刻，0 表示正常。
     *
     * <p><b>为什么单独熔断：</b>key 填错后，聊天栏每出现一行新文本都会带着同一个错 key
     * 再请求一次。日志里实测 30 秒刷出几百条 401，每条还要走「重试 5 次 → 30 分钟冷却」
     * 的流程，既刷屏又白占翻译线程。鉴权错误是配置问题，重试一万次也还是 401，
     * 只能等玩家改配置 —— 所以这里一次性停掉整条在线链路，全部改走本地兜底。
     *
     * <p>改完 API 地址/协议/模型/密钥后调用 {@link #clearAuthFailure()} 恢复。
     */
    private static volatile long authFailedAt = 0;
    private static volatile String authFailedMessage = "";

    /** 当前是否处于「鉴权失败」熔断状态（HUD / 设置页可用来提示玩家） */
    public static boolean authFailed() {
        return authFailedAt > 0;
    }

    public static String authFailedMessage() {
        return authFailedMessage;
    }

    /** 玩家改了 API 配置：解除熔断，下次请求重新试 */
    public static void clearAuthFailure() {
        authFailedAt = 0;
        authFailedMessage = "";
        // 状态行/聊天栏里那条「鉴权失败」也一起清掉，别让玩家改完了还看着红字
        com.tongyongfanyi.local.BundledRuntime.clearError();
    }

    private static void noteAuthFailure(String message) {
        if (authFailedAt > 0) {
            return;
        }
        authFailedAt = System.currentTimeMillis();
        authFailedMessage = message == null ? "" : message;
        // 同时报到运行时状态里：HUD 状态行和聊天栏提示都靠它，
        // 否则玩家只看到「翻译没反应」，根本不知道是密钥/地址配错了。
        com.tongyongfanyi.local.BundledRuntime.reportError("在线 API 鉴权失败：" + authFailedMessage);
        LOGGER.warn("在线 API 鉴权失败，已暂停在线翻译（改好 API 配置后自动恢复）: {}", authFailedMessage);
    }

    /** 判断异常是不是「配置错了」而不是「这次运气不好」 */
    private static boolean isAuthError(String lowerMsg) {
        return lowerMsg.contains("api 返回 401") || lowerMsg.contains("api 返回 403")
                || lowerMsg.contains("invalid api key") || lowerMsg.contains("invalid_api_key")
                || lowerMsg.contains("authentication fails") || lowerMsg.contains("unauthorized");
    }

    /** 当前是否在线 API 后端：本地自愈（重新加载模型、超时 streak）对它没有意义 */
    private boolean isApiBackend() {
        ModConfig cfg = config();
        return cfg != null && "api".equalsIgnoreCase(cfg.translateBackend);
    }

    /** 某段文本的失败计数 */
    private static final class Attempt {
        int count;
        long lastAt;
    }

    /**
     * 记一次失败；攒满预算就转入「放弃名单」，30 分钟内不再请求、也不再打日志。
     *
     * <p>原来的 60 秒冷却对计分板完全不够用：同一行「[335] WIZARD_ALVIN」每分钟
     * 刷新一次，于是每分钟重新提交、重新失败、重新打一行日志。一晚上 163 条
     * 「放弃这段文本」就是这么刷出来的，而且每次都占着模型去确认一个玩家名
     * 不需要翻译，真正要翻的文本只能排在后面。
     */
    private void noteFailure(String key, int maxAttempts, String text) {
        Attempt a = attempts.computeIfAbsent(key, k -> new Attempt());
        a.count++;
        a.lastAt = System.currentTimeMillis();
        if (a.count >= maxAttempts) {
            hopeless.put(key, System.currentTimeMillis());
            attempts.remove(key);
            LOGGER.info("连续 {} 次没翻出结果，30 分钟内不再重试: {}", maxAttempts, text);
        }
        if (attempts.size() > 4096) {
            attempts.clear();
        }
        if (hopeless.size() > 4096) {
            hopeless.clear();
        }
    }

    /** 颜色码：判空之前先剥掉，纯装饰行（§7§6§7§6…）不该送模型 */
    private static final Pattern SECTION_CODE = Pattern.compile("§[0-9A-Za-z]");

    /**
     * UUID / 长十六进制 ID。
     *
     * <p>必须从文本里先剔掉再判「残留英文」：UUID 里带连字符的十六进制段
     * （{@code d538a9b7-e96d-…}）会出现被数字包夹的单个 {@code a}/{@code b}，
     * 而 {@link #DANGLING_EN} 的判定是「前后不是字母」，于是那个 {@code a}
     * 被当成英文冠词 —— {@code Profile ID: <uuid>} 翻成「档案 ID: <uuid>」
     * 明明是对的，却每帧都判「中文里夹英文虚词」，重试 5 次进 30 分钟冷却。
     */
    private static final Pattern ID_TOKEN = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"
                    + "|[0-9a-fA-F]{16,}");

    private static String stripIds(String s) {
        return s == null ? null : ID_TOKEN.matcher(s).replaceAll(" ");
    }

    /** 聊天整句块的分段标记 [0] [1] …（由 TranslationHelper 加在每段前面） */
    private static final Pattern CHAT_MARK = Pattern.compile("\\[\\d{1,3}\\]");

    /**
     * 完全不需要送模型的文本。日志里这类行能刷出上百次超时
     * （www.hypixel.net / None / [Lvl 1] / 纯装饰色码），把队列堵死，
     * 真正要翻的文本反而跟着排队超时。日语/俄语聊天必须保留。
     */
    private static boolean noTranslatableContent(String text) {
        if (text == null) {
            return true;
        }
        String t = CHAT_MARK.matcher(SECTION_CODE.matcher(text).replaceAll("")).replaceAll(" ").trim();
        if (t.isEmpty()) {
            return true;
        }
        String low = t.toLowerCase(java.util.Locale.ROOT);
        if (low.equals("none") || low.equals("n/a") || low.equals("null")
                || low.equals("-") || low.equals("--") || low.equals("...")
                || (low.startsWith("[lvl ") && low.endsWith("]"))) {
            return true;
        }
        // 整行就是一个网址（句中含链接的句子不拦，交给 protectUrls 处理）
        if (!t.contains(" ") && (low.startsWith("www.") || low.startsWith("http://")
                || low.startsWith("https://"))) {
            return true;
        }
        // 标签就是整行：[TFT] [BROKE] [RISE] [DRACHE✧]。Hypixel 的计分板/聊天里
        // 队伍标签会单独成行，模型只会原样吐回来，然后被判「没翻完」再重试到放弃。
        if (TAG_ONLY.matcher(t).matches()) {
            return true;
        }
        // 单独一条 UUID（SkyHanni 会把玩家 UUID 发在聊天里）
        if (UUID_ONLY.matcher(t).matches()) {
            return true;
        }
        // 排行榜行：`1. tteqq [MEOW] - 10,001`。名次、玩家名、队伍、分数，
        // 一个可译词都没有，但计分板每秒刷新一次，于是每秒都提交一遍、
        // 原样返回、判失败。SkyBlock 的排行榜（伤害/收藏/活动）全是这个格式。
        if (LEADERBOARD_ROW.matcher(t).matches()) {
            return true;
        }
        // 整行由「ID 样 token + 装饰符号」组成：只有 ID 没有可译词。
        // [335] WIZARD_ALVIN / [303] Xelas_Shep α / 正在前往mini199AD……
        // 术语表里能查到的（SkyBlock / Minion …）不算，那些有固定译名。
        if (t.length() <= 32 && Glossary.exact(t) == null && onlyIdentifierTokens(t)) {
            return true;
        }
        // 一个字母都没有 = 纯数字/标点/装饰。汉字、假名、谚文都算字母，
        // 目标语言改成英文时纯中文仍要送模型，不能在这里拦掉。
        return !t.codePoints().anyMatch(cp -> Character.isLetter(cp));
    }

    /** 整行就是一个标签：[TFT] / [BROKE] [RISE] */
    private static final Pattern TAG_ONLY = Pattern.compile("^(\\[[^\\]]{1,20}\\]\\s*)+$");
    private static final Pattern UUID_ONLY = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    /** 排行榜行：`1. tteqq [MEOW] - 10,001` / `3. name - 45.2%` */
    private static final Pattern LEADERBOARD_ROW = Pattern.compile(
            "^\\d{1,3}\\.\\s*[A-Za-z0-9_]{2,20}\\s*(\\[[^\\]]{1,16}\\]\\s*)?[-–—:]\\s*[\\d,.]+\\s*[A-Za-z%]*$");

    /**
     * 整行每个含字母的 token 都像「机器标识」：下划线、驼峰、或夹着数字。
     *
     * <p>这类文本（玩家名、UUID 片段、被折行切下来的 {@code Lv49]}）没有任何可译语义，
     * 送进模型一定原样返回；而 {@link #noTranslatableContent} 里逐条硬编码又永远列不全，
     * 干脆按形态统一判断。{@code Skeleton}、{@code Minion}、{@code Enchanted} 这类
     * 正常单词不满足「像 ID」，照常翻译。
     */
    private static boolean onlyIdentifierTokens(String t) {
        boolean anyWord = false;
        for (String tok : t.split("\\s+")) {
            String core = tok.replaceAll("[^A-Za-z0-9_']", "");
            if (core.isEmpty() || !core.matches(".*[A-Za-z].*")) {
                continue;
            }
            anyWord = true;
            if (!looksLikeIdentifier(core)) {
                return false;   // 出现一个正常单词，就该翻译
            }
        }
        return anyWord;
    }

    /** ID 形态：含下划线、含数字、或第 2 个字母起有大写（驼峰） */
    private static boolean looksLikeIdentifier(String w) {
        if (w.indexOf('_') >= 0 || w.matches(".*\\d.*")) {
            return true;
        }
        for (int i = 1; i < w.length(); i++) {
            if (Character.isUpperCase(w.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 模型没给出译文时，用术语表把原文里认识的词本地翻掉。
     * 只用于显示、不写缓存：服务恢复后下一次请求仍会重新翻译整句。
     */
    private String localFallback(String text) {
        if (!glossaryExactOn()) {
            return text;
        }
        String out = Glossary.localize(text);
        return out == null || out.isBlank() ? text : out;
    }

    /** 只压掉正文中间的重复空格，保留首尾空白（居中 padding / 箭头装饰） */
    private static String collapseInnerSpaces(String s) {
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
        return s.substring(0, a)
                + s.substring(a, b).replaceAll("[ \\t]{2,}", " ")
                + s.substring(b);
    }

    /**
     * 短物品名 / 标题的「本地直译」：术语表能把整句拼成中文时直接返回译文，
     * 完全不排队、不等模型。
     *
     * <p>截图里的 {@code Hotspot Tonic Mixin} 一直显示英文，缓存里连条目都没有 ——
     * 日志显示它每次请求都超时。1.8B 小模型对生造专名本来就只会照抄，
     * 而词表拼出来的结果反而更准。让这类短名称绕开模型，服务卡死时也能正常显示，
     * 同时少一批每帧重复提交的僵尸请求，反过来减轻 Ollama 队列压力。
     *
     * <p>只在「拼得完整」时采纳：译文里还留着英文实词就返回 null，交回模型，
     * 免得把 {@code Trick or Treat} 这种写成半中半英还写进缓存。
     */
    private String completeLocalTranslation(String text) {
        if (text == null || text.length() > 48 || !glossaryExactOn()) {
            return null;
        }
        String plain = ANY_SEGMENT.matcher(SECTION_CODE.matcher(text).replaceAll(""))
                .replaceAll("").trim();
        if (plain.isEmpty() || plain.split("\\s+").length > 5) {
            return null;
        }
        // 原文必须是纯英文短名称；已翻译过（含汉字）的句子交给缓存/模型处理
        if (CJK.matcher(plain).find() || !LATIN_WORD.matcher(plain).find()) {
            return null;
        }
        if (plain.startsWith("/") || URL_FULL.matcher(plain).find()) {
            return null;
        }
        if (TranslationHelper.isPlayerName(plain) || TranslationHelper.isRankedName(plain)) {
            return null;
        }
        String local = Glossary.localize(text);
        if (local == null || local.equals(text)) {
            return null;
        }
        String check = ANY_SEGMENT.matcher(SECTION_CODE.matcher(local).replaceAll(""))
                .replaceAll("");
        Matcher m = LATIN_WORD.matcher(check);
        while (m.find()) {
            String w = m.group();
            // 全大写缩写、罗马数字等级、玩家 ID 形态本地就该保留
            if (w.equals(w.toUpperCase(java.util.Locale.ROOT))
                    || ROMAN_ONLY.matcher(w).matches()
                    || w.indexOf('_') >= 0
                    || w.matches(".*[a-z][A-Z].*")) {
                continue;
            }
            return null;
        }
        return local;
    }

    /**
     * 目标语言刚好就是这段文字本身的语言时不用翻。
     * 例如玩家把游戏语言设成英文、目标选「跟随游戏语言」，此时再做「英译英」
     * 模型只会照抄原文，接着被判失败、白重试 5 次还刷日志。
     */
    private static boolean alreadyInTargetLanguage(String text, String target) {
        if (target == null || text == null) {
            return false;
        }
        String t = target.toLowerCase(java.util.Locale.ROOT);
        boolean hasLatinWord = LATIN_WORD.matcher(text).find();
        boolean hasHan = CJK.matcher(text).find();
        boolean hasOtherScript = text.codePoints().anyMatch(cp ->
                (cp >= 0x3040 && cp <= 0x30FF)        // 假名
                        || (cp >= 0xAC00 && cp <= 0xD7AF) // 谚文
                        || (cp >= 0x0400 && cp <= 0x04FF) // 西里尔
                        || (cp >= 0x0370 && cp <= 0x03FF) // 希腊
                        || (cp >= 0x0600 && cp <= 0x06FF) // 阿拉伯
                        || (cp >= 0x0E00 && cp <= 0x0E7F));// 泰文
        if (t.startsWith("en")) {
            return !hasHan && !hasOtherScript && hasLatinWord;
        }
        if (t.startsWith("zh")) {
            return hasHan && !hasLatinWord;
        }
        return false;
    }

    private void submitTranslate(String text, TranslationCallback callback, int priority, boolean chat) {
        // 鼠标正悬停在某个物品上：它的 tooltip 插到队头。
        // 玩家此刻眼睛盯着这几行，等的是它，不是背景里的计分板；
        // 而且 tooltip 只在该物品被指着时才构建，信号很干净（见 ItemStackMixin）。
        if (priority < HOVER_PRIORITY && TranslationHelper.hoverBoost()) {
            priority = HOVER_PRIORITY;
        }
        // 目标语言就是原文语言：直接放行，别浪费模型请求
        if (alreadyInTargetLanguage(text, config.effectiveTarget())) {
            callback.onResult(text);
            return;
        }
        // 网址、纯数字/标点/装饰、None 这类没有任何可译内容的行不进队列
        if (noTranslatableContent(text)) {
            callback.onResult(text);
            return;
        }
        // 玩家 ID 不送模型：Tab 列表里的用户名翻不出来还挂超时 + 重试，
        // 会把线程占满、正常文本全在排队。放在这里是统一入口，
        // 聊天 / 计分板 / 物品提示 / 命令都覆盖。
        // 「单个词也翻译（含人名）」打开时按标签语义放行玩家 ID（用户自己选的）。
        // 智能翻译关掉 = 用户要求「只要是英文都翻」，这两道过滤一起放行。
        if (config.smartTranslate) {
            if (!config.translateSingleWords && TranslationHelper.isPlayerName(text)) {
                callback.onResult(text);
                return;
            }
            // 「称号 + 玩家名」整行没有可译内容，开关打开也照样放行（翻成「结束绘画」是错的）
            if (TranslationHelper.isRankedName(text)) {
                callback.onResult(text);
                return;
            }
        }
        String key = cacheKey(text);
        final boolean singleWord = isSingleWord(text);
        final int maxAttempts = singleWord ? 3 : MAX_ATTEMPTS;

        // 整句就是术语（Enchanted / Minion / Auction House …）：优先级最高。
        // 必须排在缓存之前 —— 旧版本把「原文当译文」写进磁盘缓存后，
        // 即使术语表里有固定译名也永远命中不了，这正是 enchanted 一直不翻的原因。
        if (glossaryExactOn()) {
            String direct = Glossary.exact(text);
            if (direct != null) {
                store(key, direct, chat);
                callback.onResult(direct);
                // 只有聊天译文才需要重排聊天栏；计分板/物品提示翻好不该去动聊天
                if (chat) {
                    notifyCacheUpdated();
                }
                return;
            }
        }

        // cached() 会把「译文 == 原文」的旧污染条目当作未命中
        String hit = cached(text);
        if (hit != null) {
            callback.onResult(hit);
            return;
        }

        // 短物品名 / 标题：词表能拼出完整中文就直接写缓存返回，不等模型。
        // 聊天不在此列（玩家对话要走模型，且译文落在独立的聊天缓存里（带自动过期删除））。
        if (!chat) {
            String localFull = completeLocalTranslation(text);
            if (localFull != null) {
                store(key, localFull, false);
                callback.onResult(localFull);
                return;
            }
        }

        // 模型正在后台重新加载（连续超时后的自愈流程）：这十来秒里发出去的请求
        // 必然超时，旧版还把它们堆进队列，于是「加载 → 超时 → 再卸载」死循环。
        // 这里直接给术语表兜底，不入队也不计失败；加载完成后下一帧自然重新翻译。
        if (com.tongyongfanyi.local.BundledRuntime.inReloadGrace()) {
            callback.onResult(localFallback(text));
            return;
        }

        // 在线 API 鉴权失败：地址/模型/密钥有一个对不上，再发请求也只会拿到同样的 401。
        // 整条链路先停掉，显示术语表兜底，等玩家改完 API 配置再恢复（见 clearAuthFailure）。
        if (authFailedAt > 0) {
            callback.onResult(localFallback(text));
            return;
        }

        // 已经判定「翻不出来」的文本：30 分钟内不再发起请求，也不再打日志。
        // 到期后清掉记录，让它有机会再试一次（术语表可能已经补上词了）。
        Long gaveUpAt = hopeless.get(key);
        if (gaveUpAt != null) {
            if (System.currentTimeMillis() - gaveUpAt < HOPELESS_COOLDOWN_MS) {
                callback.onResult(localFallback(text));
                return;
            }
            hopeless.remove(key);
            attempts.remove(key);
        }

        Attempt attempt = attempts.get(key);
        if (attempt != null) {
            if (attempt.count >= maxAttempts) {
                // 冷却过了就放一次进来（术语表可能刚补过词、模型可能重新就绪），
                // 否则一段文本会永远停在英文上。
                if (System.currentTimeMillis() - attempt.lastAt < ATTEMPT_COOLDOWN_MS) {
                    callback.onResult(localFallback(text));
                    return;
                }
                attempt.count = maxAttempts - 1;
            }
            if (System.currentTimeMillis() - attempt.lastAt < RETRY_INTERVAL_MS) {
                // 刚试过，等一会儿再说（期间显示术语表兜底，而不是整行英文）
                callback.onResult(localFallback(text));
                return;
            }
        }
        final int attemptNo = attempt == null ? 0 : attempt.count;

        // 同一文本已有请求在途时，只追加回调，不重复发起翻译。
        // 例外：服务崩溃/重启会让在途请求永远不返回，这种陈旧 key 必须释放，
        // 否则该文本此后所有渲染帧都被挡住，聊天行永久停在英文。
        synchronized (pending) {
            List<TranslationCallback> callbacks = pending.get(key);
            if (callbacks != null) {
                Long at = pendingAt.get(key);
                if (at != null && System.currentTimeMillis() - at > STALE_PENDING_MS) {
                    pending.remove(key);
                    pendingAt.remove(key);
                    LOGGER.info("释放超时未返回的翻译请求: {}", key);
                } else {
                    callbacks.add(callback);
                    return;
                }
            }
            callbacks = new ArrayList<>();
            callbacks.add(callback);
            pending.put(key, callbacks);
            pendingAt.put(key, System.currentTimeMillis());
        }

        // 超时从「真正开始执行」算起：队列里可能还有别的任务在排队，
        // 排队时间不该算进请求预算，否则前面一堵，后面的请求还没发出去就已经超时了。
        executor.execute(new PriorityTask(priority, () -> {
          try {
            long deadline = System.currentTimeMillis() + config.timeoutMs;
            String result;
            boolean succeeded = false;
            // 模型是否真的翻出了目标语言（在还原术语前判定）
            boolean realTranslation = false;
            try {
                if (System.currentTimeMillis() > deadline) {
                    result = text;
                } else {
                    // 网址先用 <uN> 保护起来（模型最爱改链接），再做术语占位符保护
                    List<String> urls = new ArrayList<>();
                    String noUrls = protectUrls(text, urls);
                    // 术语**先直接换成中文**再送模型（不再用 <tN> 占位符）。
                    //
                    // 实测三种送法（同一句 Fallen Star 广播，tyfy-hymt2:1.8b）：
                    //   纯英文            → 整句翻出来，但术语可能翻错
                    //   <tN> 占位符        → 只在占位符周围翻一点，其余照抄英文（最差）
                    //   预替换成中文        → 「一颗坠落的恒星已在皇家矿场坠毁！附近的矿石和粉末掉落效果被放大了！」
                    // 小模型对「已经是中文的部分」天然保持原样，只翻剩下的英文，
                    // 术语又能保证用我们固定的叫法，比占位符稳得多。
                    // 术语先直接换成中文再送模型（不用 <tN> 占位符，见上方注释）。
                    // 含拉丁字母的译名（Hyperion、Skeletor 盔甲、钛钻头 DR-X355、
                    // 玩家自定义的 book → Apple）不能直接写进去 —— 模型会再翻一遍，
                    // 所以这类改成 〖①〗 圈码占位，译完 restorePre 还原。
                    //
                    // 但**短的物品名不能预替换**。实测（tyfy-hymt2:1.8b）：
                    //   Mithril Lantern  → 米斯里尔灯笼      （原文能翻）
                    //   秘银 Lantern      → 秘银 Lantern      （预替换后完全不翻）
                    //   Power Crystal    → 能量水晶
                    //   力量 Crystal      → 力量 Crystal
                    // 模型看到「一半中文一半英文」，就把剩下的首字母大写词当成专名直接保留。
                    // 所以 ≤3 个英文词且 ≤30 字的短名走原文，术语的固定性由
                    // 「整条词条直出」（mithril lantern → 秘银灯笼）来保证。
                    // 重试时改用**原文**：第一次预替换后如果模型只翻了半句
                    // （输出里还留着 to / in / of 这类词，被 residualTooHigh 判失败），
                    // 再用同样的中英混排重试 5 次也不会变好 —— 直接给模型完整英文，
                    // 它反而能整句翻出来。术语由译后的 postfix 兜底。
                    boolean retryPlain = attemptNo >= 2;
                    boolean shortName = englishWordCount(noUrls) <= 3 && noUrls.length() <= 30;
                    Glossary.Pre pre = glossaryOn() && !shortName && !retryPlain
                            ? Glossary.preTranslate(noUrls) : Glossary.Pre.none(noUrls);
                    String preText = pre.text();
                    Glossary.Guard guard = new Glossary.Guard(preText, List.of());
                    String payload = normalizeForModel(preText);
                    // 长文本分段：上下文只有 1536 token，整段送进去会被截断，
                    // 译文后半截直接丢失。按句子切成 ≤400 字的块逐段翻译再拼回去。
                    List<String> chunks = splitChunks(payload, CHUNK_CHARS);
                    StringBuilder joined = new StringBuilder(payload.length() + 16);
                    for (String chunk : chunks) {
                        joined.append(callModel(chunk, attemptNo, singleWord && chunks.size() == 1));
                    }
                    result = joined.toString();
                    // 判定必须在「还原术语之前」做：术语还原成中文后，
                    // 哪怕整句原样抄回来也会被误判成"已翻译"（You sold 硬石 x36 for 36 硬币 就是这样漏的）。
                    // 例外：整句全都是术语占位符（EPIC BELT → <t0> <t1>）时，模型照抄占位符，
                    // restore 之后就是完整中文（史诗 腰带），这种必须算成功，否则会白重试 5 次放弃。
                    String bare = result.replaceAll("<\\s*/?\\s*[A-Za-z]{1,12}\\s*\\d*\\s*>", "")
                            .replaceAll("[^A-Za-z]", "");
                    boolean allPlaceholders = guard.changed() && bare.isEmpty();
                    // 有汉字不等于翻完：只翻一半、把 Year of the Witch! 照抄回来的
                    // 也要判失败，否则中英混排的半译文会永久写进缓存显示出来。
                    realTranslation = allPlaceholders
                            || (!looksUntranslated(text, result) && !residualTooHigh(noUrls, result));
                    if (!realTranslation && text != null && text.indexOf('[') >= 0) {
                        // 聊天整句块（带 [N] 分段标记）被判定没翻完时留一条底：
                        // 这类行一旦被判失败就永远显示术语兜底，玩家看到的就是「没翻译」。
                        LOGGER.info("聊天块未通过判定（不写缓存）: {} | 模型输出: {}", text, result);
                    }
                    result = Glossary.restore(result, guard);
                    result = Glossary.restorePre(result, pre);
                    result = restoreUrls(result, urls);
                    // 无条件检查，不能只在 guard.changed() 时查：模型会凭空编出编号标记
                    // （复读提示词示例时最明显），这时 guard/urls 都是空的，两个 restore
                    // 会直接跳过，标记就原样漏进聊天栏和磁盘缓存了。
                    boolean mangled = looksMangled(result);
                    // 第一次没翻好（标记被改坏，或者译文里还夹着 to/in/of 这类残留被
                    // residualTooHigh 判失败）时，立刻用**完整原文**再翻一次。
                    //
                    // 预替换会把句子变成「spawn a 灵体诱饵 while in 地下城」这种中英混排，
                    // 1.8B 经常只翻一半；同样的文本重试 5 次也不会变好。
                    // 给它完整英文它反而能整句翻出来，术语由译后的 postfix 兜底。
                    if ((mangled || !realTranslation) && pre.changed()) {
                        try {
                            String plain = translator.translate(noUrls, config.sourceLanguage,
                                    config.effectiveTarget());
                            String recovered = restoreUrls(plain, urls);
                            boolean ok = !looksMangled(recovered)
                                    && !looksUntranslated(noUrls, recovered)
                                    && !residualTooHigh(noUrls, recovered);
                            if (ok) {
                                result = recovered;
                                realTranslation = true;
                                mangled = false;
                                LOGGER.debug("预替换后半翻，已用原文直译救回: {}", text);
                            }
                        } catch (Exception ignored) {
                            // 救不回来就按下面丢弃处理
                        }
                    }
                    if (mangled) {
                        LOGGER.debug("译文含未配对的编号标记，丢弃这次结果: {}", text);
                        result = text;       // 不写缓存、不显示乱码，交给下次重试
                    } else if (glossaryExactOn()) {
                        // 兜底补全跟「整句术语固定译名」开关走（见上）；useGlossary 只控制
                        // 句中占位替换，不该把「Bazaar → 集市」这类补全一起关掉。
                        result = Glossary.postfix(result, pre.skipKeys());
                        // 地点/术语已放行给模型：模型若照抄英文、由 postfix 兜成中文，
                        // 也应算「真翻译了」，否则结果不写缓存、白白重试 5 次。
                        // 但不能让 postfix 换进去的几个汉字骗过判定（EPIC BELT → 史诗 BELT）：
                        // 残留英文字母超过原文 1/3 时仍算没翻完，不写缓存。
                        if (!realTranslation && !text.contains("http") && text.length() <= 40) {
                            int inLatin = latinCount(text);
                            int outLatin = latinCount(result);
                            realTranslation = !looksUntranslated(text, result)
                                    && !(inLatin >= 4 && outLatin * 3 > inLatin);
                        }
                    }
                    if (result != null) {
                        // 写缓存前统一做一次本地补全：模型只翻一半时，残留的英文实词用术语表
                        // 补成中文、悬空功能词删掉。判定已经在上面做完了，这里不影响重试逻辑，
                        // 但能保证进缓存的永远是干净译文，不会再把半翻结果留给下一轮。
                        result = Glossary.repair(result, pre.skipKeys(), glossaryExactOn());
                        // 模型丢掉的箭头/图标在写缓存前就补上，别等下一次读缓存才自愈
                        result = Glossary.restoreMarks(text, result);
                        result = fixRomanI(noUrls, result);
                        // 原文没有省略号时，擦掉模型自己补的「…」（Talk to → 与…交谈）。
                        // 以前只在显示层擦，缓存直出的路径会把这串点点点直接显示出来。
                        result = stripEllipsis(text, result);
                        // 只压掉正文中间的重复空格。无条件 trim 会把 Hypixel 用来居中的
                        // 前导空格、以及箭头装饰两侧的空格一并抹掉，译文就「失去格式」了。
                        result = collapseInnerSpaces(result);
                        // 原文没有书名号时清掉模型补的《…》：残句 "… on The" 会被
                        // 1.8B 当成书名开头翻出一对空书名号，写进缓存就永远命中。
                        result = Glossary.stripBogusQuotes(text, result);
                    }
                    // 之前这里无条件 succeeded=true，会把 mangled 分支的 false 覆盖掉，
                    // 于是带乱码/占位符的结果照样写进缓存。改成跟 mangled 走。
                    succeeded = !mangled;
                }
            } catch (Exception e) {
                LOGGER.warn("翻译失败: {} | {}", e.getMessage(), text);
                String msg = String.valueOf(e.getMessage()).toLowerCase(java.util.Locale.ROOT);
                if (isAuthError(msg)) {
                    // 401/403：key 错或模型无权限。不计数、不重试，直接停掉整条在线链路，
                    // 否则后面每一行文本都会再刷一条同样的错。
                    noteAuthFailure(e.getMessage());
                } else if (isApiBackend()) {
                    // 在线 API 的其他失败（连不上、超时、5xx）：也报到状态里，
                    // 否则玩家只能看到「翻译没反应」。同一条消息 30 秒内只提示一次。
                    com.tongyongfanyi.local.BundledRuntime.reportError(
                            "在线 API 请求失败：" + e.getMessage());
                } else if (msg.contains("timed out") || msg.contains("timeout")
                        || msg.contains("connectexception") || msg.contains("refused")) {
                    // 只有本地服务才需要「连续超时 → 重新加载模型」的自愈。
                    // 在线 API 超时多半是网络/限流，把 streak 累上去只会让后续请求的
                    // 超时预算被压到 8 秒，反而更容易超时（实测会形成恶性循环）。
                    com.tongyongfanyi.local.BundledRuntime.reportTimeout();
                }
                result = text;
            }

            // 小模型退化防护：0.8B Q4 偶尔会把一个短语循环生成几十次
            // （实测聊天栏整屏「狩猎该怪物狩猎该怪物…」）。这种结果既不能显示、
            // 也不能写缓存，否则每一帧都命中这条坏译文，还永远没机会重翻。
            if (result != null && degenerateOutput(text, result)) {
                LOGGER.info("丢弃复读输出：{} 字原文 -> {} 字译文", text.length(), result.length());
                succeeded = false;
                result = text;
            }

            String finalResult;
            if (succeeded && result != null && !result.isBlank() && realTranslation) {
                // 真翻出来了：写缓存并清掉失败计数
                finalResult = result;
                store(key, finalResult, chat);
                attempts.remove(key);
                com.tongyongfanyi.local.BundledRuntime.reportSuccess();
                // 让聊天栏原地重排，把首次显示出来的英文换成刚翻好的译文
                if (chat) {
                    notifyCacheUpdated();
                }
            } else if (succeeded && result != null && !result.isBlank() && worthRetry(text)) {
                // 模型把原文照抄回来了（生造物品名最常见）。只有「短名称 + 本地能拼出
                // 完整中文」才写缓存。以前这里用 localFallback()，它只替换认识的术语，
                // 把 "[0][MVP..] xxx joined the lobby!" 换成 "joined the 大厅!" 就落盘；
                // 之后每帧都命中这条半成品缓存，整句英文再也没有机会翻了 ——
                // 这正是聊天栏「只翻了几个词、其余全是英文」的主因。
                String local = completeLocalTranslation(text);
                if (local != null) {
                    finalResult = local;
                    store(key, finalResult, chat);
                    attempts.remove(key);
                    if (chat) {
                        notifyCacheUpdated();
                    }
                    LOGGER.info("物品名本地兜底: {} -> {}", text, finalResult);
                } else {
                    // 本地也翻不动：计一次失败，下轮再试；攒满就转进放弃名单。
                    // 本地替换仅用于本次显示，绝不写缓存，服务恢复后还能整句重翻。
                    finalResult = localFallback(text);
                    noteFailure(key, maxAttempts, text);
                }
            } else {
                // 请求失败 / 超时 / 结果不可用。这里必须计数：
                // 以前完全不计数，只要某段文本永远翻不出来（realTranslation=false 且
                // worthRetry=false，典型是 "[VIP"、"1,253/1,253 Mana"、纯装饰碎片），
                // 下一帧渲染又会重新提交，等于以每秒 10 个限速上限无限刷请求，
                // 把聊天/计分板/物品提示全挤在队列里 —— 这才是"翻译很慢"的主因。
                finalResult = localFallback(text);
                noteFailure(key, maxAttempts, text);
            }

            synchronized (pending) {
                List<TranslationCallback> callbacks = pending.remove(key);
                pendingAt.remove(key);
                if (callbacks != null) {
                    for (TranslationCallback cb : callbacks) {
                        cb.onResult(finalResult);
                    }
                }
            }
          } finally {
            // 任务体里任何未捕获异常都不得把 key 永久留在 pending 里：
            // 那样这行文本以后再也不会被提交，聊天栏就一直显示英文。
            synchronized (pending) {
                if (pending.remove(key) != null) {
                    pendingAt.remove(key);
                    LOGGER.warn("翻译任务异常结束，已释放卡住的请求: {}", key);
                }
            }
          }
        }));
    }

    /** 后台预翻译（用于物品 tooltip 等无需回调的场景）：结果写入缓存，下次渲染直接命中 */
    public void translateInBackground(String text, int category) {
        translateAsync(text, r -> { }, category);
    }

    public void translateInBackground(String text) {
        translateInBackground(text, CAT_HUD);
    }

    /** 聊天专用后台翻译（高优先级，先于计分板/界面等） */
    public void translateChatInBackground(String text) {
        translateChatAsync(text, r -> { });
    }

    /** 渲染层专用后台翻译：带每秒限速，缓存命中同样即时返回，未命中才受限速影响 */
    public void translateInBackgroundRateLimited(String text, int category) {
        // 只有「界面文字」和「物品大列表」这两类低优先级来源限速。
        //
        // 以前所有来源共用同一个每秒 15 条的名额，于是打开 REI/JEI 物品列表时，
        // 那 15 个名额全被它吃掉，聊天、悬停提示反而提交不进去 —— 排队逻辑再对也没用。
        // 聊天 / 悬停提示 / 计分板量本来就小（一屏十几行），直接放行。
        if (category != CAT_SCREEN && category != CAT_ITEM_LIST) {
            translateAsync(text, r -> { }, category);
            return;
        }
        if (category == CAT_ITEM_LIST) {
            synchronized (itemListRateLock) {
                long now = System.currentTimeMillis();
                if (now - itemListWindowStart >= 1000) {
                    itemListWindowStart = now;
                    itemListCount = 0;
                }
                if (itemListCount >= ITEM_LIST_MAX_PER_SECOND) {
                    return;
                }
                itemListCount++;
            }
        } else {
            synchronized (bgRateLock) {
                long now = System.currentTimeMillis();
                if (now - bgRateWindowStart >= 1000) {
                    bgRateWindowStart = now;
                    bgRateCount = 0;
                }
                if (bgRateCount >= BG_MAX_PER_SECOND) {
                    return;
                }
                bgRateCount++;
            }
        }
        translateAsync(text, r -> { }, category);
    }

    public interface TranslationCallback {
        void onResult(String translated);
    }

    public ModConfig config() {
        return config;
    }

    /**
     * 整体替换配置对象。
     * /tyfy reload 会重新从磁盘读出一份新配置，必须同步给这里，
     * 否则客户端与设置界面拿到的是新实例、翻译线程用的还是旧实例，
     * 表现为「设置改了、界面也显示了，但翻译毫无变化」。
     */
    public void setConfig(ModConfig newConfig) {
        this.config = newConfig;
    }

    /** 当前磁盘缓存条目数 */
    public int diskCacheSize() {
        return diskCache.size();
    }

    public void reload() {
        // 用户可能刚改过 config/tongyongfanyi_glossary.json
        Glossary.reload();
        this.translator = createTranslator();
        memoryCache.clear();
        // 术语表 / 语言对变了，之前「翻不出来而放弃」的文本现在可能能翻了，
        // 把重试计数和「已修过一次」的标记一起清掉，不用玩家手动清缓存。
        attempts.clear();
        repairedKeys.clear();
        // 按当前后端选线程数：这里以前写死 config.translationThreads，
        // 在 API 模式下 reload 会把并发又拉回本地那个 1
        applyThreadCounts();
        // 语言对、超时都会让旧的译文失效，所以统一在这里清空内存缓存。
        // 模型是 jar 内置固定的，不涉及模型切换。
    }

    /**
     * 把缓存里「没翻干净」的英文词汇总到 {@code config/tongyongfanyi_missing.txt}。
     *
     * <p>玩家不用再一个个报漏翻：启动后扫一遍已有译文，凡是「含中文但还留着英文实词」的，
     * 把那个词和出现次数、示例原文写出来。下次维护照着这个清单批量补术语表即可。
     * 玩家名（[VIP] Xxx）、命令（/bags）、品牌名、全大写缩写都跳过。
     */
    public void exportMissingWords() {
        try {
            Map<String, Integer> count = new LinkedHashMap<>();
            Map<String, String> sample = new LinkedHashMap<>();
            synchronized (diskCache) {
                for (Map.Entry<String, String> e : diskCache.entrySet()) {
                    String key = e.getKey();
                    String value = e.getValue();
                    if (value == null || key == null) {
                        continue;
                    }
                    int bar = key.indexOf('|');
                    String source = bar >= 0 ? key.substring(bar + 1) : key;
                    // 先按屏幕上的实际显示做一次本地补全：术语表后来补过的词不该再出现在清单里，
                    // 否则玩家会一直看到「明明已经记下来了」的词，等于清单失真。
                    String body = Glossary.repair(value).replaceAll("\u00a7[0-9A-Za-z]", "");
                    if (!CJK.matcher(body).find()) {
                        continue;   // 没中文 = 完全没翻，另有处理
                    }
                    // 只统计「短原文」：物品名/标签这类需要补词的都是短句；
                    // 聊天长句里冒出来的英文多半是玩家 ID（[VIP] EndPainting、xxx ago），
                    // 它们本来就不该翻，混进清单只会让清单失真。
                    if (source.length() > 80 || source.indexOf('/') >= 0) {
                        continue;   // 长句 / 带命令（/bags、/recipes）都不算待补词
                    }
                    String srcLow = source.toLowerCase(java.util.Locale.ROOT);
                    if (srcLow.contains("ago") || srcLow.contains("[vip") || srcLow.contains("joined")
                            || srcLow.contains("left") || srcLow.contains("claimed")
                            || srcLow.contains("created")) {
                        continue;
                    }
                    Matcher m = Pattern.compile("[A-Za-z][A-Za-z'\\-]{2,}").matcher(body);
                    while (m.find()) {
                        String w = m.group();
                        String low = w.toLowerCase(java.util.Locale.ROOT);
                        if (KEEP_WORDS.contains(low) || Glossary.isPreservedEnglish(low)
                                || isUppercasedAbbrev(w) || ROMAN_ONLY.matcher(w).matches()) {
                            continue;
                        }
                        if (!srcLow.contains(low)) {
                            continue;   // 原文里没有，不是「没翻」的残留
                        }
                        // 玩家 ID / 用户名不是漏翻：含数字、超长连写、CamelCase 一律跳过
                        if (w.length() > 14 || w.matches(".*\\d.*")) {
                            continue;
                        }
                        boolean innerUpper = false;
                        for (int i = 1; i < w.length(); i++) {
                            if (Character.isUpperCase(w.charAt(i))) {
                                innerUpper = true;
                                break;
                            }
                        }
                        if (w.length() >= 9 && innerUpper) {
                            continue;
                        }
                        count.merge(w, 1, Integer::sum);
                        sample.putIfAbsent(w, source);
                    }
                }
            }
            if (count.isEmpty()) {
                return;
            }
            List<Map.Entry<String, Integer>> list = new ArrayList<>(count.entrySet());
            list.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
            StringBuilder sb = new StringBuilder(2048);
            sb.append("# 没翻干净的英文词（按出现次数排序）\n")
                    .append("# 格式: 词\t次数\t示例原文\n")
                    .append("# 照这个清单往 tongyongfanyi/glossary.json 里补即可\n");
            int limit = Math.min(list.size(), 400);
            for (int i = 0; i < limit; i++) {
                String w = list.get(i).getKey();
                String src = sample.getOrDefault(w, "");
                if (src.length() > 90) {
                    src = src.substring(0, 90) + "…";
                }
                sb.append(w).append('\t').append(list.get(i).getValue()).append('\t').append(src).append('\n');
            }
            Path out = FabricLoader.getInstance().getConfigDir().resolve("tongyongfanyi_missing.txt");
            Files.writeString(out, sb.toString(), java.nio.charset.StandardCharsets.UTF_8);
            LOGGER.info("未翻译词清单已导出 {} 个词 -> {}", list.size(), out.getFileName());
        } catch (Exception e) {
            LOGGER.debug("导出未翻译词清单失败: {}", e.toString());
        }
    }

    // ------------------------------------------------------------------
    // 手动重译：扫描当前显示过的译文里残留的英文句子，整句再译一遍
    // ------------------------------------------------------------------

    /** 一次按键最多重译多少句：一次刷 24 句足够覆盖一屏，又不会把翻译队列堵死 */
    private static final int RESCAN_MAX = 12;
    /** 网址、斜杠命令、域名：这些英文是有意保留的，不算漏翻 */
    private static final Pattern KEEP_ENGLISH = Pattern.compile(
            "(?i)(?:https?://\\S+|www\\.\\S+|/[A-Za-z][A-Za-z0-9_]*|[\\w-]+(?:\\.[\\w-]+)+)");
    /** 任意位置的 [数字] 分段标记（SEGMENT_MARK 只认行首，这里用来数个数） */
    private static final Pattern ANY_SEGMENT = Pattern.compile("\\[\\d+\\]");
    private static final Pattern ENGLISH_WORD = Pattern.compile("[A-Za-z]{3,}");
    /** 下划线 ID 的「下划线 + 字母」，用来把 Real_corni 还原成 RealCorni 再判驼峰 */
    private static final Pattern UNDERSCORE_LETTER = Pattern.compile("_([A-Za-z])");

    /**
     * 译文里还剩几个「本该翻掉的英文实词」。
     * 品牌名、全大写缩写、罗马数字等级、网址、/命令、玩家 ID 都排除，
     * 否则 [VIP] EndPainting、store.hypixel.net 会被误判成漏翻。
     */
    private static int residualWordCount(String value) {
        if (value == null || !CJK.matcher(value).find()) {
            return 0;
        }
        String body = KEEP_ENGLISH.matcher(SECTION_CODE.matcher(value).replaceAll(" ")).replaceAll(" ");
        Matcher m = ENGLISH_WORD.matcher(body);
        int n = 0;
        while (m.find()) {
            String w = m.group();
            String low = w.toLowerCase(java.util.Locale.ROOT);
            if (KEEP_WORDS.contains(low) || Glossary.isPreservedEnglish(low)
                    || isUppercasedAbbrev(w) || ROMAN_ONLY.matcher(w).matches()
                    || looksLikePlayerId(w)) {
                continue;
            }
            n++;
        }
        return n;
    }

    /** 驼峰 / 下划线形态的玩家 ID：EndPainting、CakeThePug、Mugu_Is_Stinky */
    private static boolean looksLikePlayerId(String w) {
        if (w.contains("_")) {
            return true;
        }
        // 全大写词（PORTAL / NETHER / SWORD）不是 ID，是没翻掉的英文 ——
        // 以前这里只要有第二个大写字母就判成 ID，于是「普通 PORTAL」永远修不掉。
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
        return hasLower && upperAfterFirst;
    }

    /**
     * 玩家按下「重译残留英文」键：扫最近显示过的句子，把仍夹英文的整句重新交给模型。
     * 返回实际提交重译的条数（聊天栏会用它给反馈）。
     */
    public int rescanResidualEnglish() {
        if (!config.enabled) {
            return 0;
        }
        // 模型卡住 / 正在后台重新加载时，一批重译会全堵在队列里等超时，
        // 表现就是「按了键一直没反应」。直接让路并给玩家明确反馈。
        // 返回 -1 = 服务忙，调用方会提示「稍后再试」而不是「没发现残留英文」。
        if (com.tongyongfanyi.local.BundledRuntime.isDegradedForBatch()) {
            LOGGER.info("模型繁忙或正在重新加载，跳过本次残留英文扫描");
            return -1;
        }
        List<Map.Entry<String, String>> snapshot;
        synchronized (recentTexts) {
            snapshot = new ArrayList<>(recentTexts.entrySet());
        }
        // accessOrder 是「最久没用 → 最近用过」，倒序先修当前屏幕上这几行
        Collections.reverse(snapshot);
        String prefix = config.sourceLanguage + ">" + config.effectiveTarget() + "|";
        Set<String> seen = new HashSet<>();
        int submitted = 0;
        for (Map.Entry<String, String> e : snapshot) {
            if (submitted >= RESCAN_MAX) {
                break;
            }
            String text = e.getKey();
            String key = e.getValue();
            if (text == null || key == null || !key.startsWith(prefix) || !seen.add(key)) {
                continue;   // 换过语言对，或同一句被归一化到同一个 key
            }
            String value = memoryCache.get(key);
            boolean chat;
            synchronized (diskCache) {
                if (value == null) {
                    value = diskCache.get(key);
                }
                // 聊天译文落在独立的聊天缓存文件里（带 TTL 自动删除），
                // 重译结果也写回那里，别混进主缓存挤掉界面/物品名译文
                chat = !diskCache.containsKey(key);
            }
            if (value == null) {
                // 从未翻译成功（模型照抄 / attempts 判放弃）：屏幕上显示的就是英文原文，
                // 缓存里根本没有条目。旧逻辑直接 continue，于是批量扫描会报「当前屏幕上
                // 没发现残留英文」，可屏幕上明明还有 Logger XIII 这种纯英文行。
                // 这里也纳入重译：retranslate 内部会清掉放弃计数并用强化提示词整句重来。
                if (residualWords(text).isEmpty()) {
                    continue;
                }
                if (retranslate(text, key, text, chat)) {
                    submitted++;
                }
                continue;
            }
            if (residualWordCount(value) == 0) {
                continue;
            }
            if (retranslate(text, key, value, chat)) {
                submitted++;
            }
        }
        LOGGER.info("残留英文扫描完成，提交 {} 句整句重译", submitted);
        if (submitted > 0) {
            notifyCacheUpdated();
        }
        return submitted;
    }

    /** 清掉旧的「放弃计数」，把完整原文再送模型一次；结果更干净才覆盖缓存 */
    private boolean retranslate(String text, String key, String oldValue, boolean chat) {
        attempts.remove(key);
        repairedKeys.remove(key);
        synchronized (pending) {
            if (pending.containsKey(key)) {
                Long at = pendingAt.get(key);
                if (at == null || System.currentTimeMillis() - at <= STALE_PENDING_MS) {
                    return false;   // 已经有请求在途，别重复排队
                }
                // 服务重启后丢掉的请求：手动重译有权强制解锁，
                // 否则按 V 也救不了这些永远停在英文上的行。
                pending.remove(key);
                pendingAt.remove(key);
                LOGGER.info("手动重译强制释放卡住的请求: {}", key);
            }
        }
        final int priority = Math.max(1, queuePriority(CAT_TOOLTIP)) + 1;
        // 批量重译不进 pending，单独计数，否则状态行会一直写「空闲」
        batchInFlight.incrementAndGet();
        executor.execute(new PriorityTask(priority, () -> {
          try {
            // 任务可能在队列里排了一阵才轮到；这时服务若已经开始自愈，
            // 直接跳过，别再发一批注定超时的请求把恢复过程拖长。
            if (com.tongyongfanyi.local.BundledRuntime.inReloadGrace()
                    || com.tongyongfanyi.local.BundledRuntime.timeoutStreak() >= 4) {
                LOGGER.info("手动重译跳过（服务重载或连续超时）: {}", text);
                return;
            }
            String result = null;
            try {
                result = translator.translateFull(text, config.sourceLanguage, config.effectiveTarget());
            } catch (Exception e) {
                String msg = String.valueOf(e.getMessage()).toLowerCase(java.util.Locale.ROOT);
                if (msg.contains("timed out") || msg.contains("timeout") || msg.contains("refused")) {
                    com.tongyongfanyi.local.BundledRuntime.reportTimeout();
                }
                LOGGER.debug("手动重译失败: {} | {}", e.getMessage(), text);
            }
            if (result == null) {
                LOGGER.info("手动重译无结果（超时或服务不可用）: {}", text);
                return;
            }
            result = Glossary.restoreMarks(text, result);
            if (glossaryExactOn()) {
                result = Glossary.postfix(result, Set.of());
                result = Glossary.repair(result, Set.of(), true);
            }
            result = fixRomanI(text, result);
            result = stripEllipsis(text, result);
            // 只压中间空格，保留首尾排版（居中/箭头），和主翻译路径保持一致
            result = collapseInnerSpaces(result);
            result = Glossary.stripBogusQuotes(text, result);
            if (acceptableRetranslation(text, oldValue, result)) {
                store(key, result, chat);
                attempts.remove(key);
                com.tongyongfanyi.local.BundledRuntime.reportSuccess();
                notifyCacheUpdated();
                LOGGER.info("残留英文已重译: {} -> {}", oldValue, result);
            } else {
                // 以前只打 DEBUG，玩家看到「提交 12 句」却只有 3 条结果时完全无从判断，
                // 其余 9 句是被判坏还是压根没跑。提到 INFO，一次按键最多 12 条。
                LOGGER.info("手动重译结果不理想，保留原文: {} -> {}", text, result);
            }
          } finally {
            batchInFlight.decrementAndGet();
          }
        }));
        return true;
    }

    /** 只接受「确实更干净、且没把颜色分段 / 数字弄丢」的结果 */
    private boolean acceptableRetranslation(String source, String oldValue, String result) {
        if (result.isBlank() || looksMangled(result) || looksUntranslated(source, result)) {
            return false;
        }
        if (config.effectiveTarget() != null && config.effectiveTarget().startsWith("zh")
                && !CJK.matcher(result).find()) {
            return false;
        }
        if (countMatches(source, ANY_SEGMENT) != countMatches(result, ANY_SEGMENT)
                || countMatches(source, NUM_RUN) != countMatches(result, NUM_RUN)
                || countMatches(source, SECTION_CODE) != countMatches(result, SECTION_CODE)) {
            return false;
        }
        // 手动重译没走 protectUrls：链接或 Hypixel 图标字体字符被改掉就丢弃这次结果，
        // 不然为了补一个英文词反而把链接、图标弄没了。
        Matcher urls = URL_FULL.matcher(source);
        while (urls.find()) {
            String url = urls.group();
            // 纯私用区图标交给 Glossary.restoreMarks 补，这里只卡真正的链接/域名
            if (url.codePoints().allMatch(cp -> cp >= 0xE000 && cp <= 0xF8FF)) {
                continue;
            }
            if (!result.contains(url)) {
                return false;
            }
        }
        int before = residualWordCount(oldValue);
        int after = residualWordCount(result);
        if (after > before || (after == before && result.equals(oldValue))) {
            return false;
        }
        return result.length() <= Math.max(80, source.length() * 3);
    }

    // ------------------------------------------------------------------
    // 鼠标指向重译：整句 → 本地词表 → 逐词送模型，三级闭环
    // ------------------------------------------------------------------

    /** 一次按键最多补几个词：每个词一次串行请求，别让一次按键等太久 */
    private static final int HOVER_MAX_WORDS = 6;
    /**
     * 逐词替换时要跳过的片段：网址、/命令、域名、[N] 分段标记、颜色码。
     * 保护段用第一个分支吃掉，第二个分支只匹配真正的英文实词。
     */
    private static final Pattern HOVER_TOKEN = Pattern.compile(
            "(?i)(?:https?://\\S+|www\\.\\S+|/[A-Za-z][A-Za-z0-9_]*|[\\w-]+(?:\\.[\\w-]+)+"
                    + "|\\[\\d+\\]|§[0-9A-Za-z])|(?<![A-Za-z])([A-Za-z]{3,})(?![A-Za-z])");

    /** 译文里还剩哪些「本该翻掉的英文实词」（不要求已有中文，纯英文原句也能数出来） */
    /**
     * 同上，但把「原文里也有、且首字母大写」的词当作专名放行。
     *
     * <p>NPC 名（{@code Rabbit Bro}）、物品/活动专名（{@code Chocolate Factory}）
     * 本来就该保留原文，模型这么译是对的。按残留算会判成「没翻完」，
     * 于是不写缓存、无限重试。
     */
    private static List<String> residualWords(String value, String reference) {
        List<String> words = residualWords(value);
        if (reference == null || reference.isEmpty() || words.isEmpty()) {
            return words;
        }
        List<String> out = new ArrayList<>(words.size());
        for (String w : words) {
            if (Character.isUpperCase(w.charAt(0)) && reference.contains(w)) {
                continue;
            }
            out.add(w);
        }
        return out;
    }

    private static List<String> residualWords(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        String body = KEEP_ENGLISH.matcher(SECTION_CODE.matcher(value).replaceAll(" ")).replaceAll(" ");
        // 下划线 ID 先还原成驼峰形态（Real_corni → RealCorni）：
        // {@link #ENGLISH_WORD} 只认连续字母，会把一个 ID 劈成「Real」+「corni」
        // 两个词，而这两个词都不像 ID，于是**每个玩家 ID 都被算成两个没翻掉的英文**。
        // 合并成驼峰后 looksLikePlayerId 才认得出它是 ID。
        java.util.regex.Matcher um = UNDERSCORE_LETTER.matcher(body);
        StringBuilder merged = new StringBuilder(body.length());
        while (um.find()) {
            um.appendReplacement(merged, um.group(1).toUpperCase(Locale.ROOT));
        }
        um.appendTail(merged);
        body = merged.toString();
        Matcher m = ENGLISH_WORD.matcher(body);
        LinkedHashSet<String> out = new LinkedHashSet<>();
        while (m.find()) {
            String w = m.group();
            String low = w.toLowerCase(Locale.ROOT);
            if (KEEP_WORDS.contains(low) || Glossary.isPreservedEnglish(low)
                    || isUppercasedAbbrev(w) || ROMAN_ONLY.matcher(w).matches()
                    || looksLikePlayerId(w)) {
                continue;
            }
            out.add(w);
        }
        return new ArrayList<>(out);
    }

    /**
     * 全大写且 ≤4 个字母才算「有意保留的缩写」（XP/HP/DPS/VIP/NPC/ATK）。
     *
     * <p>以前一律把全大写词当缩写放过，于是 tooltip 里的「普通 PORTAL」被判成
     * 「这一条已经全是中文」，按重译键完全没反应 —— 玩家看到的英文永远修不掉。
     */
    private static boolean isUppercasedAbbrev(String w) {
        return w.length() <= 4 && w.equals(w.toUpperCase(Locale.ROOT));
    }

    /**
     * 玩家把鼠标放在某条文字上按重译键时调用。
     *
     * <p>三级闭环，专门解决「再翻一次还是漏」：
     * <ol>
     *   <li>整句送模型重译（强化提示词，不许残留英文实词）；</li>
     *   <li>用术语表 / 词根拆分把剩下的英文本地补齐（模型卡住时这一步也照样有效）；</li>
     *   <li>本地补不掉的英文词，<b>逐个</b>送模型翻译再拼回去 —— 1.8B 对整句生造专名会照抄，
     *       但单词级命中率高；翻出来的词自动写进玩家自定义词表，下次同类词直接命中。</li>
     * </ol>
     * 结果必须比原来残留更少、且格式（颜色码 / 数字 / [N] / 网址）不丢，否则保留原译文。
     *
     * @return 1 = 已提交；0 = 这条没有需要重译的英文；-1 = 模型忙且本地也补不动
     */
    public int retranslateHovered(String original, Consumer<String> feedback) {
        if (!config.enabled || original == null || original.isBlank()) {
            return 0;
        }
        final String text = resolveHoveredOriginal(original.trim());
        // 不能因为「句中有网址/图标」就整条跳过 —— URL_FULL 也匹配 Hypixel 私用区图标，
        // SkyBlock 里大半文本都带图标。格式完整性交给下面的 hoverFormatIntact 校验。
        if (text.length() > 400 || noTranslatableContent(text)
                || TranslationHelper.isPlayerName(text) || text.startsWith("/")) {
            return 0;
        }
        final String key = cacheKey(text);
        // 手动重译：把这条从「放弃名单」里放出来，否则按 V 会直接被 30 分钟冷却挡住
        hopeless.remove(key);
        attempts.remove(key);
        String oldValue = memoryCache.get(key);
        boolean chat;
        synchronized (diskCache) {
            if (oldValue == null) {
                oldValue = diskCache.get(key);
            }
            // 聊天译文只存在独立的聊天缓存文件里，主 diskCache 里查不到 → 按聊天处理，
            // 重译结果仍然写回聊天缓存，不会污染主缓存
            chat = !diskCache.containsKey(key);
        }
        final String shown = oldValue != null && !looksMangled(oldValue) ? oldValue : text;
        final int before = residualWords(shown).size();
        if (before == 0) {
            if (feedback != null) {
                feedback.accept(I18n.t("key.rescan.hover_clean"));
            }
            return 0;
        }
        final boolean degraded = com.tongyongfanyi.local.BundledRuntime.isDegradedForBatch();
        final String base = shown;
        final boolean isChat = chat;
        attempts.remove(key);
        repairedKeys.remove(key);
        final int priority = Math.max(1, queuePriority(CAT_TOOLTIP)) + 2;
        executor.execute(new PriorityTask(priority, () -> {
            // 任务可能排队了一阵，开跑前再查一次健康状态
            boolean modelUsable = !degraded
                    && !com.tongyongfanyi.local.BundledRuntime.inReloadGrace()
                    && com.tongyongfanyi.local.BundledRuntime.timeoutStreak() < 4;
            String candidate = base;

            // ---- 第 1 级：整句重译 ----
            if (modelUsable) {
                try {
                    String full = translator.translateFull(text, config.sourceLanguage,
                            config.effectiveTarget());
                    if (full != null && !full.isBlank()) {
                        String cleaned = hoverClean(text, full);
                        if (residualWords(cleaned).size() < residualWords(candidate).size()) {
                            candidate = cleaned;
                        }
                        com.tongyongfanyi.local.BundledRuntime.reportSuccess();
                    }
                } catch (Exception e) {
                    String msg = String.valueOf(e.getMessage()).toLowerCase(Locale.ROOT);
                    if (msg.contains("timed out") || msg.contains("timeout") || msg.contains("refused")) {
                        com.tongyongfanyi.local.BundledRuntime.reportTimeout();
                        modelUsable = false;
                    }
                    LOGGER.debug("鼠标指向重译整句失败: {} | {}", e.getMessage(), text);
                }
            }

            // ---- 第 2 级：术语表 + 词根拆分本地补齐 ----
            if (glossaryExactOn()) {
                String local = Glossary.repair(candidate, Set.of(), true);
                local = Glossary.localize(local);
                if (residualWords(local).size() < residualWords(candidate).size()) {
                    candidate = local;
                }
            }

            // ---- 第 3 级：剩下的英文词逐个送模型 ----
            Map<String, String> learned = new LinkedHashMap<>();
            if (modelUsable) {
                List<String> left = residualWords(candidate);
                for (String word : left) {
                    if (learned.size() >= HOVER_MAX_WORDS) {
                        break;
                    }
                    String zh = hoverTranslateWord(word);
                    if (zh != null) {
                        learned.put(word.toLowerCase(Locale.ROOT), zh);
                    }
                }
                if (!learned.isEmpty()) {
                    candidate = hoverReplaceWords(candidate, learned);
                    // 拼回去之后再本地收一次尾，处理复数/大小写变体
                    if (glossaryExactOn()) {
                        candidate = Glossary.postfix(candidate, Set.of());
                    }
                }
            }

            candidate = hoverClean(text, candidate);
            int after = residualWords(candidate).size();
            boolean acceptable = after < before && hoverFormatIntact(text, base, candidate);
            if (!acceptable) {
                LOGGER.debug("鼠标指向重译没有更干净的结果，保留原译文: {} -> {}", base, candidate);
                if (feedback != null) {
                    feedback.accept(I18n.t("key.rescan.hover_keep"));
                }
                return;
            }
            store(key, candidate, isChat);
            attempts.remove(key);
            if (modelUsable) {
                com.tongyongfanyi.local.BundledRuntime.reportSuccess();
            }
            int learnedCount = Glossary.learn(learned);
            HoverTextTracker.link(text, candidate);
            notifyCacheUpdated();
            LOGGER.info("鼠标指向重译成功: {} -> {} (残留 {}→{}, 学入词表 {})",
                    base, candidate, before, after, learnedCount);
            if (feedback != null) {
                if (after > 0) {
                    feedback.accept(I18n.t("key.rescan.hover_partial", after, text, candidate,
                            String.join(", ", residualWords(candidate))));
                } else {
                    feedback.accept(I18n.t("key.rescan.hover_ok", text, candidate, learnedCount));
                }
            }
        }));
        // 模型忙也照样提交：第 2 级本地补齐不需要模型，按键不会白按
        return 1;
    }

    /** 原文中至少要有一个外语字符；纯中文且反查不到原文时，不能把中文当原文送模型 */
    private static final Pattern FORCE_FOREIGN = Pattern.compile(
            "[A-Za-z]|[\\u3040-\\u30FF\\u31F0-\\u31FF\\uAC00-\\uD7AF\\u0400-\\u04FF]");

    /**
     * 「强制重译」键：玩家觉得现有译文不够好，无论屏幕上是否已经是中文，都把原文
     * 再送模型换一种译法。和 {@link #retranslateHovered} 的区别是这里<b>不做</b>
     * 「有没有残留英文」的判定；结果只要格式完整、不是照抄原文、残留英文不比旧译文
     * 多、且和旧译文不同就采用。请求带随机 seed，所以连续按能拿到不同译法比较。
     *
     * @param shownTexts 鼠标指向的一行，或悬停物品时整块 tooltip 的可见行
     * @return 提交的行数；0 = 没有可重译的文本；-1 = 模型忙，这批没有入队
     */
    public int forceRetranslateHovered(List<String> shownTexts, Consumer<String> feedback) {
        if (!config.enabled || shownTexts == null || shownTexts.isEmpty()) {
            return 0;
        }
        // 服务降级时排队只会把队列再堵一遍（上次卡死的根因），明确让客户端提示「稍后再按」
        if (com.tongyongfanyi.local.BundledRuntime.isDegradedForBatch()
                || com.tongyongfanyi.local.BundledRuntime.inReloadGrace()
                || com.tongyongfanyi.local.BundledRuntime.timeoutStreak() >= 4) {
            return -1;
        }
        List<String> texts = new ArrayList<>();
        for (String shown : shownTexts) {
            if (shown == null || shown.isBlank()) {
                continue;
            }
            String trimmed = shown.trim();
            String text = resolveHoveredOriginal(trimmed);
            // 没反查到原文、屏幕上又是纯中文：拿中文当原文送模型只会越翻越怪，跳过这一行
            if (text.equals(trimmed) && !FORCE_FOREIGN.matcher(text).find()) {
                continue;
            }
            if (text.length() > 400 || noTranslatableContent(text)
                    || TranslationHelper.isPlayerName(text) || text.startsWith("/")) {
                continue;
            }
            if (!texts.contains(text)) {
                texts.add(text);
            }
            if (texts.size() >= 8) {
                break;
            }
        }
        if (texts.isEmpty()) {
            return 0;
        }
        final List<String> batch = List.copyOf(texts);
        // 一个任务里串行处理整块 tooltip：模型本来就是串行推理，没必要同时占多个线程
        final int priority = Math.max(1, queuePriority(CAT_TOOLTIP)) + 1;
        executor.execute(new PriorityTask(priority, () -> {
            int changed = 0;
            for (String text : batch) {
                if (forceRetranslateOne(text, feedback)) {
                    changed++;
                }
            }
            LOGGER.info("强制重译完成：提交 {} 行，采用 {} 行", batch.size(), changed);
        }));
        return batch.size();
    }

    private boolean forceRetranslateOne(String text, Consumer<String> feedback) {
        final String key = cacheKey(text);
        // 强制重译不看冷却：把放弃记录和失败计数一起清掉
        hopeless.remove(key);
        attempts.remove(key);
        String oldValue = memoryCache.get(key);
        boolean chat;
        synchronized (diskCache) {
            if (oldValue == null) {
                oldValue = diskCache.get(key);
            }
            // 聊天译文落在独立聊天缓存（带 TTL 自动删除），重译结果也写回那里
            chat = !diskCache.containsKey(key);
        }
        final String base = oldValue != null && !looksMangled(oldValue) ? oldValue : text;
        attempts.remove(key);
        repairedKeys.remove(key);
        try {
            String raw = translator.translateAlternative(text, config.sourceLanguage,
                    config.effectiveTarget());
            if (raw == null || raw.isBlank()) {
                if (feedback != null) {
                    feedback.accept(I18n.t("key.force.keep"));
                }
                return false;
            }
            String candidate = hoverClean(text, raw);
            if (glossaryExactOn()) {
                String local = Glossary.localize(candidate);
                if (residualWords(local).size() < residualWords(candidate).size()) {
                    candidate = hoverClean(text, local);
                }
            }
            // 换了种子还是同一句：这句模型只有一种译法。不写缓存，明确告诉玩家可再按一次。
            if (candidate.equals(base)) {
                if (feedback != null) {
                    feedback.accept(I18n.t("key.force.same", candidate));
                }
                return false;
            }
            boolean acceptable = hoverFormatIntact(text, base, candidate)
                    && !candidate.equals(text)
                    && residualWords(candidate).size() <= residualWords(base).size();
            if (!acceptable) {
                LOGGER.debug("强制重译结果不合格，保留原译文: {} -> {}", base, candidate);
                if (feedback != null) {
                    feedback.accept(I18n.t("key.force.keep"));
                }
                return false;
            }
            store(key, candidate, chat);
            attempts.remove(key);
            com.tongyongfanyi.local.BundledRuntime.reportSuccess();
            HoverTextTracker.link(text, candidate);
            notifyCacheUpdated();
            LOGGER.info("强制重译: {} -> {}", base, candidate);
            if (feedback != null) {
                feedback.accept(I18n.t("key.force.ok", base, candidate));
            }
            return true;
        } catch (Exception e) {
            String msg = String.valueOf(e.getMessage()).toLowerCase(Locale.ROOT);
            if (msg.contains("timed out") || msg.contains("timeout")
                    || msg.contains("connectexception") || msg.contains("refused")) {
                com.tongyongfanyi.local.BundledRuntime.reportTimeout();
            }
            LOGGER.debug("强制重译失败: {} | {}", e.getMessage(), text);
            if (feedback != null) {
                feedback.accept(I18n.t("key.force.keep"));
            }
            return false;
        }
    }

    /**
     * 绘制层记录的文本把行内 § 颜色码剥掉了，而缓存键是按带色码的原句算的。
     * 直接拿剥过码的字符串当原文，重译结果会写进一个屏幕永远查不到的 key ——
     * 表现就是「按了键，日志说翻好了，界面却纹丝不动」。
     * 先查「译文→原文」反查表；没命中（说明这句压根没翻过）再去 recentTexts 里
     * 找归一化后一致的真实原句，把色码补回来。
     */
    private String resolveHoveredOriginal(String shown) {
        String mapped = HoverTextTracker.originalOf(shown);
        if (mapped != null && !mapped.isBlank()) {
            return mapped;
        }
        String norm = HoverTextTracker.normalizeForLookup(shown);
        if (norm.length() >= 2) {
            synchronized (recentTexts) {
                for (String candidate : new ArrayList<>(recentTexts.keySet())) {
                    if (HoverTextTracker.normalizeForLookup(candidate).equals(norm)) {
                        return candidate;
                    }
                }
            }
        }
        return shown;
    }

    /** 走一遍和正常翻译相同的收尾清洗，避免重译结果带省略号、罗马数字误译 */
    /** 行首连续颜色码：模型整句重译时最容易丢的就是这一段 */
    private static final Pattern LEADING_CODES = Pattern.compile("^(?:§[0-9A-Za-z])+");

    private String hoverClean(String source, String value) {
        if (value == null) {
            return "";
        }
        String out = Glossary.restoreMarks(source, value);
        if (glossaryExactOn()) {
            out = Glossary.postfix(out, Set.of());
            out = Glossary.repair(out, Set.of(), true);
        }
        out = fixRomanI(source, out);
        out = stripEllipsis(source, out);
        out = Glossary.stripBogusQuotes(source, out);
        out = out.replaceAll("\\s{2,}", " ").trim();
        // 颜色码数量对不上时优先把原句行首的颜色补回来，别为了一个英文词把整行样式弄没。
        // 严格相等会让「模型稍微动了色码」这类结果被整条丢弃，反而修不了漏翻。
        Matcher lead = LEADING_CODES.matcher(source);
        if (lead.find() && !out.startsWith(lead.group())) {
            String codes = lead.group();
            Matcher own = LEADING_CODES.matcher(out);
            String rest = own.find() ? out.substring(own.end()) : out;
            out = codes + rest;
        }
        return out;
    }

    /** 单个英文词：先查本地词表/词根，实在没有才问模型；结果必须是干净的译名 */
    private String hoverTranslateWord(String word) {
        if (glossaryExactOn()) {
            String exact = Glossary.exact(word);
            if (hoverValidTerm(word, exact)) {
                return exact.trim();
            }
            String local = Glossary.localize(word);
            if (hoverValidTerm(word, local)) {
                return local.trim();
            }
        }
        try {
            String raw = translator.translateTerm(word, config.effectiveTarget());
            if (raw == null) {
                return null;
            }
            String term = raw.replaceAll("[\\r\\n].*$", "")
                    .replaceAll("^[\"'“”‘’`\\s]+|[\"'“”‘’`。.!！,，;；:：\\s]+$", "")
                    .trim();
            return hoverValidTerm(word, term) ? term : null;
        } catch (Exception e) {
            String msg = String.valueOf(e.getMessage()).toLowerCase(Locale.ROOT);
            if (msg.contains("timed out") || msg.contains("timeout") || msg.contains("refused")) {
                com.tongyongfanyi.local.BundledRuntime.reportTimeout();
            }
            return null;
        }
    }

    private boolean hoverValidTerm(String word, String term) {
        if (term == null || term.isBlank() || term.length() > 16
                || term.equalsIgnoreCase(word) || looksMangled(term)) {
            return false;
        }
        // 译名里不能还留着英文实词，也不能带进颜色码 / 分段标记
        if (LATIN_WORD.matcher(term).find() || SECTION_CODE.matcher(term).find()
                || ANY_SEGMENT.matcher(term).find()) {
            return false;
        }
        if (config.effectiveTarget() != null && config.effectiveTarget().startsWith("zh")
                && !CJK.matcher(term).find()) {
            return false;
        }
        return term.codePoints().anyMatch(Character::isLetter);
    }

    /** 按词表替换，但网址 / 命令 / 颜色码 / [N] 标记原样保留 */
    private static String hoverReplaceWords(String text, Map<String, String> dict) {
        Matcher m = HOVER_TOKEN.matcher(text);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String word = m.group(1);
            String zh = word == null ? null : dict.get(word.toLowerCase(Locale.ROOT));
            m.appendReplacement(sb, Matcher.quoteReplacement(zh != null ? zh : m.group()));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** 颜色码、数字、[N] 分段、网址一个都不能少；多了也不行 */
    private boolean hoverFormatIntact(String source, String oldValue, String result) {
        if (result.isBlank() || looksMangled(result) || looksUntranslated(source, result)
                || (config.effectiveTarget() != null && config.effectiveTarget().startsWith("zh")
                && !CJK.matcher(result).find())) {
            return false;
        }
        if (countMatches(source, ANY_SEGMENT) != countMatches(result, ANY_SEGMENT)
                || countMatches(source, NUM_RUN) != countMatches(result, NUM_RUN)) {
            return false;
        }
        // 色码只要「不少于原句」即可：模型自作主张多加一两个格式码不影响可读性，
        // 但少了就说明样式丢了，宁可保留原译文。批量重译那边仍用严格相等。
        if (countMatches(result, SECTION_CODE) < countMatches(source, SECTION_CODE)) {
            return false;
        }
        Matcher urls = URL_FULL.matcher(source);
        while (urls.find()) {
            String url = urls.group();
            if (url.codePoints().allMatch(cp -> cp >= 0xE000 && cp <= 0xF8FF)) {
                continue;
            }
            if (!result.contains(url)) {
                return false;
            }
        }
        return result.length() <= Math.max(80, source.length() * 3);
    }

    private static int countMatches(String s, Pattern p) {
        if (s == null) {
            return 0;
        }
        Matcher m = p.matcher(s);
        int n = 0;
        while (m.find()) {
            n++;
        }
        return n;
    }

    /** 清空内存与磁盘缓存 */
    public void clearCache() {
        memoryCache.clear();
        synchronized (diskCache) {
            diskCache.clear();
        }
        chatCache.clear();
        chatCacheDirty = true;
        saveChatCache();
        // 清掉排队中的翻译请求，避免在途结果完成后又把缓存写回来
        synchronized (pending) {
            pending.clear();
        }
        pendingAt.clear();
        chatResubmitAt.clear();
        diskCacheDirty = true;
        saveDiskCache();
        LOGGER.info("已清空翻译缓存");
    }

    /** 游戏退出前强制刷盘，避免丢失最近几秒的新缓存 */
    public void flushCache() {
        saveScheduled.set(false);
        if (diskCacheDirty) {
            saveDiskCache();
        }
        chatSaveScheduled.set(false);
        if (chatCacheDirty) {
            saveChatCache();
        }
    }

    /** 带优先级的可比较任务：聊天优先出队，同优先级按提交顺序 FIFO */
    private static final class PriorityTask implements Runnable, Comparable<PriorityTask> {
        private static final AtomicLong SEQ = new AtomicLong();
        private final int priority;
        private final long seq = SEQ.incrementAndGet();
        private final Runnable task;

        PriorityTask(int priority, Runnable task) {
            this.priority = priority;
            this.task = task;
        }

        @Override
        public void run() {
            task.run();
        }

        @Override
        public int compareTo(PriorityTask other) {
            int c = Integer.compare(other.priority, this.priority);
            return c != 0 ? c : Long.compare(this.seq, other.seq);
        }
    }
}
