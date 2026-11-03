package com.tongyongfanyi.translate;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.tongyongfanyi.config.ModConfig;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 翻译管理器：线程池 + 内存缓存 + 磁盘缓存。
 */
public class TranslationManager {
    private static final Gson GSON = new GsonBuilder().create();
    private static final Type MAP_TYPE = new TypeToken<LinkedHashMap<String, String>>() {}.getType();

    private final ModConfig config;
    private final ExecutorService executor;
    private final File diskCacheFile;
    private final Map<String, String> memoryCache;
    private final Map<String, String> diskCache;
    private final ScheduledExecutorService saver = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "tyfy-cache-saver");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean saveScheduled = new AtomicBoolean(false);
    private volatile boolean diskCacheDirty = false;
    private final Map<String, List<TranslationCallback>> pending = new LinkedHashMap<>();

    private Translator translator;

    public TranslationManager(ModConfig config, File configDir) {
        this.config = config;
        this.memoryCache = Collections.synchronizedMap(new LinkedHashMap<String, String>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                return size() > config.cacheSize;
            }
        });
        this.diskCacheFile = new File(configDir, "tongyongfanyi_cache.json");
        this.diskCache = Collections.synchronizedMap(new LinkedHashMap<String, String>());
        this.translator = createTranslator();
        int threads = Math.max(1, Math.min(8, config.translationThreads));
        this.executor = Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "tyfy-translate");
            t.setDaemon(true);
            return t;
        });
        loadDiskCache();
    }

    public Translator createTranslator() {
        return new OllamaTranslator(config.ollamaUrl, config.ollamaModel);
    }

    public String cached(String text) {
        String hit = memoryCache.get(text);
        if (hit != null) {
            return hit;
        }
        return diskCache.get(text);
    }

    public void translateAsync(String text, TranslationCallback callback) {
        String cached = memoryCache.get(text);
        if (cached != null) {
            callback.onResult(cached);
            return;
        }
        cached = diskCache.get(text);
        if (cached != null) {
            memoryCache.put(text, cached);
            callback.onResult(cached);
            return;
        }
        synchronized (pending) {
            List<TranslationCallback> callbacks = pending.get(text);
            if (callbacks != null) {
                callbacks.add(callback);
                return;
            }
            callbacks = new ArrayList<TranslationCallback>();
            callbacks.add(callback);
            pending.put(text, callbacks);
        }
        final String key = text;
        final long deadline = System.currentTimeMillis() + config.timeoutMs;
        executor.execute(() -> {
            String result;
            try {
                if (System.currentTimeMillis() > deadline) {
                    result = key;
                } else {
                    result = translator.translate(key, config.sourceLanguage, config.targetLanguage);
                }
            } catch (Exception e) {
                System.err.println("[通用翻译] 翻译失败: " + e.getMessage() + " | " + key);
                result = key;
            }
            if (result != null && !result.trim().isEmpty()) {
                memoryCache.put(key, result);
                synchronized (diskCache) {
                    diskCache.put(key, result);
                    trimDiskCacheLocked();
                }
                markDiskCacheDirty();
            }
            String finalResult = result != null && !result.trim().isEmpty() ? result : key;
            synchronized (pending) {
                List<TranslationCallback> callbacks = pending.remove(key);
                if (callbacks != null) {
                    for (TranslationCallback cb : callbacks) {
                        cb.onResult(finalResult);
                    }
                }
            }
        });
    }

    public void translateInBackground(String text) {
        translateAsync(text, r -> { });
    }

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

    private void markDiskCacheDirty() {
        diskCacheDirty = true;
        if (saveScheduled.compareAndSet(false, true)) {
            saver.schedule(() -> {
                saveDiskCache();
                saveScheduled.set(false);
            }, 2, TimeUnit.SECONDS);
        }
    }

    private void loadDiskCache() {
        if (diskCacheFile.exists()) {
            try {
                String json = new String(Files.readAllBytes(diskCacheFile.toPath()), StandardCharsets.UTF_8);
                Map<String, String> loaded = GSON.fromJson(json, MAP_TYPE);
                if (loaded != null) {
                    synchronized (diskCache) {
                        diskCache.putAll(loaded);
                    }
                    System.out.println("[通用翻译] 已加载磁盘缓存 " + diskCache.size() + " 条");
                }
            } catch (IOException e) {
                System.err.println("[通用翻译] 读取磁盘缓存失败: " + e.getMessage());
            }
        }
    }

    private void saveDiskCache() {
        if (!diskCacheDirty) {
            return;
        }
        try {
            if (!diskCacheFile.getParentFile().exists()) {
                diskCacheFile.getParentFile().mkdirs();
            }
            String json;
            synchronized (diskCache) {
                json = GSON.toJson(diskCache);
            }
            File tmp = new File(diskCacheFile.getParentFile(), diskCacheFile.getName() + ".tmp");
            Files.write(tmp.toPath(), json.getBytes(StandardCharsets.UTF_8));
            Files.move(tmp.toPath(), diskCacheFile.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            diskCacheDirty = false;
        } catch (IOException e) {
            System.err.println("[通用翻译] 保存磁盘缓存失败: " + e.getMessage());
        }
    }

    public void reload() {
        this.translator = createTranslator();
        memoryCache.clear();
    }

    public void clearCache() {
        memoryCache.clear();
        synchronized (diskCache) {
            diskCache.clear();
        }
        synchronized (pending) {
            pending.clear();
        }
        diskCacheDirty = true;
        saveDiskCache();
    }

    public int diskCacheSize() {
        return diskCache.size();
    }

    public void flushCache() {
        if (!diskCacheDirty) {
            return;
        }
        saveScheduled.set(false);
        saveDiskCache();
    }

    public interface TranslationCallback {
        void onResult(String translated);
    }
}
