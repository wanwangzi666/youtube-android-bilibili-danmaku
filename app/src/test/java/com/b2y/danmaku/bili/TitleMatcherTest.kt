package com.b2y.danmaku.bili

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [TitleMatcher] 单元测试。
 *
 * 覆盖标题清洗、最佳片段选择、包含/高亮占比、以及 B 站搜索关键词规范化。
 */
class TitleMatcherTest {

    // ---------------------------------------------------- cleanVideoTitle

    @Test
    fun cleanVideoTitle_removesUpNameBracket() {
        assertEquals("测试视频", TitleMatcher.cleanVideoTitle("【某UP主】测试视频"))
    }

    @Test
    fun cleanVideoTitle_removesTrailingTags() {
        assertEquals("测试视频", TitleMatcher.cleanVideoTitle("测试视频 #vlog #日常"))
    }

    @Test
    fun cleanVideoTitle_collapsesWhitespaceAndTrims() {
        assertEquals("A B", TitleMatcher.cleanVideoTitle("  A    B  "))
    }

    @Test
    fun cleanVideoTitle_returnsTrimmedOriginalWhenEverythingRemoved() {
        assertEquals("【UP主】", TitleMatcher.cleanVideoTitle("【UP主】"))
    }

    @Test
    fun cleanVideoTitle_emptyInputReturnsEmpty() {
        assertEquals("", TitleMatcher.cleanVideoTitle(""))
    }

    // ---------------------------------------------------- getBestTitlePart

    @Test
    fun getBestTitlePart_picksLongestNonEnglishPart() {
        assertEquals(
            "这是一个很长的中文标题",
            TitleMatcher.getBestTitlePart("这是一个很长的中文标题 ｜ short title")
        )
    }

    @Test
    fun getBestTitlePart_fallsBackToLongestWhenAllPartsArePureEnglish() {
        // 所有片段都是纯英文数字时，退化为取最长片段（按空格切分，故取单词）
        assertEquals("hello", TitleMatcher.getBestTitlePart("hello world | abc"))
        assertEquals("abcdef", TitleMatcher.getBestTitlePart("hello | abcdef"))
    }

    @Test
    fun getBestTitlePart_returnsOriginalWhenNoSeparator() {
        assertEquals("没有分隔符的标题", TitleMatcher.getBestTitlePart("没有分隔符的标题"))
    }

    @Test
    fun getBestTitlePart_splitsOnSpacesToo() {
        assertEquals("中文标题比较长", TitleMatcher.getBestTitlePart("[MV] 中文标题比较长"))
    }

    // --------------------------------------- calculateTitleContainmentRatio

    @Test
    fun containmentRatio_returnsOneWhenTargetContainsSource() {
        assertEquals(
            1f,
            TitleMatcher.calculateTitleContainmentRatio("测试", "这是一个测试视频"),
            0.0001f
        )
    }

    @Test
    fun containmentRatio_ignoresNonLetterAndDigit() {
        assertEquals(
            1f,
            TitleMatcher.calculateTitleContainmentRatio("测试视频", "【测试】视频 123"),
            0.0001f
        )
    }

    @Test
    fun containmentRatio_returnsOneWhenSegmentContainsSource() {
        assertEquals(
            1f,
            TitleMatcher.calculateTitleContainmentRatio("中文标题", "abc - 中文标题 - def")
        )
    }

    @Test
    fun containmentRatio_returnsZeroWhenNoOverlap() {
        assertEquals(
            0f,
            TitleMatcher.calculateTitleContainmentRatio("完全不同的词", "Another Video"),
            0.0001f
        )
    }

    @Test
    fun containmentRatio_returnsZeroForBlankInput() {
        assertEquals(0f, TitleMatcher.calculateTitleContainmentRatio("", "abc"), 0.0001f)
        assertEquals(0f, TitleMatcher.calculateTitleContainmentRatio("abc", ""), 0.0001f)
    }

    // ----------------------------------------- calculateKeywordMatchRatio

    @Test
    fun keywordMatchRatio_isOneForIdenticalText() {
        assertEquals(
            1f,
            TitleMatcher.calculateKeywordMatchRatio("测试视频", "测试视频"),
            0.0001f
        )
    }

    @Test
    fun keywordMatchRatio_usesLcsLengthOverSourceLength() {
        // "abcd" 与 "abxd" 的 LCS 为 "abd"（长度 3），3/4 = 0.75
        assertEquals(
            0.75f,
            TitleMatcher.calculateKeywordMatchRatio("abcd", "abxd"),
            0.0001f
        )
    }

    @Test
    fun keywordMatchRatio_isCappedAtOne() {
        assertEquals(
            1f,
            TitleMatcher.calculateKeywordMatchRatio("ab", "abcdefgh"),
            0.0001f
        )
    }

    // ---------------------------------------- calculateHighlightRatio

    @Test
    fun highlightRatio_computesEmOverTruncatedPlainText() {
        // 高亮 2 字 / 总 8 字
        val ratio = TitleMatcher.calculateHighlightRatio("<em>测试</em>视频标题内容")
        assertEquals(0.25f, ratio, 0.0001f)
    }

    @Test
    fun highlightRatio_decodesHtmlEntities() {
        // &quot; 解码为引号，引号不是字母数字，不计入长度
        val ratio = TitleMatcher.calculateHighlightRatio("<em>&quot;测试&quot;</em>视频")
        // 高亮 2 字 / 总 4 字
        assertEquals(0.5f, ratio, 0.0001f)
    }

    @Test
    fun highlightRatio_numericEntitiesAreDecoded() {
        // &#65; => 'A'，是字母，计入长度
        val ratio = TitleMatcher.calculateHighlightRatio("<em>&#65;</em>BBBB")
        // 高亮 1 / 总 5
        assertEquals(0.2f, ratio, 0.0001f)
    }

    @Test
    fun highlightRatio_truncatesPlainTextAt26Chars() {
        // 前 26 个字符里只有 4 个是字母数字，<em> 只高亮了这 4 个 -> 1.0
        // （注意：参考实现用 \p{L}\p{N} 判断，中文属于字母，因此这里必须用真正的标点）
        val longTitle = "<em>ABCD</em>" + "!".repeat(60)
        assertEquals(1f, TitleMatcher.calculateHighlightRatio(longTitle), 0.0001f)

        // 高亮部分完全落在 26 字符截断之外 -> 不计入，总长度也为 0 -> 0
        val beyondTruncation = "!".repeat(40) + "<em>ABCD</em>"
        assertEquals(0f, TitleMatcher.calculateHighlightRatio(beyondTruncation), 0.0001f)
    }

    @Test
    fun highlightRatio_returnsZeroWithoutEmTag() {
        assertEquals(0f, TitleMatcher.calculateHighlightRatio("普通标题"), 0.0001f)
    }

    @Test
    fun highlightRatio_isCappedAtOne() {
        // 高亮部分落在 26 字截断之外时，比例仍不超过 1
        val ratio = TitleMatcher.calculateHighlightRatio("<em>被截断的高亮文本</em>")
        assertTrue(ratio <= 1f)
    }

    @Test
    fun highlightRatio_emptyInputReturnsZero() {
        assertEquals(0f, TitleMatcher.calculateHighlightRatio(""), 0.0001f)
    }

    @Test
    fun highlightRatio_onlyCountsCharactersBeforeThe26CharCutoff() {
        // 前 26 个字符: "ABCD" + 22 个中文；其后的 500 个 "A" 不参与分母
        val title = "<em>ABCD</em>" + "中文标题测试内容片段".repeat(4) + "A".repeat(500)
        val plain = TitleMatcher.decodeHtmlEntities(TitleMatcher.stripHtml(title))
        assertEquals(26, plain.take(26).length)
        assertEquals(4f / 26f, TitleMatcher.calculateHighlightRatio(title), 0.0001f)
    }

    // ------------------------------------------ decodeHtmlEntities / stripHtml

    @Test
    fun decodeHtmlEntities_handlesCommonEntities() {
        assertEquals(
            "\"a&b<c>d e",
            TitleMatcher.decodeHtmlEntities("&quot;a&amp;b&lt;c&gt;d&nbsp;e")
        )
    }

    @Test
    fun stripHtml_removesTagsAndKeepsText() {
        assertEquals("hello world", TitleMatcher.stripHtml("<em>hello</em> <b>world</b>"))
    }

    // --------------------------------- normalizeBilibiliSearchKeyword

    @Test
    fun normalizeSearchKeyword_replacesPunctuationWithSpace() {
        assertEquals(
            "测试 视频",
            TitleMatcher.normalizeBilibiliSearchKeyword("测试，视频")
        )
    }

    @Test
    fun normalizeSearchKeyword_collapsesWhitespaceAndTrims() {
        assertEquals(
            "A B C",
            TitleMatcher.normalizeBilibiliSearchKeyword("  A...B   C  ")
        )
    }

    @Test
    fun normalizeSearchKeyword_keepsLettersAndDigits() {
        assertEquals(
            "abc123 中文",
            TitleMatcher.normalizeBilibiliSearchKeyword("abc123 中文")
        )
    }

    @Test
    fun normalizeSearchKeyword_nullSafe() {
        assertEquals("", TitleMatcher.normalizeBilibiliSearchKeyword(null))
    }

    // --------------------------------- removeBracketedSearchTerms

    @Test
    fun removeBracketedSearchTerms_removesAllBracketKinds() {
        assertEquals(
            "标题 内容",
            TitleMatcher.removeBracketedSearchTerms("【前缀】标题「引」内容(括号)")
        )
    }

    @Test
    fun removeBracketedSearchTerms_handlesChineseBookBrackets() {
        assertEquals(
            "第 话",
            TitleMatcher.removeBracketedSearchTerms("《番剧名》第[01]话")
        )
    }

    @Test
    fun removeBracketedSearchTerms_canProduceEmptyString() {
        assertEquals("", TitleMatcher.removeBracketedSearchTerms("【只有括号】"))
    }

    // ------------------------------------------------ isPureEnglishOrNumber

    @Test
    fun isPureEnglishOrNumber_detectsAsciiOnly() {
        assertTrue(TitleMatcher.isPureEnglishOrNumber("Hello World 123"))
        assertTrue(TitleMatcher.isPureEnglishOrNumber("[MV] Hello!"))
        assertFalse(TitleMatcher.isPureEnglishOrNumber("中文标题"))
        assertFalse(TitleMatcher.isPureEnglishOrNumber("!!!"))
        assertFalse(TitleMatcher.isPureEnglishOrNumber(""))
    }

    // ------------------------------------------------ removeTrailingEnglish

    @Test
    fun removeTrailingEnglish_stripsTrailingAsciiWhenContentRemains() {
        assertEquals("中文标题", TitleMatcher.removeTrailingEnglish("中文标题 (Official Video)"))
    }

    @Test
    fun removeTrailingEnglish_keepsTextWhenNothingLeftAfterStrip() {
        assertEquals("Official Video", TitleMatcher.removeTrailingEnglish("Official Video"))
    }
}
