package com.tongyongfanyi.local;

import com.tongyongfanyi.config.ModConfig;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 通用模型包的发现与选择。
 *
 * <p><b>模型包不是 Minecraft 模组</b>：它就是一个标准 zip，不依赖 Fabric、不需要
 * {@code fabric.mod.json}，放在 {@code mods/} 里也不会被 Fabric 当成模组加载
 * （Fabric 只扫带元数据的 jar）。任何工具都能解压查看内容，只有本模组会读它。
 *
 * <p>包内结构（全部 STORED，无需解压即可流式读取）：
 * <pre>
 *   model-pack.json                 通用元数据（名称、引擎、模型名）
 *   tongyongfanyi/model.properties  模组读取的模型名/显示名
 *   tongyongfanyi/payload.zip       Ollama 运行时 + 模型 manifest/blobs
 * </pre>
 *
 * <p>扫描位置（按顺序）：
 * <ol>
 *   <li>{@code mods/tongyongfanyi-model-*.zip}</li>
 *   <li>{@code config/tongyongfanyi/model-packs/*.zip}</li>
 * </ol>
 * 选择顺序：{@link ModConfig#modelPack} 指定的 id → 按显示名排序的第一个。
 */
public final class ModelPacks {
    private static final Logger LOGGER = LoggerFactory.getLogger("tongyongfanyi");

    private static final String PROPS = "tongyongfanyi/model.properties";
    /** 新模型包：只含模型（manifest + blobs），运行时在代码包里 */
    private static final String MODELS_ZIP = "tongyongfanyi/models.zip";
    /** 旧模型包：整包 payload（运行时 + 模型） */
    private static final String PAYLOAD = "tongyongfanyi/payload.zip";
    /** 文件名前缀，避免误扫玩家放在 mods 里的其它 zip。 */
    public static final String FILE_PREFIX = "tongyongfanyi-model";

    /** 一个可用的模型包。{@code id} 来自 model-pack.json / model.properties / 文件名。 */
    public record Pack(String id, String name, String label, Path zip, Properties props) {
        public boolean hasPayload() {
            try (ZipFile z = new ZipFile(zip.toFile())) {
                return z.getEntry(MODELS_ZIP) != null || z.getEntry(PAYLOAD) != null;
            } catch (Exception ignored) {
                return false;
            }
        }

        /** 新格式：模型包里只有 models.zip，没有运行时 */
        public boolean hasModelsZip() {
            try (ZipFile z = new ZipFile(zip.toFile())) {
                return z.getEntry(MODELS_ZIP) != null;
            } catch (Exception ignored) {
                return false;
            }
        }
    }

    private static volatile List<Pack> cache;
    private static volatile long scannedAt;
    private static volatile Pack active;

    private ModelPacks() {
    }

    /** 扫描所有模型包，按显示名排序。结果缓存 5 秒，避免设置页每帧重扫磁盘。 */
    public static List<Pack> all() {
        long now = System.currentTimeMillis();
        List<Pack> hit = cache;
        if (hit != null && now - scannedAt < 5_000L) {
            return hit;
        }
        List<Pack> found = new ArrayList<>();
        for (Path dir : candidateDirs()) {
            if (!Files.isDirectory(dir)) {
                continue;
            }
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, FILE_PREFIX + "*.zip")) {
                for (Path p : stream) {
                    if (!Files.isRegularFile(p)) {
                        continue;
                    }
                    Pack pack = read(p);
                    if (pack != null) {
                        found.add(pack);
                    }
                }
            } catch (Exception e) {
                LOGGER.warn("扫描模型包目录 {} 失败: {}", dir, e.toString());
            }
        }
        found.sort(Comparator.comparing(Pack::label, String.CASE_INSENSITIVE_ORDER));
        cache = List.copyOf(found);
        scannedAt = now;
        return cache;
    }

    private static List<Path> candidateDirs() {
        Path game = FabricLoader.getInstance().getGameDir();
        return List.of(
                game.resolve("mods"),
                game.resolve("config").resolve("tongyongfanyi").resolve("model-packs"));
    }

    /** 当前生效的模型包；没有模型包时返回 null（此时只能用代码包自带的 payload）。 */
    public static Pack active() {
        Pack a = active;
        if (a != null) {
            return a;
        }
        List<Pack> list = all();
        return list.isEmpty() ? null : list.get(0);
    }

    /**
     * 按配置选中模型包。每次启动本地服务前调用一次：
     * 配置里指定的 id 不存在（比如玩家删了那个包）时回落列表第一个，不让翻译直接不可用。
     */
    public static void init(ModConfig config) {
        List<Pack> list = all();
        String want = config == null || config.modelPack == null ? "" : config.modelPack.trim();
        Pack pick = null;
        if (!want.isEmpty()) {
            for (Pack p : list) {
                if (p.id().equals(want)) {
                    pick = p;
                    break;
                }
            }
            if (pick == null && !list.isEmpty()) {
                LOGGER.warn("配置的模型包 {} 不在磁盘上，改用 {}", want, list.get(0).id());
            }
        }
        if (pick == null && !list.isEmpty()) {
            pick = list.get(0);
        }
        active = pick;
        if (pick != null) {
            LOGGER.info("使用模型包: {} [{}] -> {}", pick.label(), pick.id(), pick.zip().getFileName());
        }
    }

    /** 打开模型包 zip（解包 payload 用）。调用方负责关闭。 */
    public static ZipFile open(Pack pack) throws java.io.IOException {
        return new ZipFile(pack.zip().toFile());
    }

    /** 从 zip 里读 model.properties；读不到时返回 null（不是模型包，跳过）。 */
    private static Pack read(Path zip) {
        try (ZipFile z = new ZipFile(zip.toFile())) {
            ZipEntry e = z.getEntry(PROPS);
            if (e == null) {
                return null;
            }
            Properties props = new Properties();
            try (InputStream in = z.getInputStream(e)) {
                props.load(in);
            }
            String fallback = zip.getFileName().toString().replaceAll("\\.zip$", "");
            String name = props.getProperty("name", "").trim();
            String id = props.getProperty("id", fallback).trim();
            String label = props.getProperty("label", name.isEmpty() ? fallback : name).trim();
            return new Pack(id, name, label, zip, props);
        } catch (Exception ex) {
            LOGGER.warn("读取模型包 {} 失败: {}", zip, ex.toString());
            return null;
        }
    }
}
