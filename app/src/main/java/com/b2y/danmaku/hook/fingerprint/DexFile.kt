package com.b2y.danmaku.hook.fingerprint

import java.io.File
import java.util.zip.ZipFile

/**
 * 极简 DEX 读取器（纯 JVM，无 Android 依赖，可单元测试）。
 *
 * YouTube 客户端对自身代码做了混淆，类名/方法名不可硬编码。这里通过解析 APK 内的 DEX，
 * 用「字符串常量 + 方法签名」这类稳定的"指纹"反查被混淆的类与方法，再由 Xposed 反射 hook。
 *
 * 只实现本模块需要的能力：
 *  - 字符串表 / 类型表 / 方法引用表 / 类定义
 *  - 遍历 `code_item` 中的 `const-string` 与 `invoke-*` 指令
 */
class DexFile private constructor(private val buf: ByteArray) {

    val strings: List<String>
    val types: List<String>
    val methodRefs: List<MethodRef>
    val classDefs: List<ClassDef>

    private val protoReturnType: IntArray
    private val protoParamsOff: IntArray

    init {
        require(buf.size >= 112 && buf[0] == 'd'.code.toByte() && buf[3] == '\n'.code.toByte()) {
            "不是合法的 DEX 文件"
        }
        val stringIdsSize = u32(0x38)
        val stringIdsOff = u32(0x3c)
        val typeIdsSize = u32(0x40)
        val typeIdsOff = u32(0x44)
        val protoIdsSize = u32(0x48)
        val protoIdsOff = u32(0x4c)
        val methodIdsSize = u32(0x58)
        val methodIdsOff = u32(0x5c)
        val classDefsSize = u32(0x60)
        val classDefsOff = u32(0x64)

        val strArr = ArrayList<String>(stringIdsSize)
        for (i in 0 until stringIdsSize) {
            var p = u32(stringIdsOff + i * 4)
            val len = uleb(p)
            p = len.second
            strArr.add(String(buf, p, len.first, Charsets.UTF_8))
        }
        strings = strArr

        val typeArr = ArrayList<String>(typeIdsSize)
        for (i in 0 until typeIdsSize) {
            typeArr.add(strings[u32(typeIdsOff + i * 4)])
        }
        types = typeArr

        protoReturnType = IntArray(protoIdsSize)
        protoParamsOff = IntArray(protoIdsSize)
        for (i in 0 until protoIdsSize) {
            val off = protoIdsOff + i * 12
            protoReturnType[i] = u32(off + 4)
            protoParamsOff[i] = u32(off + 8)
        }

        val mRefs = ArrayList<MethodRef>(methodIdsSize)
        for (i in 0 until methodIdsSize) {
            val off = methodIdsOff + i * 8
            val clsIdx = u16(off)
            val protoIdx = u16(off + 2)
            val nameIdx = u32(off + 4)
            mRefs.add(
                MethodRef(
                    classDescriptor = if (clsIdx < types.size) types[clsIdx] else "?",
                    name = if (nameIdx < strings.size) strings[nameIdx] else "?",
                    returnType = typeName(protoReturnType[protoIdx]),
                    paramTypes = readTypeList(protoParamsOff[protoIdx]).map(::typeName)
                )
            )
        }
        methodRefs = mRefs

        val defs = ArrayList<ClassDef>(classDefsSize)
        for (i in 0 until classDefsSize) {
            val off = classDefsOff + i * 32
            val classIdx = u32(off)
            val superIdx = u32(off + 8)
            val interfacesOff = u32(off + 12)
            val classDataOff = u32(off + 24)
            val methods = ArrayList<MethodDef>()
            if (classDataOff != 0) {
                var p = classDataOff
                val staticFields: Int
                val instanceFields: Int
                val directMethods: Int
                val virtualMethods: Int
                val a = uleb(p); staticFields = a.first; p = a.second
                val b = uleb(p); instanceFields = b.first; p = b.second
                val c = uleb(p); directMethods = c.first; p = c.second
                val d = uleb(p); virtualMethods = d.first; p = d.second
                var idx = 0
                for (k in 0 until staticFields) { val e = uleb(p); idx += e.first; p = e.second; val f = uleb(p); p = f.second }
                idx = 0
                for (k in 0 until instanceFields) { val e = uleb(p); idx += e.first; p = e.second; val f = uleb(p); p = f.second }
                idx = 0
                for (k in 0 until directMethods) {
                    val e = uleb(p); idx += e.first; p = e.second
                    val f = uleb(p); val access = f.first; p = f.second
                    val g = uleb(p); val codeOff = g.first; p = g.second
                    methods.add(MethodDef(idx, access, codeOff, true))
                }
                idx = 0
                for (k in 0 until virtualMethods) {
                    val e = uleb(p); idx += e.first; p = e.second
                    val f = uleb(p); val access = f.first; p = f.second
                    val g = uleb(p); val codeOff = g.first; p = g.second
                    methods.add(MethodDef(idx, access, codeOff, false))
                }
            }
            defs.add(
                ClassDef(
                    descriptor = if (classIdx < types.size) types[classIdx] else "?",
                    superDescriptor = if (superIdx == 0xFFFFFFFF.toInt() || superIdx >= types.size) null else types[superIdx],
                    interfaces = readTypeList(interfacesOff).map(::typeName),
                    methods = methods,
                    isInterface = false
                )
            )
        }
        classDefs = defs
    }

    // ---------------------------------------------------------------- 查询

    fun classByName(descriptor: String): ClassDef? = classDefs.firstOrNull { it.descriptor == descriptor }

    fun methodRef(index: Int): MethodRef? = methodRefs.getOrNull(index)

    /**
     * 找出所有「方法体里出现了该字符串常量」的方法。
     * @param text 目标字符串（精确匹配）
     * @param limit 最多返回多少条
     */
    fun findMethodsUsingString(text: String, limit: Int = 16): List<MethodLocation> {
        val stringIndex = strings.indexOf(text)
        if (stringIndex < 0) return emptyList()
        return findMethodsUsingStringIndices(setOf(stringIndex), limit)
    }

    /**
     * 找出所有「方法体里出现了包含该子串的字符串常量」的方法。
     *
     * 很多指纹字符串带格式化后缀（例如
     * `"Media progress reported outside media playback: %s"`），因此需要子串匹配。
     */
    fun findMethodsUsingStringContaining(substring: String, limit: Int = 16): List<MethodLocation> {
        val indices = HashSet<Int>()
        for (i in strings.indices) {
            if (strings[i].contains(substring)) indices.add(i)
        }
        if (indices.isEmpty()) return emptyList()
        return findMethodsUsingStringIndices(indices, limit)
    }

    private fun findMethodsUsingStringIndices(stringIndices: Set<Int>, limit: Int): List<MethodLocation> {
        val result = ArrayList<MethodLocation>()
        for (cd in classDefs) {
            for (m in cd.methods) {
                if (m.codeOff == 0) continue
                val code = codeInfo(m.codeOff) ?: continue
                if (containsAnyStringConstant(code, stringIndices)) {
                    val ref = methodRefs.getOrNull(m.methodIndex) ?: continue
                    result.add(MethodLocation(cd.descriptor, m, ref))
                    if (result.size >= limit) return result
                }
            }
        }
        return result
    }

    /** 返回某个方法体内所有 `invoke-*` 指令指向的方法引用 */
    fun invokedMethodsOf(codeOff: Int): List<MethodRef> {
        val code = codeInfo(codeOff) ?: return emptyList()
        val out = LinkedHashSet<MethodRef>()
        forEachInstruction(code) { pc, opcode, _ ->
            if (opcode in 0x6e..0x72 || opcode in 0x74..0x78) {
                val idx = u16At(code.insnsStart + (pc + 1) * 2)
                methodRefs.getOrNull(idx)?.let { out.add(it) }
            }
        }
        return out.toList()
    }

    /** 返回某个方法体内所有 `const-string` 引用的字符串 */
    fun stringsOf(codeOff: Int): List<String> {
        val code = codeInfo(codeOff) ?: return emptyList()
        val out = ArrayList<String>()
        forEachInstruction(code) { pc, opcode, _ ->
            when (opcode) {
                0x1a -> {
                    val idx = u16At(code.insnsStart + (pc + 1) * 2)
                    if (idx < strings.size) out.add(strings[idx])
                }
                0x1b -> {
                    val lo = u16At(code.insnsStart + (pc + 1) * 2)
                    val hi = u16At(code.insnsStart + (pc + 2) * 2)
                    val idx = (hi shl 16) or lo
                    if (idx in strings.indices) out.add(strings[idx])
                }
            }
        }
        return out
    }

    // ---------------------------------------------------------------- 内部

    private class CodeInfo(val insnsStart: Int, val insnsSize: Int)

    private fun codeInfo(codeOff: Int): CodeInfo? {
        if (codeOff <= 0 || codeOff + 16 > buf.size) return null
        val triesSize = u16(codeOff + 6)
        val insnsSize = u32(codeOff + 12)
        val insnsStart = codeOff + 16
        val end = insnsStart + insnsSize * 2
        if (insnsSize <= 0 || insnsStart < 0 || end > buf.size) return null
        // tries 段紧随指令之后，这里用不到
        @Suppress("UNUSED_VARIABLE") val unused = triesSize
        return CodeInfo(insnsStart, insnsSize)
    }

    private fun containsAnyStringConstant(code: CodeInfo, stringIndices: Set<Int>): Boolean {
        var found = false
        forEachInstruction(code) { pc, opcode, _ ->
            if (found) return@forEachInstruction
            when (opcode) {
                0x1a -> if (stringIndices.contains(u16At(code.insnsStart + (pc + 1) * 2))) found = true
                0x1b -> {
                    val lo = u16At(code.insnsStart + (pc + 1) * 2)
                    val hi = u16At(code.insnsStart + (pc + 2) * 2)
                    if (stringIndices.contains((hi shl 16) or lo)) found = true
                }
            }
        }
        return found
    }

    private inline fun forEachInstruction(code: CodeInfo, action: (pc: Int, opcode: Int, unit: Int) -> Unit) {
        var pc = 0
        var guard = 0
        while (pc < code.insnsSize && guard++ < 4_000_000) {
            val unit = u16At(code.insnsStart + pc * 2)
            val opcode = unit and 0xFF
            action(pc, opcode, unit)
            pc += widthAt(code, pc, opcode, unit)
        }
    }

    /** 计算指令宽度（16 位 code unit 数），正确处理 switch / fill-array-data 载荷 */
    private fun widthAt(code: CodeInfo, pc: Int, opcode: Int, unit: Int): Int {
        if (opcode == 0x00 && ((unit ushr 8) and 0xFF) != 0x00) {
            val kind = (unit ushr 8) and 0xFF
            return when (kind) {
                0x01 -> { // packed-switch payload
                    val size = if (pc + 1 < code.insnsSize) u16At(code.insnsStart + (pc + 1) * 2) else 0
                    size + 4
                }
                0x02 -> { // sparse-switch payload
                    val size = if (pc + 1 < code.insnsSize) u16At(code.insnsStart + (pc + 1) * 2) else 0
                    size * 2 + 2
                }
                0x03 -> { // fill-array-data payload
                    val elementWidth = if (pc + 1 < code.insnsSize) u16At(code.insnsStart + (pc + 1) * 2) else 1
                    val size = u32(code.insnsStart + (pc + 2) * 2)
                    val dataUnits = (size * (if (elementWidth <= 0) 1 else elementWidth) + 1) / 2
                    dataUnits + 4
                }
                else -> 1
            }
        }
        return instructionWidth(opcode)
    }

    private fun readTypeList(off: Int): IntArray {
        if (off == 0) return IntArray(0)
        val size = u32(off)
        if (size < 0 || size > 4096) return IntArray(0)
        val out = IntArray(size)
        for (i in 0 until size) out[i] = u16(off + 4 + i * 2)
        return out
    }

    private fun typeName(typeIdx: Int): String =
        if (typeIdx in types.indices) types[typeIdx] else "?"

    private fun u16(off: Int): Int = (buf[off].toInt() and 0xFF) or ((buf[off + 1].toInt() and 0xFF) shl 8)

    private fun u16At(off: Int): Int = u16(off)

    private fun u32(off: Int): Int =
        (buf[off].toInt() and 0xFF) or
            ((buf[off + 1].toInt() and 0xFF) shl 8) or
            ((buf[off + 2].toInt() and 0xFF) shl 16) or
            ((buf[off + 3].toInt() and 0xFF) shl 24)

    private fun uleb(start: Int): Pair<Int, Int> {
        var result = 0
        var shift = 0
        var p = start
        var b: Int
        do {
            if (p >= buf.size) return Pair(result, p)
            b = buf[p++].toInt() and 0xFF
            result = result or ((b and 0x7F) shl shift)
            shift += 7
        } while ((b and 0x80) != 0 && shift < 35)
        return Pair(result, p)
    }

    companion object {
        fun from(bytes: ByteArray): DexFile? = try {
            DexFile(bytes)
        } catch (t: Throwable) {
            null
        }

        /** 只解析字符串表，用来快速判断某个 dex 是否包含目标字符串（避免全量解析） */
        fun containsString(bytes: ByteArray, text: String): Boolean {
            if (bytes.size < 112) return false
            return try {
                val stringIdsSize = (bytes[0x38].toInt() and 0xFF) or ((bytes[0x39].toInt() and 0xFF) shl 8) or
                    ((bytes[0x3a].toInt() and 0xFF) shl 16) or ((bytes[0x3b].toInt() and 0xFF) shl 24)
                val stringIdsOff = (bytes[0x3c].toInt() and 0xFF) or ((bytes[0x3d].toInt() and 0xFF) shl 8) or
                    ((bytes[0x3e].toInt() and 0xFF) shl 16) or ((bytes[0x3f].toInt() and 0xFF) shl 24)
                val target = text.toByteArray(Charsets.UTF_8)
                var found = false
                var i = 0
                while (i < stringIdsSize && !found) {
                    val p0 = stringIdsOff + i * 4
                    i++
                    if (p0 < 0 || p0 + 4 > bytes.size) break
                    var p = (bytes[p0].toInt() and 0xFF) or ((bytes[p0 + 1].toInt() and 0xFF) shl 8) or
                        ((bytes[p0 + 2].toInt() and 0xFF) shl 16) or ((bytes[p0 + 3].toInt() and 0xFF) shl 24)
                    if (p < 0 || p >= bytes.size) continue
                    var len = 0
                    var shift = 0
                    var b: Int
                    do {
                        if (p >= bytes.size) break
                        b = bytes[p++].toInt() and 0xFF
                        len = len or ((b and 0x7F) shl shift)
                        shift += 7
                    } while ((b and 0x80) != 0 && shift < 35)
                    if (len != target.size) continue
                    if (p + len > bytes.size) continue
                    var ok = true
                    for (k in target.indices) {
                        if (bytes[p + k] != target[k]) {
                            ok = false
                            break
                        }
                    }
                    if (ok) found = true
                }
                found
            } catch (_: Throwable) {
                false
            }
        }
    }
}

data class MethodRef(
    val classDescriptor: String,
    val name: String,
    val returnType: String,
    val paramTypes: List<String>
) {
    val dottedClass: String get() = classDescriptor.replace('/', '.').removePrefix("L").removeSuffix(";")
}

data class MethodDef(
    val methodIndex: Int,
    val access: Int,
    val codeOff: Int,
    val direct: Boolean
)

data class ClassDef(
    val descriptor: String,
    val superDescriptor: String?,
    val interfaces: List<String>,
    val methods: List<MethodDef>,
    val isInterface: Boolean
)

data class MethodLocation(
    val classDescriptor: String,
    val methodDef: MethodDef,
    val methodRef: MethodRef
)

/**
 * 指令宽度表（以 16 位 code unit 为单位）。0x00 载荷由 [DexFile.widthAt] 单独处理。
 */
private fun instructionWidth(opcode: Int): Int = when (opcode) {
    0x00, 0x01, 0x04, 0x07, 0x0a, 0x0b, 0x0c, 0x0d, 0x0e, 0x0f, 0x10, 0x11, 0x12,
    0x1d, 0x1e, 0x21, 0x27, 0x28 -> 1
    0x02, 0x05, 0x08, 0x13, 0x15, 0x16, 0x19, 0x1a, 0x1c, 0x1f, 0x20, 0x22, 0x23,
    0x29, 0x2d, 0x2e, 0x2f, 0x30, 0x31,
    0x32, 0x33, 0x34, 0x35, 0x36, 0x37, 0x38, 0x39, 0x3a, 0x3b, 0x3c, 0x3d,
    0x44, 0x45, 0x46, 0x47, 0x48, 0x49, 0x4a, 0x4b, 0x4c, 0x4d, 0x4e, 0x4f,
    0x50, 0x51, 0x52, 0x53, 0x54, 0x55, 0x56, 0x57, 0x58,
    0x59, 0x5a, 0x5b, 0x5c, 0x5d, 0x5e, 0x5f, 0x60, 0x61, 0x62, 0x63, 0x64,
    0x65, 0x66, 0x67, 0x68, 0x69, 0x6a, 0x6b, 0x6c, 0x6d,
    0x90, 0x91, 0x92, 0x93, 0x94, 0x95, 0x96, 0x97, 0x98, 0x99, 0x9a, 0x9b,
    0x9c, 0x9d, 0x9e, 0x9f, 0xa0, 0xa1, 0xa2, 0xa3, 0xa4, 0xa5, 0xa6, 0xa7,
    0xa8, 0xa9, 0xaa, 0xab, 0xac, 0xad, 0xae, 0xaf,
    0xd0, 0xd1, 0xd2, 0xd3, 0xd4, 0xd5, 0xd6, 0xd7,
    0xd8, 0xd9, 0xda, 0xdb, 0xdc, 0xdd, 0xde, 0xdf, 0xe0, 0xe1, 0xe2,
    0xfe, 0xff -> 2
    0x03, 0x06, 0x09, 0x14, 0x17, 0x24, 0x25, 0x26, 0x2a, 0x2b, 0x2c,
    0x6e, 0x6f, 0x70, 0x71, 0x72,
    0x74, 0x75, 0x76, 0x77, 0x78,
    0xfc, 0xfd -> 3
    0x18 -> 5
    0xfa, 0xfb -> 4
    in 0xb0..0xcf -> 1
    else -> 1
}

/**
 * APK 内所有 DEX 的索引。按需惰性加载，避免解析无用 dex 造成内存峰值。
 */
class ApkDexIndex(private val apkPaths: List<String>) {

    private var dexFiles: List<DexFile>? = null

    @Synchronized
    fun dexes(): List<DexFile> {
        dexFiles?.let { return it }
        val loaded = ArrayList<DexFile>()
        for (path in apkPaths) {
            try {
                ZipFile(File(path)).use { zip ->
                    val entries = ArrayList<java.util.zip.ZipEntry>()
                    val en = zip.entries()
                    while (en.hasMoreElements()) {
                        val e = en.nextElement()
                        if (e.name.startsWith("classes") && e.name.endsWith(".dex")) entries.add(e)
                    }
                    entries.sortBy { it.name }
                    for (e in entries) {
                        val bytes = zip.getInputStream(e).use { it.readBytes() }
                        DexFile.from(bytes)?.let { loaded.add(it) }
                    }
                }
            } catch (t: Throwable) {
                // 忽略单个 APK 的失败
            }
        }
        dexFiles = loaded
        return loaded
    }

    fun findMethodsUsingString(text: String, limit: Int = 16): List<MethodLocation> {
        val out = ArrayList<MethodLocation>()
        for (d in dexes()) {
            out.addAll(d.findMethodsUsingString(text, limit - out.size))
            if (out.size >= limit) break
        }
        return out
    }

    fun findMethodsUsingStringContaining(substring: String, limit: Int = 16): List<MethodLocation> {
        val out = ArrayList<MethodLocation>()
        for (d in dexes()) {
            out.addAll(d.findMethodsUsingStringContaining(substring, limit - out.size))
            if (out.size >= limit) break
        }
        return out
    }
}
