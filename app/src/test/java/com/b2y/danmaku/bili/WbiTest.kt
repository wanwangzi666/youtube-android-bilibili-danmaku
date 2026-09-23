package com.b2y.danmaku.bili

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [Wbi] 单元测试。
 *
 * 重点验证与浏览器扩展参考实现完全一致的语义：MD5 结果、mixinKey 重排、
 * 待签名字符串排序与字符过滤、以及最终签名查询串的形态。
 */
class WbiTest {

    // ---------------------------------------------------------------- MD5

    @Test
    fun md5_emptyString_matchesKnownVector() {
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", Wbi.md5(""))
    }

    @Test
    fun md5_abc_matchesKnownVector() {
        assertEquals("900150983cd24fb0d6963f7d28e17f72", Wbi.md5("abc"))
    }

    @Test
    fun md5_multibyteUtf8_isUtf8Encoded() {
        // "中文" 的 UTF-8 MD5
        assertEquals("a7bac2239fcdcb3a067903d8077c4a07", Wbi.md5("中文"))
    }

    @Test
    fun md5_longInput_spansMultipleBlocks() {
        // 长度超过 56 字节，会触发两轮 block 处理
        val input = "a".repeat(200)
        assertEquals("887f30b43b2867f4a9accceee7d16e6c", Wbi.md5(input))
        assertEquals(Wbi.md5(input), Wbi.md5(input))
    }

    @Test
    fun md5_paddingBoundaryAt55And56Bytes() {
        // 55 字节仍需补 1 个 block；56 字节必须补 2 个 block（否则长度字段越界）
        assertEquals("ef1772b6dff9a122358552954ad0df65", Wbi.md5("a".repeat(55)))
        assertEquals("3b0c8ac703f828b04c6c197006d17218", Wbi.md5("a".repeat(56)))
        assertEquals("652b906d60af96844ebd21b674f35e93", Wbi.md5("a".repeat(57)))
        assertEquals("014842d480b571495a4a0363793f7367", Wbi.md5("a".repeat(64)))
    }

    // ----------------------------------------------------------- mixinKey

    @Test
    fun getMixinKey_reordersByTableAndTruncatesTo32() {
        // 逐位构建 64 个不同字符（'0'..'9' + 'a'..'z' + 'A'..'Z' + "+-"），便于精确断言下标重排
        val alphabet = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ+-"
        val subKey = alphabet.substring(32)
        val mixinKey = Wbi.getMixinKey(alphabet, subKey)

        assertEquals(32, mixinKey.length)
        // 表的前 16 项: 46,47,18,2,53,8,23,32,15,50,10,31,58,3,45,35
        assertEquals("KLi2R8nwfOavW3Jz", mixinKey.substring(0, 16))
    }

    @Test
    fun getMixinKey_shortOrig_doesNotThrowAndIsShorter() {
        val mixinKey = Wbi.getMixinKey("a", "b")
        assertEquals(2, mixinKey.length)
        assertEquals("ab", mixinKey)
    }

    // ---------------------------------------------------------- buildQuery

    @Test
    fun buildQuery_sortsKeysAndAppendsWts() {
        val query = Wbi.buildQuery(mapOf("type" to 1, "oid" to 12345L, "segment_index" to 2), 1700000000L)
        assertEquals("oid=12345&segment_index=2&type=1&wts=1700000000", query)
    }

    @Test
    fun buildQuery_dropsChrFilterAndNullValues() {
        val query = Wbi.buildQuery(
            mapOf(
                "keyword" to "a!b'c(d)e*f",
                "order" to "dm",
                "missing" to null
            ),
            1700000000L
        )
        assertEquals("keyword=abcdef&order=dm&wts=1700000000", query)
        assertFalse(query.contains("missing"))
        assertFalse(query.contains("!"))
        assertFalse(query.contains("'"))
        assertFalse(query.contains("("))
        assertFalse(query.contains(")"))
        assertFalse(query.contains("*"))
    }

    @Test
    fun buildQuery_encodesNothingAndKeepsRawSpaces() {
        // buildQuery 明确不做 URL 编码，便于测试与复用
        val query = Wbi.buildQuery(mapOf("keyword" to "hello world"), 1L)
        assertEquals("keyword=hello world&wts=1", query)
    }

    // ------------------------------------------------------------- encWbi

    @Test
    fun encWbi_containsQueryWtsAndWrid() {
        val imgKey = "7cd084941338484aae1ad9425b84077c"
        val subKey = "4932caff0ff746eab6f01bf08b70ac45"
        val signed = Wbi.encWbi(
            mapOf("type" to 1, "oid" to 987654321L, "segment_index" to 1),
            imgKey,
            subKey,
            1700000000L
        )

        assertTrue(signed.startsWith("oid=987654321&segment_index=1&type=1&wts=1700000000&w_rid="))
        assertTrue(signed.contains("wts=1700000000"))
        assertTrue(signed.contains("w_rid="))
        val wRid = signed.substringAfter("w_rid=")
        assertEquals(32, wRid.length)
        assertTrue(wRid.all { it in "0123456789abcdef" })
    }

    @Test
    fun encWbi_isDeterministicForSameInputs() {
        val params = mapOf("keyword" to "测试 标题", "order" to "dm")
        val first = Wbi.encWbi(params, "img", "sub", 1700000000L)
        val second = Wbi.encWbi(params, "img", "sub", 1700000000L)
        assertEquals(first, second)
    }

    @Test
    fun encWbi_signatureMatchesWridOfQueryPlusMixinKey() {
        val imgKey = "7cd084941338484aae1ad9425b84077c"
        val subKey = "4932caff0ff746eab6f01bf08b70ac45"
        val signed = Wbi.encWbi(
            mapOf("type" to 1, "oid" to 987654321L, "segment_index" to 1),
            imgKey,
            subKey,
            1700000000L
        )

        // 与参考实现（Node/JS encWbi）逐字节一致
        assertEquals(
            "oid=987654321&segment_index=1&type=1&wts=1700000000&w_rid=77ea5f2d7dbc4de3b5ddac8d5a94a251",
            signed
        )
        val query = signed.substringBefore("&w_rid=")
        assertEquals(Wbi.md5(query + Wbi.getMixinKey(imgKey, subKey)), signed.substringAfter("w_rid="))
    }

    @Test
    fun encWbi_spaceBecomesPercent20AndKeepsUnreservedChars() {
        val signed = Wbi.encWbi(mapOf("keyword" to "a b'c~d-e_f.g"), "k", "k", 1L)
        // 空格 -> %20；' 已被 chr_filter 过滤；~ - _ . 保持原样
        assertTrue(signed.startsWith("keyword=a%20bc~d-e_f.g&wts=1&w_rid="))
    }

    @Test
    fun encWbi_chineseKeywordIsPercentEncodedAsUtf8() {
        val signed = Wbi.encWbi(mapOf("keyword" to "测试 标题", "order" to "dm"), "img", "sub", 1700000000L)
        assertTrue(
            signed.startsWith("keyword=%E6%B5%8B%E8%AF%95%20%E6%A0%87%E9%A2%98&order=dm&wts=1700000000&w_rid=")
        )
    }
}
