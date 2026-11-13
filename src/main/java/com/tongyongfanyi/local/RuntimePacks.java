package com.tongyongfanyi.local;

import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 运行时包（Ollama 可执行文件 + 依赖库）的发现与平台匹配。
 *
 * <p><b>运行时包不是 Minecraft 模组</b>：它是标准 zip，不含 {@code fabric.mod.json}，
 * 放在 {@code mods/} 里 Fabric 不会扫描。包内结构：
 * <pre>
 *   runtime-pack.json                 通用元数据（平台、架构、引擎）
 *   tongyongfanyi/runtime.properties  id / platform / arch / label / exe
 *   tongyongfanyi/runtime.zip         ollama/ 目录（可执行文件 + 库）
 * </pre>
 *
 * <p>为什么要拆出来：Ollama 二进制是**按平台分**的（Windows x64 的 .exe/.dll 在
 * Android ARM64 上跑不了）。放进代码包里等于把代码包钉死在一个平台上。拆成独立
 * 文件后，同一份代码包配合 {@code windows-x64} / {@code android-arm64} 两个运行时包
 * 就能覆盖电脑和手机；玩家把两个包都放进 {@code mods/}，本类会自己挑当前平台那个。
 *
 * <p>扫描位置：
 * <ol>
 *   <li>{@code mods/tongyongfanyi-runtime-*.zip}</li>
 *   <li>{@code config/tongyongfanyi/runtime-packs/*.zip}</li>
 * </ol>
 */
public final class RuntimePacks {
    private static final Logger LOGGER = LoggerFactory.getLogger("tongyongfanyi");

    /** 包内属性文件（轻量，几行 key=value，不用解析 json） */
    private static final String PROPS = "tongyongfanyi/runtime.properties";
    /** 包内的运行时压缩包，解出来是 {@code ollama/} 目录 */
    public static final String RUNTIME_ZIP = "tongyongfanyi/runtime.zip";
    /** 文件名前缀，避免误扫玩家放在 mods 里的其它 zip */
    public static final String FILE_PREFIX = "tongyongfanyi-runtime";

    /**
     * 一个运行时包。
     *
     * @param platform windows / android / linux / macos
     * @param arch     x64 / arm64 / any
     * @param exe      包内可执行文件相对路径，如 {@code ollama/ollama.exe}
     */
    public record Pack(String id, String platform, String arch, String label, String exe, Path zip) {
        public String key() {
            return platform + "-" + arch;
        }

        /** 这个包能不能在当前机器上跑 */
        public boolean matchesCurrent() {
            if (!platform.equalsIgnoreCase(currentPlatform())) {
                return false;
            }
            return "any".equalsIgnoreCase(arch) || arch.equalsIgnoreCase(currentArch());
        }
    }

    private static volatile List<Pack> cache;
    private static volatile long scannedAt;

    private RuntimePacks() {
    }

    // ------------------------------------------------------------------
    // 当前平台
    // ------------------------------------------------------------------

    /**
     * 当前平台。Android 要单独识别：它的 {@code os.name} 往往就是 {@code Linux}，
     * 只能靠 {@code ANDROID_ROOT} 环境变量或 ART/Dalvik 的 VM 名来区分，
     * 否则手机上的 Linux 包和 Android 包会被认成同一类。
     */
    public static String currentPlatform() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String vm = System.getProperty("java.vm.name", "").toLowerCase(Locale.ROOT);
        String vendor = System.getProperty("java.vendor", "").toLowerCase(Locale.ROOT);
        if (os.contains("android") || vm.contains("dalvik") || vendor.contains("android")
                || System.getenv("ANDROID_ROOT") != null) {
            return "android";
        }
        if (os.contains("win")) {
            return "windows";
        }
        if (os.contains("mac")) {
            return "macos";
        }
        if (os.contains("linux") || os.contains("unix")) {
            return "linux";
        }
        return "unknown";
    }

    public static String currentArch() {
        String a = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        if (a.equals("amd64") || a.equals("x86_64")) {
            return "x64";
        }
        if (a.equals("aarch64") || a.equals("arm64")) {
            return "arm64";
        }
        if (a.equals("x86") || a.equals("i386") || a.equals("i686")) {
            return "x86";
        }
        if (a.startsWith("arm")) {
            return "arm";
        }
        return a.isEmpty() ? "unknown" : a;
    }

    /** 形如 {@code windows-x64}，报错信息和文件名都用这个 */
    public static String currentKey() {
        return currentPlatform() + "-" + currentArch();
    }

    public static boolean isWindows() {
        return "windows".equals(currentPlatform());
    }

    /**
     * 是不是 Android（FCL / PojavLauncher 里的 JVM）。
     *
     * <p>拉起的 Ollama 行为差别很大：没有 Vulkan 后端、不能从 app 私有目录
     * exec 二进制（Android 10+ 的 W^X 限制）、内存只有几 GB，
     * 所以上下文长度、GPU 选项、运行时来源都要单独处理。
     */
    public static boolean isAndroid() {
        return "android".equals(currentPlatform());
    }

    /** 当前平台下 Ollama 可执行文件名（Windows 带 .exe，其它平台不带） */
    public static String exeName() {
        return "windows".equals(currentPlatform()) ? "ollama.exe" : "ollama";
    }

    // ------------------------------------------------------------------
    // 扫描与匹配
    // ------------------------------------------------------------------

    /** 扫描所有运行时包；结果缓存 5 秒，避免启动流程里反复读磁盘 */
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
                LOGGER.warn("扫描运行时包目录 {} 失败: {}", dir, e.toString());
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
                game.resolve("config").resolve("tongyongfanyi").resolve("runtime-packs"));
    }

    /** 当前平台可用的运行时包；没有匹配返回 null */
    public static Pack match() {
        for (Pack p : all()) {
            if (p.matchesCurrent()) {
                return p;
            }
        }
        return null;
    }

    /** 打开运行时包 zip。调用方负责关闭。 */
    public static ZipFile open(Pack pack) throws java.io.IOException {
        return new ZipFile(pack.zip().toFile());
    }

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
            String platform = props.getProperty("platform", "").trim();
            String arch = props.getProperty("arch", "any").trim();
            if (platform.isEmpty()) {
                return null;
            }
            String id = props.getProperty("id", fallback).trim();
            String label = props.getProperty("label", id).trim();
            String exe = props.getProperty("exe", "ollama/" + ("windows".equalsIgnoreCase(platform)
                    ? "ollama.exe" : "ollama")).trim();
            return new Pack(id, platform, arch, label, exe, zip);
        } catch (Exception ex) {
            LOGGER.warn("读取运行时包 {} 失败: {}", zip, ex.toString());
            return null;
        }
    }

    // ------------------------------------------------------------------
    // 可执行权限
    // ------------------------------------------------------------------

    /**
     * 解包后的可执行文件要补上 +x。Windows 不需要，Android/Linux 上缺这一步会直接
     * {@code Permission denied}；Android 内部存储挂载常常不支持 POSIX 权限 API，
     * 所以失败后再用 chmod 兜一次。
     */
    public static void markExecutable(Path exe) {
        if ("windows".equals(currentPlatform()) || exe == null || !Files.isRegularFile(exe)) {
            return;
        }
        try {
            var perms = Files.getPosixFilePermissions(exe);
            perms.add(PosixFilePermission.OWNER_EXECUTE);
            perms.add(PosixFilePermission.GROUP_EXECUTE);
            perms.add(PosixFilePermission.OTHERS_EXECUTE);
            Files.setPosixFilePermissions(exe, perms);
            return;
        } catch (Exception ignored) {
            // 落到 chmod
        }
        try {
            Process p = new ProcessBuilder("chmod", "+x", exe.toString())
                    .redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            p.waitFor();
        } catch (Exception e) {
            LOGGER.warn("给 {} 加可执行权限失败: {}", exe, e.toString());
        }
    }
}
