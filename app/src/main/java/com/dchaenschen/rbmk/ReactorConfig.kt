package com.dchaenschen.rbmk

import android.content.Context

/**
 * 参数持久化的统一入口。
 *
 * 为什么需要单独抽出来：动态壁纸服务在**用户没打开设置页**时也会运行
 * （开机、壁纸重启等）。参数只在 MainActivity.onCreate 里载入的话，
 * 壁纸会一直用默认值 —— 表现为「设置里改了、壁纸没变」。
 * 所以壁纸引擎启动时也会调用 load()。
 */
object ReactorConfig {

    const val PREF = "reactor"

    // 配色默认值（与 ReactorState 中的初始值保持一致）
    private val DEF_PRIMARY: Int = 0xFF9AA4AE.toInt()
    private val DEF_ACCENT_A: Int = 0xFF8F6D51.toInt()
    private val DEF_ACCENT_B: Int = 0xFF617668.toInt()
    private val DEF_SIDE: Int = 0xFF4E565E.toInt()
    private val DEF_CAP: Int = 0xFF5A636B.toInt()
    private val DEF_HIGHLIGHT: Int = 0xFFEEF2F6.toInt()
    private val DEF_RING: Int = 0xFF505948.toInt()
    private val DEF_GROUP3: Int = 0xFF5D7A96.toInt()
    private val DEF_GROUP4: Int = 0xFFB49A5E.toInt()
    private val DEF_GROUP5: Int = 0xFF7A6B8A.toInt()

    fun load(context: Context) {
        val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)

        for (p in ReactorState.PARAMS) {
            ReactorState.apply(p.key, prefs.getFloat(p.key, p.def))
        }

        ReactorState.showTitle = prefs.getBoolean("show_title", true)
        ReactorState.idleMode = prefs.getString("idle_mode", ReactorState.IDLE_WAVE)
            ?: ReactorState.IDLE_WAVE
        ReactorState.forceIdle = prefs.getBoolean("force_idle", false)

        ReactorState.colorPrimary = prefs.getInt("color_primary", DEF_PRIMARY)
        ReactorState.colorAccentA = prefs.getInt("color_accent_a", DEF_ACCENT_A)
        ReactorState.colorAccentB = prefs.getInt("color_accent_b", DEF_ACCENT_B)
        ReactorState.colorSide = prefs.getInt("color_side", DEF_SIDE)
        ReactorState.colorCap = prefs.getInt("color_cap", DEF_CAP)
        ReactorState.colorHighlight = prefs.getInt("color_highlight", DEF_HIGHLIGHT)
        ReactorState.colorRing = prefs.getInt("color_ring", DEF_RING)
        ReactorState.colorGroup3 = prefs.getInt("color_group3", DEF_GROUP3)
        ReactorState.colorGroup4 = prefs.getInt("color_group4", DEF_GROUP4)
        ReactorState.colorGroup5 = prefs.getInt("color_group5", DEF_GROUP5)

        ReactorState.wallpaperWidth = prefs.getInt("wallpaper_w", 0)
        ReactorState.wallpaperHeight = prefs.getInt("wallpaper_h", 0)

        // 分组设置
        ReactorState.groupMode = prefs.getString(
            "group_mode", ReactorState.GROUP_RANDOM
        ) ?: ReactorState.GROUP_RANDOM
        ReactorState.groupCount = prefs.getInt("group_count", 3).coerceIn(2, ReactorState.GROUP_MAX)
        ReactorState.manualGroups = prefs.getString("manual_groups", "") ?: ""
    }

    /** 保存单个颜色 */
    fun saveColor(context: Context, key: String, color: Int) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putInt(key, color).apply()
    }

    /** 保存壁纸真实画布尺寸 */
    fun saveWallpaperSize(context: Context, width: Int, height: Int) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putInt("wallpaper_w", width)
            .putInt("wallpaper_h", height)
            .apply()
    }

    /** 保存分组设置 */
    fun saveGroups(context: Context) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putString("group_mode", ReactorState.groupMode)
            .putInt("group_count", ReactorState.groupCount)
            .putString("manual_groups", ReactorState.manualGroups)
            .apply()
    }

    /** 保存全部配色（用于「恢复默认配色」） */
    fun saveAllColors(context: Context) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putInt("color_primary", ReactorState.colorPrimary)
            .putInt("color_accent_a", ReactorState.colorAccentA)
            .putInt("color_accent_b", ReactorState.colorAccentB)
            .putInt("color_side", ReactorState.colorSide)
            .putInt("color_cap", ReactorState.colorCap)
            .putInt("color_highlight", ReactorState.colorHighlight)
            .putInt("color_ring", ReactorState.colorRing)
            .putInt("color_group3", ReactorState.colorGroup3)
            .putInt("color_group4", ReactorState.colorGroup4)
            .putInt("color_group5", ReactorState.colorGroup5)
            .apply()
    }
}
