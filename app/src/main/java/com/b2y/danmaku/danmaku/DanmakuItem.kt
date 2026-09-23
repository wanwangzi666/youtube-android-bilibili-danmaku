package com.b2y.danmaku.danmaku

/**
 * 一条待显示的弹幕数据（不可变值对象，由加载器产出）。
 *
 * 本文件刻意不引用任何 Android API，保证 [DanmakuEngine] 可以在纯 JVM 单元测试中运行。
 *
 * @param timeMs     弹幕相对于视频开始的时间戳（毫秒）
 * @param text       文本内容
 * @param color      文字颜色，0xRRGGBB（注意不含 alpha，透明度由引擎按不透明度设置统一计算）
 * @param mode       显示模式：[MODE_SCROLL_*] 从右向左滚动，[MODE_BOTTOM] 底部固定，[MODE_TOP] 顶部固定
 * @param weight     权重，越小越"不重要"；配合 [EngineConfig.weightThreshold] 过滤
 * @param fontSizeSp 单条弹幕的独立字号（sp），0f 表示使用全局字号
 */
data class DanmakuItem(
    val timeMs: Long,
    val text: String,
    val color: Int,
    val mode: Int,
    val weight: Int = 5,
    val fontSizeSp: Float = 0f
) {
    companion object {
        /** 滚动弹幕（B 站原始 mode 1/2/3，本项目统一按同一种滚动方式渲染） */
        const val MODE_SCROLL: Int = 1

        /** 滚动弹幕（同上，保留原始语义） */
        const val MODE_SCROLL_2: Int = 2

        /** 滚动弹幕（同上，保留原始语义） */
        const val MODE_SCROLL_3: Int = 3

        /** 底部固定弹幕 */
        const val MODE_BOTTOM: Int = 4

        /** 顶部固定弹幕 */
        const val MODE_TOP: Int = 5

        /** 权重默认值（B 站 JSON 中缺失 weight 时使用） */
        const val DEFAULT_WEIGHT: Int = 5
    }
}
