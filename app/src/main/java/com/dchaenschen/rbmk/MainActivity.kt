package com.dchaenschen.rbmk

import android.app.Activity
import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/**
 * 设置 / 授权 / 参数调试界面。
 *
 * 三件必须由用户手动完成的事（Android 权限设计决定，App 无法代劳）：
 *   1. MediaProjection 授权 —— 才能用 AudioPlaybackCapture 采集系统音频
 *   2. 通知使用权 —— 才能读到别的 App 正在播的歌名
 *   3. 设为动态壁纸 —— 系统壁纸选择器
 *
 * 所有渲染参数都可实时调整，方便按喜好调。
 */
class MainActivity : Activity() {

    companion object {
        private const val REQ_PROJECTION = 1001
        private const val REQ_BACKGROUND = 1002
    }

    private lateinit var prefs: SharedPreferences
    private lateinit var statusText: TextView
    private lateinit var projectionBtn: Button
    private lateinit var notifBtn: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("reactor", MODE_PRIVATE)
        loadConfig()
        setContentView(buildUi())
        requestRuntimePermissions()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    // ---------------------------------------------------------------- 配置
    private fun loadConfig() {
        for (p in ReactorState.PARAMS) {
            ReactorState.apply(p.key, prefs.getFloat(p.key, p.def))
        }
        ReactorState.showTitle = prefs.getBoolean("show_title", true)
    }

    /** 保存预览里拖动/缩放得到的几何参数 */
    private fun saveGeometry() {
        prefs.edit()
            .putFloat("zoom", ReactorState.zoom)
            .putFloat("offset_x", ReactorState.offsetX)
            .putFloat("offset_y", ReactorState.offsetY)
            .apply()
    }

    private fun saveParam(key: String, value: Float) {
        prefs.edit().putFloat(key, value).apply()
    }

    private fun resetDefaults() {
        prefs.edit().clear().apply()
        loadConfig()
        recreate()
    }

    // ---------------------------------------------------------------- UI
    private fun buildUi(): View {
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(36))
            setBackgroundColor(Color.parseColor("#0a0d0b"))
        }
        scroll.addView(root)

        root.addView(label("RBMK-1000 控制棒", 26f, Color.parseColor("#e6ece0"), bold = true))
        root.addView(label("动态壁纸 · 跟随正在播放的音乐", 14f, Color.parseColor("#8e998a")))
        root.addView(spacer(dp(16)))

        statusText = label("", 14f, Color.parseColor("#c9d1c9"))
        root.addView(statusText)
        root.addView(spacer(dp(16)))

        // ============ 预览：调整位置和大小 ============
        root.addView(sectionTitle("预览 · 调整位置和大小"))
        val preview = PreviewView(this)
        preview.onChanged = { saveGeometry() }
        root.addView(
            preview, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(260)
            )
        )
        root.addView(hint("单指拖动 = 移动位置　双指捏合 = 缩放。调整立刻保存并作用到壁纸。"))
        root.addView(button("重置位置和大小") {
            ReactorState.offsetX = 0f
            ReactorState.offsetY = 0f
            ReactorState.zoom = 1f
            saveGeometry()
            Toast.makeText(this, "已重置", Toast.LENGTH_SHORT).show()
        })
        root.addView(spacer(dp(16)))

        // ============ 1. 音频来源 ============
        root.addView(sectionTitle("1. 系统音频采集"))
        projectionBtn = button("系统音频 · 精确（需一次录屏授权）") { requestProjection() }
        root.addView(projectionBtn)
        root.addView(button("停止采集") {
            AudioCaptureService.stop(this)
            ReactorState.audioSource = ReactorState.SOURCE_NONE
            Toast.makeText(this, "已停止采集", Toast.LENGTH_SHORT).show()
        })
        root.addView(
            hint(
                "Android 把「采集本机播放的音频」绑在 MediaProjection 上，" +
                        "所以会弹「录制或投射屏幕」授权框。\n" +
                        "本应用只使用其中的音频通道：不读取画面、不录屏、不保存、不上传。\n" +
                        "授权是会话级的：手机重启或进程被杀之后需要重新点一次授权。"
            )
        )
        root.addView(button("允许后台运行（防被杀）") { requestBatteryExemption() })
        root.addView(hint("三星会清理后台应用导致采集中断，加入白名单可显著减少。"))
        root.addView(spacer(dp(16)))

        // ============ 2. 歌名 ============
        root.addView(sectionTitle("2. 读取正在播放的歌名"))
        notifBtn = button("打开通知使用权设置") { openNotificationAccess() }
        root.addView(notifBtn)
        root.addView(hint("在列表里找到「RBMK-1000」并打开。未授权只是不显示歌名，不影响其他功能。"))
        root.addView(spacer(dp(16)))

        // ============ 3. 壁纸 ============
        root.addView(sectionTitle("3. 壁纸"))
        root.addView(button("设为动态壁纸") { setLiveWallpaper() })
        root.addView(button("选择背景图（可选）") { pickBackground() })
        root.addView(button("清除背景图") {
            prefs.edit().putString("background_uri", "").apply()
            Toast.makeText(this, "已清除，壁纸会变纯黑底", Toast.LENGTH_SHORT).show()
        })
        root.addView(button("还原为普通壁纸") { restoreStaticWallpaper() })
        root.addView(
            hint(
                "动态壁纸会取代原来的静态壁纸。想换回去点「还原为普通壁纸」，" +
                        "再挑一张图即可 —— 系统不会保存原来的壁纸，所以需要重新选。"
            )
        )
        root.addView(spacer(dp(16)))

        // ============ 4. 参数调试 ============
        root.addView(sectionTitle("4. 参数调试（实时生效）"))
        root.addView(hint("调整后动态壁纸立刻变化，不需要重新应用。"))
        for (p in ReactorState.PARAMS) {
            root.addView(slider(p))
        }

        val sw = Switch(this).apply {
            text = "显示歌名"
            textSize = 15f
            setTextColor(Color.parseColor("#e6ece0"))
            isChecked = ReactorState.showTitle
            setOnCheckedChangeListener { _, checked ->
                ReactorState.showTitle = checked
                prefs.edit().putBoolean("show_title", checked).apply()
            }
        }
        root.addView(
            sw, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) }
        )

        root.addView(button("恢复默认参数") { resetDefaults() })
        root.addView(spacer(dp(24)))

        // ============ 5. 关于 ============
        root.addView(sectionTitle("关于"))
        root.addView(button("开源许可") {
            startActivity(Intent(this, LicensesActivity::class.java))
        })
        root.addView(hint("基于 Andy Wang 的 reactor-audio-visualizer（MIT 许可证）的 Android 移植。"))

        return scroll
    }

    private fun label(text: String, size: Float, color: Int, bold: Boolean = false): TextView =
        TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(4), 0, dp(4))
        }

    private fun sectionTitle(t: String): TextView =
        label(t, 17f, Color.parseColor("#dded84"), bold = true)

    private fun hint(t: String): TextView =
        label(t, 12f, Color.parseColor("#7f8a7c")).apply {
            setPadding(0, dp(6), 0, dp(6))
        }

    private fun spacer(px: Int): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(1, px)
    }

    private fun button(text: String, onClick: () -> Unit): Button =
        Button(this).apply {
            this.text = text
            textSize = 15f
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        }

    private fun slider(p: ReactorState.Param): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10), 0, 0)
        }
        val initial = ReactorState.value(p.key)
        val fmt = "%." + p.digits + "f"
        val valueText = label(
            "${p.label}：${fmt.format(initial)}",
            14f, Color.parseColor("#c9d1c9")
        )
        box.addView(valueText)
        if (p.hint.isNotEmpty()) {
            box.addView(label(p.hint, 11f, Color.parseColor("#6b7568")))
        }

        val sb = SeekBar(this).apply {
            max = 1000
            progress = (((initial - p.min) / (p.max - p.min)) * 1000).toInt().coerceIn(0, 1000)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, prog: Int, fromUser: Boolean) {
                    val v = p.min + (p.max - p.min) * prog / 1000f
                    valueText.text = "${p.label}：${fmt.format(v)}"
                    if (fromUser) {
                        ReactorState.apply(p.key, v)
                        saveParam(p.key, v)
                    }
                }

                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        box.addView(sb)
        return box
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    // ---------------------------------------------------------------- 状态
    private fun refreshStatus() {
        val parts = mutableListOf<String>()
        val srcName =
            if (ReactorState.audioSource == ReactorState.SOURCE_SYSTEM) "系统音频" else "未采集"
        parts += if (AudioCaptureService.running) "采集：$srcName" else "采集：未开启"
        parts += if (hasNotificationAccess()) "歌名读取：已授权" else "歌名读取：未授权"
        val err = AudioCaptureService.lastError
        statusText.text = parts.joinToString("    ") +
                if (err.isBlank()) "" else "\n⚠ $err"
        projectionBtn.text =
            if (ReactorState.audioSource == ReactorState.SOURCE_SYSTEM)
                "系统音频 · 使用中（点此重新授权）"
            else
                "系统音频 · 精确（需一次录屏授权）"
        notifBtn.text =
            if (hasNotificationAccess()) "通知使用权（已开启）" else "打开通知使用权设置"
    }

    private fun hasNotificationAccess(): Boolean {
        val flat = Settings.Secure.getString(
            contentResolver, "enabled_notification_listeners"
        ) ?: return false
        return flat.contains(packageName)
    }

    // ---------------------------------------------------------------- 授权
    private fun requestProjection() {
        val mpm = getSystemService(MediaProjectionManager::class.java) ?: return
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_PROJECTION)
    }

    private fun openNotificationAccess() {
        try {
            startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
        } catch (_: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            } catch (_: Exception) {
            }
        }
    }

    private fun setLiveWallpaper() {
        try {
            val intent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).apply {
                putExtra(
                    WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                    // 这里在 Intent.apply {} 内部，this 是 Intent 而不是 Activity
                    ComponentName(
                        this@MainActivity,
                        ReactorWallpaperService::class.java
                    )
                )
            }
            startActivity(intent)
        } catch (_: Exception) {
            try {
                startActivity(Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER))
            } catch (_: Exception) {
                Toast.makeText(this, "系统没有动态壁纸入口", Toast.LENGTH_LONG).show()
            }
        }
    }

    /** 打开系统静态壁纸选择器，用来把动态壁纸换回普通壁纸 */
    private fun restoreStaticWallpaper() {
        try {
            // 注意：WallpaperManager 没有 ACTION_CHANGE_WALLPAPER，
            // 设置静态壁纸用的是系统 action：android.intent.action.SET_WALLPAPER
            startActivity(Intent(Intent.ACTION_SET_WALLPAPER))
        } catch (_: Exception) {
            Toast.makeText(
                this,
                "请到 设置 → 壁纸和风格 里重新选择一张壁纸",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun pickBackground() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            )
        }
        startActivityForResult(intent, REQ_BACKGROUND)
    }

    @Deprecated("不使用 AndroidX，保持 startActivityForResult")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == REQ_PROJECTION) {
            if (resultCode == RESULT_OK && data != null) {
                AudioCaptureService.startSystem(this, resultCode, data)
                Toast.makeText(this, "开始采集系统音频", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "已取消授权", Toast.LENGTH_SHORT).show()
            }
            refreshStatus()
            return
        }

        if (requestCode == REQ_BACKGROUND) {
            if (resultCode == RESULT_OK && data?.data != null) {
                val uri: Uri = data.data!!
                try {
                    contentResolver.takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (_: Exception) {
                }
                prefs.edit().putString("background_uri", uri.toString()).apply()
                Toast.makeText(this, "背景图已设置，重新应用壁纸后生效", Toast.LENGTH_LONG).show()
            }
        }
    }

    /** 申请忽略电池优化：三星会主动杀后台，导致音频采集中断 */
    private fun requestBatteryExemption() {
        try {
            val pm = getSystemService(android.os.PowerManager::class.java)
            if (pm?.isIgnoringBatteryOptimizations(packageName) == true) {
                Toast.makeText(this, "已在电池优化白名单中", Toast.LENGTH_SHORT).show()
                return
            }
            val i = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                .setData(android.net.Uri.parse("package:$packageName"))
            startActivity(i)
        } catch (_: Exception) {
            Toast.makeText(this, "请手动到 设置 → 电池 → 应用启动管理 里允许后台运行", Toast.LENGTH_LONG).show()
        }
    }

    private fun requestRuntimePermissions() {
        val need = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33) need += "android.permission.POST_NOTIFICATIONS"
        need += android.Manifest.permission.RECORD_AUDIO
        try {
            requestPermissions(need.toTypedArray(), 2001)
        } catch (_: Exception) {
        }
    }
}
