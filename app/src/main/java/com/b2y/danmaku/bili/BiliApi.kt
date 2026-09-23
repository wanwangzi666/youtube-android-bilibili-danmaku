package com.b2y.danmaku.bili

import com.b2y.danmaku.core.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.zip.GZIPInputStream

/**
 * B 站接口客户端（Xposed 模块内使用）。
 *
 * 对应浏览器扩展 `entrypoints/background/index.js` 的网络层。全部网络调用都是**阻塞**的，
 * 请在调用方自行放到后台线程执行。
 *
 * 约束：
 * - 仅使用 `java.net.HttpURLConnection`，不引入 OkHttp 等第三方库；
 * - JSON 使用 `org.json`；
 * - 连接超时 10s、读取超时 15s，所有连接必定 `disconnect()`；
 * - `SESSDATA` 非空时以 Cookie 形式发送。
 *
 * @param sessData B 站 SESSDATA Cookie 值（可为空）
 */
class BiliApi(private val sessData: String = "") {

    companion object {
        /** B 站 API 根地址 */
        const val BASE_URL = "https://api.bilibili.com"

        /** 默认连接超时（毫秒） */
        const val CONNECT_TIMEOUT_MS = 10_000

        /** 默认读取超时（毫秒） */
        const val READ_TIMEOUT_MS = 15_000

        /** oEmbed 使用的较短超时（毫秒） */
        const val OEMBED_TIMEOUT_MS = 6_000

        /** WBI Keys 内存缓存有效期：6 小时 */
        const val WBI_KEYS_TTL_MS = 6L * 60 * 60 * 1000

        /** 弹性分段长度：6 分钟（秒） */
        const val SEGMENT_SECONDS = 360

        /** 响应体读取上限（约 32MB），防御异常服务端 */
        private const val MAX_BODY_BYTES = 32 * 1024 * 1024

        /** 搜索结果最多返回条数 */
        private const val SEARCH_RESULT_LIMIT = 15

        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        const val REFERER = "https://www.bilibili.com/"

        const val ORIGIN = "https://www.bilibili.com"

        /** `BV` + 10 位 base62 字符 */
        private val BV_REGEX = Regex("""BV[0-9A-Za-z]{10}""")

        /** `av` + 数字（大小写不敏感） */
        private val AV_REGEX = Regex("""[Aa][Vv](\d+)""")
    }

    /** WBI Keys 内存缓存（6 小时） */
    private data class WbiKeyCache(val imgKey: String, val subKey: String, val expiresAt: Long)

    @Volatile
    private var wbiKeysCache: WbiKeyCache? = null

    /** 服务端是否启用了 gzip（命中后后续请求都带 Accept-Encoding） */
    @Volatile
    private var gzipSupported: Boolean = false

    /** 本次实例的请求/读取超时，可由构造后的 setter 调整 */
    var connectTimeoutMs: Int = CONNECT_TIMEOUT_MS
    var readTimeoutMs: Int = READ_TIMEOUT_MS

    // ================================================================ WBI Keys

    /**
     * 获取 WBI 签名所需的 `img_key` / `sub_key`。
     *
     * 结果在内存中缓存 6 小时；[forceRefresh] 为 true 时强制重新请求。
     *
     * @throws BiliException 网络异常或接口返回异常
     */
    @Throws(BiliException::class)
    fun getWbiKeys(forceRefresh: Boolean = false): Pair<String, String> {
        if (!forceRefresh) {
            val cached = wbiKeysCache
            if (cached != null && System.currentTimeMillis() < cached.expiresAt) {
                return cached.imgKey to cached.subKey
            }
        }

        val json = try {
            val body = request("$BASE_URL/x/web-interface/nav", null)
            JSONObject(body)
        } catch (e: BiliException) {
            throw e
        } catch (t: Throwable) {
            throw BiliException("获取 WBI Keys 失败: ${t.message}", t)
        }

        val data = json.optJSONObject("data")
        val wbiImg = data?.optJSONObject("wbi_img")
            ?: throw BiliException("无法获取 WBI Keys：响应缺少 data.wbi_img")
        val imgUrl = wbiImg.optString("img_url", "")
        val subUrl = wbiImg.optString("sub_url", "")
        if (imgUrl.isEmpty() || subUrl.isEmpty()) {
            throw BiliException("无法获取 WBI Keys：img_url / sub_url 为空")
        }

        val imgKey = extractWbiKey(imgUrl)
        val subKey = extractWbiKey(subUrl)
        if (imgKey.isEmpty() || subKey.isEmpty()) {
            throw BiliException("无法解析 WBI Keys：$imgUrl / $subUrl")
        }

        wbiKeysCache = WbiKeyCache(imgKey, subKey, System.currentTimeMillis() + WBI_KEYS_TTL_MS)
        Log.d("WBI Keys 已更新: img=$imgKey len=${subKey.length}")
        return imgKey to subKey
    }

    /** 从 `https://i0.hdslb.com/bfs/wbi/xxxx.png` 中取出文件名（不含扩展名） */
    private fun extractWbiKey(url: String): String {
        val slash = url.lastIndexOf('/')
        val name = if (slash >= 0) url.substring(slash + 1) else url
        val dot = name.lastIndexOf('.')
        return if (dot > 0) name.substring(0, dot) else name
    }

    // ============================================================== 视频信息

    /**
     * 获取视频信息。
     *
     * [bvid] 支持 `BV...`、`av123`（数字 av 号）两种形式；av 号会走 `?aid=` 分支。
     *
     * @throws BiliException 接口返回异常或缺少必填字段
     */
    @Throws(BiliException::class)
    fun getVideoInfo(bvid: String): BiliVideoInfo {
        val input = bvid.trim()
        if (input.isEmpty()) throw BiliException("视频标识为空")

        val url = if (input.startsWith("av", ignoreCase = true)) {
            val aid = input.substring(2).toLongOrNull()
                ?: throw BiliException("无法解析 av 号: $input")
            "$BASE_URL/x/web-interface/view?aid=$aid"
        } else {
            val bv = parseBvid(input) ?: input
            "$BASE_URL/x/web-interface/view?bvid=${encodeURIComponent(bv)}"
        }

        val json = JSONObject(request(url, null))
        requireCodeZero(json, "获取视频信息失败")

        val data = json.optJSONObject("data")
            ?: throw BiliException("无法获取视频信息：响应缺少 data")
        val aid = data.optLong("aid", 0L)
        val cid = data.optLong("cid", 0L)
        if (aid <= 0L || cid <= 0L) throw BiliException("无法获取视频信息：aid/cid 缺失")

        val owner = data.optJSONObject("owner")
        return BiliVideoInfo(
            bvid = data.optString("bvid", input),
            aid = aid,
            cid = cid,
            durationSec = data.optInt("duration", 0),
            title = data.optString("title", ""),
            pic = normalizePic(data.optString("pic", "")),
            author = owner?.optString("name", "") ?: ""
        )
    }

    // ================================================================= 弹幕

    /**
     * 下载全部弹幕：按每 6 分钟一段遍历 `seg.so`，合并后按时间排序并去重。
     *
     * 单个分段失败只记录日志并继续；分段之间随机等待 250-350ms 以避免触发风控。
     *
     * @throws BiliException 获取 WBI Keys 失败等致命错误
     */
    @Throws(BiliException::class)
    fun fetchDanmaku(video: BiliVideoInfo): List<DanmakuEntry> {
        val (imgKey, subKey) = getWbiKeys()
        val segmentCount = if (video.durationSec <= 0) {
            1
        } else {
            (video.durationSec + SEGMENT_SECONDS - 1) / SEGMENT_SECONDS
        }
        Log.i("开始下载弹幕: cid=${video.cid} aid=${video.aid} 共 $segmentCount 段")

        val all = ArrayList<DanmakuEntry>()
        for (index in 1..segmentCount) {
            try {
                val wts = System.currentTimeMillis() / 1000L
                val query = Wbi.encWbi(
                    mapOf(
                        "type" to 1,
                        "oid" to video.cid,
                        "segment_index" to index,
                        "pid" to video.aid,
                        "web_location" to 1315873,
                        "wts" to wts
                    ),
                    imgKey,
                    subKey,
                    wts
                )
                val bytes = requestBytes("$BASE_URL/x/v2/dm/wbi/web/seg.so?$query", null)
                val entries = DanmakuProto.parseSegment(bytes)
                Log.d("第 $index 段弹幕: ${entries.size} 条")
                all.addAll(entries)
            } catch (e: Throwable) {
                Log.w("第 $index 段弹幕获取失败，已跳过", e)
            }
            if (index < segmentCount) sleepQuietly(250L + (0..100).random())
        }

        val deduped = dedupe(all)
        Log.i("弹幕下载完成: 原始 ${all.size} 条，去重后 ${deduped.size} 条")
        return deduped
    }

    /** 通过 bvid（或 av 号）下载弹幕 */
    @Throws(BiliException::class)
    fun fetchDanmakuByBvid(bvid: String): List<DanmakuEntry> = fetchDanmaku(getVideoInfo(bvid))

    /** 按 `(timeMs, text)` 去重（保留首次出现的），并按时间升序排序 */
    private fun dedupe(list: List<DanmakuEntry>): List<DanmakuEntry> {
        val seen = HashSet<String>(list.size * 2)
        val out = ArrayList<DanmakuEntry>(list.size)
        for (item in list) {
            val key = item.timeMs.toString() + '\u0000' + item.text
            if (seen.add(key)) out.add(item)
        }
        out.sortBy { it.timeMs }
        return out
    }

    // ============================================================ 综合搜索

    /**
     * 综合搜索（`/x/web-interface/wbi/search/all/v2`），只取 `result_type == "video"` 的前 15 条。
     *
     * 命中 csrf / 关键词拦截时，会用 [TitleMatcher.removeBracketedSearchTerms] 清洗关键词重试一次。
     *
     * @param keyword 搜索关键词
     * @param matchKeyword 用于计算匹配度的关键词，默认同 [keyword]（即清洗前的原始标题）
     */
    @Throws(BiliException::class)
    fun searchAllV2(keyword: String, matchKeyword: String = keyword): List<BiliSearchResult> {
        val normalized = TitleMatcher.normalizeBilibiliSearchKeyword(keyword)
        if (normalized.isEmpty()) throw BiliException("搜索关键词为空")

        var json = signedGet("/x/web-interface/wbi/search/all/v2", mapOf("keyword" to normalized))
        if (isCsrfSearchBlock(json)) {
            val fallback = TitleMatcher.removeBracketedSearchTerms(keyword)
            if (fallback.isNotEmpty() && fallback != normalized) {
                Log.w("搜索被拦截，移除括号内容后重试: \"$normalized\" -> \"$fallback\"")
                json = signedGet("/x/web-interface/wbi/search/all/v2", mapOf("keyword" to fallback))
            }
        }
        requireCodeZero(json, "搜索失败")

        val results = ArrayList<BiliSearchResult>()
        val data = json.optJSONObject("data")
        val resultArr: JSONArray? = data?.optJSONArray("result")
        if (resultArr != null) {
            var videoArr: JSONArray? = null
            for (i in 0 until resultArr.length()) {
                val item = resultArr.optJSONObject(i) ?: continue
                if (item.optString("result_type", "") == "video") {
                    videoArr = item.optJSONArray("data")
                    break
                }
            }
            if (videoArr != null) {
                val limit = minOf(videoArr.length(), SEARCH_RESULT_LIMIT)
                for (i in 0 until limit) {
                    val video = videoArr.optJSONObject(i) ?: continue
                    results.add(parseSearchVideo(video, matchKeyword))
                }
            }
        }

        results.sortWith(compareByDescending { it.danmaku })
        Log.i("综合搜索 \"$normalized\" 命中 ${results.size} 条视频")
        return results
    }

    /** 解析单条搜索结果，并计算 `highlightRatio` */
    private fun parseSearchVideo(video: JSONObject, matchKeyword: String): BiliSearchResult {
        val rawTitle = video.optString("title", "")
        val cleanTitle = TitleMatcher.decodeHtmlEntities(TitleMatcher.stripHtml(rawTitle))
        val emRatio = TitleMatcher.calculateHighlightRatio(rawTitle)
        val keywordRatio = if (matchKeyword.isNotEmpty()) {
            TitleMatcher.calculateKeywordMatchRatio(matchKeyword, cleanTitle)
        } else {
            0f
        }
        val containmentRatio = TitleMatcher.calculateTitleContainmentRatio(matchKeyword, cleanTitle)
        val highlightRatio = maxOf(emRatio, keywordRatio, containmentRatio)

        val danmaku = firstPositive(
            video.optLong("danmaku", 0L),
            video.optLong("dm", 0L),
            video.optLong("video_review", 0L)
        )

        return BiliSearchResult(
            bvid = video.optString("bvid", ""),
            title = cleanTitle,
            author = video.optString("author", ""),
            mid = video.optLong("mid", 0L),
            pic = normalizePic(video.optString("pic", "")),
            play = video.optLong("play", 0L),
            danmaku = danmaku,
            durationSec = parseDurationSeconds(video.opt("duration")),
            pubdate = video.optLong("pubdate", 0L),
            highlightRatio = highlightRatio
        )
    }

    /** 判断是否为 csrf / 关键词拦截响应 */
    private fun isCsrfSearchBlock(json: JSONObject): Boolean {
        val code = json.optInt("code", 0)
        val message = json.optString("message", "")
        val lower = message.lowercase()
        val csrf = code == -111 || code == -412
        val keywordBlocked = lower.contains("csrf") || message.contains("关键词")
        return csrf || keywordBlocked
    }

    // ========================================================== YouTube 标题

    /**
     * 通过 YouTube oEmbed 获取原始（未翻译）标题。
     *
     * 任何失败（超时、非 200、无 title 字段）都返回 null，不抛异常。
     */
    fun fetchYouTubeTitle(videoId: String): String? {
        val id = videoId.trim()
        if (id.isEmpty()) return null
        val url = "https://www.youtube.com/oembed?url=" +
            URLEncoder.encode("https://www.youtube.com/watch?v=$id", "UTF-8") +
            "&format=json"
        return try {
            val body = request(
                url = url,
                body = null,
                referer = "https://www.youtube.com/",
                origin = null,
                timeoutMs = OEMBED_TIMEOUT_MS
            )
            val title = JSONObject(body).optString("title", "")
            title.ifEmpty { null }
        } catch (t: Throwable) {
            Log.w("获取 YouTube 原始标题失败: $id", t)
            null
        }
    }

    // ============================================================== bvid 解析

    /**
     * 从任意文本中解析视频标识。
     *
     * 支持：`BV1xx411c7mD`、`av12345`、完整 URL、B 站分享文本。
     * 只找到 av 号时返回 `"av" + 数字`，由 [getVideoInfo] 走 `?aid=` 分支。
     */
    fun parseBvid(input: String?): String? {
        val text = input?.trim().orEmpty()
        if (text.isEmpty()) return null
        BV_REGEX.find(text)?.let { return it.value }
        AV_REGEX.find(text)?.let { return "av" + it.groupValues[1] }
        return null
    }

    // ==================================================== internal 复用接口

    /**
     * 拼接可选的 WBI 签名查询串（供 [Bangumi] 复用）。
     *
     * @param base 绝对 URL 或 `/` 开头的路径
     * @param params 业务参数（同一个对象既参与签名也参与拼接）
     * @param wbi 是否做 WBI 签名
     */
    internal fun signedUrl(base: String, params: Map<String, Any?>, wbi: Boolean): String {
        val url = if (base.startsWith("http://") || base.startsWith("https://")) {
            base
        } else {
            BASE_URL + if (base.startsWith("/")) base else "/$base"
        }
        val query = if (wbi) {
            val (imgKey, subKey) = getWbiKeys()
            val wts = System.currentTimeMillis() / 1000L
            Wbi.encWbi(params, imgKey, subKey, wts)
        } else {
            params.entries
                .filter { it.value != null }
                .joinToString("&") {
                    encodeURIComponent(it.key) + "=" + encodeURIComponent(it.value.toString())
                }
        }
        if (query.isEmpty()) return url
        return if (url.contains('?')) "$url&$query" else "$url?$query"
    }

    /** 按 [signedUrl] 发起 GET 并返回响应体（供 [Bangumi] 复用） */
    @Throws(BiliException::class)
    internal fun getJson(url: String): String = request(url, null)

    /** WBI 签名的 GET，返回解析后的 JSON，并校验 `code == 0` */
    @Throws(BiliException::class)
    internal fun signedGet(path: String, params: Map<String, Any?>): JSONObject {
        val json = JSONObject(request(signedUrl(path, params, true), null))
        requireCodeZero(json, "请求失败")
        return json
    }

    // ================================================================ HTTP

    /** `code != 0` 时抛出 [BiliException] */
    private fun requireCodeZero(json: JSONObject, prefix: String) {
        val code = json.optInt("code", 0)
        if (code != 0) {
            val message = json.optString("message", "未知错误")
            throw BiliException("$prefix: $message (code=$code)")
        }
    }

    /** 封面/头像 URL 规范化：`//` 开头补 `https:` */
    private fun normalizePic(pic: String): String =
        if (pic.startsWith("//")) "https:$pic" else pic

    /** 时长字段兼容字符串与数字，并处理 `"12:34"` 形式 */
    private fun parseDurationSeconds(value: Any?): Int {
        return when (value) {
            is Number -> value.toInt()
            is String -> {
                value.toIntOrNull() ?: run {
                    val parts = value.split(':')
                    if (parts.isEmpty()) {
                        0
                    } else {
                        var total = 0
                        for (p in parts) total = total * 60 + (p.trim().toIntOrNull() ?: 0)
                        total
                    }
                }
            }
            else -> 0
        }
    }

    /** 取第一个大于 0 的候选值 */
    private fun firstPositive(vararg values: Long): Long {
        for (v in values) if (v > 0L) return v
        return 0L
    }

    /** 睡眠（随机抖动用），被中断时恢复中断标记且不抛出 */
    private fun sleepQuietly(millis: Long) {
        try {
            Thread.sleep(millis)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    /** 通用 GET/POST 文本请求 */
    private fun request(
        url: String,
        body: String?,
        referer: String = REFERER,
        origin: String? = ORIGIN,
        timeoutMs: Int = 0
    ): String {
        val connection = openConnection(url, body, referer, origin, timeoutMs)
        return try {
            val stream = readStream(connection)
            val bytes = readAll(stream)
            String(bytes, Charsets.UTF_8)
        } finally {
            try {
                connection.disconnect()
            } catch (_: Throwable) {
                // 忽略
            }
        }
    }

    /** 通用 GET/POST 二进制请求（用于弹幕 protobuf） */
    private fun requestBytes(url: String, body: String?): ByteArray {
        val connection = openConnection(url, body, REFERER, ORIGIN, 0)
        return try {
            val stream = readStream(connection)
            readAll(stream)
        } finally {
            try {
                connection.disconnect()
            } catch (_: Throwable) {
                // 忽略
            }
        }
    }

    /** 建立连接并配置请求头 */
    private fun openConnection(
        url: String,
        body: String?,
        referer: String,
        origin: String?,
        timeoutMs: Int
    ): HttpURLConnection {
        val connection = try {
            URL(url).openConnection() as HttpURLConnection
        } catch (t: Throwable) {
            throw BiliException("URL 无效: $url", t)
        }
        val connectTimeout = if (timeoutMs > 0) timeoutMs else connectTimeoutMs
        val readTimeout = if (timeoutMs > 0) timeoutMs else readTimeoutMs
        try {
            connection.connectTimeout = connectTimeout
            connection.readTimeout = readTimeout
            connection.instanceFollowRedirects = true
            connection.useCaches = false
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.setRequestProperty("Referer", referer)
            if (!origin.isNullOrEmpty()) connection.setRequestProperty("Origin", origin)
            connection.setRequestProperty("Accept", "*/*")
            connection.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
            if (gzipSupported) connection.setRequestProperty("Accept-Encoding", "gzip")
            val sess = sessData
            if (sess.isNotEmpty()) {
                connection.setRequestProperty("Cookie", "SESSDATA=$sess")
            }
            if (body != null) {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            } else {
                connection.requestMethod = "GET"
            }
            if (body != null) {
                connection.outputStream.use { out -> out.write(body.toByteArray(Charsets.UTF_8)) }
            }
        } catch (t: Throwable) {
            try {
                connection.disconnect()
            } catch (_: Throwable) {
                // 忽略
            }
            if (t is BiliException) throw t
            throw BiliException("建立连接失败: ${t.message}", t)
        }
        return connection
    }

    /** 根据响应码选择输入流，并处理 gzip 解压 */
    private fun readStream(connection: HttpURLConnection): InputStream {
        val code = try {
            connection.responseCode
        } catch (t: Throwable) {
            throw BiliException("读取响应码失败: ${t.message}", t)
        }
        if (code !in 200..299) {
            val message = try {
                val errorBytes = readAll(connection.errorStream)
                String(errorBytes, Charsets.UTF_8).take(300)
            } catch (_: Throwable) {
                ""
            }
            throw BiliException("HTTP $code: $message")
        }
        val raw = connection.inputStream ?: throw BiliException("响应流为空")
        val encoding = connection.contentEncoding?.lowercase() ?: ""
        return if (encoding.contains("gzip")) {
            gzipSupported = true
            try {
                GZIPInputStream(raw)
            } catch (t: Throwable) {
                throw BiliException("gzip 解压失败: ${t.message}", t)
            }
        } else {
            raw
        }
    }

    /** 读取输入流全部内容（带大小上限），始终关闭流 */
    private fun readAll(stream: InputStream?): ByteArray {
        if (stream == null) return ByteArray(0)
        return stream.use { input ->
            val out = ByteArrayOutputStream(16 * 1024)
            val chunk = ByteArray(16 * 1024)
            while (true) {
                val read = input.read(chunk)
                if (read < 0) break
                if (read == 0) continue
                out.write(chunk, 0, read)
                if (out.size() > MAX_BODY_BYTES) throw BiliException("响应体过大，已中断读取")
            }
            out.toByteArray()
        }
    }

    /** 等价于 JS 的 `encodeURIComponent`（见 [Wbi] 的说明） */
    private fun encodeURIComponent(value: String): String {
        val encoded = URLEncoder.encode(value, "UTF-8")
        return encoded
            .replace("+", "%20")
            .replace("%2A", "*")
            .replace("%21", "!")
            .replace("%27", "'")
            .replace("%28", "(")
            .replace("%29", ")")
            .replace("%7E", "~")
    }
}
