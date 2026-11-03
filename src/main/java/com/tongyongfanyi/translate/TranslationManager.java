package com.tongyongfanyi.translate;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.tongyongfanyi.config.ModConfig;
import com.tongyongfanyi.local.LocalModelService;
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
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 翻译管理器。负责选择后端、异步翻译，并维护两层缓存：
 * 1. 内存缓存(最近使用 LRU)
 * 2. 磁盘持久化缓存(存到 config/tongyongfanyi_cache.json)，下次启动/再次遇见相同文本直接命中。
 */
public class TranslationManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("tongyongfanyi");
    private static final Gson GSON = new GsonBuilder().create();
    private static final Type MAP_TYPE = new TypeToken<LinkedHashMap<String, String>>() {}.getType();
    private static final int PRIORITY_LOW = 0;
    private static final int PRIORITY_HIGH = 1;

    /** 当前配置。运行时会被整体替换（/tyfy reload），且翻译线程会读取，故非 final + volatile */
    private volatile ModConfig config;
    /** 优先级队列线程池：聊天(高优先级)先翻，其余批量文字并行处理 */
    private final ThreadPoolExecutor executor;
    private volatile OllamaNativeTranslator translator;
    /** 上一次使用的本地模型名（用于检测模型切换，自动卸载旧模型/加载新模型） */
    private String lastLocalModel = "";
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
    /** 正在翻译中的文本 -> 等待回调，避免同一文本在短时间内被重复请求 */
    private final Map<String, List<TranslationCallback>> pending = new HashMap<>();
    /** 渲染层批量翻译限速（每秒最多新提交条数），避免打开英文界面时把翻译队列打爆 */
    private final Object bgRateLock = new Object();
    private long bgRateWindowStart = 0L;
    private int bgRateCount = 0;
    private static final int BG_MAX_PER_SECOND = 10;

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
        this.diskCache = Collections.synchronizedMap(new LinkedHashMap<>());
        loadDiskCache();
        this.translator = createTranslator();
        int threads = clampThreads(config.translationThreads);
        this.executor = new ThreadPoolExecutor(threads, threads, 60L, TimeUnit.SECONDS,
                new PriorityBlockingQueue<>(), r -> {
            Thread t = new Thread(r, "tongyongfanyi-translate");
            t.setDaemon(true);
            return t;
        });
    }

    private static int clampThreads(int threads) {
        return Math.max(1, Math.min(8, threads));
    }

    /** 运行时调整翻译线程数（设置页可选） */
    public void setThreadCount(int threads) {
        int t = clampThreads(threads);
        executor.setCorePoolSize(t);
        executor.setMaximumPoolSize(t);
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
        purgePoisonedCacheOnce();
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

    /** 同步查询缓存(内存 + 磁盘) */
    public String cached(String text) {
        String hit = memoryCache.get(text);
        if (hit != null) return hit;
        return diskCache.get(text);
    }

    /** 异步翻译（普通优先级：计分板/工具提示/界面等），结果经回调返回 */
    public void translateAsync(String text, TranslationCallback callback) {
        submitTranslate(text, callback, PRIORITY_LOW);
    }

    /** 异步翻译（高优先级：聊天栏），优先于其他批量文字 */
    public void translateChatAsync(String text, TranslationCallback callback) {
        submitTranslate(text, callback, PRIORITY_HIGH);
    }

    private void submitTranslate(String text, TranslationCallback callback, int priority) {
        // 先查内存缓存
        String cached = memoryCache.get(text);
        if (cached != null) {
            callback.onResult(cached);
            return;
        }
        // 再查磁盘缓存
        cached = diskCache.get(text);
        if (cached != null) {
            memoryCache.put(text, cached);
            callback.onResult(cached);
            return;
        }

        // 同一文本已有请求在途时，只追加回调，不重复发起翻译
        synchronized (pending) {
            List<TranslationCallback> callbacks = pending.get(text);
            if (callbacks != null) {
                callbacks.add(callback);
                return;
            }
            callbacks = new ArrayList<>();
            callbacks.add(callback);
            pending.put(text, callbacks);
        }

        long deadline = System.currentTimeMillis() + config.timeoutMs;
        executor.execute(new PriorityTask(priority, () -> {
            String result;
            // 只有后端真的返回了结果才写缓存。失败或超时一律不缓存，
            // 否则原文会被当成译文写进（含磁盘持久化）缓存，之后再怎么改配置都不会重新翻译。
            boolean succeeded = false;
            try {
                if (System.currentTimeMillis() > deadline) {
                    result = text;
                } else {
                    result = translator.translate(text, config.sourceLanguage, config.targetLanguage);
                    succeeded = true;
                }
            } catch (Exception e) {
                LOGGER.warn("翻译失败: {} | {}", e.getMessage(), text);
                result = text;
            }
            if (succeeded && result != null && !result.isBlank()) {
                memoryCache.put(text, result);
                // 磁盘缓存也受总量限制，避免无限增长
                synchronized (diskCache) {
                    diskCache.put(text, result);
                    trimDiskCacheLocked();
                }
                markDiskCacheDirty();
            }
            String finalResult = result != null && !result.isBlank() ? result : text;
            synchronized (pending) {
                List<TranslationCallback> callbacks = pending.remove(text);
                if (callbacks != null) {
                    for (TranslationCallback cb : callbacks) {
                        cb.onResult(finalResult);
                    }
                }
            }
        }));
    }

    /** 后台预翻译（用于物品 tooltip 等无需回调的场景）：结果写入缓存，下次渲染直接命中 */
    public void translateInBackground(String text) {
        translateAsync(text, r -> { });
    }

    /** 聊天专用后台翻译（高优先级，先于计分板/界面等） */
    public void translateChatInBackground(String text) {
        translateChatAsync(text, r -> { });
    }

    /** 渲染层专用后台翻译：带每秒限速，缓存命中同样即时返回，未命中才受限速影响 */
    public void translateInBackgroundRateLimited(String text) {
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
        translateAsync(text, r -> { });
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
        this.translator = createTranslator();
        memoryCache.clear();
        setThreadCount(config.translationThreads);
        // 模型变化时自动卸载旧模型、加载新模型。
        // 改模型、改超时、改服务地址的入口最终都会走 reload()，因此在这里统一触发。
        String newModel = config.localModel.model;
        String prev = lastLocalModel;
        if (newModel != null && !newModel.isBlank() && !newModel.equals(prev)) {
            LocalModelService.switchModel(config, prev, newModel);
            lastLocalModel = newModel;
        }
    }

    /** 清空内存与磁盘缓存 */
    public void clearCache() {
        memoryCache.clear();
        synchronized (diskCache) {
            diskCache.clear();
        }
        // 清掉排队中的翻译请求，避免在途结果完成后又把缓存写回来
        synchronized (pending) {
            pending.clear();
        }
        diskCacheDirty = true;
        saveDiskCache();
        LOGGER.info("已清空翻译缓存");
    }

    /** 游戏退出前强制刷盘，避免丢失最近几秒的新缓存 */
    public void flushCache() {
        if (!diskCacheDirty) {
            return;
        }
        saveScheduled.set(false);
        saveDiskCache();
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
