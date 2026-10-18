package com.dchaenschen.rbmk

/**
 * 进程内共享状态。
 *
 * 动态壁纸服务、音频采集服务、设置界面都在同一个进程，所以用一个单例对象
 * 传数据最简单（跨进程才需要 AIDL/Broadcast）。
 */
object ReactorState {

    // ---- 常量（必须声明在使用它们的属性之前）----
    const val BINS = 256
    const val FMIN_DEFAULT = 20.0
    const val FMAX_DEFAULT = 16000.0

    /** 频谱分析范围（Hz），可由用户在设置里调整 */
    @Volatile
    var fmin: Float = FMIN_DEFAULT.toFloat()

    @Volatile
    var fmax: Float = FMAX_DEFAULT.toFloat()

    /** 256 段对数频谱，值域 0f..1f。由 AudioCaptureService 持续更新。 */
    @Volatile
    var spectrum: FloatArray = FloatArray(BINS)

    /** 是否正在采集音频 */
    @Volatile
    var capturing: Boolean = false

    /** 当前播放的歌曲信息（由 MusicListenerService 更新） */
    @Volatile
    var title: String = ""
    @Volatile
    var artist: String = ""
    @Volatile
    var hasMusic: Boolean = false

    // ---------------------------------------------------------------- 可调参数
    // 全部暴露到设置界面，默认值对齐桌面版的观感。

    /** 灵敏度：整体放大倍数 */
    @Volatile
    var sensitivity: Float = 1.4f

    /** 平滑度：0 = 立刻响应（抖），0.95 = 非常柔和（迟钝） */
    @Volatile
    var smoothing: Float = 0.75f

    /** 噪声门限：低于该值的频段视为静音 */
    @Volatile
    var noiseFloor: Float = 0.10f

    /** 响应曲线 γ：>1 更强调强频段（更有节律），<1 更平均 */
    @Volatile
    var gamma: Float = 1.7f

    /** 单根棒最大高度（世界单位） */
    @Volatile
    var maxHeight: Float = 4.8f

    /** 静止时的基础高度 */
    @Volatile
    var baseHeight: Float = 0.07f

    /** 视角旋转角（度） */
    @Volatile
    var angleDeg: Float = -18.3f

    /** 等距俯视系数：0.3 = 很斜，1.0 = 接近正上方 */
    @Volatile
    var tilt: Float = 0.58f

    /** 缩放 */
    @Volatile
    var zoom: Float = 1f

    /** 水平偏移（占屏宽的比例，-0.5..0.5） */
    @Volatile
    var offsetX: Float = 0f

    /** 垂直偏移（占屏高的比例，-0.5..0.5） */
    @Volatile
    var offsetY: Float = 0f

    // ---------------------------------------------------------------- 频率调控
    /** 频率倾斜：每个倍频程提升/衰减多少 dB（以 1kHz 为基准）。
     *  正值强调高频，负值强调低频。 */
    @Volatile
    var freqTilt: Float = 3f

    /** 低频搁架：对 250Hz 以下再额外增减多少 dB（线性过渡到 250Hz）。 */
    @Volatile
    var lowShelf: Float = 0f

    // ---- 调试读数（设置页展示，用于确认参数是否生效）----
    @Volatile
    var dbgPeak: Float = 0f
    @Volatile
    var dbgAgc: Float = 0f
    @Volatile
    var dbgAvgAmp: Float = 0f

    /** 是否显示歌名浮层 */
    @Volatile
    var showTitle: Boolean = true

    /**
     * 音频来源。
     *
     * SYSTEM = AudioPlaybackCapture（需一次「录屏」授权，能精确捕捉本机播放的音乐）
     * MIC    = 麦克风（不需要授权弹窗、不会被系统回收，但戴耳机时听不到）
     * NONE   = 不采集，用待机波动
     */
    const val SOURCE_SYSTEM = "system"
    const val SOURCE_MIC = "mic"
    const val SOURCE_NONE = "none"

    @Volatile
    var audioSource: String = SOURCE_NONE

    /** 参数说明表：设置界面据此自动生成滑杆 */
    data class Param(
        val key: String,
        val label: String,
        val min: Float,
        val max: Float,
        val def: Float,
        val digits: Int = 2,
        val hint: String = "",
    )

    val PARAMS: List<Param> = listOf(
        Param("sensitivity", "灵敏度", 0.3f, 3f, 1.4f, hint = "整体放大倍数"),
        Param("smoothing", "平滑度", 0f, 0.95f, 0.75f, hint = "越大越柔和、越迟钝"),
        Param("noise_floor", "噪声门限", 0f, 0.6f, 0.10f, hint = "低于此值当静音，调大可压掉底噪"),
        Param("gamma", "响应曲线 γ", 0.5f, 3f, 1.7f, hint = "越大越强调强频段，节律更明显"),
        Param("max_height", "最大高度", 1f, 8f, 4.8f, hint = "控制棒最高能升多高"),
        Param("base_height", "基础高度", 0f, 0.4f, 0.07f, hint = "静音时控制棒的高度"),
        Param("angle_deg", "视角角度", -90f, 90f, -18.3f, digits = 1, hint = "水平旋转"),
        Param("tilt", "俯视倾角", 0.3f, 1f, 0.58f, hint = "越小越接近平视"),
        Param("zoom", "缩放", 0.5f, 2f, 1f, hint = "阵列在屏幕上的大小"),
        Param("offset_x", "水平位置", -0.5f, 0.5f, 0f, hint = "整体左右平移（占屏宽比例）"),
        Param("offset_y", "垂直位置", -0.5f, 0.5f, 0f, hint = "整体上下平移（占屏高比例）"),
        Param("freq_tilt", "频率倾斜", -6f, 12f, 3f, digits = 1,
            hint = "每个倍频程升降多少 dB。调大=更强调高频，负值=更强调低频"),
        Param("low_shelf", "低频增减", -12f, 12f, 0f, digits = 1,
            hint = "对 250Hz 以下单独增减 dB。中间一直顶满时调成负值"),
        Param("freq_low", "频谱下限", 20f, 400f, 20f, digits = 0,
            hint = "低于该频率不参与分析（Hz）"),
        Param("freq_high", "频谱上限", 2000f, 16000f, 16000f, digits = 0,
            hint = "高于该频率不参与分析（Hz）"),
    )

    /** 把某个参数写入内存 */
    fun apply(key: String, value: Float) {
        when (key) {
            "sensitivity" -> sensitivity = value
            "smoothing" -> smoothing = value
            "noise_floor" -> noiseFloor = value
            "gamma" -> gamma = value
            "max_height" -> maxHeight = value
            "base_height" -> baseHeight = value
            "angle_deg" -> angleDeg = value
            "tilt" -> tilt = value
            "zoom" -> zoom = value
            "offset_x" -> offsetX = value
            "offset_y" -> offsetY = value
            "freq_tilt" -> freqTilt = value
            "low_shelf" -> lowShelf = value
            "freq_low" -> fmin = value
            "freq_high" -> fmax = value
        }
    }

    /** 读取某个参数的当前值 */
    fun value(key: String): Float = when (key) {
        "sensitivity" -> sensitivity
        "smoothing" -> smoothing
        "noise_floor" -> noiseFloor
        "gamma" -> gamma
        "max_height" -> maxHeight
        "base_height" -> baseHeight
        "angle_deg" -> angleDeg
        "tilt" -> tilt
        "zoom" -> zoom
        "offset_x" -> offsetX
        "offset_y" -> offsetY
        "freq_tilt" -> freqTilt
        "low_shelf" -> lowShelf
        "freq_low" -> fmin
        "freq_high" -> fmax
        else -> 0f
    }
}
