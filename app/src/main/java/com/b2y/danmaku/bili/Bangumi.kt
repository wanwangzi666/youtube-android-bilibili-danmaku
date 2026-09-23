package com.b2y.danmaku.bili

import com.b2y.danmaku.core.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * B 站番剧（pgc）支持。
 *
 * 参考实现：浏览器扩展 `entrypoints/background/bangumi.js`。
 * - 番剧搜索：`/x/web-interface/wbi/search/type?search_type=media_bangumi`
 * - 剧集明细：`/pgc/view/web/season?ep_id=`
 *
 * 本文件仅依赖 [BiliApi] 的 internal 辅助方法（同模块内可见）。
 */
object Bangumi {

    /** pgc 剧集明细接口 */
    private const val PGC_SEASON_URL = BiliApi.BASE_URL + "/pgc/view/web/season"

    /** 番剧标题匹配规则：`《标题》第N话：`（"话"后必须紧跟全角/半角冒号） */
    private val BANGUMI_TITLE_REGEX = Regex("""《(.+?)》第(\d+)[话話][:：]""")

    /** 剧集标题中的话数：`第N话` / `第N集` */
    private val EPISODE_NUMBER_REGEX = Regex("""第\s*(\d+)\s*[话話集]""")

    /** 纯数字剧集标题 */
    private val PURE_NUMBER_REGEX = Regex("""^\d+$""")

    /**
     * 解析番剧标题。
     *
     * 匹配 `《标题》第N话：` 形式（"话"后必须带冒号），返回 `(标题, 集数)`；
     * 不匹配时返回 null。
     */
    fun parseBangumiTitle(videoTitle: String): Pair<String, Int>? {
        if (videoTitle.isEmpty()) return null
        val match = BANGUMI_TITLE_REGEX.find(videoTitle) ?: return null
        val title = match.groupValues[1].trim()
        val episode = match.groupValues[2].toIntOrNull() ?: return null
        if (title.isEmpty()) return null
        return title to episode
    }

    /**
     * 通过 pgc 搜索 + season 接口得到该话的 cid/aid。
     *
     * 流程与参考实现一致：
     * 1. `search/type?search_type=media_bangumi&keyword=标题` 找到番剧（优先标题完全匹配）；
     * 2. 在 `eps` 中按话数精确匹配，失败则按 `episode - 1` 索引兜底；
     * 3. `pgc/view/web/season?ep_id=` 取该话的 bvid/cid/aid/duration。
     *
     * @return 未找到时返回 null（接口本身异常时抛 [BiliException]）
     */
    @Throws(BiliException::class)
    fun findEpisode(api: BiliApi, title: String, episode: Int): BiliVideoInfo? {
        val keyword = title.trim()
        if (keyword.isEmpty() || episode <= 0) return null

        Log.i("搜索番剧: \"$keyword\" 第 $episode 话")
        val searchJson = api.signedGet(
            "/x/web-interface/wbi/search/type",
            mapOf("search_type" to "media_bangumi", "keyword" to keyword, "page" to 1)
        )

        val resultArr = searchJson.optJSONObject("data")?.optJSONArray("result")
            ?: return null
        if (resultArr.length() == 0) {
            Log.w("未找到匹配的番剧: \"$keyword\"")
            return null
        }

        // 优先标题完全一致的条目，否则取第一条（与参考实现一致）
        var bangumi: JSONObject? = null
        for (i in 0 until resultArr.length()) {
            val item = resultArr.optJSONObject(i) ?: continue
            if (item.optString("title", "").trim() == keyword) {
                bangumi = item
                break
            }
        }
        if (bangumi == null) bangumi = resultArr.optJSONObject(0)
        if (bangumi == null) return null

        val eps = bangumi.optJSONArray("eps")
        if (eps == null || eps.length() == 0) {
            Log.w("番剧没有集数信息: ${bangumi.optString("title", "")}")
            return null
        }

        val target = findEpisodeByNumber(eps, episode) ?: return null
        val epId = target.optLong("id", 0L)
        if (epId <= 0L) return null

        Log.d("命中剧集: ${target.optString("title", "")} (ep_id=$epId)")
        val seasonJson = JSONObject(api.getJson("$PGC_SEASON_URL?ep_id=$epId"))
        val code = seasonJson.optInt("code", 0)
        if (code != 0) {
            throw BiliException("获取剧集明细失败: ${seasonJson.optString("message", "未知错误")} (code=$code)")
        }
        val result = seasonJson.optJSONObject("result") ?: return null
        val episodes = result.optJSONArray("episodes") ?: return null

        var current: JSONObject? = null
        for (i in 0 until episodes.length()) {
            val ep = episodes.optJSONObject(i) ?: continue
            if (ep.optLong("id", 0L) == epId) {
                current = ep
                break
            }
        }
        if (current == null) {
            Log.w("season 响应中未找到 ep_id=$epId")
            return null
        }
        return toVideoInfo(current, result.optJSONObject("up_info"))
    }

    /**
     * 在 `eps` 中查找指定话数。
     *
     * 与参考实现一致：先精确匹配 `第N话` / 纯数字标题，失败则按 `episode - 1` 索引兜底。
     */
    fun findEpisodeByNumber(eps: JSONArray, episodeNumber: Int): JSONObject? {
        for (i in 0 until eps.length()) {
            val ep = eps.optJSONObject(i) ?: continue
            val title = ep.optString("title", "")
            if (title.isEmpty()) continue

            val matched = EPISODE_NUMBER_REGEX.find(title)?.groupValues?.getOrNull(1)
                ?: if (PURE_NUMBER_REGEX.matches(title)) title else null
            if (matched != null && matched.toIntOrNull() == episodeNumber) return ep
        }
        if (episodeNumber in 1..eps.length()) {
            return eps.optJSONObject(episodeNumber - 1)
        }
        Log.w("未找到第 $episodeNumber 话对应的剧集")
        return null
    }

    /** 从 season 剧集对象构造 [BiliVideoInfo] */
    private fun toVideoInfo(episode: JSONObject, upInfo: JSONObject?): BiliVideoInfo {
        val cover = firstNonEmpty(
            episode.optString("cover", ""),
            episode.optString("pic", "")
        )
        val title = firstNonEmpty(
            episode.optString("title", ""),
            episode.optString("long_title", ""),
            episode.optString("share_copy", "")
        )
        val author = firstNonEmpty(
            upInfo?.optString("uname", "") ?: "",
            episode.optJSONObject("rights")?.optString("area_name", "") ?: ""
        )
        return BiliVideoInfo(
            bvid = episode.optString("bvid", ""),
            aid = episode.optLong("aid", 0L),
            cid = episode.optLong("cid", 0L),
            durationSec = firstPositive(
                episode.optInt("duration", 0),
                episode.optInt("duration_sec", 0)
            ),
            title = title,
            pic = if (cover.startsWith("//")) "https:$cover" else cover,
            author = author
        )
    }

    private fun firstNonEmpty(vararg values: String): String {
        for (v in values) if (v.isNotEmpty()) return v
        return ""
    }

    private fun firstPositive(vararg values: Int): Int {
        for (v in values) if (v > 0) return v
        return 0
    }
}
