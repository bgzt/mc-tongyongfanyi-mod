# 通用翻译 Together（tongyongfanyi）

把服务器发来的英文消息**自动翻成中文**的 Minecraft 客户端模组。翻译全部在你自己的电脑上（Ollama）离线完成，不上传任何内容；**只修改客户端显示，发给服务器的一个字节都不改**。

最初为 Hypixel SkyBlock 写，其他服务器同样适用。

---

## 翻译范围

| 位置 | 说明 |
|---|---|
| 聊天 / 系统消息 | 服务器广播、队伍消息、私聊 |
| 物品悬停提示 | tooltip 名称与描述 |
| Boss 栏 | 顶部 Boss 名称（末影龙、世界 Boss 等） |
| 计分板侧栏 | 行文字，含队伍前缀/后缀拼出来的那种 |
| 空中悬浮文字 | text display 实体（箱子商店、提示牌一类） |
| 屏幕标题 | 大标题、副标题、动作栏 |
| 模组界面文字 | 未汉化模组的设置界面文字（可关） |

配套功能：本地磁盘缓存（默认 2048 条，退出时落盘）、已含中文的消息自动跳过、可设置请求超时与翻译线程数。

## 两个版本

仓库里有两个互相独立的工程，对应不同的 Minecraft 版本线：

| 目录 | 游戏版本 | 加载器 | 当前版本 |
|---|---|---|---|
| [`src/`](src) | Minecraft **26.3** | Fabric | **1.4.0** |
| [`forge189/`](forge189) | Minecraft **1.8.9** | Forge 11.15.1.2318 | **1.0.0** |

两版都分「自带模型」和「不带模型」两种 jar，**功能完全一样**，区别只在模型从哪来。

---

## 版本要求

### Fabric 版（Minecraft 26.3）

| 项 | 要求 |
|---|---|
| Minecraft | **26.3**（26.2 及更早不能用这一版） |
| Fabric Loader | ≥ 0.19.3 |
| Fabric API | ≥ 0.161.0 |
| Java | 25 |
| ModMenu | 可选，装了才会在模组列表里多一个「设置」按钮（对应用 21.0.0） |

### Forge 1.8.9 版

| 项 | 要求 |
|---|---|
| Minecraft | **1.8.9** |
| Forge | 11.15.1.2318（其他 1.8.9 的 Forge 一般也行） |
| Java | 8 |
| 额外 | 客户端设置里打开「强制使用 Unicode 字体」，否则中文显示成方块 |

---

## 两个 jar 的区别

**同一个版本里两个 jar 的 modid 相同，只能放一个进 `mods/`，同时放会因 modid 重复启动失败。**

### Fabric 26.3（[Releases v1.4.0](https://github.com/bgzt/mc-tongyongfanyi-mod/releases/tag/v1.4.0)）

| 文件 | 大小 | 什么时候用 |
|---|---|---|
| `tongyongfanyi-1.4.0.jar` | 约 **380 MB** | **自带 Qwen2.5 0.5B**。首次进游戏自动把它铺到 Ollama 的 models 目录；换台电脑只需拷这一个文件 |
| `tongyongfanyi-nomodel-1.4.0.jar` | 约 **79 KB** | **不含模型**。本机已经装了 `qwen2.5:0.5b`（比如用的是便携包的 models 目录）时用它，省 380MB |

### Forge 1.8.9（[Releases forge189-v1.0.0](https://github.com/bgzt/mc-tongyongfanyi-mod/releases/tag/forge189-v1.0.0)）

| 文件 | 大小 | 什么时候用 |
|---|---|---|
| `tongyongfanyi-forge189-1.0.0-with-model.jar` | 约 **368 MB** | 自带模型，首次进游戏自动安装 |
| `tongyongfanyi-forge189-1.0.0.jar` | 约 **38 KB** | 不带模型，模型自己准备（便携包的 `models/` 里就有） |

> 自带模型的 jar 内部是 STORED（不压缩）的，解包就是一次纯字节拷贝，通常一两秒；中途打断也没关系，下次进游戏会补齐。
> 不带模型的 jar **不会联网下载**模型，必须自己准备好。

---

## 安装

1. **准备模型服务**：装 [Ollama](https://ollama.com)，或直接用便携包里的 `ollama/`（免安装）。默认地址 `http://127.0.0.1:11434`。
2. **放 jar**：把选中的**一个** jar 丢进 `mods/`
   - 官方启动器：`.minecraft/mods/`
   - PCL 等版本隔离启动器：`.minecraft/versions/<版本>/mods/`
3. **启动游戏**，输入 `/tyfy` 打开设置界面（装了 ModMenu 也可以从模组列表点「设置」）。也可以：
   - `/tyfy toggle` 开关翻译
   - `/tyfy status` 查看当前模型 / 服务状态 / 缓存条数
   - `/tyfy reload` 重新读配置
4. **选模型**：设置 → 本地模型，默认 `qwen2.5:0.5b`（最快、约 0.5GB 显存）。想翻译质量更好可以切 `qwen2.5:1.5b` 或 `HY-MT-1.8B`（腾讯混元翻译专用，英译中效果更好）。
5. 用「自带模型」的 jar 时，模型安装不需要任何操作——进游戏后设置页那一行会从「安装内置模型…」变成「已内置」，失败会变成「点此重试」。

## 配置文件

`config/tongyongfanyi.json`（PCL：`.minecraft/versions/<版本>/config/`）。常用字段：

| 字段 | 默认 | 说明 |
|---|---|---|
| `enabled` | `true` | 总开关 |
| `targetLanguage` | `zh-CN` | 目标语言 |
| `translateGameMessages` / `translateChatMessages` | `true` | 系统消息 / 玩家聊天 |
| `translateItemTooltips` | `true` | 物品悬停提示 |
| `translateScreenText` | `true` | 模组界面绘制的文字 |
| `minLength` | `3` | 太短的句子不翻（减少请求） |
| `cacheSize` | `2048` | 内存缓存条数 |
| `timeoutMs` | `10000` | 单次请求超时；CPU 跑本地模型建议放宽到 20000~30000 |
| `translationThreads` | `4` | 翻译线程数（1~8） |
| `ollamaPath` | 空 | `ollama.exe` 完整路径（留空自动探测） |
| `ollamaModelsPath` | 空 | 模型目录，留空回退到用户目录下的 `.ollama/models` |
| `localModel.url` | 空 | 服务地址，留空用 `http://127.0.0.1:11434` |
| `localModel.model` | `qwen2.5:0.5b` | 使用的模型 |

## 从源码构建

Fabric 版需要 **JDK 25**，1.8.9 版需要 **JDK 8**。

```bash
# Fabric 26.3：自带模型（约 380MB）
./gradlew build

# Fabric 26.3：不带模型（约 79KB）
./gradlew build -PbundleModel=false

# 1.8.9 Forge：不带模型
cd forge189 && ./gradlew build

# 1.8.9 Forge：自带模型
cd forge189 && ./gradlew build -PwithModel=true
```

模型源文件从 `本地模型包/models` 读取（Ollama 的 manifest + 5 个 blob），工程里不重复存放这 380MB。
`forge189` 用的是 architectury-loom，如果卡在下载 Forge 的 `-universal.jar`，见该目录 `build.gradle.kts` 里的本地 maven 说明。

## 目录结构

```
src/                 Fabric 版（Minecraft 26.3）
forge189/            1.8.9 Forge 版
shared/              两版共用的源码（内置模型安装器，必须保持 Java 8 语法）
本地模型包/           便携包：Ollama 运行时 + 模型 + 打包好的 jar（模型和 jar 不入库）
```

`.gitignore` 排除了 `build/`、模型、便携包和所有 jar；二进制只通过 GitHub Releases 分发。

## 常见问题

**中文显示成方块**：1.8.9 版请在游戏设置 → 语言里打开「强制使用 Unicode 字体」。

**翻译没反应**：先在设置页看一眼「服务：运行中 / 未运行」。未运行就点「启动本地模型服务」，或手动启动 Ollama；再看「模型」那一行是不是「模型未安装」。

**一直超时**：CPU 跑本地模型会慢，把设置 → 通用 → 请求超时调到 20~30 秒。

**11434 端口被占用**：本机已经装过别的 Ollama 时会出现，先停掉那个再启动。

**两个 jar 同时放了**：删掉一个。装 ModMenu 只是多一个设置入口，不影响功能。

## License

[MIT](LICENSE)
