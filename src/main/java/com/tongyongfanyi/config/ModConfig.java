package com.tongyongfanyi.config;

/**
 * 通用翻译 Mod 配置。
 * 使用 Gson 序列化到 config/tongyongfanyi.json。
 *
 * 目前只有「本地模型（Ollama）」这一种翻译方式，因此不再有后端选择项。
 */
public class ModConfig {
    /** 总开关 */
    public boolean enabled = true;
    /** 目标语言代码 */
    public String targetLanguage = "zh-CN";
    /** 源语言代码, auto 表示自动检测 */
    public String sourceLanguage = "auto";
    /** 最短翻译长度(字符), 太短不翻译 */
    public int minLength = 3;
    /** 缓存大小 */
    public int cacheSize = 2048;
    /** 翻译服务器系统消息(game messages) */
    public boolean translateGameMessages = true;
    /** 翻译玩家聊天消息 */
    public boolean translateChatMessages = true;
    /** 翻译物品悬停提示 (tooltip) */
    public boolean translateItemTooltips = true;
    /** 翻译模组/界面绘制出来的英文文字（屏幕绘制层通用翻译） */
    public boolean translateScreenText = true;
    /** 翻译线程数（1-8），聊天优先，批量文字并行翻译 */
    public int translationThreads = 4;
    /** 已含中文的消息不再翻译(避免二次翻译) */
    public boolean skipIfContainsChinese = true;
    /** 单次翻译请求超时(毫秒)：作为 HTTP 请求的真实超时，超时即中断请求并放弃该条结果 */
    public int timeoutMs = 10000;
    /** 本地模型: ollama.exe 完整路径（便携包 ollama 目录内） */
    public String ollamaPath = "";
    /** 本地模型: 模型存放目录 (OLLAMA_MODELS，便携包 models 目录) */
    public String ollamaModelsPath = "";
    /** 本地模型配置 */
    public LocalModelConfig localModel = new LocalModelConfig();

    /** 本地模型（Ollama）参数 */
    public static class LocalModelConfig {
        /** Ollama 服务地址，留空用 http://127.0.0.1:11434 */
        public String url = "";
        /** 使用的模型（Ollama 模型名），默认最轻量的 qwen2.5:0.5b */
        public String model = "qwen2.5:0.5b";
    }
}
