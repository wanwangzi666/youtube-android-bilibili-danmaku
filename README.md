# B2Y for Vector —— 在 YouTube Android 客户端里看 B 站弹幕

把 [ahaduoduoduo/bilibili-youtube-danmaku](https://github.com/ahaduoduoduo/bilibili-youtube-danmaku)（浏览器扩展）
改造成 **Vector / LSPosed 的 Xposed 模块**，让 **YouTube 安卓客户端**在播放视频时自动叠加哔哩哔哩弹幕。

> 目标框架：[JingMatrix/Vector](https://github.com/JingMatrix/Vector)（Modern Xposed Framework，兼容 legacy Xposed API）

---

## 项目来源与致谢

**本项目是基于 [ahaduoduoduo/bilibili-youtube-danmaku](https://github.com/ahaduoduoduo/bilibili-youtube-danmaku)（B2Y 浏览器扩展，作者：啊铎铎铎仔，MIT 许可）改造而来的派生项目。**

上游是一个 WXT 框架的浏览器扩展，把 B 站弹幕同步显示在 **YouTube 网页版**。本项目保留了它的全部核心
算法与产品行为，把数据链路与渲染层**重写为 Kotlin / Android**，并新增了「在 YouTube 原生 App 内
hook 播放器」这一层，使其能运行在 Vector / LSPosed 上。

**从上游移植过来的部分**（逐函数重写，语义对齐）：

- **B 站接口层**：WBI 请求签名（`mixinKeyEncTab` 重排 + MD5）、`nav` / `view` / `seg.so` 调用、
  按 6 分钟分段下载弹幕、手写 protobuf 解析 `DmSegMobileReply`、csrf 关键词拦截重试
- **标题匹配**：标题清洗（去 `【UP主名】`、去尾部 `#tag`、按 `｜`/空格切分取最佳片段）、
  三重打分（搜索高亮比例 / 关键词 LCS 占比 / 包含度占比）、多结果策略（选弹幕最多 或 弹窗选择）
- **弹幕引擎**：轨道高度与数量计算、`findAvailableTrack` 防重叠、顶部/底部固定弹幕、
  权重过滤，以及 `resyncDanmakus` 的 seek 语义（复位发射标记，不重播已过去的弹幕）
- **番剧支持**：`《标题》第N话：` 解析 + PGC 接口取该话弹幕
- **设置项语义**：透明度 / 字号 / 速度 / 轨道间距 / 显示区域 / 时间轴偏移 / 匹配阈值

**本项目新增的部分**（上游没有、也做不到的）：

- 在 YouTube Android 客户端里定位**播放进度、视频 ID、视频画面区域**的 hook 层
  （框架类 hook + 自研零依赖 DEX 指纹解析器）
- 基于播放时间的弹幕渲染（`Canvas` 自绘），暂停 / 倍速 / 拖动进度条天然同步
- 播放页内控制面板与模块设置界面

**没有移植**：繁简转换（OpenCC，体积过大）、频道关联 UP 主、广告片段剔除、弹幕搜索跳转、夸克网盘支持。
逐项对照见 [`docs/PORTING-NOTES.md`](docs/PORTING-NOTES.md)，完整的第三方来源与许可清单见
[`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md)。

> YouTube 客户端的 DEX 指纹思路参考了 [NexAlloy/NexAlloy](https://github.com/NexAlloy/NexAlloy)
> 公开的指纹常量（仅参考字符串常量与思路，未复制代码），并用自研解析器在真实 APK 上独立验证通过。

---

## 一、它做了什么

| 能力 | 说明 |
| --- | --- |
| 自动识别视频 | 通过 DEX 指纹拿到 YouTube 当前视频 ID，再用 oEmbed 取**原始标题**（避开客户端翻译标题） |
| 自动匹配 B 站视频 | WBI 签名调用 B 站综合搜索，按「关键词匹配度 / 包含度 / 搜索高亮比例」打分，超过阈值自动加载 |
| 加载弹幕 | `/x/v2/dm/wbi/web/seg.so` 分段（每 6 分钟）拉取 protobuf 弹幕，解析、排序、合并 |
| 弹幕渲染 | 自绘 `View` + `Canvas`，轨道排布、滚动/顶部/底部、权重过滤、透明度/字号/速度/显示区域 |
| 时间轴同步 | 播放位置来自 YouTube 内部时间指纹（毫秒级），回退方案是 Android `MediaSession` 外推；暂停/倍速/拖动天然同步 |
| 手动干预 | 播放页悬浮「弹」按钮：重新搜索、搜索关键词、粘贴 B 站链接、番剧模式、时间轴微调、实时调参 |
| 番剧支持 | `《标题》第N话：` 形式的标题自动走 PGC 接口取该话弹幕 |

---

## 二、安装

### 1. 前置条件

- 已 root 的 Android 设备（Android 8.1 ~ 17）
- 已安装 **Magisk / KernelSU + Zygisk**，并装好 **Vector**（或 LSPosed）
- YouTube 客户端（`com.google.android.youtube`）

### 2. 安装模块

```bash
# 编译（见第四节）得到 app/build/outputs/apk/debug/app-debug.apk
adb install -r app-debug.apk
```

然后在 **Vector / LSPosed 管理器** 中：

1. 启用 **B2Y 弹幕** 模块
2. 勾选作用域 **YouTube**（模块已在 `AndroidManifest.xml` 中声明 `xposedscope`）
3. **强制停止** YouTube，再重新打开（不要只是切后台）

### 3. 打开模块 App 保存一次设置

首次使用必须打开模块 App（桌面图标「B2Y 弹幕」）点一次 **保存设置**：
被注入的 YouTube 进程通过 `XSharedPreferences` 读取设置文件，文件不存在时会退回默认值。

---

## 三、使用

1. 打开任意 YouTube 视频 → 自动识别标题 → 自动匹配 → 自动加载弹幕
2. 右下角悬浮 **「弹」** 按钮（可拖动）打开控制面板：
   - **重新搜索 / 搜索关键词 / 粘贴 B 站链接 / 番剧模式**
   - **时间轴 ±0.5s** 微调（B 站与 YouTube 的正片起始点常常差几秒，甚至几分钟）
   - **不透明度 / 字号 / 速度 / 显示区域 / 权重过滤 / 开关**
   - 面板里显示当前识别到的 `videoId`、标题、匹配到的 `bvid`、已加载弹幕条数、播放位置
3. 需要长期保存的参数（Cookie、匹配阈值、自动加载、多结果策略…）在模块 App 里设置

### 关于匹配不准

YouTube 与 B 站的标题经常不同（翻译、副标题、UP 主加的 tag）。模块按扩展的逻辑做了标题清洗
（去 `【UP主名】`、去尾部 `#tag`、按 `｜`/空格切分取最佳片段），并用 oEmbed 拿原始标题。
仍匹配不上时：

- 调低模块设置里的「标题匹配阈值」（默认 90%）
- 把「多结果处理」改成「弹窗让我选择」
- 或者直接在播放页粘贴对应的 B 站链接（可用「强制指定 B 站视频」永久锁定）

---

## 四、编译

工程是标准 Android Gradle 项目（Kotlin，零第三方运行时依赖）。

```bash
# 需要 JDK 17 + Android SDK (compileSdk 34, build-tools 34.0.0)
export JAVA_HOME=/path/to/jdk17
export ANDROID_HOME=/path/to/android-sdk

./gradlew :app:assembleDebug        # 产物 app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest    # 96+ 个 JVM 单元测试
```

Windows PowerShell 下（本仓库开发时使用的环境）：

```powershell
$env:JAVA_HOME='C:\path\to\jdk17'; $env:ANDROID_HOME='C:\path\to\android-sdk'
.\gradlew.bat :app:assembleDebug
```

> 已经实测：`assembleDebug` 与 `testDebugUnitTest` 在 JDK 17 + Gradle 8.7 + AGP 8.5.2 + Kotlin 1.9.24 下通过。

---

## 五、代码结构

```
app/src/main/java/com/b2y/danmaku/
├── B2YModule.kt                  模块入口（assets/xposed_init 指向它）
├── core/
│   ├── Log.kt                    统一日志（XposedBridge.log）
│   ├── DanmakuSettings.kt        设置模型 + XSharedPreferences 读取
│   ├── SettingsCodec.kt          设置的 JSON 编解码
│   ├── PlaybackClock.kt          播放时钟（锚点 + 外推 + 跳转/暂停/倍速事件）
│   └── VideoSessionController.kt 会话编排：视频 → 标题 → 搜索 → 弹幕 → 视图
├── hook/
│   ├── HookInstaller.kt          统一安装入口
│   ├── ActivityWatcher.kt        Application/Activity 生命周期 → 挂载浮层
│   ├── MediaSessionWatcher.kt    MediaSession → 播放位置/倍速/元数据
│   ├── VideoSurfaceTracker.kt    收集 SurfaceView/TextureView → 视频画面矩形
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

详细设计见 [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md)，
与原扩展的功能对照见 [`docs/PORTING-NOTES.md`](docs/PORTING-NOTES.md)。

---

## 六、排查

| 现象 | 处理 |
| --- | --- |
| 完全没有弹幕、无悬浮按钮 | 确认模块已启用、作用域包含 YouTube、**强制停止**后重开；检查 Vector 日志里是否有 `[B2Y]` 前缀的输出 |
| 有悬浮按钮但一直「等待识别」 | 打开控制面板看状态；若 DEX 指纹失效（YouTube 大版本更新），可先用「粘贴 B 站链接」手动加载 |
| 弹幕位置整体偏移 | 用面板的时间轴 ±0.5s 微调，或设为长期偏移 |
| 弹幕位置不跟手 | 说明 `MediaSession` 位置更新不足，查看日志是否有「播放时间指纹 hook 安装成功」 |
| 匹配到错误的视频 | 调低阈值 + 改成「弹窗让我选择」，或用「强制指定 B 站视频」锁定 bvid |

日志过滤关键字：`B2Y`。

---

## 七、仓库内容

本仓库**只包含 Vector / LSPosed 模块本身**（也就是把上游算法重写为 Kotlin 之后的新代码）：

```
app/src/main/java/com/b2y/danmaku/   # 模块源码
app/src/test/java/com/b2y/danmaku/   # JVM 单元测试（106 个）
docs/ARCHITECTURE.md                 # 架构与设计说明
docs/PORTING-NOTES.md                # 与上游扩展的逐功能移植对照表
THIRD_PARTY_NOTICES.md               # 第三方来源与许可清单
```

**不包含**（有意为之）：

- 上游浏览器扩展的源码 —— 它是独立项目，请直接前往
  [ahaduoduoduo/bilibili-youtube-danmaku](https://github.com/ahaduoduoduo/bilibili-youtube-danmaku) 获取
- 编译产物（APK）与本地分析用的中间文件 —— 请用下面的命令自行构建

---

## 八、声明与许可

- 本项目的**算法与产品行为来自上游扩展**，版权归原作者 **啊铎铎铎仔** 所有（MIT）；
  本项目作为派生作品同样以 **MIT** 发布，`LICENSE` 中保留了两份版权声明
- 本项目仅用于技术学习与研究；弹幕版权归哔哩哔哩及原发布者所有，请勿用于商业用途
- 请遵守哔哩哔哩与 YouTube 的服务条款；模块不存储、不转发任何用户数据，所有请求都在
  用户设备的 YouTube 进程内直接发出

