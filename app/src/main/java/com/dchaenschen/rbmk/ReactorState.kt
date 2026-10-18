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
    const val FMIN = 20.0
    const val FMAX = 16000.0

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

    // ---- 用户可调参数（由 MainActivity 从 SharedPreferences 载入）----
    @Volatile
    var sensitivity: Float = 1.4f
    @Volatile
    var smoothing: Float = 0.75f

    /** 是否显示歌名浮层 */
    @Volatile
    var showTitle: Boolean = true
}
