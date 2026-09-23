# 原扩展 → 模块 移植对照表

对照对象：`ahaduoduoduo/bilibili-youtube-danmaku` v2.0.0（WXT 浏览器扩展）

图例：✅ 已实现 ｜ 🔶 部分实现 ｜ ⬜ 未实现

---

## 1. 识别与匹配

| 原扩展能力 | 来源 | 模块实现 | 说明 |
| --- | --- | --- | --- |
| ✅ 读取 YouTube videoId | `content/index.js#getVideoId`（解析 URL `?v=`） | ✅ `PlayerFingerprintHook`（DEX 指纹） | 安卓端拿不到 URL，改用 `PlayerResponseModel.getVideoId()` 指纹；另可用 `MediaMetadata.METADATA_KEY_MEDIA_ID` 兜底 |
| ✅ 获取原始标题（多语言） | `getOriginalVideoTitle`（background 代发 oEmbed，绕 CORS） | ✅ `BiliApi.fetchYouTubeTitle` | 进程内直接请求 oEmbed，无需代发 |
| ✅ 标题清洗 | `cleanVideoTitle` / `getBestTitlePart` / `removeTrailingEnglish` / `selectBestPart` | ✅ `TitleMatcher` | 逐行移植 |
| ✅ 繁转简 | `opencc.min.js`（1MB 词库） | ⬜ | 会显著增加 APK 体积；B 站搜索对繁体容忍度尚可。后续可接入轻量映射表 |
| ✅ 标题匹配阈值 | `youtubeMatchThreshold`（默认 90） | ✅ `DanmakuSettings.matchThreshold` | |
| ✅ 匹配度打分 | `max(搜索高亮比例, 关键词LCS占比, 标题包含度占比)` | ✅ `TitleMatcher` | |
| ✅ 多结果策略 | `mostDanmaku` / `ask` | ✅ `multiMatchMode` + `ControlPanel.showChooser` | 弹窗选择改为播放页 `AlertDialog` |
| ✅ 频道关联 UP 主（旧版模式） | `channelAssociation.js` + `channel-associations.json` | ⬜ | 新版模式（标题搜索）已足够；频道关联依赖 DOM 抓取，安卓端代价高、收益低 |
| ✅ 番剧自动识别 | `MadeByBilibili` 频道 + `《标题》第N话：` | 🔶 仅按标题正则识别 | 无法读取频道名（会退化为纯标题搜索），但标题正则已能覆盖大部分番剧 |
| ✅ 广告片段弹幕剔除 | `removeAdSegments`（bsbsb.top / BilibiliSponsorBlock） | ⬜ | 需要额外请求第三方接口 + 时间轴重映射，留作后续 |
| ✅ 手动输入 B 站链接 | Popup 输入框 | ✅ `BiliApi.parseBvid` + `ControlPanel` | 支持 bvid / av 号 / 完整链接 / 分享文本 |
| ✅ 强制指定视频 | — | ✅ `DanmakuSettings.manualBvid` | 新增 |

## 2. B 站接口

| 原扩展能力 | 来源 | 模块实现 |
| --- | --- | --- |
| ✅ WBI 签名 | `getMixinKey` / `md5` / `encWbi` | ✅ `Wbi`（JDK `MessageDigest`，含 56~63 字节填充边界修正） |
| ✅ WBI key 6 小时缓存 | `getWbiKeys` + storage | ✅ `BiliApi`（内存缓存） |
| ✅ 视频信息 | `/x/web-interface/view` | ✅ `BiliApi.getVideoInfo`（支持 bvid 与 av） |
| ✅ 分段弹幕 | `/x/v2/dm/wbi/web/seg.so`（每 360s 一段） | ✅ `BiliApi.fetchDanmaku`（段间 250ms 限速，单段失败容忍） |
| ✅ 弹幕 protobuf 解析 | `lib/protobuf-parser.js` | ✅ `DanmakuProto`（额外支持 zlib/gzip 兜底） |
| ✅ 综合搜索 | `/x/web-interface/wbi/search/all/v2` | ✅ `BiliApi.searchAllV2`（只取第 1 页，与原扩展一致） |
| ✅ csrf 关键词拦截重试 | `isCsrfSearchBlock` + 去括号重试 | ✅ 同逻辑 |
| ✅ 番剧 PGC | `bangumi.js` `/pgc/view/web/season` | ✅ `Bangumi` |
| ✅ UP 主空间搜索 | `/x/space/wbi/arc/search` | ⬜（新版模式不再需要） |
| ✅ 搜索 UP 主 | `/x/web-interface/wbi/search/type` | ⬜ |
| ✅ 登录态（Cookie） | 浏览器自带 Cookie | ✅ `DanmakuSettings.sessData`（手动填 SESSDATA） |

## 3. 弹幕渲染

| 原扩展能力 | 来源 | 模块实现 | 说明 |
| --- | --- | --- | --- |
| ✅ 滚动弹幕 | Web Animations API | ✅ `DanmakuEngine` + `DanmakuView` | 改为「位置 = 播放时间的函数」 |
| ✅ 顶部/底部固定弹幕 | `mode === 4 / 5` | ✅ | 独立轨道占用 |
| ✅ 轨道分配与防重叠 | `findAvailableTrack` | ✅ | 随机兜底改为「最快清空」确定性策略 |
| ✅ 字号 / 间距 / 透明度 | CSS 变量 | ✅ `applySettings` | 支持 sp/dp 与百分比 |
| ✅ 滚动速度 | `settings.speed` | ✅ | 基准 8s |
| ✅ 显示区域百分比 | `displayAreaPercentage` | ✅ | |
| ✅ 权重过滤 | `weightThreshold` | ✅ | 权重裁剪按时间排序恢复 |
| ✅ 暂停 / 倍速同步 | `pause()` / `handleSpeedChange` | ✅ | 时间驱动，天然正确 |
| ✅ 拖动跳转重排 | `resyncDanmakus` + `resetDanmakuStates` | ✅ `onSeek` | 向后拖动会复位发射标记 |
| ✅ 时间轴偏移 | `timeOffset` | ✅（面板 ±0.5s + 设置页毫秒级） | |
| ✅ 弹幕数量上限 | — | ✅ `maxDanmakuCount` | 超出时优先保留高权重弹幕 |
| ✅ 全屏 / 旋转适配 | ResizeObserver + fullscreenchange | ✅ 250ms 轮询 `SurfaceView` 边界 → 弹幕舞台即视频画面 | |
| ⬜ 弹幕搜索与跳转 | Popup 里的弹幕搜索 | ⬜ | |
| ⬜ 发送弹幕 | 原扩展也没有 | ⬜ | |
| ⬜ 高级/代码弹幕 | 原扩展也没有 | ⬜ | |

## 4. 交互与设置

| 原扩展能力 | 来源 | 模块实现 |
| --- | --- | --- |
| ✅ 设置持久化 | `browser.storage.local` | ✅ 模块 SharedPreferences + `XSharedPreferences` |
| ✅ 设置界面 | `entrypoints/popup/*`（1200+ 行 HTML/CSS） | ✅ `SettingsActivity`（程序化布局，零 AndroidX 依赖） |
| ✅ 结果选择弹窗 | Popup 列表 | ✅ `ControlPanel.showChooser` |
| ✅ 总开关 / 启用状态 | `settings.enabled` | ✅ |
| ✅ 自动检测开关 | — | ✅ `autoLoad` |
| ✅ 悬浮入口 | 浏览器工具栏图标 | ✅ 播放页可拖动悬浮「弹」按钮（`showFloatButton`） |
| ⬜ 夸克网盘支持 | `entrypoints/quark.content/*` | ⬜ 与本次目标（YouTube App）无关 |

---

## 5. 依赖体积对比

| | 原扩展 | 本模块 |
| --- | --- | --- |
| 运行时依赖 | WXT 打包产物 + `opencc.min.js`（1.06 MB） | **零第三方运行时依赖**（仅 Kotlin stdlib） |
| 产物 | `chrome.zip` 等 | `app-debug.apk`（约 1.6 MB） |

`opencc` 是原扩展里唯一的大依赖，本模块刻意不引入（舍弃了繁转简，见第 1 节）。
DEX 指纹解析器是自研的（约 400 行），避免引入 dexkit 及其传递依赖。
