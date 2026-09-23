# 第三方代码与参考来源

本项目的算法与产品行为**主要移植自一个浏览器扩展**，另外在 YouTube 客户端 hook 方案上参考了
社区公开的资料。下面逐项说明来源、使用方式与许可。

---

## 1. 主要来源（算法移植，派生自该项目）

**[ahaduoduoduo/bilibili-youtube-danmaku](https://github.com/ahaduoduoduo/bilibili-youtube-danmaku)** — B2Y
（WXT 浏览器扩展，自动把 B 站弹幕同步显示在 YouTube 网页上）

- 作者：啊铎铎铎仔
- 许可：**MIT**
- 使用方式：**逐模块重写为 Kotlin / Android**，行为与算法对齐上游实现；未直接复制源文件（语言与运行环境完全不同）

被移植的具体内容：

| 上游文件 / 函数 | 本项目对应实现 | 说明 |
| --- | --- | --- |
| `entrypoints/background/index.js` — `mixinKeyEncTab` / `getMixinKey` / `md5` / `encWbi` | `bili/Wbi.kt` | B 站 WBI 请求签名算法 |
| `entrypoints/background/index.js` — `getWbiKeys` / `getVideoInfo` / `getSegmentDanmaku` | `bili/BiliApi.kt` | `nav` 取 key、`view` 取 aid/cid、`seg.so` 按 6 分钟分段取弹幕 |
| `entrypoints/background/index.js` — `downloadAllDanmaku` | `bili/BiliApi.fetchDanmaku` | 分段循环、段间限速、去重与排序 |
| `lib/protobuf-parser.js` | `bili/DanmakuProto.kt` | `DmSegMobileReply` 手写 protobuf 解析（字段号与语义完全一致） |
| `entrypoints/background/index.js` — `cleanVideoTitle` / `getBestTitlePart` / `selectBestPart` / `isPureEnglishOrNumber` / `removeTrailingEnglish` / `normalizeBilibiliSearchKeyword` / `removeBracketedSearchTerms` | `bili/TitleMatcher.kt` | 标题清洗与搜索词归一化 |
| `entrypoints/background/index.js` — `calculateKeywordMatchRatio` / `calculateTitleContainmentRatio` / `calculateHighlightRatio` | `bili/TitleMatcher.kt` | 三重匹配打分（LCS / 包含度 / 搜索高亮） |
| `entrypoints/background/index.js` — `searchBilibiliVideoAllV2` | `bili/BiliApi.searchAllV2` | 综合搜索 + csrf 关键词拦截重试 |
| `entrypoints/background/bangumi.js` | `bili/Bangumi.kt` | `《标题》第N话：` 解析与 PGC 取番剧弹幕 |
| `utils/danmaku-engine.js` | `danmaku/DanmakuEngine.kt` | 轨道高度/数量、`findAvailableTrack` 防重叠、顶部/底部固定弹幕、权重过滤、`resyncDanmakus` 的 seek 语义 |
| `entrypoints/content/index.js` | `core/VideoSessionController.kt` | 自动识别 → 标题 → 搜索 → 阈值过滤 → 多结果策略（选弹幕最多 / 弹窗选择）的完整流程 |
| `entrypoints/popup/*`（交互设计） | `ui/ControlPanel.kt`、`ui/module/SettingsActivity.kt` | 设置项语义（透明度/字号/速度/间距/显示区域/偏移/阈值）与多结果选择交互 |
| `README.md` / `LICENSE` | 本项目文档与 `LICENSE` | 保留原作者版权声明 |

**没有移植**的部分：`opencc.min.js` 繁简转换（体积过大）、频道关联 UP 主、广告片段剔除
（BilibiliSponsorBlock）、弹幕搜索与跳转、夸克网盘支持。详见 [`docs/PORTING-NOTES.md`](docs/PORTING-NOTES.md)。

---

## 2. YouTube 客户端 hook 方案的参考

**[NexAlloy/NexAlloy](https://github.com/NexAlloy/NexAlloy)**（原名 ReVanced Xposed，LSPosed 模块）

- 使用方式：**仅参考其公开的 DEX 指纹思路与字符串常量**，未复制任何代码
- 参考内容：
  - 用字符串常量 `"Null initialPlayabilityStatus"` 定位持有 `PlayerResponseModel` 的方法，再由该方法
    反查 `getVideoId()`（`app/.../youtube/video/videoid/Fingerprints.kt`）
  - 用字符串常量 `"Media progress reported outside media playback"` 定位播放时间上报路径
    （`app/.../youtube/video/information/VideoInformationPatch.kt`）
- 本项目的工作：用**自研的 DEX 解析器**（`hook/fingerprint/DexFile.kt`，零第三方依赖）独立实现了
  「字符串常量 → 被混淆的类/方法」反查，并在真实 APK（YouTube 21.38.124）上验证了这两条指纹确实命中
  （见 `app/src/test/java/com/b2y/danmaku/hook/fingerprint/DexFileTest.kt`）。

---

## 3. 框架与 API

| 项目 | 用途 | 许可 |
| --- | --- | --- |
| [JingMatrix/Vector](https://github.com/JingMatrix/Vector) | 目标运行框架（Modern Xposed Framework，基于 LSPlant） | GPL-3.0 |
| [LSPosed](https://github.com/LSPosed/LSPosed) | Vector 的上游，兼容同一套模块 API | GPL-3.0 |
| [Xposed API (api.xposed.info)](https://api.xposed.info/) `de.robv.android.xposed:api:82` | 模块编译期依赖（legacy Xposed API） | Apache-2.0 |
| AndroidX / Kotlin stdlib | **未使用 AndroidX**；仅使用 Kotlin 标准库 | Apache-2.0 |

本项目是**独立的模块 APK**，不包含也不修改 Vector / LSPosed 的任何代码；GPL-3.0 的框架与 MIT 的模块
分属不同进程/产物，不构成衍生关系。

---

## 4. 数据与接口

- 弹幕数据、视频信息、搜索结果均来自 **哔哩哔哩** 的公开 Web 接口，通过用户自己的账号 Cookie（可选）访问；
  弹幕版权归哔哩哔哩及原发布者所有。
- YouTube 视频标题通过 **YouTube oEmbed** 公共接口获取。
- 本项目不存储、不转发任何用户数据；所有请求都在用户设备的 YouTube 进程内直接发出。
