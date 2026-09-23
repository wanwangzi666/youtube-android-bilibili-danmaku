package com.b2y.danmaku.bili

/**
 * 标题清洗与相似度计算。
 *
 * 逐函数对齐浏览器扩展参考实现 `entrypoints/background/index.js`：
 * `calculateKeywordMatchRatio` / `calculateTitleContainmentRatio` / `calculateHighlightRatio` /
 * `cleanVideoTitle` / `getBestTitlePart` / `selectBestPart` / `isPureEnglishOrNumber` /
 * `normalizeBilibiliSearchKeyword` / `removeBracketedSearchTerms`。
 *
 * 本文件不引用任何 `android.*`，可直接在普通 JVM 单元测试中运行。
 */
object TitleMatcher {

    // ------------------------------------------------------------ 基础工具

    /** 解码常见 HTML 实体（`&quot;`/`&amp;`/`&lt;`/`&gt;`/`&nbsp;`/`&#NNN;`） */
    fun decodeHtmlEntities(s: String): String {
        var out = s
            .replace("&quot;", "\"")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&nbsp;", " ")
        out = NUMERIC_ENTITY.replace(out) { m ->
            val code = m.groupValues[1].toIntOrNull()
            if (code == null || code <= 0 || code > 0x10FFFF) "" else String(Character.toChars(code))
        }
        // 与 JS 参考实现一致：只做一轮，不递归处理 &amp;quot; 之类
        return out
    }

    /** 去掉所有 HTML 标签 */
    fun stripHtml(s: String): String = HTML_TAG.replace(s, "")

    /** 去掉所有非字母/数字字符（等价 JS `replace(/[^\p{L}\p{N}]/gu, '')`） */
    private fun removeNonText(s: String): String {
        val sb = StringBuilder(s.length)
        for (ch in s) {
            if (Character.isLetterOrDigit(ch)) sb.append(ch)
        }
        return sb.toString()
    }

    /** 判断文本是否为纯英文/数字（允许空格，先剥离标点符号） */
    fun isPureEnglishOrNumber(text: String?): Boolean {
        if (text.isNullOrEmpty()) return false
        val sb = StringBuilder(text.length)
        for (ch in text) {
            if (Character.isLetterOrDigit(ch) || ch.isWhitespace()) sb.append(ch)
        }
        if (sb.isBlank()) return false
        for (ch in sb) {
            val ascii = ch.code in 0x30..0x39 || ch.code in 0x41..0x5A || ch.code in 0x61..0x7A
            if (!ascii && !ch.isWhitespace()) return false
        }
        return true
    }

    /** 去掉结尾的英文字符（仅当去掉后仍有内容时才去掉） */
    fun removeTrailingEnglish(text: String?): String? {
        if (text.isNullOrEmpty()) return text
        val match = TRAILING_ENGLISH.find(text) ?: return text
        val withoutTrailing = text.substring(0, match.range.first).trim()
        return if (withoutTrailing.isNotEmpty()) withoutTrailing else text
    }

    // ------------------------------------------------------------ 标题清洗

    /**
     * 清理视频标题：
     * 1. 去掉 `【UP主名】`；
     * 2. 去掉末尾的 `#tag`（连续多个也一并去掉）；
     * 3. 合并多余空格并 trim。
     *
     * 清理后为空则返回 `title.trim()`。
     */
    fun cleanVideoTitle(title: String): String {
        if (title.isEmpty()) return title
        var cleaned = title
        cleaned = cleaned.replace(BRACKET_CN, "")
        cleaned = cleaned.replace(TRAILING_TAG, "")
        cleaned = cleaned.replace(WHITESPACE, " ").trim()
        if (cleaned.isEmpty()) return title.trim()
        return cleaned
    }

    /**
     * 按 `｜` / `|` / 空格切分后挑选最合适的部分。
     *
     * 如果切分后没有多个部分，则原样返回 [title]（与参考实现一致）。
     */
    fun getBestTitlePart(title: String): String {
        if (title.isEmpty()) return title
        val parts = TITLE_SEPARATOR_PLAIN.split(title).map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.size <= 1) return title
        return selectBestPart(parts)
    }

    /** 从多个候选中挑选：优先非纯英文数字中最长的，否则取最长。 */
    fun selectBestPart(parts: List<String>): String {
        if (parts.isEmpty()) return ""
        if (parts.size == 1) return parts[0]
        val nonPure = parts.filter { !isPureEnglishOrNumber(it) }
        if (nonPure.isNotEmpty()) return nonPure.maxByOrNull { it.length } ?: nonPure[0]
        return parts.maxByOrNull { it.length } ?: parts[0]
    }

    // -------------------------------------------------------- 搜索词规范化

    /** 把标点替换为空格并压缩空白，优化 B 站搜索 */
    fun normalizeBilibiliSearchKeyword(keyword: String?): String {
        return (keyword ?: "")
            .replace(PUNCTUATION, " ")
            .replace(WHITESPACE, " ")
            .trim()
    }

    /** 移除各类括号内容（用于关键词被拦截时重试） */
    fun removeBracketedSearchTerms(keyword: String?): String {
        var s = keyword ?: ""
        for (re in BRACKET_PAIRS) s = re.replace(s, " ")
        return normalizeBilibiliSearchKeyword(s)
    }

    // ---------------------------------------------------------- 相似度计算

    /**
     * 最长公共子序列长度占 [sourceTitle] 有效字符数的比例，上限 1。
     *
     * 有效字符 = 去掉非字母数字后的文本。
     */
    fun calculateKeywordMatchRatio(sourceTitle: String, targetTitle: String): Float {
        val sourceText = removeNonText(sourceTitle)
        val targetText = removeNonText(targetTitle)
        if (sourceText.isEmpty() || targetText.isEmpty()) return 0f
        val matchLength = lcsLength(sourceText, targetText)
        return minOf(matchLength.toFloat() / sourceText.length.toFloat(), 1f)
    }

    /**
     * 包含关系占比：目标标题（含按分隔符切分后的片段）包含源标题则 1，否则 0。
     */
    fun calculateTitleContainmentRatio(sourceTitle: String, targetTitle: String): Float {
        val sourceText = removeNonText(sourceTitle)
        val targetText = removeNonText(targetTitle)
        if (sourceText.isEmpty() || targetText.isEmpty()) return 0f
        if (targetText.contains(sourceText)) return 1f
        for (segment in splitTitleSegments(targetTitle)) {
            val segmentText = removeNonText(segment)
            if (segmentText.isNotEmpty() && segmentText.contains(sourceText)) return 1f
        }
        return 0f
    }

    /** 按 `|`/`｜`/`丨`、`-`、`:` 切分标题片段 */
    fun splitTitleSegments(title: String): List<String> {
        return TITLE_SEGMENT_SPLIT.split(title).map { it.trim() }.filter { it.isNotEmpty() }
    }

    /**
     * B 站搜索结果 `<em>` 高亮占比。
     *
     * 规则与参考实现一致：
     * - 高亮长度 = 所有 `<em>` 内文本（去标签、解码实体后）的字母数字个数；
     * - 总长度 = 去标签、解码实体后的纯文本，**截断前 26 个字符**，再取字母数字个数；
     * - 结果上限 1。
     */
    fun calculateHighlightRatio(htmlTitle: String): Float {
        if (htmlTitle.isEmpty()) return 0f
        var highlightLength = 0
        val matcher = EM_TAG.findAll(htmlTitle)
        for (m in matcher) {
            val raw = m.groupValues.getOrNull(1) ?: ""
            val inner = decodeHtmlEntities(HTML_TAG.replace(raw, ""))
            highlightLength += removeNonText(inner).length
        }

        val plainTextFull = decodeHtmlEntities(HTML_TAG.replace(htmlTitle, ""))
        val truncated = plainTextFull.take(26)
        val totalLength = removeNonText(truncated).length
        if (totalLength <= 0) return 0f
        return minOf(highlightLength.toFloat() / totalLength.toFloat(), 1f)
    }

    /** 最长公共子序列长度（滚动数组，空间 O(min(a,b))） */
    private fun lcsLength(a: String, b: String): Int {
        if (a.isEmpty() || b.isEmpty()) return 0
        val n = b.length
        val previous = IntArray(n + 1)
        val current = IntArray(n + 1)
        for (i in 1..a.length) {
            for (j in 1..n) {
                current[j] = if (a[i - 1] == b[j - 1]) {
                    previous[j - 1] + 1
                } else {
                    if (previous[j] >= current[j - 1]) previous[j] else current[j - 1]
                }
            }
            System.arraycopy(current, 0, previous, 0, n + 1)
            java.util.Arrays.fill(current, 0)
        }
        return previous[n]
    }

    // ------------------------------------------------------------- 正则常量

    private val NUMERIC_ENTITY = Regex("""&#(\d+);""")
    private val HTML_TAG = Regex("""<[^>]*>""")
    private val EM_TAG = Regex("""<em[^>]*>([^<]*)</em>""")
    private val BRACKET_CN = Regex("""【[^】]*】""")
    private val TRAILING_TAG = Regex("""\s*#[^\s#]+(\s*#[^\s#]+)*\s*$""")
    private val WHITESPACE = Regex("""\s+""")
    private val TITLE_SEPARATOR_PLAIN = Regex("""[｜|\s]+""")
    private val TITLE_SEGMENT_SPLIT = Regex("""\s*(?:[|｜丨]|-{1,2}|:|：)\s*""")
    private val PUNCTUATION = Regex("""\p{P}""")
    private val TRAILING_ENGLISH = Regex("""[a-zA-Z0-9\s.,!?\-_'"():;]+$""")

    private val BRACKET_PAIRS = listOf(
        Regex("""【[^】]*】"""),
        Regex("""「[^」]*」"""),
        Regex("""『[^』]*』"""),
        Regex("""《[^》]*》"""),
        Regex("""〈[^〉]*〉"""),
        Regex("""（[^）]*）"""),
        Regex("""\([^)]*\)"""),
        Regex("""\[[^\]]*\]"""),
        Regex("""［[^］]*］"""),
        Regex("""〔[^〕]*〕"""),
        Regex("""\{[^}]*\}""")
    )
}
