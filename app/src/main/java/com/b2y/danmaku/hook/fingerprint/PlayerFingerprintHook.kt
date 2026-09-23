package com.b2y.danmaku.hook.fingerprint

import com.b2y.danmaku.core.Log
import com.b2y.danmaku.core.VideoSessionController
import com.b2y.danmaku.hook.PlaybackClockHolder
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.reflect.Method

/**
 * 通过 DEX 指纹在**被混淆**的 YouTube 代码中定位关键方法。
 *
 * 目前解析两类目标（指纹来自社区长期验证的稳定字符串常量）：
 *
 * 1. 视频 ID —— 包含字符串 `"Null initialPlayabilityStatus"` 的方法会收到一个 `PlayerResponseModel`，
 *    该方法内部会调用 `PlayerResponseModel.getVideoId()`。我们据此定位接口方法并在 hook 中反射取值。
 *
 * 2. 播放时间 —— 包含字符串 `"Media progress reported outside media playback: "` 的方法内部会
 *    构造一个持有视频时间的对象（`<init>(J...)`）。hook 该构造函数即可拿到精确到毫秒的播放位置，
 *    用于替代/校正媒体会话推算出的位置。
 *
 * 这两层都是"尽力而为"：任何一步失败都只记录日志，模块会退回 [MediaSessionWatcher] 的方案。
 */
object PlayerFingerprintHook {

    private const val STRING_VIDEO_ID = "Null initialPlayabilityStatus"

    /**
     * 注意这里用的是**子串**：APK 里的真实常量是
     * `"Media progress reported outside media playback: %s"`（带格式化后缀）。
     */
    private const val STRING_VIDEO_TIME = "Media progress reported outside media playback"

    /** 视频时间指纹是否成功挂上（挂上后媒体会话只负责播放/暂停与倍速） */
    @Volatile
    var videoTimeHookActive: Boolean = false
        private set

    @Volatile
    var videoIdHookActive: Boolean = false
        private set

    fun install(classLoader: ClassLoader, sourceDir: String?, splitDirs: Array<String>?) {
        val paths = ArrayList<String>()
        sourceDir?.let { paths.add(it) }
        splitDirs?.let { paths.addAll(it.filterNotNull()) }
        if (paths.isEmpty()) {
            Log.w("无法获取 APK 路径，跳过 DEX 指纹扫描")
            return
        }

        val index = ApkDexIndex(paths)

        // 先做一次轻量的"是否可能成功"检查，避免无谓地完整解析 dex
        try {
            installVideoIdHook(classLoader, index)
        } catch (t: Throwable) {
            Log.w("视频 ID 指纹失败", t)
        }
        try {
            installVideoTimeHook(classLoader, index)
        } catch (t: Throwable) {
            Log.w("播放时间指纹失败", t)
        }
    }

    // ------------------------------------------------------------------ 视频 ID

    private fun installVideoIdHook(classLoader: ClassLoader, index: ApkDexIndex) {
        val loc = index.findMethodsUsingString(STRING_VIDEO_ID, 4).firstOrNull() ?: run {
            Log.i("未找到视频 ID 指纹（$STRING_VIDEO_ID），改用媒体会话元数据")
            return
        }
        val ref = loc.methodRef
        Log.i("视频 ID 指纹命中: ${ref.dottedClass}#${ref.name}(${ref.paramTypes.joinToString()})")

        val paramDescriptor = ref.paramTypes.firstOrNull { it.startsWith("L") } ?: run {
            Log.w("视频 ID 指纹方法没有对象参数，放弃")
            return
        }

        val dex = index.dexes().firstOrNull { it.classByName(loc.classDescriptor) != null }
        val getter = dex?.invokedMethodsOf(loc.methodDef.codeOff)
            ?.firstOrNull {
                it.classDescriptor == paramDescriptor &&
                    it.returnType == "Ljava/lang/String;" &&
                    it.paramTypes.isEmpty() &&
                    it.name != "<init>"
            } ?: run {
            Log.w("未能在指纹方法里定位 getVideoId()，放弃视频 ID 指纹")
            return
        }
        Log.i("视频 ID getter: ${getter.dottedClass}#${getter.name}()")

        val targetMethod = findMethod(classLoader, ref.dottedClass, ref.name, ref.paramTypes)
        val getterMethod = findMethod(classLoader, getter.dottedClass, getter.name, getter.paramTypes)
        if (targetMethod == null || getterMethod == null) {
            Log.w("反射定位方法失败 (target=$targetMethod getter=$getterMethod)")
            return
        }

        XposedBridge.hookMethod(targetMethod, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                try {
                    val model = param.args.firstOrNull() ?: return
                    val id = getterMethod.invoke(model) as? String ?: return
                    if (id.isBlank()) return
                    VideoSessionController.onVideoIdFromFingerprint(id)
                } catch (t: Throwable) {
                    Log.d("读取视频 ID 失败: ${t.message}")
                }
            }
        })
        videoIdHookActive = true
        Log.i("视频 ID 指纹 hook 安装成功")
    }

    // ------------------------------------------------------------------ 播放时间

    private fun installVideoTimeHook(classLoader: ClassLoader, index: ApkDexIndex) {
        val loc = index.findMethodsUsingStringContaining(STRING_VIDEO_TIME, 4).firstOrNull() ?: run {
            Log.i("未找到播放时间指纹，使用媒体会话推算位置")
            return
        }
        val dex = index.dexes().firstOrNull { it.classByName(loc.classDescriptor) != null } ?: return
        val invokes = dex.invokedMethodsOf(loc.methodDef.codeOff).filter { it.name == "<init>" }
        val ctorRef = invokes.firstOrNull { it.paramTypes.firstOrNull() == "J" }
            ?: invokes.singleOrNull()
            ?: run {
                Log.w("播放时间指纹里找到 ${invokes.size} 个构造函数，无法确定目标")
                return
            }
        Log.i("播放时间指纹命中: ${ctorRef.dottedClass}<init>(${ctorRef.paramTypes.joinToString()})")

        val ctor = try {
            XposedHelpers.findConstructorExact(
                ctorRef.dottedClass,
                classLoader,
                *javaTypes(ctorRef.paramTypes)
            )
        } catch (t: Throwable) {
            Log.w("定位构造函数失败: ${ctorRef.dottedClass}", t)
            null
        } ?: return

        val firstIsLong = ctorRef.paramTypes.firstOrNull() == "J"
        if (!firstIsLong) {
            Log.w("构造函数第一个参数不是 long，放弃播放时间指纹")
            return
        }

        XposedBridge.hookMethod(ctor, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                try {
                    val time = param.args.firstOrNull() as? Long ?: return
                    if (time < 0) return
                    val clock = PlaybackClockHolder.clock
                    clock.submit(time, clock.playbackSpeed, clock.isPlaying)
                } catch (t: Throwable) {
                    Log.d("提交播放时间失败: ${t.message}")
                }
            }
        })
        videoTimeHookActive = true
        Log.i("播放时间指纹 hook 安装成功（同步精度提升到毫秒级）")
    }

    // ------------------------------------------------------------------ 工具

    private fun findMethod(
        classLoader: ClassLoader,
        dottedClass: String,
        name: String,
        paramDescriptors: List<String>
    ): Method? = try {
        XposedHelpers.findMethodExact(dottedClass, classLoader, name, *javaTypes(paramDescriptors))
    } catch (t: Throwable) {
        Log.w("findMethodExact 失败: $dottedClass#$name", t)
        null
    }

    /** 把 DEX 类型描述符转成 XposedHelpers 能识别的 Java 类型名 */
    private fun javaTypes(descriptors: List<String>): Array<Any> =
        descriptors.map { descriptorToJavaType(it) }.toTypedArray()

    fun descriptorToJavaType(descriptor: String): Any = when (descriptor) {
        "V" -> "void"
        "Z" -> "boolean"
        "B" -> "byte"
        "S" -> "short"
        "C" -> "char"
        "I" -> "int"
        "J" -> "long"
        "F" -> "float"
        "D" -> "double"
        else -> {
            if (descriptor.startsWith("[")) {
                // 数组类型交给 XposedHelpers 按名字解析
                descriptor.replace('/', '.')
            } else if (descriptor.startsWith("L") && descriptor.endsWith(";")) {
                descriptor.substring(1, descriptor.length - 1).replace('/', '.')
            } else {
                descriptor.replace('/', '.')
            }
        }
    }
}
