package com.b2y.danmaku.bili

import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.Inflater

/**
 * B 站弹幕 protobuf 解析器（0 依赖手写实现）。
 *
 * 对应接口 `https://api.bilibili.com/x/v2/dm/wbi/web/seg.so` 返回的
 * `DmSegMobileReply`。参考实现见扩展的 `lib/protobuf-parser.js`。
 *
 * 顶层消息：
 * ```
 * message DmSegMobileReply {
 *   repeated DanmakuElem elems = 1;  // length-delimited
 * }
 * message DanmakuElem {
 *   int64  id       = 1;
 *   int32  progress = 2;  // 毫秒
 *   int32  mode     = 3;
 *   int32  fontsize = 4;
 *   uint32 color    = 5;  // 0xRRGGBB
 *   string midHash  = 6;
 *   string content  = 7;
 *   int64  ctime    = 8;
 *   int32  weight   = 9;
 *   string idStr    = 10;
 *   int32  attr     = 11;
 *   string action   = 12;
 * }
 * ```
 *
 * 设计要点：
 * - 任何异常/越界/未知 wire type 都不抛出，停止当前层级解析并返回已收集结果；
 * - 支持 varint / fixed32 / fixed64 / length-delimited，未知字段安全跳过；
 * - `color` 保持协议原值并屏蔽到 24 位；`weight` 原样保留（可能合法地为 0）；
 * - 内容为空白（或缺失）的弹幕直接丢弃；
 * - 兼容 zlib / gzip 压缩载荷（部分客户端下 `seg.so` 会返回压缩数据）。
 *
 * 本文件不引用任何 `android.*`，可直接在普通 JVM 单元测试中运行。
 */
object DanmakuProto {

    private const val WIRE_VARINT = 0
    private const val WIRE_FIXED64 = 1
    private const val WIRE_LENGTH_DELIMITED = 2
    private const val WIRE_FIXED32 = 5

    /** 解压后的最大字节数，防御性上限（约 64MB） */
    private const val MAX_INFLATED_BYTES = 64 * 1024 * 1024

    /**
     * 解析弹幕分段数据。
     *
     * @param buffer `seg.so` 的原始响应体（可能被 zlib/gzip 压缩）
     * @return 解析出的弹幕列表；数据异常时返回已成功解析的部分
     */
    fun parseSegment(buffer: ByteArray): List<DanmakuEntry> {
        if (buffer.isEmpty()) return emptyList()
        val data = decompressIfNeeded(buffer)
        val result = ArrayList<DanmakuEntry>()
        val reader = ProtoReader(data)
        while (reader.hasMore()) {
            val tag = reader.readTag()
            if (tag == null) break
            if (tag.wireType == WIRE_LENGTH_DELIMITED && tag.fieldNumber == 1) {
                val bytes = reader.readBytes() ?: break
                val entry = parseElem(bytes) ?: continue
                result.add(entry)
            } else {
                if (!reader.skip(tag.wireType)) break
            }
        }
        return result
    }

    /** 解析单个 `DanmakuElem`；内容为空时返回 null */
    private fun parseElem(data: ByteArray): DanmakuEntry? {
        var progress = 0L
        var mode = 0
        var fontSize = 0
        var color = 0
        var weight = 0
        var content: String? = null

        val reader = ProtoReader(data)
        while (reader.hasMore()) {
            val tag = reader.readTag() ?: break
            when (tag.wireType) {
                WIRE_VARINT -> {
                    val value = reader.readVarint() ?: break
                    when (tag.fieldNumber) {
                        2 -> progress = value
                        3 -> mode = value.toInt()
                        4 -> fontSize = value.toInt()
                        5 -> color = value.toInt()
                        9 -> weight = value.toInt()
                        else -> Unit
                    }
                }
                WIRE_LENGTH_DELIMITED -> {
                    val raw = reader.readBytes() ?: break
                    if (tag.fieldNumber == 7) {
                        content = String(raw, Charsets.UTF_8)
                    }
                }
                else -> {
                    if (!reader.skip(tag.wireType)) break
                }
            }
        }

        val text = content ?: return null
        if (text.isBlank()) return null
        return DanmakuEntry(
            timeMs = progress,
            text = text,
            color = color and 0xFFFFFF,
            mode = mode,
            fontSize = fontSize,
            weight = weight
        )
    }

    /**
     * 若载荷带 zlib 头（0x78）或 gzip 头（0x1F 0x8B）则先解压。
     *
     * 解压失败时回退为原始 buffer（上层仍会尝试按 protobuf 解析）。
     */
    private fun decompressIfNeeded(buffer: ByteArray): ByteArray {
        if (buffer.size < 2) return buffer
        val b0 = buffer[0].toInt() and 0xFF
        val b1 = buffer[1].toInt() and 0xFF
        return when {
            b0 == 0x78 -> inflate(buffer, nowrap = false) ?: buffer
            b0 == 0x1F && b1 == 0x8B -> gunzip(buffer) ?: buffer
            else -> buffer
        }
    }

    /** zlib / raw deflate 解压 */
    private fun inflate(buffer: ByteArray, nowrap: Boolean): ByteArray? {
        val inflater = Inflater(nowrap)
        try {
            inflater.setInput(buffer)
            val out = ByteArrayOutputStream(buffer.size.coerceAtLeast(64) * 4)
            val chunk = ByteArray(8192)
            while (!inflater.finished()) {
                val read = try {
                    inflater.inflate(chunk)
                } catch (_: Throwable) {
                    return null
                }
                if (read == 0) {
                    if (inflater.needsInput() || inflater.needsDictionary()) break
                } else {
                    out.write(chunk, 0, read)
                    if (out.size() > MAX_INFLATED_BYTES) return null
                }
            }
            val bytes = out.toByteArray()
            return if (bytes.isEmpty()) null else bytes
        } catch (_: Throwable) {
            return null
        } finally {
            try {
                inflater.end()
            } catch (_: Throwable) {
                // 忽略
            }
        }
    }

    /** gzip 解压 */
    private fun gunzip(buffer: ByteArray): ByteArray? {
        return try {
            GZIPInputStream(buffer.inputStream()).use { input ->
                val out = ByteArrayOutputStream(buffer.size.coerceAtLeast(64) * 4)
                val chunk = ByteArray(8192)
                while (true) {
                    val read = input.read(chunk)
                    if (read <= 0) break
                    out.write(chunk, 0, read)
                    if (out.size() > MAX_INFLATED_BYTES) return null
                }
                out.toByteArray().takeIf { it.isNotEmpty() }
            }
        } catch (_: Throwable) {
            null
        }
    }

    /** protobuf tag：字段号 + wire type */
    private class Tag(val fieldNumber: Int, val wireType: Int)

    /**
     * 极简 protobuf 读游标。
     *
     * 所有读取方法在越界或数据非法时返回 null / false，绝不抛异常。
     */
    private class ProtoReader(private val data: ByteArray) {
        private var pos = 0

        fun hasMore(): Boolean = pos < data.size

        /** 读取 tag；数据不足返回 null */
        fun readTag(): Tag? {
            val value = readVarint() ?: return null
            val fieldNumberRaw = value ushr 3
            if (fieldNumberRaw <= 0 || fieldNumberRaw > Int.MAX_VALUE) return null
            val fieldNumber = fieldNumberRaw.toInt()
            val wireType = (value and 0x07).toInt()
            return Tag(fieldNumber, wireType)
        }

        /**
         * 读取 varint（最多 10 字节，兼容 int64/uint64）。
         *
         * 超过 64 位时返回 null（视为数据损坏，由调用方停止解析）。
         */
        fun readVarint(): Long? {
            var result = 0L
            var shift = 0
            var count = 0
            while (count < 10) {
                if (pos >= data.size) return null
                val b = data[pos++].toInt() and 0xFF
                result = result or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
                count++
            }
            return null
        }

        /** 读取 length-delimited 字节串 */
        fun readBytes(): ByteArray? {
            val length = readVarint() ?: return null
            if (length < 0 || length > Int.MAX_VALUE) return null
            val len = length.toInt()
            if (len < 0 || pos + len > data.size) return null
            val out = data.copyOfRange(pos, pos + len)
            pos += len
            return out
        }

        /** 按 wire type 跳过字段；失败返回 false */
        fun skip(wireType: Int): Boolean {
            return when (wireType) {
                WIRE_VARINT -> readVarint() != null
                WIRE_FIXED64 -> advance(8)
                WIRE_LENGTH_DELIMITED -> {
                    val length = readVarint() ?: return false
                    if (length < 0 || length > Int.MAX_VALUE) return false
                    advance(length.toInt())
                }
                WIRE_FIXED32 -> advance(4)
                else -> false
            }
        }

        private fun advance(count: Int): Boolean {
            if (count < 0 || pos + count > data.size) return false
            pos += count
            return true
        }
    }
}
