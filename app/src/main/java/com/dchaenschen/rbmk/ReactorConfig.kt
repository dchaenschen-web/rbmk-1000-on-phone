package com.dchaenschen.rbmk

import android.content.Context

/**
 * 参数持久化的统一入口。
 *
 * 为什么需要单独抽出来：动态壁纸服务在**用户没打开设置页**时也会运行
 * （开机、壁纸重启等）。之前参数只在 MainActivity.onCreate 里载入，
 * 于是壁纸会一直用默认值 —— 表现为「设置里改了、壁纸没变」。
 * 现在壁纸引擎启动时也会调用 load()。
 */
object ReactorConfig {

    const val PREF = "reactor"

    fun load(context: Context) {
        val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        for (p in ReactorState.PARAMS) {
            ReactorState.apply(p.key, prefs.getFloat(p.key, p.def))
        }
        ReactorState.showTitle = prefs.getBoolean("show_title", true)
        ReactorState.idleMode = prefs.getString("idle_mode", ReactorState.IDLE_WAVE)
            ?: ReactorState.IDLE_WAVE
        ReactorState.forceIdle = prefs.getBoolean("force_idle", false)
    }
}
