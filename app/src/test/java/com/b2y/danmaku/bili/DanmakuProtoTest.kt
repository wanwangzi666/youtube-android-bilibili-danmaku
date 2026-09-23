package com.b2y.danmaku.bili

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater

/**
 * [DanmakuProto] 单元测试。
 *
 * 手工构造 protobuf 载荷，验证字段解析、未知字段跳过、异常数据不抛异常等行为。
 */
class DanmakuProtoTest {

    // ------------------------------------------------------------ 构造工具

    /** 编码 varint */
    private fun varint(value: Long): ByteArray {
        var v = value
        val out = ByteArrayOutputStream()
        while (true) {
            if (v and 0x7FL.inv() == 0L) {
                out.write(v.toInt() and 0x7F)
                break
            }
            out.write(((v and 0x7F) or 0x80).toInt())
            v = v ushr 7
        }
        return out.toByteArray()
    }

    /** 编码 tag（字段号 + wire type） */
    private fun tag(fieldNumber: Int, wireType: Int): ByteArray =
        varint(((fieldNumber.toLong() shl 3) or wireType.toLong()))

    /** 编码 varint 字段 */
    private fun fieldVarint(fieldNumber: Int, value: Long): ByteArray =
        tag(fieldNumber, 0) + varint(value)

    /** 编码 length-delimited 字段 */
    private fun fieldBytes(fieldNumber: Int, payload: ByteArray): ByteArray =
        tag(fieldNumber, 2) + varint(payload.size.toLong()) + payload

    /** 编码字符串字段 */
    private fun fieldString(fieldNumber: Int, value: String): ByteArray =
        fieldBytes(fieldNumber, value.toByteArray(Charsets.UTF_8))

    /** 编码 fixed32 字段（用于验证被安全跳过） */
    private fun fieldFixed32(fieldNumber: Int, value: Int): ByteArray =
        tag(fieldNumber, 5) + byteArrayOf(
            (value and 0xFF).toByte(),
            ((value ushr 8) and 0xFF).toByte(),
            ((value ushr 16) and 0xFF).toByte(),
            ((value ushr 24) and 0xFF).toByte()
        )

    /** 编码 fixed64 字段（用于验证被安全跳过） */
    private fun fieldFixed64(fieldNumber: Int, value: Long): ByteArray =
        tag(fieldNumber, 1) + ByteArray(8) { idx -> ((value ushr (8 * idx)) and 0xFF).toByte() }

    /** 构造一条 DanmakuElem */
    private fun elem(
        progress: Long = 1000L,
        mode: Int = 1,
        fontSize: Int = 25,
        color: Long = 0xFFFFFFL,
        content: String? = "测试弹幕",
        weight: Int = 5,
        extra: ByteArray = ByteArray(0)
    ): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(fieldVarint(1, 123456789L))            // id
        out.write(fieldVarint(2, progress))              // progress
        out.write(fieldVarint(3, mode.toLong()))         // mode
        out.write(fieldVarint(4, fontSize.toLong()))     // fontsize
        out.write(fieldVarint(5, color))                 // color
        out.write(fieldString(6, "abcdef0123456789"))    // midHash
        if (content != null) out.write(fieldString(7, content)) // content
        out.write(fieldVarint(8, 1700000000L))           // ctime
        out.write(fieldVarint(9, weight.toLong()))       // weight
        out.write(fieldString(10, "123456789"))          // idStr
        out.write(fieldVarint(11, 0L))                   // attr
        out.write(fieldString(12, ""))                   // action
        out.write(extra)
        return out.toByteArray()
    }

    /** 构造顶层 DmSegMobileReply */
    private fun segment(vararg elems: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        for (e in elems) out.write(fieldBytes(1, e))
        return out.toByteArray()
    }

    /** zlib 压缩 */
    private fun zlib(data: ByteArray): ByteArray {
        val deflater = Deflater()
        deflater.setInput(data)
        deflater.finish()
        val out = ByteArrayOutputStream()
        val chunk = ByteArray(1024)
        while (!deflater.finished()) {
            val n = deflater.deflate(chunk)
            out.write(chunk, 0, n)
        }
        deflater.end()
        return out.toByteArray()
    }

    // ---------------------------------------------------------------- 测试

    @Test
    fun parseSegment_parsesAllRequiredFields() {
        val payload = segment(elem(progress = 12345L, mode = 4, fontSize = 18, color = 0xFF0000L, content = "红色底部", weight = 7))

        val result = DanmakuProto.parseSegment(payload)

        assertEquals(1, result.size)
        val entry = result[0]
        assertEquals(12345L, entry.timeMs)
        assertEquals("红色底部", entry.text)
        assertEquals(0xFF0000, entry.color)
        assertEquals(4, entry.mode)
        assertEquals(18, entry.fontSize)
        assertEquals(7, entry.weight)
    }

    @Test
    fun parseSegment_parsesMultipleElemsInOrder() {
        val payload = segment(
            elem(progress = 100L, content = "第一条"),
            elem(progress = 200L, content = "第二条", mode = 5),
            elem(progress = 300L, content = "第三条", mode = 4)
        )

        val result = DanmakuProto.parseSegment(payload)

        assertEquals(3, result.size)
        assertEquals(listOf("第一条", "第二条", "第三条"), result.map { it.text })
        assertEquals(listOf(100L, 200L, 300L), result.map { it.timeMs })
        assertEquals(listOf(1, 5, 4), result.map { it.mode })
    }

    @Test
    fun parseSegment_keepsZeroWeightAsIs() {
        val payload = segment(elem(weight = 0, content = "权重为零"))

        val result = DanmakuProto.parseSegment(payload)

        assertEquals(1, result.size)
        assertEquals(0, result[0].weight)
    }

    @Test
    fun parseSegment_filtersBlankContent() {
        val payload = segment(
            elem(content = "   "),
            elem(content = ""),
            elem(content = "有效弹幕"),
            elem(content = null)
        )

        val result = DanmakuProto.parseSegment(payload)

        assertEquals(1, result.size)
        assertEquals("有效弹幕", result[0].text)
    }

    @Test
    fun parseSegment_colorIsMaskedTo24Bits() {
        // 协议里 color 是 uint32，可能带上 alpha 或越界值
        val payload = segment(elem(color = 0xFF123456L, content = "颜色"))

        val result = DanmakuProto.parseSegment(payload)

        assertEquals(1, result.size)
        assertEquals(0x123456, result[0].color)
    }

    @Test
    fun parseSegment_skipsUnknownAndOtherWireTypes() {
        val extra = ByteArray(0) +
            fieldVarint(63, 999L) +                                  // 未知 varint 字段
            fieldFixed32(64, 0x12345678) +                           // 未知 fixed32
            fieldFixed64(65, 0x1122334455667788L) +                  // 未知 fixed64
            fieldString(66, "未知字符串") +                            // 未知 length-delimited
            fieldBytes(67, byteArrayOf(0x01, 0x02, 0x03))
        val payload = segment(elem(content = "带未知字段", extra = extra))

        val result = DanmakuProto.parseSegment(payload)

        assertEquals(1, result.size)
        assertEquals("带未知字段", result[0].text)
    }

    @Test
    fun parseSegment_skipsTopLevelUnknownFields() {
        val payload = fieldVarint(2, 42L) + segment(elem(content = "顶层未知字段")) + fieldString(3, "x")

        val result = DanmakuProto.parseSegment(payload)

        assertEquals(1, result.size)
        assertEquals("顶层未知字段", result[0].text)
    }

    @Test
    fun parseSegment_truncatedBufferDoesNotThrow() {
        val full = segment(
            elem(progress = 1L, content = "第一条"),
            elem(progress = 2L, content = "第二条"),
            elem(progress = 3L, content = "第三条")
        )

        // 在每一个可能的截断点都不应抛异常
        for (cut in 0..full.size) {
            val partial = full.copyOfRange(0, cut)
            val result = DanmakuProto.parseSegment(partial)
            assertNotNull(result)
            assertTrue("截断到 $cut 字节时不应返回 null", result.size <= 3)
        }
    }

    @Test
    fun parseSegment_garbageBufferReturnsEmptyWithoutThrowing() {
        val garbage = ByteArray(64) { (it * 7 + 3).toByte() }
        val result = DanmakuProto.parseSegment(garbage)
        assertNotNull(result)
    }

    @Test
    fun parseSegment_emptyBufferReturnsEmpty() {
        assertEquals(0, DanmakuProto.parseSegment(ByteArray(0)).size)
    }

    @Test
    fun parseSegment_handlesZlibCompressedPayload() {
        val payload = zlib(segment(elem(progress = 500L, content = "压缩弹幕")))

        // 前置断言：确实带 zlib 头
        assertEquals(0x78, payload[0].toInt() and 0xFF)

        val result = DanmakuProto.parseSegment(payload)

        assertEquals(1, result.size)
        assertEquals("压缩弹幕", result[0].text)
        assertEquals(500L, result[0].timeMs)
    }

    @Test
    fun parseSegment_largeVarintProgressIsDecodedSafely() {
        // progress 用 10 字节 varint（int64 边界）编码，确保 shift 不丢失高位
        val payload = segment(elem(progress = Long.MAX_VALUE, content = "超大时间"))

        val result = DanmakuProto.parseSegment(payload)

        assertEquals(1, result.size)
        assertEquals(Long.MAX_VALUE, result[0].timeMs)
    }

    @Test
    fun parseSegment_handlesEmptyContentAndEmptyActionGracefully() {
        // content 存在但为 UTF-8 多字节字符时不应被截断
        val payload = segment(elem(content = "弹幕内容 emoji 🎉 混合 ascii"))

        val result = DanmakuProto.parseSegment(payload)

        assertEquals(1, result.size)
        assertEquals("弹幕内容 emoji 🎉 混合 ascii", result[0].text)
    }
}
