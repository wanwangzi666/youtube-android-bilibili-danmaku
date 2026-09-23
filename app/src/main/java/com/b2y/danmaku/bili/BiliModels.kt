package com.b2y.danmaku.bili

/**
 * B 站视频基础信息。
 *
 * 对应浏览器扩展 `entrypoints/background/index.js` 中 `getVideoInfo()` 的返回值。
 *
 * @param bvid 视频 BV 号（由 av 号反查时也会尽量填充）
 * @param aid 视频 av 号（数字）
 * @param cid 视频分 P 的 cid，弹幕接口的 `oid` 参数
 * @param durationSec 视频总时长（秒），用于计算弹幕分段数量
 * @param title 视频标题
 * @param pic 封面图 URL
 * @param author UP 主昵称
 */
data class BiliVideoInfo(
    val bvid: String,
    val aid: Long,
    val cid: Long,
    val durationSec: Int,
    val title: String,
    val pic: String,
    val author: String
)

/**
 * B 站综合搜索（`/x/web-interface/wbi/search/all/v2`）的单条视频结果。
 *
 * @param mid UP 主 uid
 * @param play 播放量
 * @param danmaku 弹幕数
 * @param pubdate 发布时间戳（秒）
 * @param highlightRatio 标题匹配度 0..1（取 `<em>` 高亮占比、LCS 占比、包含占比的最大值）
 */
data class BiliSearchResult(
    val bvid: String,
    val title: String,
    val author: String,
    val mid: Long,
    val pic: String,
    val play: Long,
    val danmaku: Long,
    val durationSec: Int,
    val pubdate: Long,
    val highlightRatio: Float
)

/**
 * 单条弹幕。
 *
 * @param timeMs 出现时间（毫秒，protobuf 中的 `progress`）
 * @param text 弹幕文本
 * @param color RGB 颜色值（0xRRGGBB，已屏蔽到 24 位）
 * @param mode 弹幕类型：1/2/3 = 滚动，4 = 底部固定，5 = 顶部固定
 * @param fontSize 字号
 * @param weight 权重（原样保留协议中的值，可能为 0；由渲染层决定阈值过滤）
 */
data class DanmakuEntry(
    val timeMs: Long, val text: String, val color: Int,
    val mode: Int, val fontSize: Int, val weight: Int
)

/**
 * B 站接口调用失败异常。
 *
 * 网络错误、HTTP 状态码异常、`code != 0` 等情况统一抛出该异常。
 */
class BiliException(message: String, cause: Throwable? = null) : Exception(message, cause)
