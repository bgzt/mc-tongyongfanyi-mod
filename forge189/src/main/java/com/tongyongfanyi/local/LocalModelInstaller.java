package com.tongyongfanyi.local;

import java.io.File;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 内置模型安装（1.8.9 Forge 版）。
 *
 * <p>1.8.9 版不负责启动 Ollama（由玩家用"启动翻译服务.bat"或系统服务启动），
 * 所以这里只做一件事：把 jar 自带的 Qwen2.5 0.5B 铺到 Ollama 的 models 目录。
 *
 * <p>真正的读/写逻辑在共用的 {@link BundledModel} 里，两个模组用的是同一份源码。
 */
public final class LocalModelInstaller {
    private static volatile boolean installing;
    private static volatile String status = "";
    private static volatile int percent = -1;
    private static volatile boolean ready;
    private static volatile String error = "";
    /** 启动时只自动尝试一次 */
    private static final AtomicBoolean autoAttempted = new AtomicBoolean(false);

    private LocalModelInstaller() {
    }

    /** 这个 jar 里是否带着内置模型 */
    public static boolean hasBundledModel() {
        return BundledModel.payloadPresent(LocalModelInstaller.class.getClassLoader());
    }

    public static boolean isInstalling() {
        return installing;
    }

    public static String status() {
        return status;
    }

    /** 0~100；未在安装时为 -1 */
    public static int percent() {
        return installing ? Math.max(0, percent) : -1;
    }

    /** 内置模型是否已就绪 */
    public static boolean isReady() {
        return ready;
    }

    /** 失败原因；没失败过为空串 */
    public static String error() {
        return error;
    }

    /** 启动时自动装一次（已就绪/正在装/已试过都直接返回） */
    public static void autoInstallOnce(String modelsPath) {
        if (!hasBundledModel() || !autoAttempted.compareAndSet(false, true)) {
            return;
        }
        install(modelsPath);
    }

    /**
     * 手动安装或重试。
     *
     * @return 是否真的开始了安装（jar 没带模型、或已经在装时返回 false）
     */
    public static boolean install(String modelsPath) {
        if (!hasBundledModel() || installing) {
            return false;
        }
        autoAttempted.set(true);
        final Path modelsDir = resolveModelsDir(modelsPath);
        final ClassLoader resources = LocalModelInstaller.class.getClassLoader();
        try {
            if (BundledModel.isInstalled(modelsDir, resources)) {
                ready = true;
                error = "";
                status = "内置模型已就绪";
                percent = 100;
                return false;
            }
        } catch (Exception e) {
            // 检查失败不算致命，继续尝试安装
            error = String.valueOf(e.getMessage());
        }
        installing = true;
        percent = 0;
        status = "正在安装内置模型 " + BundledModel.MODEL_LABEL + "…";
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                installTask(modelsDir, resources);
            }
        }, "tongyongfanyi-bundled");
        thread.setDaemon(true);
        thread.start();
        return true;
    }

    private static void installTask(Path modelsDir, ClassLoader resources) {
        try {
            BundledModel.Result result = BundledModel.install(modelsDir, resources,
                    new java.util.function.BiConsumer<Long, Long>() {
                        @Override
                        public void accept(Long done, Long total) {
                            long t = total == null ? 0L : total.longValue();
                            long d = done == null ? 0L : done.longValue();
                            percent = t <= 0L ? 0 : (int) Math.round(100.0 * d / t);
                        }
                    });
            ready = true;
            error = "";
            if (result != null && result.copiedFiles() > 0) {
                status = "内置模型已就绪（写入 " + result.copiedFiles() + " 个文件，"
                        + Math.round(result.copiedBytes() / 1048576.0) + "MB）";
            } else {
                status = "内置模型已就绪";
            }
            percent = 100;
        } catch (Throwable t) {
            ready = false;
            error = String.valueOf(t.getMessage());
            status = "内置模型安装失败: " + error;
            percent = -1;
            System.err.println("[通用翻译] 内置模型安装失败: " + error);
        } finally {
            installing = false;
        }
    }

    /**
     * 定位 Ollama 的 models 目录：配置路径 → 用户目录下的 .ollama\models。
     * 1.8.9 版无法从运行中的 Ollama 问出模型目录，所以用便携包的用户需要在设置里
     * 填一次（就是包里的 models 文件夹）。
     */
    public static Path resolveModelsDir(String configured) {
        if (configured != null && !configured.trim().isEmpty()) {
            return new File(configured.trim()).toPath();
        }
        return new File(System.getProperty("user.home"), ".ollama" + File.separator + "models").toPath();
    }

    /** 给界面显示用的一句话状态 */
    public static String displayLine() {
        if (!hasBundledModel()) {
            return "内置模型: 这个 jar 未打包模型";
        }
        if (installing) {
            return "内置模型: 安装中 " + Math.max(0, percent) + "%";
        }
        if (ready) {
            return "内置模型: " + BundledModel.MODEL_LABEL + " 已就绪";
        }
        if (error != null && !error.isEmpty()) {
            return "内置模型: 安装失败（" + error + "），点按钮重试";
        }
        return "内置模型: 未安装";
    }
}
