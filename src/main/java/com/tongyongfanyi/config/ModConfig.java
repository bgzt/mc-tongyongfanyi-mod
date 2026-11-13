package com.tongyongfanyi.config;

/**
 * 通用翻译 Mod 配置，Gson 序列化到 config/tongyongfanyi.json。
 *
 * <p>1.5.0 起模型固定内置在 jar 里（分 qwen35 / qwen25 / hymt 三个版本），不再支持自选模型，
 * 因此没有 model / ollamaPath / modelsPath 这类字段。
 */
public class ModConfig {
    /** 总开关 */
    public boolean enabled = true;
    /** 源语言，{@code auto} 表示自动检测；取值见 {@link Languages} */
    public String sourceLanguage = "auto";
    /** 目标语言，取值见 {@link Languages}；{@code game} 表示跟随游戏语言 */
    public String targetLanguage = "zh-CN";
    /** 设置页界面语言：{@code auto} 跟随游戏语言，或 {@link I18n#UI_LANGUAGES} 里的具体代码 */
    public String uiLanguage = "auto";

    /**
     * 解析「跟随游戏语言」之后的真实目标语言。
     * 提示词、术语表判定和缓存键都必须用这个值，否则玩家把游戏语言从中文改成英文后，
     * 还会继续命中之前缓存的中文译文。
     */
    public String effectiveTarget() {
        return Languages.resolveTarget(targetLanguage);
    }

    /** 翻译服务器系统消息(game messages) */
    public boolean translateGameMessages = true;
    /** 翻译玩家聊天消息 */
    public boolean translateChatMessages = true;
    /** 翻译物品悬停提示 (tooltip) */
    public boolean translateItemTooltips = true;
    /** 翻译模组/界面绘制出来的英文文字（屏幕绘制层通用翻译） */
    public boolean translateScreenText = true;
    /** 翻译世界里立在方块上的告示牌文字（含悬挂告示牌，正反面都算） */
    public boolean translateSignText = true;
    /** 翻译成书（written book）阅读界面里的整页文字 */
    public boolean translateBookText = true;

    /**
     * 整行合并翻译：把同一行里「同色、无点击事件」的多段拼成一句送模型。
     *
     * <p>开（默认）：请求数降到 1/4 左右，翻得快，但小模型对长句更容易逐词直译。
     * 关：仍按段分别翻译（段与段之间不会串），请求多、出现慢，但每段更短更稳。
     */
    public boolean mergeWholeLine = true;
    /** 左上角显示状态行（当前模型 + 是否正在翻译） */
    public boolean showHudStatus = true;
    /**
     * 句中术语替换：把句子里的术语用占位符锁成固定译名。
     * 关掉后模型自由翻译整句（语序更自然，但 Purse→钱包 这类固定叫法会变成模型自己猜）。
     */
    public boolean useGlossary = true;

    /**
     * 整句术语固定译名（与上面分开控制）。
     *
     * <p>计分板、按钮、物品名常常整段就是一个术语（{@code Bits:}、{@code Enchanted Hard Stone}），
     * 这种交给模型几乎必然出错（实测 {@code Bits: → 位：}）。所以即使玩家关掉了句中替换，
     * 整句命中也应当直接给固定译名 —— 它不会影响普通句子的语序。
     */
    public boolean glossaryExact = true;
    /**
     * 内置 NEU 物品名表（约 4800 条 SkyBlock 物品名 → 中文）。
     *
     * <p>它的作用是让物品名**整句直接出中文**，不走模型：Hypixel 的物品名大量是
     * 「专名 + 等级」（{@code Acacia Minion XII}、{@code Absorption IV Potion}），
     * 交给 1.8B 模型很容易翻成「金合欢 仆从 十二」这种半成品。
     *
     * <p>关掉它不影响其它翻译，只是物品名重新交给模型/缓存处理；
     * 内存里那张表也不会被加载，省几 MB。
     */
    public boolean itemNameTable = true;
    // ------------------------------------------------------------------
    // 远程数据表：只手动更新。启动、进游戏、切界面都不会发起任何网络请求。
    // 只有玩家点「SkyBlock 翻译增强 → 检查并更新数据表」（或输入 /tyfy tables）
    // 才会去读 manifest、比对版本并下载。下载的表放在
    // config/tongyongfanyi/tables/，之后每次启动优先用这份；
    // 没点过更新就一直用 jar 内置的那份快照。
    // ------------------------------------------------------------------
    /** 数据表仓库，格式 {@code owner/name}。想用自建同步源就改这里 */
    public String tableRepo = "bgzt/tongyongfanyi-data";
    /** 数据表分支 */
    public String tableBranch = "main";
    /** 聊天里的「玩家名: 正文」只翻正文，名字保持原文 */
    public boolean keepSpeakerNames = true;
    /**
     * 单个词也翻译（默认关）。关着时普通单词、术语、地名照常翻译，
     * 只有「在线玩家 ID / 头顶用户名」被精确过滤；打开后连玩家 ID 也送模型
     * （可能音译人名，且会明显增加请求量）。
     */
    public boolean translateSingleWords = false;

    /**
     * 智能翻译（默认开）：先判断这段英文「值不值得翻」再送模型 ——
     * 玩家 ID、称号、游戏内时间、网址、装饰符号、人名一律跳过，
     * 只翻真正有语义的句子和术语。
     *
     * <p>关掉之后不再做任何判断，只要带英文字母就送去翻译，
     * 代价是玩家名会被音译成中文、请求量明显上升（容易被模型照抄→判失败→重试）。
     */
    public boolean smartTranslate = true;
    /** 最短翻译长度(字符), 太短不翻译 */
    public int minLength = 3;
    /** 内存缓存的条目上限 */
    public int cacheSize = 2048;
    /** 已含中文的消息不再翻译(避免二次翻译) */
    public boolean skipIfContainsChinese = true;
    /**
     * 本地模型的翻译线程数。默认 1（串行），上限 8：
     * 每多一路并行就多一份 KV 缓存（约 96MB/路），低配机器建议保持 1。
     */
    public int translationThreads = 1;

    /**
     * 在线 API 的翻译线程数，和 {@link #translationThreads} 完全独立。
     *
     * <p>本地受显存和「单模型一次只能跑一路推理」限制，1~4 就到顶了；
     * 云端 API 天生并发，开 6~8 路能把一屏文字一次发完，不会像本地那样排队。
     * 分开存是为了切后端时不用反复手动改数字 —— 切回本地仍用原来那个 1。
     */
    public int apiThreads = 6;

    /** 单次翻译请求超时(毫秒) */
    public int timeoutMs = 20000;

    /**
     * 本地模型是否开启思考（Ollama 的 {@code think} 参数）。
     *
     * <p>默认关。Qwen3.5 是 thinking 模型，开思考会先输出一大段推理再给正文：
     * 单条延迟翻几倍，还可能把 {@code num_predict} 预算吃光导致译文为空。
     * 翻译是短句任务，用不上推理，所以只提供开/关，不提供强度档
     * （Ollama 协议本身也没有 reasoning_effort / budget_tokens 这类参数）。
     */
    public boolean localThinking = false;

    /**
     * 闲置多久之后释放模型显存（分钟，0 = 不释放）。
     *
     * <p>模型常驻显存约 1.6GB，挂着不动也占着。默认 10 分钟没有任何翻译请求就把它卸掉，
     * 下次需要翻译再自动加载（1~2 秒，只发生一次）。
     *
     * <p>为什么是「闲置才释放」而不是「定时清上下文」：每次请求都是无状态的
     * （只发 system + 这一句），模型侧没有会累积的上下文，定时卸载只会白白多付
     * 一次 1~2 秒的加载，玩的时候就是每 N 分钟卡一下。闲置释放则不会撞上正在翻译的时刻。
     */
    public int idleReleaseMinutes = 10;

    /**
     * 选中的模型包 id（{@code mods/tongyongfanyi-model-*.zip} 里的 id）。
     * 空 = 自动选按显示名排序的第一个。设置页「模型包」下拉会写这里。
     */
    public String modelPack = "";

    /**
     * Ollama 运行时来源：
     * <ul>
     *   <li>{@code auto}：优先用本机安装的 Ollama（版本够新时省掉 122 MB 解包）；
     *       找不到或版本过旧才解 {@code mods/} 里的运行时包。</li>
     *   <li>{@code bundled}：强制用运行时包，完全不碰本机安装的 Ollama。
     *       适合「本机 Ollama 版本旧/被改过/想让版本固定」的情况。</li>
     * </ul>
     * 注意：模型包里只有模型名、没有权重时，必须依赖本机 Ollama，
     * 此时 {@code bundled} 会直接报错而不是静默回落。
     */
    public String runtimeMode = "auto";

    /**
     * 翻译后端：
     * <ul>
     *   <li>{@code local}：本地 Ollama（内置运行时或模型包），离线；</li>
     *   <li>{@code api}：OpenAI 兼容的在线接口，密钥单独存在 secret 文件里。</li>
     * </ul>
     */
    public String translateBackend = "local";

    /** API 预设：{@code deepseek} / {@code mimo} / {@code custom} */
    public String apiProvider = "deepseek";

    /** API 协议：{@code openai}（/v1/chat/completions）或 {@code ollama}（/api/chat） */
    public String apiProtocol = "openai";

    /** API 模式的 Base URL，不含 {@code /v1/chat/completions}；默认 DeepSeek */
    public String apiBaseUrl = "https://api.deepseek.com";

    /** API 模式的模型名 */
    public String apiModel = "deepseek-chat";

    /**
     * 思考强度：{@code off} / {@code low} / {@code medium} / {@code high}。
     *
     * <p>翻译场景默认关掉：思考 token 既慢又贵，还会把 {@code max_tokens}
     * 挤占掉导致正文为空。需要模型处理复杂长句时可以调高。
     */
    public String apiReasoning = "off";

    // ------------------------------------------------------------------
    // 翻译优先级（1 最高，5 最低）。翻译线程有限，同时涌入大量文本时，
    // 数字小的来源先出队 —— 你觉得哪儿更该先看到中文就把它调小。
    // ------------------------------------------------------------------

    /** 聊天栏 / NPC 对话 */
    public int priorityChat = 1;
    /** 物品悬停提示（名称 + lore） */
    public int priorityTooltip = 2;
    /** 右侧计分板 */
    public int priorityScoreboard = 3;
    /** 模组/原版界面上的绘制文字（背包、菜单等） */
    public int priorityScreen = 4;
    /** 屏幕标题、动作栏、Boss 血条、头顶名 */
    public int priorityHud = 5;
    /**
     * 物品大列表界面（REI / JEI / NEU 物品浏览器）。
     *
     * <p>这类界面一屏能画出上千件物品，每件都要翻名字和描述，量级远超其它来源。
     * 默认给最低档（6），让聊天、悬停提示、计分板都排在它前面 ——
     * 否则一打开物品列表，正常文本全被挤到后面。
     */
    public int priorityItemList = 6;

    /**
     * 推理设备：{@code auto} / {@code gpu} / {@code cpu}。
     * gpu 只在本机确有可用显卡时才会出现在设置页里。
     */
    public String device = "auto";
}
