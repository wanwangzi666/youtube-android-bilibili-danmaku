# B2Y for Vector

**在 YouTube 安卓客户端里，看哔哩哔哩弹幕。**

把 [ahaduoduoduo/bilibili-youtube-danmaku](https://github.com/ahaduoduoduo/bilibili-youtube-danmaku)
（B2Y 浏览器扩展）改造成 **Vector / LSPosed 的 Xposed 模块**。打开 YouTube 视频后，模块自动在
B 站找到同一个视频、把弹幕拉下来、叠在原生播放器上。全程不用切 App，也不用退出登录。

> 目标框架：[JingMatrix/Vector](https://github.com/JingMatrix/Vector)（Modern Xposed Framework，
> 兼容 legacy Xposed API），也兼容 LSPosed。

| 项目 | 值 |
| --- | --- |
| 最新版本 | **1.1.3** —— [下载](https://github.com/wanwangzi666/youtube-android-bilibili-danmaku/releases/latest) |
| 需要 | 已 root 的 Android 8.1 ~ 17 + Vector / LSPosed |
| 作用域 | `com.google.android.youtube` |
| 技术栈 | Kotlin · 零第三方运行时依赖 · APK 约 700 KB |
| 代码量 | 主源码 25 个文件 / 5,640 行，JVM 单测 8 个文件 / **134 个用例全绿** |
| 许可 | MIT（派生自上游 MIT 项目） |

---

## 目录

- [它解决什么问题](#它解决什么问题)
- [功能](#功能)
- [安装](#安装)
- [使用](#使用)
- [Shorts（竖屏短视频）怎么处理](#shorts竖屏短视频怎么处理)
- [匹配不准怎么办](#匹配不准怎么办)
- [编译](#编译)
- [它是怎么做到的](#它是怎么做到的)
- [代码结构](#代码结构)
- [排查](#排查)
- [仓库内容](#仓库内容)
- [名词表](#名词表)
- [声明与许可](#声明与许可)

---

## 它解决什么问题

关注的 UP 主在 YouTube 和 B 站都投稿。YouTube 画质更好、能看 4K，但没有弹幕；
B 站有弹幕，但画质和码率往往差一截。

于是就有两种看片方式二选一：要么忍画质，要么忍没有弹幕。

这个模块把两边拼起来：**用 YouTube 看画面，用 B 站看弹幕**。它做的事就是自动找到两边
对应的同一个视频，然后把弹幕铺在 YouTube 的播放器上。

难点不在拉弹幕（B 站有公开接口），而在于**在别人的 App 里**：

- 拿不到当前视频的 ID —— 网页版能读 URL 里的 `?v=`，安卓端没有 URL
- 拿不到播放进度 —— 没有 `<video>` 元素可以问 `currentTime`
- 拿不到视频画面区域 —— 不知道该把弹幕铺在屏幕的哪块矩形上
- YouTube 客户端做了 R8 混淆，55544 个类里绝大部分类名方法名都被改过

模块用「hook 框架类 + 自研 DEX 指纹」这两层来解决，详见[它是怎么做到的](#它是怎么做到的)。

## 功能

| 能力 | 说明 |
| --- | --- |
| **自动识别** | 用 DEX 指纹拿到当前 YouTube 视频 ID，再用 oEmbed 取**原始标题**（避开客户端翻译过的标题，匹配率明显更高） |
| **自动匹配** | WBI 签名调 B 站综合搜索，按「关键词匹配度 / 标题包含度 / 搜索高亮比例」三重打分，超过阈值自动加载 |
| **弹幕加载** | `/x/v2/dm/wbi/web/seg.so` 每 6 分钟一段拉取 protobuf 弹幕，解析、排序、合并 |
| **弹幕渲染** | 自绘 `Canvas`：滚动 / 顶部 / 底部固定、轨道防重叠、权重过滤、透明度、字号、速度、轨道间距、显示区域 |
| **时间轴同步** | 播放位置取自 YouTube 内部毫秒级时间指纹，回退到 Android `MediaSession` 外推；**暂停 / 倍速 / 拖进度条天然同步** |
| **时间轴偏移** | ±0.5s 快捷微调（B 站与 YouTube 的正文起点常差几秒甚至几分钟），也可设长期偏移 |
| **番剧** | `《标题》第N话：` 形式的标题自动走 PGC 接口取该话弹幕 |
| **Shorts 处理** | 自动识别竖屏短视频流，默认识别到就不匹配、不显示，也可开关成旧行为 |
| **手动兜底** | 播放页悬浮「弹」按钮：重新搜索、搜关键词、粘贴 B 站链接、番剧模式、实时调参、复制诊断信息 |

## 安装

### 1. 前置条件

- 已 root 的 Android 设备（Android 8.1 ~ 17）
- 已装 **Magisk / KernelSU + Zygisk**，并装好 **Vector**（或 LSPosed）
- YouTube 官方客户端

### 2. 装包

从 [Releases](https://github.com/wanwangzi666/youtube-android-bilibili-danmaku/releases/latest)
下载 `B2Y-Vector-<版本>.apk`：

```bash
adb install -r B2Y-Vector-1.1.3.apk
```

### 3. 启用模块

在 **Vector / LSPosed 管理器** 里：

1. 启用 **B2Y 弹幕** 模块
2. 作用域勾选 **YouTube**（模块已在 `AndroidManifest.xml` 声明 `xposedscope`，通常会默认勾上）
3. **强制停止** YouTube，再重新打开（切后台不算）

### 4. 打开模块 App 存一次设置（推荐）

桌面图标 **B2Y 弹幕** → 点一次 **保存设置**。

模块有一套完整默认值，不保存也能用；存一次的意义是把配置文件落到磁盘上，
之后改设置、写入 Cookie 才有地方可写。

<a id="signing"></a>

### ⚠️ 关于签名：两种包不能互相覆盖

模块存在**两种签名**，装了其中一种就没法直接装另一种（报
`INSTALL_FAILED_UPDATE_INCOMPATIBLE`），必须先卸载：

| 包 | 签名 | 证书 SHA-256 |
| --- | --- | --- |
| GitHub Release 的 `B2Y-Vector-*.apk`、本地 `assembleRelease` | 固定的项目发布密钥 | `ca566fdb5ec9af60241c43232cdfbb85167c7e0f25952922558b31471013a85e` |
| 本地 `assembleDebug`（`app-debug.apk`） | 你机器的 `~/.android/debug.keystore` | 每台机器都不同 |

查任意一个包用的是哪种签名：

```bash
apksigner verify --print-certs <apk>
```

**建议统一用 Release 包**：发布密钥是固定的，以后每个版本都能直接覆盖升级。
如果之前装的是本地 `assembleDebug` 出的包又不想丢设置，就用同一台机器重新出包覆盖
（签名一致）—— 但这条路径的包和 Release 包不能混用，换来源就得卸载一次。

## 使用

1. 打开任意 YouTube 视频 → 自动识别 → 自动匹配 → 自动加载弹幕
2. 右上角悬浮 **「弹」** 按钮（可拖动）打开控制面板：

   | 分组 | 内容 |
   | --- | --- |
   | **Shorts（竖屏短视频）** | 「在 Shorts 里也匹配弹幕」全局开关，**立即生效** |
   | **识别与加载** | 重新搜索 / 搜索关键词 / 粘贴 B 站链接 / 番剧模式 |
   | **时间轴** | ±0.5s 微调、归零、毫秒级滑杆 |
   | **显示** | 不透明度 / 字号 / 速度 / 显示区域 / 权重过滤 / 弹幕开关 / 清空 |
   | **诊断** | 当前 `videoId`、标题、匹配到的 `bvid`、弹幕条数、播放位置、Shorts 判定依据、渲染帧率，可一键复制 |

3. 需要长期保存的参数（Cookie、匹配阈值、自动加载、多结果策略…）在模块 App 里设置
   （改完点「保存设置」，再切换一次视频生效）

## Shorts（竖屏短视频）怎么处理

刷 Shorts 时，模块以前会拿短视频的标题去 B 站搜，匹配度只有 0~25%，然后弹出
「选择要同步的 B 站视频」——纯属打扰。现在：

- **默认就不匹配**：识别到 Shorts 时不搜索、不显示弹幕，悬浮「弹」按钮也一起隐藏
- **想恢复旧行为**：打开「在 Shorts 里也匹配弹幕」开关（模块 App 或播放页面板里都有）

**怎么判断"当前是 Shorts"**（三层，任一命中即可）：

| 层 | 依据 | 说明 |
| --- | --- | --- |
| 1 | 底部导航的 **Shorts 标签处于选中态** + 画面是竖屏 | 只用框架 API 遍历视图树找标签（`findViewsWithText` + `selected`），不 hook YouTube 内部类；要求标签在屏幕下半部分，避免把标题里的 `#shorts` 当成导航 |
| 2 | **Shorts 播放器视图在线** | `com.google.android.libraries.youtube.reel.internal.*` 的类名被 R8 保留，按类名在视图树里找实例 |
| 3 | **竖屏铺满画面** | 宽/高 < 0.9 且高度 ≥ 屏幕高 55%，纯几何判定，完全不依赖 YouTube 实现 |

判定结果带 3 秒保鲜期，避免视图瞬时回收导致状态抖动。

**开关走哪条路**：模块 App 保存的设置要通过 `XSharedPreferences` 跨进程传给被注入的
YouTube 进程，实测在部分框架/系统组合下读不到最新值。所以这个开关额外有一条
**不依赖跨进程机制**的通道：播放页面板里的开关直接写被注入进程自己的配置文件，
**点一下立刻生效**，不用重启 YouTube。

> 无论开关怎么设，面板里的「粘贴 B 站链接 / 搜索关键词 / 番剧模式」始终可用 ——
> 那是明确的手动意图，不会被拦截。

## 匹配不准怎么办

YouTube 与 B 站的标题经常不一样（翻译、副标题、UP 主加的 tag、正片/分P 差异）。模块按上游
逻辑做了标题清洗（去 `【UP主名】`、去尾部 `#tag`、按 `｜`/空格切分取最佳片段），并优先用
oEmbed 拿原始标题。仍然匹配不上时：

1. 调低模块设置里的 **「标题匹配阈值」**（默认 90%）
2. 把 **「多结果处理」** 改成「弹窗让我选择」，自己挑
3. 直接在播放页 **粘贴对应的 B 站链接**；常用的话可以设 **「强制指定 B 站视频」** 永久锁定

## 编译

标准 Android Gradle 项目，Kotlin，**零第三方运行时依赖**（只有 Kotlin stdlib；
Xposed API 是 `compileOnly`，运行时由框架提供）。

```bash
# 需要 JDK 17 + Android SDK（compileSdk 34 / build-tools 34.0.0）
export JAVA_HOME=/path/to/jdk17
export ANDROID_HOME=/path/to/android-sdk

./gradlew :app:testDebugUnitTest    # 134 个 JVM 单元测试
./gradlew :app:assembleDebug        # → app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:assembleRelease      # → app/build/outputs/apk/release/app-release.apk
```

Windows PowerShell：

```powershell
$env:JAVA_HOME='C:\path\to\jdk17'; $env:ANDROID_HOME='C:\path\to\android-sdk'
.\gradlew.bat :app:testDebugUnitTest
```

`assembleRelease` 默认产出**未签名**包。要用固定发布密钥签名（这样能和 Release 包互相覆盖），
设置这几个环境变量后再构建：

```bash
export B2Y_KEYSTORE_PATH=/path/to/b2y-release.keystore
export B2Y_KEYSTORE_PASSWORD=...
export B2Y_KEY_ALIAS=b2y
export B2Y_KEY_PASSWORD=...
```

> 实测环境：JDK 17 + Gradle 8.7 + AGP 8.5.2 + Kotlin 1.9.24，`testDebugUnitTest` /
> `assembleDebug` / `assembleRelease` 全部通过、零编译警告。

## 它是怎么做到的

### 三层 hook 策略

YouTube 客户端重度混淆，所以模块优先 hook **不受混淆影响的框架类**，只在必要时才去啃 YouTube 内部：

```
第 1 层（最稳）：框架类
    Activity 生命周期        → 挂载 / 卸载弹幕浮层
    SurfaceView / TextureView → 定位视频画面矩形
    MediaSession             → 播放位置 / 倍速 / 播放暂停 / 元数据

第 2 层（提精度）：DEX 指纹
    "Null initialPlayabilityStatus"          → 视频 ID
    "Media progress reported outside media…" → 毫秒级播放时间

第 3 层（保底）：手动
    悬浮面板里粘贴 B 站链接 / 搜索关键词 / 强制指定 bvid
```

第 1 层保证「即使指纹全部失效，模块依然能用」；第 2 层把同步精度和视频识别自动化；
第 3 层保证任何情况下用户都能自己救回来。

DEX 指纹用**自研的极简 DEX 解析器**（约 400 行，零第三方依赖）：解析 header / 字符串表 /
类型表 / 方法表 / 类定义，遍历 `code_item` 指令流定位 `const-string` 与 `invoke-*`，
从稳定的字符串常量反查到被混淆的类与方法。

### 为什么位置是「播放时间的纯函数」

浏览器版依赖 Web Animations 让浏览器自己补间；安卓版把「弹幕位置」写成播放时间的纯函数：

```
progress = clamp((nowMs - item.timeMs) / (BASE_DURATION / speed), 0, 1)
x        = stageWidth - progress * (stageWidth + textWidth)
```

好处是暂停 / 倍速 / 拖动进度条**天然同步**，不需要像 Web Animations 那样去修正
`animation.currentTime`；代价是每帧重算活动弹幕位置，而活动弹幕数量很少，成本可忽略。

### 播放时钟

`PlaybackClock` 是「锚点 + 外推」模型：

```
submit(positionMs, speed, playing, elapsedRealtimeMs)
positionMs() = playing ? anchorPos + (now - anchorTime) * speed : anchorPos
```

它同时承担事件检测：外推位置与新采样偏差 > 1.2s 判定为**跳转**，以及暂停/恢复、倍速变化。
两个数据源（MediaSession 与内部时间指纹）同时可用时以指纹为准，指纹失效自动退回媒体会话。

设计细节见 [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md)。

## 代码结构

```
app/src/main/java/com/b2y/danmaku/
├── B2YModule.kt                  模块入口（assets/xposed_init 指向它）
├── core/
│   ├── Log.kt                    统一日志（XposedBridge.log）
│   ├── DanmakuSettings.kt        设置模型 + 跨进程读写（含 Shorts 开关的运行时通道）
│   ├── SettingsCodec.kt          设置的 JSON 编解码 + 生效值优先级规则
│   ├── PlaybackClock.kt          播放时钟（锚点 + 外推 + 跳转/暂停/倍速事件）
│   └── VideoSessionController.kt 会话编排：视频 → 标题 → 搜索 → 弹幕 → 视图
├── hook/
│   ├── HookInstaller.kt          统一安装入口
│   ├── ActivityWatcher.kt        Application/Activity 生命周期 → 挂载浮层
│   ├── MediaSessionWatcher.kt    MediaSession → 播放位置/倍速/元数据
│   ├── VideoSurfaceTracker.kt    收集 SurfaceView/TextureView → 视频画面矩形
│   ├── ShortsDetector.kt         Shorts 三层判定（导航标签 / 播放器视图 / 竖屏几何）
│   └── fingerprint/
│       ├── DexFile.kt            自研极简 DEX 解析器（类/方法/常量池/指令）
│       └── PlayerFingerprintHook.kt  用字符串常量指纹定位被混淆的视频 ID / 播放时间方法
├── bili/                         B 站 API（纯 JVM 可测）
│   ├── Wbi.kt                    WBI 签名（mixin key + MD5）
│   ├── DanmakuProto.kt           弹幕 protobuf 解析（含 zlib/gzip 兜底）
│   ├── TitleMatcher.kt           标题清洗与匹配度算法
│   ├── BiliApi.kt                nav / view / seg.so / search all v2 / oEmbed
│   ├── Bangumi.kt                PGC 番剧
│   └── BiliModels.kt             数据模型
├── danmaku/
│   ├── DanmakuItem.kt            弹幕数据
│   ├── DanmakuEngine.kt          轨道/发射/回收/seek 逻辑（纯 JVM 可测）
│   └── DanmakuView.kt            Canvas 渲染 + 跟随播放时钟的刷新循环
└── ui/
    ├── DanmakuOverlay.kt         挂在 Activity 上的透明浮层
    ├── ControlPanel.kt           播放页控制面板
    └── module/SettingsActivity.kt 模块设置界面
```

纯 JVM 的部分（`bili/`、`danmaku/`、`core/PlaybackClock`、`hook/fingerprint/DexFile`）都能在
桌面 JVM 上跑单测，其中 `DexFileTest` 会拿真实 YouTube APK 的 DEX 验证指纹链路——
目录不存在时自动跳过，不影响 CI。

其它文档：

- [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) —— 架构与设计说明（含客户端混淆分析结论）
- [`docs/PORTING-NOTES.md`](docs/PORTING-NOTES.md) —— 与上游扩展的逐功能移植对照表
- [`CHANGELOG.md`](CHANGELOG.md) —— 版本更新日志
- [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md) —— 第三方来源与许可清单

## 排查

日志过滤关键字：**`B2Y`**。

| 现象 | 处理 |
| --- | --- |
| 完全没有弹幕、也没有悬浮按钮 | 确认模块已启用、作用域含 YouTube，**强制停止**后重开；看日志里有没有 `[B2Y]` 输出 |
| 有悬浮「弹」但一直「等待识别」 | 打开面板看状态；若 DEX 指纹失效（YouTube 大版本更新），先用「粘贴 B 站链接」手动加载 |
| 弹幕位置整体偏移 | 面板里 `±0.5s` 微调；固定偏移可在模块 App 里设毫秒级时间轴偏移 |
| 弹幕位置不跟手 | `MediaSession` 位置更新不足。看日志有没有「播放时间指纹 hook 安装成功」 |
| 匹配到错误的视频 | 调低阈值 + 改成「弹窗让我选择」，或用「强制指定 B 站视频」锁定 bvid |
| 刷 Shorts 时弹出选择框 | 面板顶部取消「在 Shorts 里也匹配弹幕」。若已取消仍弹，看诊断区 `Shorts：` 那行 |
| 关掉 Shorts 匹配后，普通视频也被跳过 | Shorts 判定误判。把诊断区 `Shorts：` 那行原文贴到 Issue |
| 某些 Shorts 没被识别 | 三层都没命中。同样把诊断区 `Shorts：` 那行贴出来（含画面与屏幕尺寸） |
| 装包报 `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | 两个包的签名不同。见[关于签名](#signing)：`adb uninstall com.b2y.danmaku` 后重装（会清掉已存设置），或改用同一来源出的包 |

反馈问题时最有用的三样东西：诊断区那段文字、日志里 `B2Y` 相关的行、YouTube 客户端版本号。

## 仓库内容

本仓库**只包含 Vector / LSPosed 模块本身**（把上游算法重写为 Kotlin 之后的新代码）：

```
app/src/main/java/com/b2y/danmaku/   # 模块源码
app/src/test/java/com/b2y/danmaku/   # JVM 单元测试（134 个）
docs/ARCHITECTURE.md                 # 架构与设计说明
docs/PORTING-NOTES.md                # 与上游扩展的逐功能移植对照表
CHANGELOG.md                         # 版本更新日志
THIRD_PARTY_NOTICES.md               # 第三方来源与许可清单
```

**不包含**（有意为之）：上游浏览器扩展的源码（独立项目，请前往
[上游仓库](https://github.com/ahaduoduoduo/bilibili-youtube-danmaku)）、
编译产物 APK、本地分析用的中间文件。

### 从上游移植了什么

逐函数重写、语义对齐：

- **B 站接口层** —— WBI 签名（`mixinKeyEncTab` 重排 + MD5）、`nav` / `view` / `seg.so` 调用、
  6 分钟分段下载、手写 protobuf 解析、csrf 关键词拦截重试
- **标题匹配** —— 标题清洗、三重打分、多结果策略（选弹幕最多 / 弹窗选择）
- **弹幕引擎** —— 轨道几何与数量、`findAvailableTrack` 防重叠、顶部/底部固定弹幕、权重过滤、
  seek 语义（复位发射标记，不重播已过去的弹幕）
- **番剧支持** —— `《标题》第N话：` 解析 + PGC 接口
- **设置项语义** —— 透明度 / 字号 / 速度 / 轨道间距 / 显示区域 / 时间轴偏移 / 匹配阈值

**本项目新增**（上游没有、也做不到的）：定位播放进度/视频 ID/画面区域的 hook 层、
自研零依赖 DEX 指纹解析器、基于播放时间的 `Canvas` 渲染、播放页控制面板与设置界面、
Shorts 判定。

**没有移植**：繁简转换（OpenCC，词库 1 MB，体积不划算）、频道关联 UP 主（依赖 DOM 抓取）、
广告片段剔除（SponsorBlock）、弹幕搜索跳转、夸克网盘支持。

> YouTube 客户端的 DEX 指纹思路参考了 [NexAlloy/NexAlloy](https://github.com/NexAlloy/NexAlloy)
> 公开的指纹常量（仅参考字符串常量与思路，未复制代码），并用自研解析器在真实 APK 上独立验证。

## 名词表

| 名词 | 含义 |
| --- | --- |
| **B2Y** | 项目名，Bilibili → YouTube |
| **Vector / LSPosed** | Android 上的 Xposed 框架实现，模块靠它注入目标 App |
| **作用域 (scope)** | 模块对哪些 App 生效；本模块只需要 `com.google.android.youtube` |
| **DEX 指纹** | 用 APK 里稳定的字符串常量反查被混淆的类与方法，从而 hook 到它们 |
| **WBI 签名** | B 站 Web 接口的请求签名（`mixinKeyEncTab` 重排 + MD5 得到 `w_rid`） |
| **弹幕轨道** | 弹幕在垂直方向上的"车道"；同一轨道内前后两条弹幕不能重叠 |
| **seek 语义** | 拖动进度条后如何重排弹幕：清空在场弹幕、复位发射标记，但不重播已过去的 |
| **Shorts** | YouTube 的竖屏短视频流 |

## 声明与许可

- 本项目的**算法与产品行为来自上游扩展**，版权归原作者 **啊铎铎铎仔** 所有（MIT）；
  本项目作为派生作品同样以 **MIT** 发布，`LICENSE` 中保留两份版权声明
- 本项目仅用于技术学习与研究；弹幕版权归哔哩哔哩及原发布者所有，请勿用于商业用途
- 请遵守哔哩哔哩与 YouTube 的服务条款；模块不存储、不转发任何用户数据，
  所有请求都在用户设备的 YouTube 进程内直接发出
