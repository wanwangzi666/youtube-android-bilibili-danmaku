package com.b2y.danmaku.hook.fingerprint

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * DEX 指纹解析的验证测试。
 *
 * 这些测试需要一个真实的 YouTube APK 反解出来的 dex 目录（开发机上用 apk-analysis/dex）。
 * 目录不存在时会自动跳过（[assumeTrue]），因此不会影响正常 CI。
 *
 * 这是本模块最"脆"的一环：如果测试通过，说明「字符串常量 → 被混淆的类/方法」这条
 * 反查链路在当前 YouTube 版本上是成立的。
 */
class DexFileTest {

    private val candidates = listOf(
        File("C:/123/bilibili-youtube-danmaku/apk-analysis/dex"),
        File("../apk-analysis/dex"),
        File("apk-analysis/dex")
    )

    private fun dexDirOrSkip(): File {
        val dir = candidates.firstOrNull { it.isDirectory && (it.listFiles()?.any { f -> f.name.endsWith(".dex") } == true) }
        assumeTrue("没有可用的 YouTube dex 目录，跳过指纹测试", dir != null)
        return dir!!
    }

    private fun loadAll(dir: File): List<DexFile> =
        dir.listFiles().orEmpty()
            .filter { it.name.endsWith(".dex") }
            .sortedBy { it.name }
            .mapNotNull { DexFile.from(it.readBytes()) }

    @Test
    fun `dex 头解析正确`() {
        val dir = dexDirOrSkip()
        val dexes = loadAll(dir)
        assertTrue("应至少解析出一个 dex", dexes.isNotEmpty())
        // YouTube 的 classes.dex 至少有几万个字符串
        assertTrue("字符串表规模异常", dexes[0].strings.size > 1000)
        assertTrue("类定义规模异常", dexes.sumOf { it.classDefs.size } > 1000)
    }

    @Test
    fun `能找到视频 ID 指纹并推导出 getVideoId`() {
        val dir = dexDirOrSkip()
        val dexes = loadAll(dir)

        var found: MethodLocation? = null
        var owner: DexFile? = null
        for (d in dexes) {
            val hit = d.findMethodsUsingString("Null initialPlayabilityStatus", 1).firstOrNull()
            if (hit != null) {
                found = hit
                owner = d
                break
            }
        }
        assertNotNull("没有找到 'Null initialPlayabilityStatus' 指纹", found)
        val loc = found!!
        val dex = owner!!

        val ref = loc.methodRef
        println("视频 ID 指纹: ${ref.dottedClass}#${ref.name}(${ref.paramTypes.joinToString()}) : ${ref.returnType}")

        val paramDescriptor = ref.paramTypes.firstOrNull { it.startsWith("L") }
        assertNotNull("指纹方法的第一个对象参数应为 PlayerResponseModel 接口", paramDescriptor)

        val getter = dex.invokedMethodsOf(loc.methodDef.codeOff).firstOrNull {
            it.classDescriptor == paramDescriptor &&
                it.returnType == "Ljava/lang/String;" &&
                it.paramTypes.isEmpty() &&
                it.name != "<init>"
        }
        assertNotNull("没有在该方法里找到返回 String 的无参接口调用（getVideoId）", getter)
        println("推导出的 getVideoId: ${getter!!.dottedClass}#${getter.name}()")
        assertTrue("getVideoId 应声明在接口上", getter.classDescriptor.startsWith("L"))
    }

    @Test
    fun `能找到播放时间指纹`() {
        val dir = dexDirOrSkip()
        val dexes = loadAll(dir)

        var found: MethodLocation? = null
        var owner: DexFile? = null
        for (d in dexes) {
            val hit = d.findMethodsUsingStringContaining("Media progress reported outside media playback", 1)
                .firstOrNull()
            if (hit != null) {
                found = hit
                owner = d
                break
            }
        }
        assertNotNull("没有找到播放时间指纹", found)
        val loc = found!!
        val dex = owner!!

        val invokes = dex.invokedMethodsOf(loc.methodDef.codeOff).filter { it.name == "<init>" }
        println("播放时间指纹所在方法: ${loc.methodRef.dottedClass}#${loc.methodRef.name}")
        invokes.forEach { println("   构造: ${it.dottedClass}<init>(${it.paramTypes.joinToString()})") }

        val ctor = invokes.firstOrNull { it.paramTypes.firstOrNull() == "J" } ?: invokes.singleOrNull()
        assertNotNull("没有在该方法里找到接收 long 的构造函数", ctor)
        assertEquals("J", ctor!!.paramTypes.first())
        println("推导出的播放时间构造: ${ctor.dottedClass}<init>(${ctor.paramTypes.joinToString()})")
    }

    @Test
    fun `containsString 快速探测与完整解析一致`() {
        val dir = dexDirOrSkip()
        val file = dir.listFiles()!!.first { it.name.endsWith(".dex") }
        val bytes = file.readBytes()
        val target = "Null initialPlayabilityStatus"
        val quick = DexFile.containsString(bytes, target)
        val full = DexFile.from(bytes)?.strings?.contains(target) ?: false
        assertEquals("快速探测结果应与完整解析一致", full, quick)
        assertTrue("不存在的字符串应返回 false", !DexFile.containsString(bytes, "__b2y_definitely_missing__"))
    }
}
