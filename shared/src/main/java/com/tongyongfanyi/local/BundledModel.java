package com.tongyongfanyi.local;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * 打包进 mod jar 的内置模型（Qwen2.5 0.5B）。
 *
 * <p>ollama 的一个模型 = 一个 manifest + 若干 blob：
 * <pre>
 *   models/manifests/registry.ollama.ai/library/qwen2.5/0.5b   (manifest，很小)
 *   models/blobs/sha256-&lt;64位十六进制&gt;                           (权重/模板/许可等)
 * </pre>
 * 所以「把模型整合进 mod」= 把 manifest 和它引用的全部 blob 放进 jar，
 * 首次启动时原样铺到 Ollama 的 models 目录里，之后 Ollama 就当它是本地已装模型。
 *
 * <p><b>本文件是两个模组共用的</b>（Fabric 26.2 与 Forge 1.8.9），源码位于仓库根的
 * {@code shared/src/main/java}，两边构建各自把它加进 srcDir。
 * 因此这里必须保持 <b>Java 8 语法</b>（不能用 record / var / readAllBytes 等），
 * 且只依赖 Gson 与 JDK，不碰 Minecraft。
 */
public final class BundledModel {
    /** 内置模型在 Ollama 里的名字 */
    public static final String MODEL_NAME = "qwen2.5:0.5b";
    /** 内置模型在界面上的显示名 */
    public static final String MODEL_LABEL = "Qwen2.5 0.5B";

    /** jar 内的资源目录 */
    private static final String RESOURCE_DIR = "tongyongfanyi/bundled/qwen2.5-0.5b";
    private static final String MANIFEST_RESOURCE = RESOURCE_DIR + "/manifest.json";
    /** 该模型 manifest 在 models 目录里的相对路径（与 Ollama 自己的布局一致） */
    private static final String MANIFEST_TARGET = "manifests/registry.ollama.ai/library/qwen2.5/0.5b";
    private static final String BLOB_PREFIX = "sha256-";
    private static final int BUFFER = 1 << 16;
    private static final Charset UTF8 = Charset.forName("UTF-8");

    private BundledModel() {
    }

    /** 某个模型名是不是内置模型 */
    public static boolean isBundled(String model) {
        return MODEL_NAME.equals(model);
    }

    /** jar 里是否真的带着模型（没打包模型的版本会返回 false） */
    public static boolean payloadPresent(ClassLoader resources) {
        return resources != null && resources.getResource(MANIFEST_RESOURCE) != null;
    }

    /** 安装结果统计 */
    public static final class Result {
        private final int copiedFiles;
        private final int skippedFiles;
        private final long copiedBytes;

        Result(int copiedFiles, int skippedFiles, long copiedBytes) {
            this.copiedFiles = copiedFiles;
            this.skippedFiles = skippedFiles;
            this.copiedBytes = copiedBytes;
        }

        /** 本次新写入的文件数 */
        public int copiedFiles() {
            return copiedFiles;
        }

        /** 已存在且大小正确、被跳过的文件数 */
        public int skippedFiles() {
            return skippedFiles;
        }

        /** 本次新写入的字节数 */
        public long copiedBytes() {
            return copiedBytes;
        }

        /** 是否本次什么都没做（目标目录里本来就装好了） */
        public boolean alreadyInstalled() {
            return copiedFiles == 0;
        }
    }

    /**
     * 目标目录里是否已经完整装好内置模型。
     * 逐个核对 blob 文件长度（与 manifest 声明的 size 比对）+ manifest 是否存在，
     * 这样半途中断/被截断的安装会被识别为「未装好」并重做。
     */
    public static boolean isInstalled(Path modelsDir, ClassLoader resources) throws IOException {
        byte[] manifest = readManifest(resources);
        if (manifest == null) {
            return false;
        }
        if (!Files.isRegularFile(modelsDir.resolve(MANIFEST_TARGET))) {
            return false;
        }
        List<Entry> entries = parseEntries(manifest);
        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            Path target = modelsDir.resolve("blobs").resolve(e.name);
            if (!Files.isRegularFile(target) || Files.size(target) != e.size) {
                return false;
            }
        }
        return true;
    }

    /**
     * 把内置模型铺到 {@code modelsDir}。已存在且大小正确的文件会跳过（便于断点重试）。
     *
     * @param progress 进度回调 (已处理字节, 总字节)，可为 null
     * @return 安装结果；jar 里没有内置模型时返回 {@code null}
     */
    public static Result install(Path modelsDir, ClassLoader resources,
                                 BiConsumer<Long, Long> progress) throws IOException {
        byte[] manifest = readManifest(resources);
        if (manifest == null) {
            return null;
        }
        List<Entry> entries = parseEntries(manifest);
        long total = 0L;
        for (int i = 0; i < entries.size(); i++) {
            total += entries.get(i).size;
        }
        long done = 0L;
        int copied = 0;
        int skipped = 0;
        if (progress != null) {
            progress.accept(Long.valueOf(0L), Long.valueOf(total));
        }
        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            Path target = modelsDir.resolve("blobs").resolve(e.name);
            if (Files.isRegularFile(target) && Files.size(target) == e.size) {
                skipped++;
                done += e.size;
                if (progress != null) {
                    progress.accept(Long.valueOf(done), Long.valueOf(total));
                }
                continue;
            }
            InputStream in = resources.getResourceAsStream(RESOURCE_DIR + "/" + e.name);
            if (in == null) {
                throw new IOException("jar 内缺少内置模型文件 " + e.name
                        + "（这个 jar 可能没有把模型打包进来）");
            }
            try {
                Files.createDirectories(target.getParent());
                // 先写 .part 再改名：中途失败不会留下一个"看起来完整"的 blob
                Path tmp = target.resolveSibling(target.getFileName() + ".part");
                long entryDone = 0L;
                OutputStream out = Files.newOutputStream(tmp);
                try {
                    byte[] buf = new byte[BUFFER];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                        entryDone += n;
                        if (progress != null) {
                            progress.accept(Long.valueOf(done + entryDone), Long.valueOf(total));
                        }
                    }
                } finally {
                    out.close();
                }
                move(tmp, target);
            } finally {
                in.close();
            }
            done += e.size;
            copied++;
        }
        // manifest 最后写：它一旦存在就代表应该能找到全部 blob
        Path manifestTarget = modelsDir.resolve(MANIFEST_TARGET);
        Files.createDirectories(manifestTarget.getParent());
        Files.write(manifestTarget, manifest);
        return new Result(copied, skipped, done);
    }

    private static byte[] readManifest(ClassLoader resources) throws IOException {
        if (resources == null) {
            return null;
        }
        InputStream in = resources.getResourceAsStream(MANIFEST_RESOURCE);
        if (in == null) {
            return null;
        }
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(4096);
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } finally {
            in.close();
        }
    }

    /** 一个待安装文件：blob 文件名 + 期望字节数 */
    private static final class Entry {
        private final String name;
        private final long size;

        Entry(String name, long size) {
            this.name = name;
            this.size = size;
        }
    }

    private static List<Entry> parseEntries(byte[] manifest) throws IOException {
        JsonObject root;
        try {
            root = new JsonParser().parse(new String(manifest, UTF8)).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new IOException("内置模型 manifest 解析失败: " + e.getMessage());
        }
        List<Entry> entries = new ArrayList<Entry>();
        JsonElement config = root.get("config");
        if (config != null && config.isJsonObject()) {
            entries.add(entry(config.getAsJsonObject()));
        }
        JsonArray layers = root.getAsJsonArray("layers");
        if (layers != null) {
            for (int i = 0; i < layers.size(); i++) {
                entries.add(entry(layers.get(i).getAsJsonObject()));
            }
        }
        if (entries.isEmpty()) {
            throw new IOException("内置模型 manifest 里没有任何 blob");
        }
        return entries;
    }

    private static Entry entry(JsonObject obj) throws IOException {
        JsonElement digestElement = obj.get("digest");
        JsonElement sizeElement = obj.get("size");
        if (digestElement == null || sizeElement == null) {
            throw new IOException("内置模型 manifest 的条目缺少 digest/size");
        }
        String digest = digestElement.getAsString();
        if (!digest.startsWith("sha256:")) {
            throw new IOException("不支持的 blob digest: " + digest);
        }
        return new Entry(BLOB_PREFIX + digest.substring("sha256:".length()), sizeElement.getAsLong());
    }

    private static void move(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
