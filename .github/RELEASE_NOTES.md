在 YouTube 安卓客户端内同步显示**哔哩哔哩弹幕**的 Xposed 模块，运行于 **Vector / LSPosed**。

> 本项目是基于 [ahaduoduoduo/bilibili-youtube-danmaku](https://github.com/ahaduoduoduo/bilibili-youtube-danmaku)
> （B2Y 浏览器扩展，作者：啊铎铎铎仔，MIT）改造而来的派生项目，算法与产品行为移植自上游，
> 数据链路与渲染层重写为 Kotlin / Android，并新增了 YouTube 客户端的 hook 层。
> 详见 [README](https://github.com/wanwangzi666/youtube-android-bilibili-danmaku#项目来源与致谢) 与
> [THIRD_PARTY_NOTICES](https://github.com/wanwangzi666/youtube-android-bilibili-danmaku/blob/main/THIRD_PARTY_NOTICES.md)。

---

## 功能

- 打开视频后**自动识别**（DEX 指纹取视频 ID → oEmbed 取原始标题）并按标题匹配 B 站视频
- 匹配算法与上游一致：搜索高亮比例 / 关键词 LCS 占比 / 包含度占比 三重打分 + 阈值过滤
- 支持多结果策略：自动选「弹幕最多」或弹窗让你选
- 弹幕渲染：滚动 / 顶部 / 底部固定、权重过滤、透明度、字号、速度、轨道间距、显示区域
- **时间轴偏移可调**（B 站与 YouTube 正片起点常差几秒甚至几分钟）
- 播放页悬浮「弹」按钮：重新搜索 / 搜索关键词 / 粘贴 B 站链接 / 番剧模式 / 实时调参 / 诊断信息
- 番剧支持：`《标题》第N话：` 形式的标题自动走 PGC 接口

## 安装要求

| 项 | 要求 |
| --- | --- |
| 系统 | Android 8.1 ~ 17，**已 root** |
| 框架 | Magisk / KernelSU + Zygisk，并已安装 [Vector](https://github.com/JingMatrix/Vector)（或 LSPosed） |
| 目标应用 | YouTube 官方客户端 `com.google.android.youtube` |

## 安装步骤

1. 下载本页附件里的 `B2Y-Vector-*.apk`，安装：
   ```bash
   adb install -r B2Y-Vector-1.0.0.apk
   ```
2. 打开 **Vector / LSPosed 管理器** → 启用 **B2Y 弹幕** 模块 → 勾选作用域 **YouTube**
3. **强制停止** YouTube 客户端，再重新打开（只切后台不行）
4. 打开桌面上的 **B2Y 弹幕** App，点一次 **保存设置**
   （被注入的进程通过 `XSharedPreferences` 读设置，文件不存在时会退回默认值）
5. 打开任意 YouTube 视频 → 弹幕会自动加载

> **首次安装如果是覆盖旧版本失败的**：早期测试包用的是临时 debug 密钥，签名不一致会导致
> `INSTALL_FAILED_UPDATE_INCOMPATIBLE`。先执行 `adb uninstall com.b2y.danmaku` 再安装即可
> （会清掉模块里已保存的设置）。**从本版本起的后续 Release 都使用同一个固定发布密钥，
> 可以直接覆盖升级。**

## 使用提示

- 匹配不上时：调低模块设置里的「标题匹配阈值」（默认 90%），或把「多结果处理」改成「弹窗让我选择」，
  或直接在播放页粘贴对应的 B 站链接
- 弹幕整体偏移时：用面板的 `-0.5s / +0.5s` 微调，长期偏移可在模块 App 里设
- 反馈问题时：播放页 → 悬浮「弹」→ 底部「复制诊断信息」，把内容贴到 Issue 里

## 附件说明

| 文件 | 说明 |
| --- | --- |
| `B2Y-Vector-<版本>.apk` | 模块安装包，使用**固定的项目发布密钥**签名，可跨版本覆盖升级 |
| `B2Y-Vector-<版本>.apk.sha256` | 上面的 APK 的 SHA-256 校验值 |

## 当前状态

- 构建与测试：**编译通过，106 个 JVM 单元测试全部通过**（本 Release 由 CI 自动构建并跑完测试后产出）
- DEX 指纹：已在真实 YouTube APK（21.38.124）上验证能正确定位视频 ID 与播放时间方法
- 真机迭代：已根据实测反馈修复弹幕上下闪烁、竖屏溢出、横屏滑入异常、首页信息流误匹配等问题；
  **最近一轮修复（渲染帧率优化与信息流门控）尚未经过充分真机验证**，欢迎反馈

## 已知限制

- 未移植上游的：繁简转换（OpenCC）、频道关联 UP 主、广告片段剔除、弹幕搜索跳转、夸克网盘支持
- 仅支持普通视频与番剧；Shorts 未做专门适配
- 弹幕为只读展示，不支持发送
- 弹幕数据来自哔哩哔哩公开接口，版权归哔哩哔哩及原发布者所有

## 许可

MIT。派生自上游 MIT 项目，`LICENSE` 中保留了两份版权声明。
