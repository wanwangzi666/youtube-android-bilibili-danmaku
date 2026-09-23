package com.b2y.danmaku.bili

import java.net.URLEncoder

/**
 * B 站 WBI 签名实现。
 *
 * 逐行对齐浏览器扩展参考实现 `entrypoints/background/index.js`：
 * - `mixinKeyEncTab`
 * - `getMixinKey()`
 * - `md5()`
 * - `encWbi()`
 *
 * 本文件不引用任何 `android.*`，可直接在普通 JVM 单元测试中运行。
 *
 * 签名语义：
 * 1. 非 null 参数值转字符串并删除 `!'()*`；
 * 2. 加入 `wts`；
 * 3. 按 key 升序排序；
 * 4. `encodeURIComponent` 编码 key 与 value（空格 => `%20`）；
 * 5. `w_rid = md5(query + mixinKey)`。
 */
object Wbi {

    /**
     * mixinKey 重排表（64 项）。
     *
     * 注意：该表是 `imgKey + subKey` 的下标序列，必须严格按顺序使用。
     */
    private val MIXIN_KEY_ENC_TAB = intArrayOf(
        46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35,
        27, 43, 5, 49, 33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13,
        37, 48, 7, 16, 24, 55, 40, 61, 26, 17, 0, 1, 60, 51, 30, 4,
        22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11, 36, 20, 34, 44, 52
    )

    /** 需要从参数值中过滤掉的字符（与 JS `/[!'()*]/g` 一致）。 */
    private const val CHR_FILTER = "!'()*"

    /** 十六进制字符表 */
    private const val HEX = "0123456789abcdef"

    // ---------------------------------------------------------------- MD5

    /** 第一轮辅助函数 F(x,y,z) = (x & y) | (~x & z) */
    private fun f(x: Int, y: Int, z: Int): Int = (x and y) or (x.inv() and z)

    /** 第二轮辅助函数 G(x,y,z) = (x & z) | (y & ~z) */
    private fun g(x: Int, y: Int, z: Int): Int = (x and z) or (y and z.inv())

    /** 第三轮辅助函数 H(x,y,z) = x ^ y ^ z */
    private fun h(x: Int, y: Int, z: Int): Int = x xor y xor z

    /** 第四轮辅助函数 I(x,y,z) = y ^ (x | ~z) */
    private fun i(x: Int, y: Int, z: Int): Int = y xor (x or z.inv())

    /** 循环左移 */
    private fun rotl(value: Int, bits: Int): Int = (value shl bits) or (value ushr (32 - bits))

    /**
     * 将 [message] 按 MD5 padding 规则填充为 64 字节整数倍的数组。
     *
     * 规则：追加 1 字节 `0x80`，补 `0x00` 直到长度 ≡ 56 (mod 64)，最后 8 字节写入小端序位长度。
     * 例如 56 字节的消息需要填充到 128 字节（而不是 64），否则 8 字节长度字段会越界。
     */
    private fun pad(message: ByteArray): ByteArray {
        val len = message.size
        val padded = (len + 8) / 64 * 64 + 64
        val out = ByteArray(padded)
        System.arraycopy(message, 0, out, 0, len)
        out[len] = 0x80.toByte()
        val bitLen = len.toLong() * 8L
        for (idx in 0 until 8) {
            out[padded - 8 + idx] = ((bitLen ushr (8 * idx)) and 0xFFL).toByte()
        }
        return out
    }

    /** 单块处理：对 [block] 的第 [offset] 字节起的 64 字节做一轮压缩 */
    private fun processBlock(block: ByteArray, offset: Int, state: IntArray) {
        val m = IntArray(16)
        for (j in 0 until 16) {
            val p = offset + j * 4
            m[j] = (block[p].toInt() and 0xFF) or
                ((block[p + 1].toInt() and 0xFF) shl 8) or
                ((block[p + 2].toInt() and 0xFF) shl 16) or
                ((block[p + 3].toInt() and 0xFF) shl 24)
        }

        var a = state[0]
        var b = state[1]
        var c = state[2]
        var d = state[3]

        // 第 1 轮
        a = b + rotl(a + f(b, c, d) + m[0] + -680876936, 7)
        d = a + rotl(d + f(a, b, c) + m[1] + -389564586, 12)
        c = d + rotl(c + f(d, a, b) + m[2] + 606105819, 17)
        b = c + rotl(b + f(c, d, a) + m[3] + -1044525330, 22)
        a = b + rotl(a + f(b, c, d) + m[4] + -176418897, 7)
        d = a + rotl(d + f(a, b, c) + m[5] + 1200080426, 12)
        c = d + rotl(c + f(d, a, b) + m[6] + -1473231341, 17)
        b = c + rotl(b + f(c, d, a) + m[7] + -45705983, 22)
        a = b + rotl(a + f(b, c, d) + m[8] + 1770035416, 7)
        d = a + rotl(d + f(a, b, c) + m[9] + -1958414417, 12)
        c = d + rotl(c + f(d, a, b) + m[10] + -42063, 17)
        b = c + rotl(b + f(c, d, a) + m[11] + -1990404162, 22)
        a = b + rotl(a + f(b, c, d) + m[12] + 1804603682, 7)
        d = a + rotl(d + f(a, b, c) + m[13] + -40341101, 12)
        c = d + rotl(c + f(d, a, b) + m[14] + -1502002290, 17)
        b = c + rotl(b + f(c, d, a) + m[15] + 1236535329, 22)

        // 第 2 轮
        a = b + rotl(a + g(b, c, d) + m[1] + -165796510, 5)
        d = a + rotl(d + g(a, b, c) + m[6] + -1069501632, 9)
        c = d + rotl(c + g(d, a, b) + m[11] + 643717713, 14)
        b = c + rotl(b + g(c, d, a) + m[0] + -373897302, 20)
        a = b + rotl(a + g(b, c, d) + m[5] + -701558691, 5)
        d = a + rotl(d + g(a, b, c) + m[10] + 38016083, 9)
        c = d + rotl(c + g(d, a, b) + m[15] + -660478335, 14)
        b = c + rotl(b + g(c, d, a) + m[4] + -405537848, 20)
        a = b + rotl(a + g(b, c, d) + m[9] + 568446438, 5)
        d = a + rotl(d + g(a, b, c) + m[14] + -1019803690, 9)
        c = d + rotl(c + g(d, a, b) + m[3] + -187363961, 14)
        b = c + rotl(b + g(c, d, a) + m[8] + 1163531501, 20)
        a = b + rotl(a + g(b, c, d) + m[13] + -1444681467, 5)
        d = a + rotl(d + g(a, b, c) + m[2] + -51403784, 9)
        c = d + rotl(c + g(d, a, b) + m[7] + 1735328473, 14)
        b = c + rotl(b + g(c, d, a) + m[12] + -1926607734, 20)

        // 第 3 轮
        a = b + rotl(a + h(b, c, d) + m[5] + -378558, 4)
        d = a + rotl(d + h(a, b, c) + m[8] + -2022574463, 11)
        c = d + rotl(c + h(d, a, b) + m[11] + 1839030562, 16)
        b = c + rotl(b + h(c, d, a) + m[14] + -35309556, 23)
        a = b + rotl(a + h(b, c, d) + m[1] + -1530992060, 4)
        d = a + rotl(d + h(a, b, c) + m[4] + 1272893353, 11)
        c = d + rotl(c + h(d, a, b) + m[7] + -155497632, 16)
        b = c + rotl(b + h(c, d, a) + m[10] + -1094730640, 23)
        a = b + rotl(a + h(b, c, d) + m[13] + 681279174, 4)
        d = a + rotl(d + h(a, b, c) + m[0] + -358537222, 11)
        c = d + rotl(c + h(d, a, b) + m[3] + -722521979, 16)
        b = c + rotl(b + h(c, d, a) + m[6] + 76029189, 23)
        a = b + rotl(a + h(b, c, d) + m[9] + -640364487, 4)
        d = a + rotl(d + h(a, b, c) + m[12] + -421815835, 11)
        c = d + rotl(c + h(d, a, b) + m[15] + 530742520, 16)
        b = c + rotl(b + h(c, d, a) + m[2] + -995338651, 23)

        // 第 4 轮
        a = b + rotl(a + i(b, c, d) + m[0] + -198630844, 6)
        d = a + rotl(d + i(a, b, c) + m[7] + 1126891415, 10)
        c = d + rotl(c + i(d, a, b) + m[14] + -1416354905, 15)
        b = c + rotl(b + i(c, d, a) + m[5] + -57434055, 21)
        a = b + rotl(a + i(b, c, d) + m[12] + 1700485571, 6)
        d = a + rotl(d + i(a, b, c) + m[3] + -1894986606, 10)
        c = d + rotl(c + i(d, a, b) + m[10] + -1051523, 15)
        b = c + rotl(b + i(c, d, a) + m[1] + -2054922799, 21)
        a = b + rotl(a + i(b, c, d) + m[8] + 1873313359, 6)
        d = a + rotl(d + i(a, b, c) + m[15] + -30611744, 10)
        c = d + rotl(c + i(d, a, b) + m[6] + -1560198380, 15)
        b = c + rotl(b + i(c, d, a) + m[13] + 1309151649, 21)
        a = b + rotl(a + i(b, c, d) + m[4] + -145523070, 6)
        d = a + rotl(d + i(a, b, c) + m[11] + -1120210379, 10)
        c = d + rotl(c + i(d, a, b) + m[2] + 718787259, 15)
        b = c + rotl(b + i(c, d, a) + m[9] + -343485551, 21)

        state[0] += a
        state[1] += b
        state[2] += c
        state[3] += d
    }

    /**
     * 计算字符串的 MD5，返回 32 位小写十六进制。
     *
     * 纯 Kotlin 实现（不依赖 `java.security.MessageDigest`），便于与参考实现逐位对齐。
     */
    fun md5(input: String): String {
        val bytes = input.toByteArray(Charsets.UTF_8)
        val padded = pad(bytes)
        val state = intArrayOf(0x67452301, -0x10325477, -0x67452302, 0x10325476)
        var offset = 0
        while (offset + 64 <= padded.size) {
            processBlock(padded, offset, state)
            offset += 64
        }
        val sb = StringBuilder(32)
        for (word in state) {
            for (j in 0 until 4) {
                val b = (word ushr (8 * j)) and 0xFF
                sb.append(HEX[b ushr 4]).append(HEX[b and 0x0F])
            }
        }
        return sb.toString()
    }

    // ------------------------------------------------------------ mixinKey

    /**
     * 由 `img_key + sub_key` 使用 [MIXIN_KEY_ENC_TAB] 重排并取前 32 位。
     *
     * @param imgKey 来自 `wbi_img.img_url` 文件名（不含扩展名）
     * @param subKey 来自 `wbi_img.sub_url` 文件名（不含扩展名）
     */
    fun getMixinKey(imgKey: String, subKey: String): String {
        val orig = imgKey + subKey
        val sb = StringBuilder(32)
        for (n in MIXIN_KEY_ENC_TAB) {
            if (n < orig.length) sb.append(orig[n])
        }
        return sb.toString().take(32)
    }

    /**
     * 生成待签名字符串（不编码）。
     *
     * 与参考实现 `encWbi` 中 `query` 的构造一致：过滤 `!'()*`、加入 `wts`、按 key 升序拼接。
     * 单元测试与调试可直接复用。
     */
    fun buildQuery(params: Map<String, Any?>, wts: Long): String {
        val safe = LinkedHashMap<String, String>()
        for ((key, value) in params) {
            if (value == null) continue
            safe[key] = value.toString().filterNot { CHR_FILTER.indexOf(it) >= 0 }
        }
        safe["wts"] = wts.toString()
        return safe.keys.sorted().joinToString("&") { key -> "$key=${safe[key]}" }
    }

    /**
     * 生成 WBI 签名后的查询串：`k=v&k2=v2&wts=...&w_rid=...`。
     *
     * @param params 业务参数，值为 null 的项会被丢弃
     * @param wts 秒级时间戳（由调用方传入，便于测试与复用）
     */
    fun encWbi(params: Map<String, Any?>, imgKey: String, subKey: String, wts: Long): String {
        val mixinKey = getMixinKey(imgKey, subKey)
        val safe = LinkedHashMap<String, String>()
        for ((key, value) in params) {
            if (value == null) continue
            safe[key] = value.toString().filterNot { CHR_FILTER.indexOf(it) >= 0 }
        }
        safe["wts"] = wts.toString()
        val query = safe.keys.sorted().joinToString("&") { key ->
            encodeURIComponent(key) + "=" + encodeURIComponent(safe[key] ?: "")
        }
        val wRid = md5(query + mixinKey)
        return "$query&w_rid=$wRid"
    }

    /**
     * 等价于 JS 的 `encodeURIComponent`。
     *
     * `URLEncoder.encode` 与 JS 的差异：
     * - 空格：`+` → `%20`
     * - `*` → `%2A` 需要还原
     * - `!'()~` 需要还原（`URLEncoder` 保持 `~` 不变，这里统一还原一次）
     * - `%7E` → `~`
     */
    private fun encodeURIComponent(value: String): String {
        val encoded = URLEncoder.encode(value, "UTF-8")
        val sb = StringBuilder(encoded.length)
        var idx = 0
        while (idx < encoded.length) {
            val ch = encoded[idx]
            if (ch == '+') {
                sb.append("%20")
                idx++
            } else if (ch == '%' && idx + 2 < encoded.length) {
                val hex = encoded.substring(idx + 1, idx + 3).uppercase()
                val code = hex.toIntOrNull(16) ?: -1
                val keep = when (code) {
                    0x21, 0x27, 0x28, 0x29, 0x2A, 0x7E -> code.toChar()
                    else -> null
                }
                if (keep != null) {
                    sb.append(keep)
                    idx += 3
                } else {
                    sb.append('%').append(hex)
                    idx += 3
                }
            } else {
                sb.append(ch)
                idx++
            }
        }
        return sb.toString()
    }
}
