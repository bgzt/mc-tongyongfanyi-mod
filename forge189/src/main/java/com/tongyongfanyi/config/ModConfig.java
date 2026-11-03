package com.tongyongfanyi.config;

/**
 * 通用翻译配置（存到 config/tongyongfanyi.json）。
 */
public class ModConfig {
    /** 总开关 */
    public boolean enabled = true;
    /** 后端: local(Ollama 本地模型)。便携包已不再附带 OPUS-MT 服务，故只保留本地模型后端。 */
    public String backend = "local";
    /** 目标语言 */
    public String targetLanguage = "zh-CN";
    /** 源语言, auto 表示自动 */
    public String sourceLanguage = "auto";
    /** 最短翻译长度 */
    public int minLength = 3;
    /** 内存缓存条数 */
    public int cacheSize = 2048;
    /** 翻译聊天消息 */
    public boolean translateChat = true;
    /** 翻译物品提示 */
    public boolean translateItemTooltips = true;
    /** 已含中文不再翻译 */
    public boolean skipIfContainsChinese = true;
    /** 翻译超时(毫秒) */
    public int timeoutMs = 3000;
    /** 翻译线程数 */
    public int translationThreads = 4;
    /** 本地 Ollama 地址 */
    public String ollamaUrl = "http://127.0.0.1:11434";
    /** 本地模型名 */
    public String ollamaModel = "qwen2.5:0.5b";
    /**
     * Ollama 的 models 目录（自带模型的那个 jar 会往这里铺 qwen2.5:0.5b）。
     * 留空则用用户目录下的 .ollama\models；用便携包的话填包里的 models 文件夹。
     */
    public String ollamaModelsPath = "";
}
