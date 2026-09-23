package com.b2y.danmaku.danmaku

/**
 * 文本宽度测量回调，由 View 注入（引擎本身不依赖 Android）。
 */
fun interface TextMeasurer {
    /** @return 文本在给定像素字号下的宽度（px） */
    fun measure(text: String, fontSizePx: Float): Float
}

/**
 * 引擎配置（值对象，由 View 在设置变化时重新构造并调用 [DanmakuEngine.setConfig]）。
 */
data class EngineConfig(
    val fontSizePx: Float = 42f,
    /** 滚动速度倍率，越大越快，基准时长 BASE_DURATION_MS 表示 1.0 倍 */
    val speed: Float = 1.0f,
    val trackSpacingPx: Int = 10,
    /** 弹幕显示区域占舞台高度的百分比 10..100 */
    val displayAreaPercent: Int = 100,
    val showTop: Boolean = true,
    val showBottom: Boolean = true,
    /** 低于该权重的弹幕不显示，0 = 不过滤 */
    val weightThreshold: Int = 0,
    /** 最大轨道数，0 = 根据舞台高度自动计算 */
    val maxTracks: Int = 0,
    val opacityPercent: Int = 100
) {
    companion object {
        /** 滚动弹幕的基准存活时长（毫秒），实际时长 = BASE / speed */
        const val BASE_DURATION_MS: Long = 8000L

        /** 顶部/底部固定弹幕的存活时长 */
        const val FIXED_DURATION_MS: Long = 4000L
    }
}

/**
 * 一帧内处于活动状态的弹幕（可变对象，逐帧更新位置以避免分配）。
 *
 * 由 [DanmakuEngine] 从内部对象池取出并复用，**外部只应读取**，不要持有引用跨帧使用。
 */
class ActiveDanmaku internal constructor() {
    var text: String = ""
    var color: Int = 0xFFFFFF
    var mode: Int = 1
    var fontSizePx: Float = 42f
    var width: Float = 0f
    /** 当前左上角 X（像素，相对于舞台） */
    var x: Float = 0f
    /** 当前顶部 Y（像素，相对于舞台） */
    var y: Float = 0f
    /** 0..255 */
    var alpha: Int = 255
    var timeMs: Long = 0L
    internal var track: Int = -1
    internal var released: Boolean = false

    /** 归还对象池前复位（仅供引擎内部使用） */
    internal fun resetForPool() {
        text = ""
        color = 0xFFFFFF
        mode = DanmakuItem.MODE_SCROLL
        fontSizePx = 0f
        width = 0f
        x = 0f
        y = 0f
        alpha = 255
        timeMs = 0L
        track = -1
        released = false
    }
}

/**
 * 弹幕布局 / 轨道 / 碰撞引擎。
 *
 * 移植自浏览器扩展 `utils/danmaku-engine.js` 的轨道分配与进度计算逻辑，但去掉了所有 DOM 依赖：
 * - 不做动画，只按 [update] 传入的时间计算每条弹幕当前应该在的位置；
 * - 每帧复用同一批 [ActiveDanmaku] 对象（对象池），避免 GC 抖动；
 * - 轨道占用缓存只在有弹幕进出轨道时重建，正常帧不扫描全部轨道。
 *
 * 本类是纯 JVM 实现（不引用任何 Android API），可直接被 JUnit 测试覆盖。
 */
class DanmakuEngine(private val measurer: TextMeasurer) {

    private companion object {
        /** 单帧最多发射条数（含 seek 后追帧），避免时间轴抖动时瞬间刷屏 */
        const val MAX_EMIT_PER_FRAME = 12

        /** 判定"已经让出足够空间"的右侧留白（px），来自 JS 版 `findAvailableTrack()` */
        const val LEAVE_ROOM_PX = 100f

        /** 对象池上限（超出部分直接丢弃，交给 GC） */
        const val POOL_LIMIT = 512
    }

    // ---- 不可变渲染状态（@JvmField internal，便于测试直接读取）----

    @JvmField
    internal var stageWidth: Int = 0

    @JvmField
    internal var stageHeight: Int = 0

    @JvmField
    internal var trackHeight: Int = 0

    @JvmField
    internal var trackCount: Int = 0

    @JvmField
    internal var nowMs: Long = 0L

    @JvmField
    internal var lastNowMs: Long = Long.MIN_VALUE

    @JvmField
    internal var alpha: Int = 255

    // ---- 已加载数据 ----

    private var items: Array<DanmakuItem> = emptyArray()

    /**
     * 发射标记。API 约定的 [DanmakuItem] 是不可变 data class（没有 emitted 字段），
     * 因此这里用与之等长的布尔数组按索引记录，排序后索引即光标位置。
     */
    private var emittedFlags: BooleanArray = BooleanArray(0)

    private var nextIndex: Int = 0

    // ---- 活动弹幕（紧凑存储，无 per-frame 分配）----

    private val activeList = ArrayList<ActiveDanmaku>(64)
    private var activeArr: Array<Any?> = arrayOfNulls(64)
    private var activeSize: Int = 0
    private val pool = ArrayDeque<ActiveDanmaku>(64)

    private val buf = arrayOfNulls<ActiveDanmaku>(MAX_EMIT_PER_FRAME)
    private var bufSize: Int = 0

    // ---- 轨道占用缓存 ----

    private var trackRolling: Array<ActiveDanmaku?> = arrayOfNulls(0)
    private var trackFixed: Array<ActiveDanmaku?> = arrayOfNulls(0)
    private var trackRightNow: FloatArray = FloatArray(0)
    private var trackGen: IntArray = IntArray(0)
    private var trackGenCur: Int = 1

    private var config = EngineConfig()

    init {
        this.trackHeight = (config.fontSizePx + config.trackSpacingPx).toInt().coerceAtLeast(1)
        this.alpha = 255
    }

    // ------------------------------------------------------------------
    // 公开 API
    // ------------------------------------------------------------------

    /**
     * 更新配置。字号 / 间距 / 轨道数 / 速度变化会清空当前画面（已发射弹幕需要按新参数重新测量），
     * 仅 opacity / 过滤开关之类的改动不打断现有弹幕。
     */
    fun setConfig(config: EngineConfig) {
        val prev = this.config
        this.config = config
        this.alpha = ((255 * config.opacityPercent.coerceIn(0, 100)) / 100.0f + 0.5f).toInt()
        this.trackHeight = (config.fontSizePx + config.trackSpacingPx).toInt().coerceAtLeast(1)
        val geometryChanged = prev.fontSizePx != config.fontSizePx ||
            prev.trackSpacingPx != config.trackSpacingPx ||
            prev.displayAreaPercent != config.displayAreaPercent ||
            prev.maxTracks != config.maxTracks ||
            prev.speed != config.speed
        if (geometryChanged) clearActive()
    }

    /** 载入弹幕。内部按 timeMs 排序；条数上限裁剪由调用方负责。 */
    fun load(items: List<DanmakuItem>) {
        val n = items.size
        val arr = arrayOfNulls<DanmakuItem>(n)
        for (i in 0 until n) arr[i] = items[i]
        @Suppress("UNCHECKED_CAST")
        val typed = arr as Array<DanmakuItem>
        typed.sortBy { it.timeMs }
        this.items = typed
        this.emittedFlags = BooleanArray(n)
        resetPlayback()
    }

    /** 清空所有弹幕（包括待发射的） */
    fun clear() {
        items = emptyArray()
        emittedFlags = BooleanArray(0)
        resetPlayback()
    }

    /**
     * 跳转到 [nowMs]（毫秒）。
     *
     * 语义：释放全部在场弹幕；把 `timeMs <= nowMs` 的弹幕直接标记为已发射（属于"刚刚过去"的
     * 区间，不重播、也不补发）；时间戳在未来的保持待发射。
     */
    fun onSeek(nowMs: Long) {
        clearActive()
        val arr = items
        val n = arr.size
        // 先整体复位：向后拖动时，原本已经播过、但现在又处于"未来"的弹幕必须能重新出现
        // （对应浏览器扩展里的 resetDanmakuStates + resyncDanmakus）
        java.util.Arrays.fill(emittedFlags, false)
        var i = 0
        while (i < n && arr[i].timeMs <= nowMs) {
            // 已经过去的弹幕直接标记为已发射：不重播、也不补发
            emittedFlags[i] = true
            i++
        }
        nextIndex = i
        this.nowMs = nowMs
        this.lastNowMs = nowMs
    }

    /**
     * 推进到 [nowMs] 时刻并重算所有活动弹幕的位置。
     *
     * 同一 [nowMs] 重复调用是幂等的（位置只由 nowMs 决定，第二次直接返回）；
     * [stageWidth] / [stageHeight] <= 0 时视为"舞台尚不可见"：清空画面且不抛异常。
     *
     * @param stageWidth  舞台宽度（px）
     * @param stageHeight 舞台高度（px）
     */
    fun update(nowMs: Long, stageWidth: Int, stageHeight: Int) {
        this.nowMs = nowMs
        this.stageWidth = stageWidth
        this.stageHeight = stageHeight

        if (nowMs == lastNowMs) return
        lastNowMs = nowMs

        if (stageWidth <= 0 || stageHeight <= 0) {
            clearActive()
            // 舞台还不可见（视频区域尚未确定 / 后台）：把到点弹幕标记为"已发射但不绘制"，
            // 避免舞台恢复后一次性爆发。
            drainDueWithoutDrawing()
            return
        }

        refreshTrackGeometry(stageHeight)
        removeOutOfRange()
        val broken = refreshBrokenTracks()
        updateActive(broken)
        fillTrackCache()
        bufSize = 0
        emitDue()
        val emittedAny = bufSize > 0
        flushEmitted()
        if (emittedAny) {
            // 本帧新发射的弹幕必须立即定位并做存活判定：
            // 否则它们会停在 obtain() 给的 x=stageWidth 上"闪一帧"，
            // 而且已经超期的弹幕要等到下一帧才被回收。
            updateActive(false)
            fillTrackCache()
        }
    }

    /** 把已经到点的弹幕标记为已发射，但不产生任何绘制对象 */
    private fun drainDueWithoutDrawing() {
        var idx = nextIndex
        val arr = items
        val n = arr.size
        var drained = 0
        while (idx < n && drained < MAX_EMIT_PER_FRAME) {
            if (arr[idx].timeMs > nowMs) break
            if (!emittedFlags[idx]) {
                emittedFlags[idx] = true
                drained++
            }
            idx++
        }
        nextIndex = idx
    }

    /** 当前应当绘制的弹幕（只读；不要修改顺序，也不要跨帧持有） */
    val activeItems: List<ActiveDanmaku> get() = activeList

    /** 已载入的弹幕条数 */
    val loadedCount: Int get() = items.size

    // ------------------------------------------------------------------
    // 轨道几何
    // ------------------------------------------------------------------

    private fun refreshTrackGeometry(stageHeight: Int) {
        val th = (config.fontSizePx + config.trackSpacingPx).toInt().coerceAtLeast(1)
        trackHeight = th
        // 与 JS 版一致：floor(舞台高度 * 显示区域百分比 / 轨道高度)
        val byArea = (stageHeight.toLong() * config.displayAreaPercent.coerceIn(0, 100) / 100L / th).toInt()
        val limit = if (config.maxTracks > 0) config.maxTracks else Int.MAX_VALUE
        val count = minOf(limit, byArea).coerceAtLeast(1)
        if (count != trackCount) {
            trackCount = count
            trackGenCur++
            trackFixed.fill(null)
        }
        ensureTrackCapacity(count)
    }

    private fun ensureTrackCapacity(size: Int) {
        if (trackRolling.size >= size) return
        val n = maxOf(size, 16)
        trackRolling = arrayOfNulls(n)
        trackFixed = arrayOfNulls(n)
        trackRightNow = FloatArray(n)
        trackGen = IntArray(n)
        trackGenCur++
    }

    /** 回收轨道索引已经越界的弹幕（窗口尺寸变化导致的轨道数减少） */
    private fun removeOutOfRange() {
        var i = 0
        while (i < activeSize) {
            val a = activeArr[i] as? ActiveDanmaku
            if (a == null) {
                removeAt(i)
                continue
            }
            if (a.track < 0 || a.track >= trackCount) {
                release(a)
                removeAt(i)
                continue
            }
            i++
        }
    }

    /** 重建本帧需要重新扫描的轨道缓存；返回是否发生过重建 */
    private fun refreshBrokenTracks(): Boolean {
        var broken = false
        for (t in 0 until trackCount) {
            if (trackGen[t] != trackGenCur) {
                broken = true
                rebuildTrack(t)
            }
        }
        return broken
    }

    private fun rebuildTrack(t: Int) {
        var rolling: ActiveDanmaku? = null
        var fixed: ActiveDanmaku? = null
        for (i in 0 until activeSize) {
            val a = activeArr[i] as? ActiveDanmaku ?: continue
            if (a.track != t) continue
            if (a.mode >= DanmakuItem.MODE_BOTTOM) {
                if (fixed == null) fixed = a
            } else if (rolling == null) {
                rolling = a
            }
        }
        trackRolling[t] = rolling
        trackFixed[t] = fixed
        trackGen[t] = trackGenCur
    }

    /** 逐帧更新位置（只遍历活动弹幕，不遍历轨道） */
    private fun updateActive(broken: Boolean) {
        var i = 0
        while (i < activeSize) {
            val a = activeArr[i] as? ActiveDanmaku
            if (a == null) {
                removeAt(i)
                continue
            }
            val alive = updatePosition(a)
            if (!alive) {
                release(a)
                removeAt(i)
                continue
            }
            if (broken) {
                if (a.mode >= DanmakuItem.MODE_BOTTOM) {
                    trackFixed[a.track] = a
                } else {
                    trackRolling[a.track] = a
                }
            }
            i++
        }
    }

    /** 重建每轨道"最后一条滚动弹幕的当前右边缘"，供本帧轨道选择使用 */
    private fun fillTrackCache() {
        val rightArr = trackRightNow
        val rollingArr = trackRolling
        for (t in 0 until trackCount) {
            val r = rollingArr[t]
            rightArr[t] = if (r == null) Float.NEGATIVE_INFINITY else r.x + r.width
        }
    }

    /** 滚动弹幕在本帧时刻的右边缘（尚未 update 过的上一帧对象用匀速外推近似） */
    private fun rightEdgeNow(a: ActiveDanmaku): Float {
        if (a.released) return Float.NEGATIVE_INFINITY
        if (a.timeMs == lastNowMs) return a.x + a.width
        val w = stageWidth.toFloat()
        val duration = EngineConfig.BASE_DURATION_MS / config.speed
        if (duration <= 0f) return Float.NEGATIVE_INFINITY
        val p = (nowMs - a.timeMs).toFloat() / duration
        return w - p * (w + a.width) + a.width
    }

    // ------------------------------------------------------------------
    // 位置计算 / 存活判定
    // ------------------------------------------------------------------

    /** @return 是否仍然存活 */
    private fun updatePosition(a: ActiveDanmaku): Boolean {
        val w = stageWidth.toFloat()
        return if (a.mode >= DanmakuItem.MODE_BOTTOM) {
            a.x = (w - a.width) * 0.5f
            a.y = clampY((a.track * trackHeight).toFloat(), a.fontSizePx)
            nowMs - a.timeMs < EngineConfig.FIXED_DURATION_MS
        } else {
            val duration = (EngineConfig.BASE_DURATION_MS / config.speed).toFloat()
            if (duration <= 0f) {
                false
            } else {
                val p = ((nowMs - a.timeMs).toFloat() / duration).coerceIn(0f, 1f)
                a.x = w - p * (w + a.width)
                a.y = clampY((a.track * trackHeight).toFloat(), a.fontSizePx)
                a.x + a.width > 0f && p < 1f
            }
        }
    }

    /** 顶部轨道贴到显示区域下沿时上移，避免被裁掉；滚动弹幕从轨道 0 开始，不会溢出 */
    private fun clampY(y: Float, fontSizePx: Float): Float {
        val limit = stageHeight * config.displayAreaPercent.coerceIn(0, 100) / 100f
        val h = fontSizePx * 1.35f
        return if (y + h > limit) (limit - h).coerceAtLeast(0f) else y
    }

    // ------------------------------------------------------------------
    // 发射
    // ------------------------------------------------------------------

    /** 把到点但尚未发射的弹幕放进待发射缓冲（最多 MAX_EMIT_PER_FRAME 条，其余留给下一帧） */
    private fun emitDue() {
        var idx = nextIndex
        val arr = items
        val n = arr.size
        while (idx < n && bufSize < MAX_EMIT_PER_FRAME) {
            if (arr[idx].timeMs > nowMs) break
            if (emittedFlags[idx]) {
                idx++
                continue
            }
            emittedFlags[idx] = true
            val d = arr[idx]
            if (shouldDisplay(d)) {
                val fs = if (d.fontSizeSp > 0f) d.fontSizeSp else config.fontSizePx
                val a = obtain(d, fs, measurer.measure(d.text, fs))
                if (a != null) {
                    buf[bufSize] = a
                    bufSize++
                }
            }
            idx++
        }
        nextIndex = idx
    }

    private fun flushEmitted() {
        for (i in 0 until bufSize) {
            val a = buf[i]
            buf[i] = null
            if (a == null) continue
            if (!assignTrack(a)) {
                release(a)
                continue
            }
            addActive(a)
        }
        bufSize = 0
    }

    /** 过滤：空白文本 / 权重阈值 / 顶部开关 / 底部开关 */
    private fun shouldDisplay(d: DanmakuItem): Boolean {
        if (d.text.isEmpty()) return false
        if (config.weightThreshold > 0 && d.weight < config.weightThreshold) return false
        if (d.mode == DanmakuItem.MODE_TOP && !config.showTop) return false
        if (d.mode == DanmakuItem.MODE_BOTTOM && !config.showBottom) return false
        return true
    }

    /**
     * 分配轨道。
     *
     * - 滚动（mode 1/2/3）：移植 JS 版 `findAvailableTrack()` —— 轨道上每条已有弹幕都必须
     *   "已经让出空间"（右边缘 `x + width <= stageWidth - 100`）；若没有完全空闲的轨道，
     *   选择尾部最靠左（最快清空）的那条（JS 版此处是 `Math.random()`，改为确定性更利于测试）。
     * - 固定（mode 4/5）：各自独立的占用计数，顶部自上而下、底部自下而上，每轨道最多一条。
     *
     * @return 是否成功分配（轨道尚未计算出来之前返回 false）
     */
    private fun assignTrack(a: ActiveDanmaku): Boolean {
        val tc = trackCount
        if (tc <= 0) return false
        if (a.mode < DanmakuItem.MODE_BOTTOM) {
            val t = selectRollingTrack(tc)
            a.track = t
            trackRolling[t] = a
            trackRightNow[t] = a.x + a.width
            return true
        }
        val bottom = a.mode == DanmakuItem.MODE_BOTTOM
        var t = if (bottom) tc - 1 else 0
        val step = if (bottom) -1 else 1
        while (t in 0 until tc) {
            if (trackFixed[t] == null) {
                trackFixed[t] = a
                a.track = t
                return true
            }
            t += step
        }
        return false
    }

    /** 选择滚动轨道，逻辑对应 JS 的 `findAvailableTrack()` */
    private fun selectRollingTrack(tc: Int): Int {
        val w = stageWidth.toFloat()
        val leaveRoomAt = w - LEAVE_ROOM_PX
        var best = 0
        var bestRight = Float.MAX_VALUE
        for (t in 0 until tc) {
            val last = trackRolling[t]
            val right = when {
                last == null -> Float.NEGATIVE_INFINITY
                last.released -> {
                    // 已释放的残留引用：视为空轨道（不要在这里改 trackGen，读路径保持无副作用）
                    trackRolling[t] = null
                    trackRightNow[t] = Float.NEGATIVE_INFINITY
                    Float.NEGATIVE_INFINITY
                }
                // 同帧刚发射的弹幕还没算过位置，直接用发射时的位置
                last.timeMs == nowMs -> last.x + last.width
                trackGen[t] == trackGenCur -> trackRightNow[t]
                else -> rightEdgeNow(last)
            }
            // 轨道完全空闲，或尾部弹幕已经让出 100px 空间
            if (right <= leaveRoomAt) return t
            if (right < bestRight) {
                bestRight = right
                best = t
            }
        }
        // 没有完全空闲的轨道：选尾部最靠左（最快清空）的那条
        return best
    }

    // ------------------------------------------------------------------
    // 对象池
    // ------------------------------------------------------------------

    private fun obtain(d: DanmakuItem, fontSizePx: Float, width: Float): ActiveDanmaku? {
        val w = if (width > 0f) width else 1f
        val a = if (pool.isEmpty()) ActiveDanmaku() else pool.removeLast()
        a.released = false
        a.text = d.text
        a.color = d.color
        a.mode = d.mode
        a.fontSizePx = fontSizePx
        a.width = w
        a.x = stageWidth.toFloat()
        a.y = 0f
        a.alpha = alpha
        a.timeMs = d.timeMs
        a.track = -1
        return a
    }

    private fun release(a: ActiveDanmaku) {
        if (a.released) return
        a.released = true
        val t = a.track
        if (t in 0 until trackCount) {
            if (trackRolling[t] === a) {
                trackRolling[t] = null
                trackRightNow[t] = Float.NEGATIVE_INFINITY
                trackGen[t] = trackGenCur
            }
            if (trackFixed[t] === a) {
                trackFixed[t] = null
                trackGen[t] = trackGenCur
            }
        }
        if (pool.size < POOL_LIMIT) {
            a.resetForPool()
            pool.addLast(a)
        }
    }

    private fun addActive(a: ActiveDanmaku) {
        if (activeSize == activeArr.size) {
            val n = arrayOfNulls<Any?>(activeArr.size * 2)
            System.arraycopy(activeArr, 0, n, 0, activeSize)
            activeArr = n
        }
        activeArr[activeSize] = a
        activeSize++
        activeList.add(a)
    }

    /** O(1) 交换删除，避免 ArrayList.remove 的元素搬移 */
    private fun removeAt(i: Int) {
        val last = activeSize - 1
        if (i != last) {
            val moved = activeArr[last] as ActiveDanmaku
            activeArr[i] = moved
            activeList[i] = moved
        }
        activeArr[last] = null
        activeSize = last
        activeList.removeAt(last)
    }

    private fun resetPlayback() {
        clearActive()
        nextIndex = 0
        lastNowMs = Long.MIN_VALUE
        nowMs = 0L
    }

    /** 释放所有在场弹幕并清空轨道占用缓存 */
    private fun clearActive() {
        var i = activeSize - 1
        while (i >= 0) {
            (activeArr[i] as? ActiveDanmaku)?.let { release(it) }
            activeArr[i] = null
            i--
        }
        activeSize = 0
        activeList.clear()
        bufSize = 0
        java.util.Arrays.fill(trackRolling, null)
        java.util.Arrays.fill(trackFixed, null)
        trackGenCur++
        java.util.Arrays.fill(trackGen, trackGenCur)
    }

    // ------------------------------------------------------------------
    // 辅助查询（供 DanmakuView / 测试使用）
    // ------------------------------------------------------------------

    /** 是否还有尚未发射的弹幕（View 可据此决定是否继续逐帧刷新） */
    val hasPending: Boolean
        get() {
            val arr = items
            var i = nextIndex
            val n = arr.size
            while (i < n) {
                if (!emittedFlags[i]) return true
                i++
            }
            return false
        }

    /** 当前画面上的弹幕条数 */
    val activeCount: Int get() = activeSize

    /** 已被标记为"发射过"的弹幕条数（测试用：验证 seek 不回放） */
    internal val emittedCount: Int
        get() {
            var c = 0
            for (i in emittedFlags.indices) {
                if (emittedFlags[i]) c++
            }
            return c
        }
}
