# 通用翻译 Together（tongyongfanyi）

把服务器发来的英文消息**自动翻成中文**的 Minecraft 客户端模组（Fabric，26.2 / 26.3 双版本）。只修改客户端显示，**发给服务器的消息一个字节都不动**。最初为 Hypixel SkyBlock 写，其他服务器同样适用。

1.5.0 起采用**拆分包**架构：代码包按游戏版本分发，运行时包与模型包跨版本通用。

---

## 翻译范围

| 位置 | 说明 |
|---|---|
| 聊天 / 系统消息 | 服务器广播、队伍消息、私聊 |
| 物品悬停提示 | tooltip 名称与描述 |
| Boss 栏 | 顶部 Boss 名称 |
| 计分板侧栏 | 行文字，含队伍前缀/后缀拼出来的那种 |
| 空中悬浮文字 | text display 实体（箱子商店、提示牌一类） |
| 屏幕标题 | 大标题、副标题、动作栏 |
| 模组界面文字 | 未汉化模组绘制的界面文字（可关） |

配套能力：本地磁盘缓存、已含中文的消息自动跳过、翻译队列优先级（悬停插队）、
纯 ID / UUID 行自动跳过、术语表（内置 → 远程 → 用户自定义层层覆盖）、
「检查并更新数据表」手动联网入口。

## 两种翻译后端

| 后端 | 说明 |
|---|---|
| **在线 API** | OpenAI 兼容接口（DeepSeek、MiMo 等），设置页填地址 / 密钥 / 模型即可；支持思考强度开关、并发限速 |
| **本机 Ollama** | 完全离线，不上传任何内容；可用仓库提供的运行时包免装 Ollama |

用在线 API 的话，运行时包和模型包**都不需要**。

---

## 安装（v1.5.0 三件套）

把下面三个文件**全部丢进 `mods/`**（官方启动器 `.minecraft/mods/`，PCL 等版本隔离启动器 `.minecraft/versions/<版本>/mods/`）：

| 包 | 文件 | 版本要求 | 下载 |
|---|---|---|---|
| **代码包** | `tongyongfanyi-mc26.2-1.5.0.jar` | Minecraft **26.2** | [Releases v1.5.0](https://github.com/bgzt/mc-tongyongfanyi-mod/releases/tag/v1.5.0) |
| **代码包** | `tongyongfanyi-mc26.3-1.5.0.jar` | Minecraft **26.3** | [Releases v1.5.0](https://github.com/bgzt/mc-tongyongfanyi-mod/releases/tag/v1.5.0) |
| **运行时包** | `tongyongfanyi-runtime-windows-x64.zip` | 无要求，跨版本通用 | 同上 |
| **模型包** | `tongyongfanyi-model-*.zip` | 无要求，跨版本通用 | [模型包仓库](https://github.com/bgzt/mc-tongyongfanyi-models/releases) |

- 两个代码包**只能放一个**（modid 相同，同时放会启动失败）。
- 运行时包 / 模型包只在用**本地 Ollama** 时需要；换 MC 版本不用重新下载。
- 首次进游戏自动解包到 `versions/<版本>/tongyongfanyi/`（运行时约 122 MB、模型 0.4~1.4 GB，中途打断下次会补齐）。**更新代码包不会触发重新解包**，只有换包文件才会。
- 已经自己装了 Ollama 的话，运行时包和模型包都可以不要，模组会自动探测本机 Ollama。

装完启动游戏，输入 `/tyfy` 打开设置界面（装了 ModMenu 也可以从模组列表点「设置」）。

### 模型包一览

| 文件 | 模型 | 体积 | 特点 |
|---|---|---|---|
| `tongyongfanyi-model-qwen35-q3.zip` | Qwen3.5 0.8B Q3_K_M | 约 448 MB，显存/内存占用通常小于1G |（低） 更省显存，速度最快，质量较差 |
| `tongyongfanyi-model-qwen35-q4.zip` | Qwen3.5 0.8B Q4 | 约 517 MB，显存/内存占用通常小于1G | （中）速度快，显存占用小 |
| `tongyongfanyi-model-qwen35-2b-q4.zip` | Qwen3.5 2B Q4 | 约 1.2 GB，显存/内存占用大于1G | （高）质量更好|
| `tongyongfanyi-model-hymt2-q4.zip` | 混元 HY-MT2 1.8B Q4 | 约 1.4 GB，显存/内存占用大于1.5G | （高）腾讯混元翻译专用模型，英译中效果好 |

上下文窗口统一 1536；本地模型闲置 10 分钟自动释放显存。

## 常用命令

```
/tyfy            打开设置界面
/tyfy toggle     开关翻译
/tyfy status     查看当前后端 / 模型 / 缓存条数
/tyfy reload     重新读配置
/tyfy tables     检查并更新远程数据表（唯一会联网拉数据的入口，不会自动更新）
```

## 配置文件

`config/tongyongfanyi.json`（PCL：`.minecraft/versions/<版本>/config/`）。常用字段：

| 字段 | 说明 |
|---|---|
| `enabled` / `targetLanguage` | 总开关 / 目标语言 |
| `translateChatMessages` 等 | 各位置翻译开关 |
| `backend` | 翻译方式：在线 API 或本地 Ollama |
| `apiBaseUrl` / `apiKey` / `apiModel` | 在线 API 三件套（OpenAI 兼容格式） |
| `apiReasoning` | 思考强度（翻译用 `off` 即可） |
| `timeoutMs` / `translationThreads` | 超时与线程数（本地 CPU 模型建议放宽超时） |
| `ollamaPath` / `ollamaModelsPath` | 本机 Ollama 路径，留空自动探测 |

密钥只保存在本地配置文件里，不写入日志。

## 从源码构建

需要 **JDK 25**（构建脚本固定 `--release 25`）：

```bash
# 快速验证（不内置任何包，产出 26.3 代码包，文件名带 -mc26.3）
./gradlew build -Pbundle=none

# 26.2 代码包
./gradlew build -Pbundle=none -Pmcver=26.2 -PapiVersion=0.161.0+26.2 \
  -PapiDep=0.161.0 -PmodmenuVersion=20.0.3 -ParchiveSuffix=-mc26.2

# 运行时包 / 模型包（从 本地模型包/ 读取，模型不入库）
./gradlew runtimePack -PrtPlatform=windows-x64
./gradlew modelPack -Pbundle=qwen35q4   # 可选：qwen35q3 / qwen35q4 / qwen35q8 / hymt，见 build.gradle 头部
```

模型源文件放在 `本地模型包/models`（Ollama manifest + blob 结构），工程不重复存放这几个 GB。

## 旧版本（1.8.9 Forge）

1.8.9 Forge 版停留在 [forge189-v1.0.0](https://github.com/bgzt/mc-tongyongfanyi-mod/releases/tag/forge189-v1.0.0)，`forge189/` 目录保留源码但不再活跃维护。

## License

本项目代码以 [Apache-2.0](LICENSE) 开源。

模型包仓库 [bgzt/mc-tongyongfanyi-models](https://github.com/bgzt/mc-tongyongfanyi-models)：
Qwen3.5 系列模型遵循其官方 **Apache-2.0** 协议；混元 HY-MT2 模型遵循腾讯混元自身的模型协议，使用前请自行查阅。
