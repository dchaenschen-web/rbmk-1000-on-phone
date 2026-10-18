package com.dchaenschen.rbmk

import android.app.Activity
import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
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
 * 设置 / 授权 / 预览界面。
 *
 * 三件必须由用户手动完成的事（Android 权限设计决定，App 无法代劳）：
 *   1. MediaProjection 授权 —— 才能用 AudioPlaybackCapture 采集系统音频
 *   2. 通知使用权 —— 才能读到别的 App 正在播的歌名
 *   3. 设为动态壁纸 —— 系统壁纸选择器
 *
 * UI 全部用代码搭，不引 AndroidX，减少构建依赖。
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
        requestNotificationPermissionIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    // ---------------------------------------------------------------- 配置
    private fun loadConfig() {
        ReactorState.sensitivity = prefs.getFloat("sensitivity", 1.4f)
        ReactorState.smoothing = prefs.getFloat("smoothing", 0.75f)
        ReactorState.showTitle = prefs.getBoolean("show_title", true)
    }

    private fun saveConfig() {
        prefs.edit()
            .putFloat("sensitivity", ReactorState.sensitivity)
            .putFloat("smoothing", ReactorState.smoothing)
            .putBoolean("show_title", ReactorState.showTitle)
            .apply()
    }

    // ---------------------------------------------------------------- UI
    private fun buildUi(): View {
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(28))
            setBackgroundColor(Color.parseColor("#0a0d0b"))
        }
        scroll.addView(root)

        root.addView(label("RBMK-1000 控制棒", 26f, Color.parseColor("#e6ece0"), bold = true))
        root.addView(
            label(
                "动态壁纸 · 跟随正在播放的音乐",
                14f, Color.parseColor("#8e998a")
            )
        )
        root.addView(spacer(20))

        statusText = label("", 14f, Color.parseColor("#c9d1c9"))
        root.addView(statusText)
        root.addView(spacer(20))

        // 1. 音频授权
        root.addView(sectionTitle("1. 音频采集（AudioPlaybackCapture）"))
        projectionBtn = button("授权采集系统音频") { requestProjection() }
        root.addView(projectionBtn)
        root.addView(
            hint("授权一次即可；手机重启或系统回收后需要重新授权。")
        )
        root.addView(spacer(20))

        // 2. 歌名授权
        root.addView(sectionTitle("2. 读取正在播放的歌名"))
        notifBtn = button("打开通知使用权设置") { openNotificationAccess() }
        root.addView(notifBtn)
        root.addView(hint("在列表里找到「RBMK-1000」并打开。没有授权只是不显示歌名，不影响其他功能。"))
        root.addView(spacer(20))

        // 3. 壁纸
        root.addView(sectionTitle("3. 设为动态壁纸"))
        root.addView(button("选择动态壁纸") { setLiveWallpaper() })
        root.addView(button("选择背景图（可选）") { pickBackground() })
        root.addView(button("清除背景图") {
            prefs.edit().putString("background_uri", "").apply()
            Toast.makeText(this, "已清除，壁纸会变纯黑底", Toast.LENGTH_SHORT).show()
        })
        root.addView(hint("动态壁纸会取代原壁纸；选一张自己的图当背景，控制棒就会浮在它上面。"))
        root.addView(spacer(20))

        // 4. 参数
        root.addView(sectionTitle("4. 参数"))
        root.addView(
            slider("灵敏度", 0.3f, 3f, ReactorState.sensitivity) {
                ReactorState.sensitivity = it
                saveConfig()
            })
        root.addView(
            slider("平滑度", 0f, 0.95f, ReactorState.smoothing) {
                ReactorState.smoothing = it
                saveConfig()
            })

        val sw = Switch(this).apply {
            text = "显示歌名"
            textSize = 15f
            setTextColor(Color.parseColor("#e6ece0"))
            isChecked = ReactorState.showTitle
            setOnCheckedChangeListener { _, checked ->
                ReactorState.showTitle = checked
                saveConfig()
            }
        }
        root.addView(sw, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(12) })
        root.addView(spacer(24))

        // 5. 关于
        root.addView(sectionTitle("关于"))
        root.addView(button("开源许可") {
            startActivity(Intent(this, LicensesActivity::class.java))
        })
        root.addView(hint("基于 Andy Wang 的 reactor-audio-visualizer（MIT 许可证）。"))

        return scroll
    }

    private fun label(text: String, size: Float, color: Int, bold: Boolean = false): TextView =
        TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
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
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        }

    private fun slider(
        name: String, min: Float, max: Float, initial: Float,
        onChange: (Float) -> Unit,
    ): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10), 0, 0)
        }
        val valueText = label("$name：${"%.2f".format(initial)}", 14f, Color.parseColor("#c9d1c9"))
        box.addView(valueText)
        val sb = SeekBar(this).apply {
            this.max = 100
            progress = (((initial - min) / (max - min)) * 100).toInt().coerceIn(0, 100)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    val v = min + (max - min) * p / 100f
                    valueText.text = "$name：${"%.2f".format(v)}"
                    if (fromUser) onChange(v)
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
        parts += if (AudioCaptureService.running) "音频采集：已开启" else "音频采集：未开启"
        parts += if (hasNotificationAccess()) "歌名读取：已授权" else "歌名读取：未授权"
        statusText.text = parts.joinToString("    ")
        projectionBtn.text =
            if (AudioCaptureService.running) "重新授权采集系统音频" else "授权采集系统音频"
        notifBtn.text =
            if (hasNotificationAccess()) "通知使用权（已开启，可再次进入调整）"
            else "打开通知使用权设置"
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
                    // 注意：这里在 Intent.apply {} 内部，this 是 Intent 而不是 Activity
                    ComponentName(this@MainActivity, ReactorWallpaperService::class.java)
                )
            }
            startActivity(intent)
        } catch (e: Exception) {
            // 部分机型没有直接入口，退回通用壁纸设置
            try {
                startActivity(Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER))
            } catch (_: Exception) {
                Toast.makeText(this, "系统没有动态壁纸入口", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun pickBackground() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(intent, REQ_BACKGROUND)
    }

    @Deprecated("不使用 AndroidX，保持 startActivityForResult")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == REQ_PROJECTION) {
            if (resultCode == RESULT_OK && data != null) {
                AudioCaptureService.start(this, resultCode, data)
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

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 2001)
            } catch (_: Exception) {
            }
        }
        try {
            requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 2002)
        } catch (_: Exception) {
        }
    }
}
