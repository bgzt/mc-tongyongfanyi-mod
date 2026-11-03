import org.apache.commons.lang3.SystemUtils

plugins {
    id("java")
    id("gg.essential.loom") version "0.10.0.5"
    id("dev.architectury.architectury-pack200") version "0.1.3"
}

val baseGroup: String by project
val mcVersion: String by project
val modid: String by project
val modVersion: String by project

group = baseGroup
version = modVersion

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

loom {
    forge {
        pack200Provider.set(dev.architectury.pack200.java.Pack200Adapter())
    }
}

sourceSets.main {
    output.setResourcesDir(sourceSets.main.get().java.classesDirectory)
    // 与 Fabric 版共用内置模型安装器（必须保持 Java 8 语法）
    java.srcDir("../shared/src/main/java")
}

repositories {
    mavenCentral()
    maven("https://repo.spongepowered.org/maven/")
}

dependencies {
    minecraft("com.mojang:minecraft:1.8.9")
    mappings("de.oceanlabs.mcp:mcp_stable:22-1.8.9")
    forge("net.minecraftforge:forge:1.8.9-11.15.1.2318-1.8.9")
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.release.set(8)
}

tasks.withType<Jar> {
    archiveBaseName.set(modid)
}

// ---------------------------------------------------------------------------
// 内置模型（Qwen2.5 0.5B）：-PwithModel=true 时把模型打进 jar
// ---------------------------------------------------------------------------
// 和 Fabric 版用同一份模型源（便携包的 models 目录）与同一份安装器源码
// （shared/src/main/java），所以两边行为一致：首次启动自动铺到 Ollama 的 models 目录。
// 默认不打，产出的是小体积的普通版；两者只能放一个进 mods。
val withModel: Boolean = (project.findProperty("withModel") as String?) == "true"

if (withModel) {
    val modelsDir = project.projectDir.parentFile.resolve("本地模型包/models")
    val blobDigests = listOf(
        "c5396e06af294bd101b30dce59131a76d2b773e76950acc870eda801d3ab0515", // model    397807936
        "005f95c7475154a17e84b85cd497949d6dd2a4f9d77c096e3c66e4d9c32acaf5", // config         490
        "66b9ea09bd5b7099cbb4fc820f31b575c0366fa439b08245566692c6784e281e", // system          68
        "eb4402837c7829a690fa845de4d7f3fd842c2adee476d5341da8a46ea9255175", // template      1482
        "832dd9e00a68dd83b3c3fb9f5588dad7dcf337a0db50f7d9483f310cd292e92e", // license      11343
    )
    val manifest = modelsDir.resolve("manifests/registry.ollama.ai/library/qwen2.5/0.5b")
    if (!manifest.exists()) {
        throw GradleException("找不到内置模型 manifest: $manifest（模型源文件在根目录的 本地模型包/models 下）")
    }
    blobDigests.forEach { digest ->
        val blob = modelsDir.resolve("blobs/sha256-$digest")
        if (!blob.exists()) {
            throw GradleException("找不到内置模型 blob: $blob")
        }
    }
    // 只往源码 jar 里加内容；loom 的 remapJar 会带着它一起产出最终包
    tasks.named<Jar>("jar") {
        into("tongyongfanyi/bundled/qwen2.5-0.5b") {
            from(manifest) { rename { "manifest.json" } }
            blobDigests.forEach { from(modelsDir.resolve("blobs/sha256-$it")) }
        }
        // GGUF 压不动，STORED 让构建不再白花时间压缩 380MB
        entryCompression = org.gradle.api.tasks.bundling.ZipEntryCompression.STORED
    }
}

tasks.processResources {
    inputs.property("version", project.version)
    inputs.property("mcversion", mcVersion)
    inputs.property("modid", modid)
    filesMatching(listOf("mcmod.info")) {
        expand(inputs.properties)
    }
}
