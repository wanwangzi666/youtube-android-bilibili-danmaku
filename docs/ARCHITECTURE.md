# 架构与设计说明

本文说明「浏览器扩展 → Xposed 模块」的完整设计思路，包括原扩展的工作方式、目标平台差异、
YouTube 客户端的混淆分析结论，以及为什么最终选用当前这套 hook 方案。

---

## 1. 原扩展是怎么工作的

`bilibili-youtube-danmaku`（WXT 框架，Manifest V3）由四部分组成：

| 模块 | 职责 |
| --- | --- |
| `entrypoints/content/index.js` | YouTube 页面内容脚本：读 `?v=` 拿 videoId、从 DOM 抓标题与频道、监听 SPA 切视频、找 `.html5-video-container` 挂弹幕舞台 |
| `entrypoints/background/index.js` | 后台脚本：WBI 签名、B 站搜索、分段下载弹幕、protobuf 解析、广告分段剔除、标题匹配打分 |
| `utils/danmaku-engine.js` | 弹幕引擎：轨道分配、Web Animations API 滚动、seek/倍速处理、设置项 |
| `entrypoints/popup/*` | 设置与多结果选择 UI |

关键算法（本模块逐个移植）：

1. **WBI 签名**：`nav` 接口拿 `img_key`/`sub_key` → 用 64 项 `mixinKeyEncTab` 重排 → 拼 `mixinKey` →
   参数排序 + `encodeURIComponent` → `md5(query + mixinKey)` 得 `w_rid`
2. **弹幕下载**：`/x/web-interface/view?bvid=` 拿 `aid/cid/duration` → 按 6 分钟分段循环
   `/x/v2/dm/wbi/web/seg.so` → 手写 protobuf 解析 `DmSegMobileReply.elems`
3. **标题匹配打分**：`max(搜索高亮比例, 关键词 LCS 占比, 标题包含度占比)`
4. **轨道分配**：轨道高度 = 字号 + 间距；新弹幕找一个"尾部已让出 100px"的轨道
5. **seek 语义**：`resyncDanmakus()` 复位所有 `emitted` 标记，再只补发当前时间窗内的弹幕

---

## 2. 平台差异与映射

| 浏览器扩展 | Android Xposed 模块 |
| --- | --- |
| `document.querySelector('video').currentTime` | 见第 4 节「时间轴同步」 |
| `window.location` 解析 `?v=` | DEX 指纹 hook `PlayerResponseModel.getVideoId()` |
| DOM 标题元素 / YouTube oEmbed | oEmbed 原始标题 + `MediaMetadata.METADATA_KEY_TITLE` |
| `.html5-video-container` 里插 `<div>` | 在 Activity 的 `android.R.id.content` 上叠 `FrameLayout` |
| Web Animations API 每帧补间 | 自绘 `View` + `Canvas`，位置由「当前播放时间」纯函数计算 |
| `browser.storage.local` | 模块 SharedPreferences + `XSharedPreferences` |
| `fetch()`（后台脚本代发，绕 CORS） | 进程内 `HttpURLConnection`（YouTube 自带 INTERNET 权限） |
| Popup 设置界面 | 模块 App 的 `SettingsActivity` + 播放页内 `ControlPanel` |

**渲染模型的改变**是这次移植最重要的设计决定。浏览器版依赖 Web Animations 让浏览器自己补间；
安卓版把「位置」写成播放时间的纯函数：

```
progress = clamp((nowMs - item.timeMs) / (BASE_DURATION / speed), 0, 1)
x        = stageWidth - progress * (stageWidth + textWidth)
```

好处是暂停 / 倍速 / 拖动进度条**天然同步**，不需要像 Web Animations 那样去修正
`animation.currentTime`；代价是每帧要重算活动弹幕位置（活动弹幕数量很少，成本可忽略）。

---

## 3. YouTube 客户端混淆分析（决策依据）

本模块开发时对 `YouTube 21.38.124`（`classes.dex` ~ `classes8.dex`，共 55544 个类）做了完整 DEX 解析，
结论直接决定了 hook 方案：

| 观察 | 结论 |
| --- | --- |
| `androidx.media3.*` 的类名**几乎全部被 R8 重命名**；只有 `androidx.media3.exoplayer.ExoPlayer` 这个接口名保留，而且它自己的方法也被改成了 `R/S/T/U/V/W/X/Y/Z/aa/c/d/l` | **不能**硬编码 media3 类名/方法名去 hook 播放器 |
| `getCurrentPosition` / `getPlaybackState` / `isPlaying` / `seekTo` 这些字符串在常量池里存在，但**没有任何类声明这些方法** | 播放器 API 已被混淆，靠名字找不到 |
| `com.google.android.apps.youtube.*` 只有少量类保留原名（如 `watchwhile.MainActivity`），播放器相关的 `PlayerControllerImpl` 等只以**字符串常量**形式出现 | 应用自身的类同样被混淆 |
| 字符串常量 `"Null initialPlayabilityStatus"`、`"Media progress reported outside media playback: "` 完好保留 | **字符串常量是最稳定的指纹**，用它反查被混淆的类/方法 |
| `android.media.session.MediaSession`、`android.view.SurfaceView`、`android.app.Activity` 等框架类不受影响 | 优先 hook 框架类，版本无关 |

### 由此确定的三层 hook 策略

```
第 1 层（首选，最稳）：框架类
    Activity 生命周期      → 挂载/卸载弹幕浮层
    SurfaceView/TextureView → 定位视频画面矩形
    MediaSession           → 播放位置 / 倍速 / 播放暂停 / 标题

第 2 层（精度增强，尽力而为）：DEX 指纹
    "Null initialPlayabilityStatus"            → videoId
    "Media progress reported outside media…"   → 毫秒级播放时间

第 3 层（保底）：手动
    悬浮面板粘贴 B 站链接 / 关键词搜索 / 强制指定 bvid
```

第 1 层保证「即使 YouTube 大版本更新导致指纹全部失效，模块依然能用」；第 2 层把同步精度和视频识别
自动化提升到毫秒级；第 3 层保证任何情况下用户都能自己救回来。

### Shorts（竖屏短视频）的处理

上游扩展在网页版里天然能拿到 URL，`/shorts/` 路径一目了然；安卓端拿不到 URL，因此新增
`hook/ShortsDetector.kt`，用两层信号判断「当前画面是不是 Shorts」：

```
第 1 层：Shorts 播放器视图
    com.google.android.libraries.youtube.reel.internal.*   ← R8 保留原名（被字符串/资源引用）
    → 惰性按类名解析一次 → 遍历当前 Activity 视图树找实例
      （模块不额外 hook YouTube 内部类，避免解析全部方法带来的开销）

第 2 层：竖屏全屏几何（兜底，完全不依赖 YouTube 实现）
    视频画面 宽/高 < 0.9 且 宽、高都 ≥ 屏幕的 60%
```

判定结果带 3 秒保鲜期，避免视图瞬时回收导致状态来回抖动。设置项 `matchInShorts`（默认 `true`，
保持老版本行为）关掉后：

- `VideoSessionController.skipBecauseShorts()` 拦住**所有自动匹配入口**
  （视频 ID 确认 / 纯标题确认 / `startResolve`），只更新状态文字，不发起搜索
- `DanmakuOverlay` 把弹幕层与悬浮「弹」按钮一起隐藏
- 播放页面板里的「粘贴 B 站链接 / 搜索关键词 / 番剧模式」**不受影响** —— 那是用户明确的手动意图

`ShortsDetector.looksLikeShortsGeometry(...)` 的判定规则被抽成纯整数入参的函数，
因此可以在 JVM 单测里完整覆盖（`ShortsDetectorTest`），另一条测试会在真实 APK 的 DEX 上
确认那几个 `reel.internal.*` 类名确实被保留下来。

---

## 4. 时间轴同步

### 4.1 播放时钟

`core/PlaybackClock.kt` 是一个「锚点 + 外推」模型：

```
submit(positionMs, speed, playing, elapsedRealtimeMs)
positionMs() = playing ? anchorPos + (now - anchorTime) * speed : anchorPos
```

它同时承担事件检测：

- **跳转**：外推预期位置与新采样位置偏差 > 1.2s → 触发 `onSeek`（弹幕引擎据此复位重排）
- **暂停/恢复**：`onPlayStateChanged`
- **倍速**：`onSpeedChanged`

### 4.2 两个数据源

| 数据源 | 提供 | 精度 |
| --- | --- | --- |
| `MediaSessionWatcher` | `PlaybackState.getPosition()` + `getLastPositionUpdateTime()` + `getPlaybackSpeed()` | 播放中精确（外推无误差），跳转后依赖 App 更新 PlaybackState |
| `PlayerFingerprintHook` | YouTube 内部上报的视频时间（毫秒） | 毫秒级、更新频繁 |

两者同时可用时，位置以**指纹为准**，媒体会话只提供播放/暂停与倍速
（`PlaybackClock.submitFlags()` 会在当前时刻重新锚定，保证暂停时位置冻结）。
指纹失效时自动退回媒体会话单独供数。

### 4.3 渲染循环

`DanmakuView` 在 `onDraw` 前用 `clock.positionMs() + timeOffsetMs` 作为 `nowMs` 调
`DanmakuEngine.update()`，然后用 `Canvas.drawText` 绘制 `engine.activeItems`。
播放中通过 `postInvalidateOnAnimation()` 逐帧刷新；暂停/跳转时只刷新一次。

---

## 5. 弹幕引擎

`danmaku/DanmakuEngine.kt`（纯 JVM，可在桌面 JVM 上跑单测）负责：

- **载入与排序**：按 `timeMs` 排序，用 `nextIndex` 游标做 O(1) 发射
- **轨道几何**：轨道高 = 字号 + 间距；轨道数 = `min(maxTracks, floor(高度 × 显示区域% / 轨道高))`
- **轨道分配**：移植 JS 版 `findAvailableTrack()`——要求轨道尾部弹幕右边缘
  `x + width <= 舞台宽 - 100`；全部占用时选"尾部最靠左（最快清空）"的那条（JS 版这里是随机，改成确定性便于测试）
- **顶部/底部固定弹幕**：独立轨道占用，顶部自上而下、底部自下而上，水平居中，存活 4s
- **对象池**：`ActiveDanmaku` 复用，每帧零分配
- **单帧发射上限 12 条**：防止卡顿/拖动后一次性爆发；未发射的留给下一帧
- **seek**：清空在场弹幕 + **复位所有发射标记** + 把已过去的标记为已发射（不重播），未来的到点正常出现
- **舞台不可见时**（视频区域还没算出来 / 后台）：把到点弹幕标记为"已发射但不绘制"，避免舞台恢复后爆发

---

## 6. DEX 指纹层

`hook/fingerprint/DexFile.kt` 是一个自研的极简 DEX 解析器（**零第三方依赖**，因此不需要引入 dexkit）：

- 解析 header / `string_ids` / `type_ids` / `proto_ids` / `method_ids` / `class_defs` / `class_data_item`
- 遍历 `code_item` 的指令流（含完整指令宽度表 + `packed-switch`/`sparse-switch`/`fill-array-data` 载荷），
  以支持 `const-string` 与 `invoke-*` 的定位
- 提供 `findMethodsUsingString(text)`、`invokedMethodsOf(codeOff)`、`stringsOf(codeOff)`
- `ApkDexIndex` 从 `ApplicationInfo.sourceDir` + `splitSourceDirs` 惰性读取 APK 内的 `classes*.dex`

`PlayerFingerprintHook` 用它实现两个指纹：

```
指纹 A（视频 ID）
  找含 "Null initialPlayabilityStatus" 的方法 M
  → M 的第一个对象参数类型 P 就是 PlayerResponseModel 接口
  → 在 M 的 invoke 指令里找「声明在 P 上、返回 String、无参」的方法 = getVideoId()
  → hook M，after 时对 args[0] 反射调用 getVideoId()

指纹 B（播放时间）
  找含 "Media progress reported outside media playback: " 的方法
  → 取其 invoke 里第一个参数是 long 的 <init>
  → hook 该构造函数，before 时把 args[0]（毫秒）提交给 PlaybackClock
```

`app/src/test/java/.../fingerprint/DexFileTest.kt` 会在开发机上拿真实 dex 验证这条链路
（目录不存在时自动跳过），实测在 YouTube 21.38.124 上两个指纹都能命中并正确推导。

---

## 7. 线程模型

| 线程 | 工作 |
| --- | --- |
| App 主线程 | 所有 hook 回调、浮层挂载、`DanmakuView` 绘制 |
| `b2y-network` 单线程池 | B 站搜索、标题解析、分段下载弹幕（结果通过 `Handler` 回主线程） |
| `PlaybackClock` | 不加锁，字段由 hook 线程写、主线程读；时钟只做「锚点 + 外推」，读到旧值最多差一帧 |

`VideoSessionController` 用 `AtomicInteger generation` 丢弃过期结果：切换视频后，
上一次搜索/下载的回调不会污染新视频的画面。

---

## 8. 已知限制

- **未在真机上跑过**：本仓库只做到「编译通过 + JVM 单测通过 + 指纹在真实 APK 上验证」。
  hook 的实际生效情况需要在设备上验证（详见 README 的排查表）。
- `MediaSession` 位置更新频率取决于 YouTube 自身；若某版本只在状态变化时更新，
  拖动进度条后需要等一次 `PlaybackState` 才完全对齐（此时指纹层通常是生效的）。
- `searchAllV2` 只取第 1 页（与原扩展一致）。翻页可作为后续改进。
- 弹幕渲染为**自绘 Canvas**，未实现 B 站的高级弹幕（代码弹幕 / 高级弹幕）与图片弹幕。
- 广告分段剔除（BilibiliSponsorBlock）尚未移植。
- 弹幕写入/发送功能未实现（只读展示）。
