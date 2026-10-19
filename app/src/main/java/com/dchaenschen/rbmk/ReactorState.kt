package com.dchaenschen.rbmk

/**
 * 进程内共享状态。
 *
 * 动态壁纸服务、音频采集服务、设置界面都在同一个进程，所以用一个单例对象
 * 传数据最简单（跨进程才需要 AIDL/Broadcast）。
 *
 * 注意：Kotlin 的 object 里属性初始化有顺序要求 ——
 * 常量统一放在最上面，否则会报「Variable 'X' must be initialized」。
 */
object ReactorState {

    // ================================================================
    // 常量（必须放在所有使用它们的属性之前）
    // ================================================================

    const val BINS = 256
    const val FMIN_DEFAULT = 20.0
    const val FMAX_DEFAULT = 16000.0

    // 律动模式
    const val IDLE_OFF = "off"
    const val IDLE_WAVE = "wave"
    const val IDLE_BREATH = "breath"
    const val IDLE_PULSE = "pulse"
    const val IDLE_CHAOS = "chaos"

    // 音频来源
    const val SOURCE_SYSTEM = "system"
    const val SOURCE_NONE = "none"

    // 预览比例
    const val ASPECT_OUTER = "outer"
    const val ASPECT_INNER = "inner"

    // 控制棒分组方式
    const val GROUP_RANDOM = "random"     // 原项目的随机分组
    const val GROUP_RINGS = "rings"       // 按同心环分组
    const val GROUP_SECTORS = "sectors"   // 按扇形分区
    const val GROUP_MANUAL = "manual"     // 手动在俯视图上刷

    const val GROUP_MAX = 6

    // ================================================================
    // 实时数据
    // ================================================================

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

    // ================================================================
    // 可调参数（滑杆）
    // ================================================================

    @Volatile
    var fmin: Float = FMIN_DEFAULT.toFloat()
    @Volatile
    var fmax: Float = FMAX_DEFAULT.toFloat()

    @Volatile
    var sensitivity: Float = 1.4f

    @Volatile
    var smoothing: Float = 0.75f

    @Volatile
    var noiseFloor: Float = 0.05f

    @Volatile
    var gamma: Float = 1.7f

    @Volatile
    var maxHeight: Float = 4.8f

    @Volatile
    var baseHeight: Float = 0.07f

    @Volatile
    var angleDeg: Float = -18.3f

    @Volatile
    var tilt: Float = 0.58f

    @Volatile
    var zoom: Float = 1f

    @Volatile
    var offsetX: Float = 0f

    @Volatile
    var offsetY: Float = 0f

    @Volatile
    var freqTilt: Float = 3f

    @Volatile
    var lowShelf: Float = 0f

    @Volatile
    var accentRatio: Float = 0.15f

    // ================================================================
    // 律动模式
    // ================================================================

    @Volatile
    var idleMode: String = IDLE_WAVE

    /** 强制用律动模式替代音频驱动 */
    @Volatile
    var forceIdle: Boolean = false

    // ================================================================
    // 配色（分组 0..5 + 通用部件）
    // ================================================================

    @Volatile
    var colorPrimary: Int = 0xFF9AA4AE.toInt()     // 分组 0

    @Volatile
    var colorAccentA: Int = 0xFF8F6D51.toInt()     // 分组 1

    @Volatile
    var colorAccentB: Int = 0xFF617668.toInt()     // 分组 2

    @Volatile
    var colorGroup3: Int = 0xFF5D7A96.toInt()      // 分组 3

    @Volatile
    var colorGroup4: Int = 0xFFB49A5E.toInt()      // 分组 4

    @Volatile
    var colorGroup5: Int = 0xFF7A6B8A.toInt()      // 分组 5

    @Volatile
    var colorSide: Int = 0xFF4E565E.toInt()

    @Volatile
    var colorCap: Int = 0xFF5A636B.toInt()

    @Volatile
    var colorHighlight: Int = 0xFFEEF2F6.toInt()

    @Volatile
    var colorRing: Int = 0xFF505948.toInt()

    // ================================================================
    // 分组
    // ================================================================

    @Volatile
    var groupMode: String = GROUP_RANDOM

    /** 分组数（2..6） */
    @Volatile
    var groupCount: Int = 3

    /** 同心环模式下每组占多少格 */
    @Volatile
    var ringSize: Float = 3f

    /** 扇形模式下的扇区数 */
    @Volatile
    var sectorCount: Int = 6

    /**
     * 手动分组数据：31×31 = 961 个字符，每个字符 '0'..'5' 表示组号。
     * 空字符串表示尚未手动编辑过。
     */
    @Volatile
    var manualGroups: String = ""

    /** 手动分组每次修改 +1，让渲染层知道要重算 */
    @Volatile
    var manualRev: Int = 0

    /** 设置页里「当前正在刷的组」 */
    @Volatile
    var paintingGroup: Int = 0

    // ================================================================
    // 调试读数
    // ================================================================

    @Volatile
    var dbgPeak: Float = 0f

    @Volatile
    var dbgAvgAmp: Float = 0f

    @Volatile
    var dbgAgc: Float = 0f

    /**
     * 预览使用的屏幕比例。
     *
     * 渲染公式依赖画布长宽比（scale = min(w/45, h/37)，垂直锚点 h*0.55），
     * 所以预览必须用和目标屏幕**完全相同的比例**，否则调出来的位置/大小
     * 和壁纸实际效果对不上。
     */
    @Volatile
    var previewAspect: String = ASPECT_OUTER

    /**
     * 壁纸画布的**真实像素尺寸**，由 ReactorWallpaperService 在
     * onSurfaceChanged 时写入。
     *
     * 为什么重要：系统壁纸选择器里的预览就是按这个尺寸渲染的，
     * 所以 app 内预览必须用同一尺寸，位置和大小才能对上。
     * 为 0 表示还没拿到（壁纸没运行过）。
     */
    @Volatile
    var wallpaperWidth: Int = 0

    @Volatile
    var wallpaperHeight: Int = 0

    /** 是否显示歌名浮层 */
    @Volatile
    var showTitle: Boolean = true

    // ================================================================
    // 参数表 / 读写
    // ================================================================

    /** 当前音频来源（system / none） */
    @Volatile
    var audioSource: String = SOURCE_NONE

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
        Param("noise_floor", "噪声门限", 0f, 0.6f, 0.05f, hint = "低于此值当静音，调大可压掉底噪"),
        Param("gamma", "响应曲线 γ", 0.5f, 3f, 1.7f, hint = "越大越强调强频段，节律更明显"),
        Param("max_height", "最大高度", 1f, 8f, 4.8f, hint = "控制棒最高能升多高"),
        Param("base_height", "基础高度", 0f, 0.4f, 0.07f, hint = "静音时控制棒的高度"),
        Param("angle_deg", "视角角度", -90f, 90f, -18.3f, digits = 1, hint = "水平旋转"),
        Param("tilt", "俯视倾角", 0.3f, 1f, 0.58f, hint = "越小越接近平视"),
        Param("zoom", "缩放", 0.5f, 2f, 1f, hint = "阵列在屏幕上的大小"),
        Param("offset_x", "水平位置", -0.5f, 0.5f, 0f, hint = "整体左右平移（占屏宽比例）"),
        Param("offset_y", "垂直位置", -0.5f, 0.5f, 0f, hint = "整体上下平移（占屏高比例）"),
        Param("freq_tilt", "频率倾斜", -6f, 12f, 3f, digits = 1,
            hint = "每个倍频程升降多少 dB。调大=更强调高频"),
        Param("low_shelf", "低频增减", -12f, 12f, 0f, digits = 1,
            hint = "对 250Hz 以下单独增减 dB"),
        Param("freq_low", "频谱下限", 20f, 400f, 20f, digits = 0, hint = "低于该频率不参与分析（Hz）"),
        Param("freq_high", "频谱上限", 2000f, 16000f, 16000f, digits = 0, hint = "高于该频率不参与分析（Hz）"),
        Param("ring_size", "环宽", 1f, 8f, 3f, digits = 1, hint = "同心环分组时每组占多少格"),
        Param("sector_count", "扇区数", 2f, 12f, 6f, digits = 0, hint = "扇形分组时分成几个扇区"),
        Param("accent_ratio", "随机点缀比例", 0f, 0.8f, 0.15f, hint = "随机分组时非主色棒的比例"),
    )

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
            "ring_size" -> ringSize = value
            "sector_count" -> sectorCount = value.toInt().coerceIn(2, 12)
            "accent_ratio" -> accentRatio = value
        }
    }

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
        "ring_size" -> ringSize
        "sector_count" -> sectorCount.toFloat()
        "accent_ratio" -> accentRatio
        else -> 0f
    }

    // ================================================================
    // 分组颜色
    // ================================================================

    fun groupColor(i: Int): Int = when (i.coerceIn(0, GROUP_MAX - 1)) {
        0 -> colorPrimary
        1 -> colorAccentA
        2 -> colorAccentB
        3 -> colorGroup3
        4 -> colorGroup4
        else -> colorGroup5
    }

    fun setGroupColor(i: Int, color: Int) {
        when (i.coerceIn(0, GROUP_MAX - 1)) {
            0 -> colorPrimary = color
            1 -> colorAccentA = color
            2 -> colorAccentB = color
            3 -> colorGroup3 = color
            4 -> colorGroup4 = color
            else -> colorGroup5 = color
        }
    }

    fun resetColors() {
        colorPrimary = 0xFF9AA4AE.toInt()
        colorAccentA = 0xFF8F6D51.toInt()
        colorAccentB = 0xFF617668.toInt()
        colorGroup3 = 0xFF5D7A96.toInt()
        colorGroup4 = 0xFFB49A5E.toInt()
        colorGroup5 = 0xFF7A6B8A.toInt()
        colorSide = 0xFF4E565E.toInt()
        colorCap = 0xFF5A636B.toInt()
        colorHighlight = 0xFFEEF2F6.toInt()
        colorRing = 0xFF505948.toInt()
        accentRatio = 0.15f
    }
}
