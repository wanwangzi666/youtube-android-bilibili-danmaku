package com.b2y.danmaku.hook

import com.b2y.danmaku.hook.fingerprint.DexFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Shorts 判定逻辑的验证。
 *
 * 两部分：
 * 1. **几何兜底**：纯整数入参的判定规则，直接在 JVM 上覆盖（横屏正片 / 竖屏全屏 / 小窗预览 /
 *    近正方形 / 非法尺寸）。
 * 2. **类名判定**：需要在真实的 YouTube dex 上确认那几个 `com.google.android.libraries.youtube.reel.*`
 *    类名确实被 R8 保留了下来；dex 目录不存在时自动跳过
 *    （与 [com.b2y.danmaku.hook.fingerprint.DexFileTest] 一致）。
 */
class ShortsDetectorTest {

    private val candidates = listOf(
        File("C:/123/bilibili-youtube-danmaku/apk-analysis/dex"),
        File("../apk-analysis/dex"),
        File("apk-analysis/dex")
    )

    private fun dexDirOrSkip(): File {
        val dir = candidates.firstOrNull {
            it.isDirectory && (it.listFiles()?.any { f -> f.name.endsWith(".dex") } == true)
        }
        assumeTrue("没有可用的 YouTube dex 目录，跳过 Shorts 指纹测试", dir != null)
        return dir!!
    }

    // ------------------------------------------------------------------ 几何兜底

    /** 一台常见手机的屏幕（1080×2340） */
    private fun isShorts(w: Int, h: Int, sw: Int = 1080, sh: Int = 2340) =
        ShortsDetector.looksLikeShortsGeometry(w, h, sw, sh)

    @Test
    fun `竖屏全屏画面判定为 Shorts`() {
        // 1080×1920 的竖屏视频，占满屏幕宽度
        assertTrue(isShorts(1080, 1920))
    }

    @Test
    fun `实测截图那样的 Shorts 布局判定为 Shorts`() {
        // 1080×2340 的屏幕上，Shorts 画面从顶部工具栏下方铺到底部
        // （截图里约 1080×1860，高度占 79%）
        assertTrue(isShorts(1080, 1860))
    }

    @Test
    fun `原始比例竖屏视频在超高屏幕上可能漏判`() {
        // 已知盲区（写在 ShortsDetector 的注释与 README 里）：
        // 20:9 屏（1080×2400）上 9:16 的 Shorts「适应宽度」后只有 50% 高，低于 0.55 阈值。
        // 这类机型依赖第 1 层（Shorts 播放器视图）判定。
        assertFalse(isShorts(675, 1200, sw = 1080, sh = 2400))
    }

    @Test
    fun `横屏正片不是 Shorts`() {
        // 常规 16:9 正片
        assertFalse(isShorts(1080, 608))
    }

    @Test
    fun `接近正方形也不算 Shorts`() {
        // 宽/高 = 1.0，不满足竖屏条件
        assertFalse(isShorts(900, 900))
    }

    @Test
    fun `小窗竖屏预览不算 Shorts`() {
        // 首页信息流里的小窗预览：虽然竖屏，但只占屏幕一小部分
        assertFalse(isShorts(200, 400))
    }

    @Test
    fun `竖屏但在横屏设备上带大黑边不算 Shorts`() {
        // 横屏设备（2340×1080）里播竖屏视频：铺满高度，但远不到屏幕宽的一半
        assertFalse(isShorts(608, 1080, sw = 2340, sh = 1080))
    }

    @Test
    fun `普通播放页里的竖屏视频不算 Shorts`() {
        // 竖屏视频在普通播放页里「适应宽度」，只占约 46% 高（1080×1080 / 2340）
        assertFalse(isShorts(1080, 1080, sw = 1080, sh = 2340))
    }

    @Test
    fun `非法尺寸不算 Shorts`() {
        assertFalse(isShorts(0, 0))
        assertFalse(isShorts(1080, 0))
        assertFalse(isShorts(0, 1920))
        assertFalse(ShortsDetector.looksLikeShortsGeometry(1080, 1920, 0, 0))
    }

    @Test
    fun `Rect 重载对空输入返回 false`() {
        // 单测里的 android.graphics.Rect 是返回 0 的桩，只能验证空值与 0 尺寸分支
        assertFalse(ShortsDetector.looksLikeShortsGeometry(null, null))
        assertFalse(
            ShortsDetector.looksLikeShortsGeometry(
                android.graphics.Rect(),
                android.graphics.Rect()
            )
        )
    }

    // ------------------------------------------------------------------ 类名判定

    @Test
    fun `Shorts 播放器类名在真实 dex 里被保留`() {
        val dir = dexDirOrSkip()
        val descriptors = dir.listFiles().orEmpty()
            .filter { it.name.endsWith(".dex") }
            .sortedBy { it.name }
            .mapNotNull { DexFile.from(it.readBytes()) }
            .flatMap { it.classDefs }
            .map { it.descriptor }
            .toSet()

        val found = ShortsDetector.candidateClassNames.filter { name ->
            descriptors.contains("L" + name.replace('.', '/') + ";")
        }
        println("Shorts 候选类命中：$found")
        println("候选类总数：${ShortsDetector.candidateClassNames.size}")

        assertTrue(
            "YouTube 版本更新后 Shorts 播放器类名全部消失，需要重新确认判定依据",
            found.isNotEmpty()
        )
    }

    @Test
    fun `候选类名列表不含空项`() {
        assertEquals(
            ShortsDetector.candidateClassNames.size,
            ShortsDetector.candidateClassNames.filter { it.isNotBlank() }.size
        )
    }
}
