package com.dchaenschen.rbmk

import android.app.Activity
import android.app.AlertDialog
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
import android.os.Handler
import android.os.Looper
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

    /** 调色面板中的一个颜色槽 */
    private data class ColorSlot(
        val key: String,
        val name: String,
        val get: () -> Int,
        val set: (Int) -> Unit,
    )

    private lateinit var prefs: SharedPreferences
    private lateinit var statusText: TextView
    private lateinit var projectionBtn: Button
    private lateinit var notifBtn: Button
    private var previewView: PreviewView? = null
    private var debugText: TextView? = null
    private var sizeInfoText: TextView? = null
    private val uiHandler = Handler(Looper.getMainLooper())
    private val debugTicker = object : Runnable {
        override fun run() {
            val t = debugText
            if (t != null) {
                t.text = "峰值 %.2f    AGC %.2f    平均振幅 %.2f    灵敏度 %.2f".format(
                    ReactorState.dbgPeak,
                    ReactorState.dbgAgc,
                    ReactorState.dbgAvgAmp,
                    ReactorState.sensitivity
                )
            }
            val si = sizeInfoText
            if (si != null) {
                val ww = ReactorState.wallpaperWidth
                val wh = ReactorState.wallpaperHeight
                si.text = if (ww > 0 && wh > 0) {
                    "按真实壁纸画布 ${ww}×${wh} 显示，与系统壁纸预览一致"
                } else {
                    "尚未获取真实画布尺寸（壁纸还没运行过），当前用估算比例"
                }
            }
            uiHandler.postDelayed(this, 400)
        }
    }

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
        uiHandler.post(debugTicker)
    }

    override fun onPause() {
        uiHandler.removeCallbacks(debugTicker)
        super.onPause()
    }

    // ---------------------------------------------------------------- 配置
    private fun loadConfig() {
        // 统一由 ReactorConfig 载入，壁纸服务启动时也走同一份逻辑
        ReactorConfig.load(this)
    }

    /** 保存预览里拖动/缩放得到的几何参数 */
    private fun saveGeometry() {
        prefs.edit()
            .putFloat("zoom", ReactorState.zoom)
            .putFloat("offset_x", ReactorState.offsetX)
            .putFloat("offset_y", ReactorState.offsetY)
            .apply()
    }

    /** 保存分组设置 */
    private fun saveGroupConfig() {
        ReactorConfig.saveGroups(this)
    }

    /** 组号 -> 持久化 key */
    private fun groupColorKey(g: Int): String = when (g) {
        0 -> "color_primary"
        1 -> "color_accent_a"
        2 -> "color_accent_b"
        3 -> "color_group3"
        4 -> "color_group4"
        else -> "color_group5"
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
        root.addView(spacer(dp(12)))

        statusText = label("", 14f, Color.parseColor("#c9d1c9"))
        root.addView(statusText)
        root.addView(spacer(dp(20)))

        // ============================================================
        // 一、控制棒（预览 + 频谱 + 参数，全部放在一起）
        // ============================================================
        root.addView(sectionTitle("一、控制棒"))

        // ---------------- 预览 ----------------
        root.addView(
            label(
                "位置与大小 · 单指拖动改位置，双指捏合改大小",
                14f, Color.parseColor("#c9d1c9")
            )
        )
        sizeInfoText = label("", 11f, Color.parseColor("#6b7568"))
        root.addView(sizeInfoText)
        val preview = PreviewView(this)
        previewView = preview
        preview.onChanged = { saveGeometry() }

        // FrameLayout 居中：预览要保持目标屏幕的长宽比，
        // 竖屏比例时宽度会小于屏幕宽。
        val previewFrame = android.widget.FrameLayout(this)
        previewFrame.addView(
            preview,
            android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.CENTER_HORIZONTAL
            )
        )
        root.addView(
            previewFrame, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        root.addView(button("切换估算比例（仅在未获取真实尺寸时生效）") {
            ReactorState.previewAspect =
                if (ReactorState.previewAspect == ReactorState.ASPECT_OUTER)
                    ReactorState.ASPECT_INNER
                else
                    ReactorState.ASPECT_OUTER
            preview.requestLayout()
            Toast.makeText(
                this,
                if (ReactorState.previewAspect == ReactorState.ASPECT_OUTER)
                    "预览比例：外屏 720×748" else "预览比例：内屏",
                Toast.LENGTH_SHORT
            ).show()
        })

        val resetBtn = button("重置位置和大小") {
            ReactorState.offsetX = 0f
            ReactorState.offsetY = 0f
            ReactorState.zoom = 1f
            saveGeometry()
            Toast.makeText(this, "已重置", Toast.LENGTH_SHORT).show()
        }
        root.addView(resetBtn)
        root.addView(spacer(dp(14)))

        // ---------------- 频谱 ----------------
        root.addView(
            label(
                "频谱 · 上：实时频谱　下：频率增益曲线",
                14f, Color.parseColor("#c9d1c9")
            )
        )
        root.addView(
            SpectrumView(this), LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(200)
            )
        )
        root.addView(
            hint("调「频率倾斜 / 低频增减」时，直接看下面这条增益曲线的形状。")
        )
        debugText = label("", 12f, Color.parseColor("#9aa596")).apply {
            typeface = Typeface.MONOSPACE
        }
        root.addView(debugText)
        root.addView(spacer(dp(14)))

        // ---------------- 无音乐时的律动模式 ----------------
        root.addView(
            label("运动预览 · 只演示律动，不参与位置和大小", 14f, Color.parseColor("#c9d1c9"))
        )

        // 运动预览：忽略位置与缩放，只用来挑律动模式
        val motionPreview = PreviewView(this).apply {
            geometryEditing = false
        }
        val motionFrame = android.widget.FrameLayout(this)
        motionFrame.addView(
            motionPreview,
            android.widget.FrameLayout.LayoutParams(
                dp(160), dp(160), android.view.Gravity.CENTER_HORIZONTAL
            )
        )
        root.addView(
            motionFrame, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        root.addView(spacer(dp(8)))

        root.addView(label("无音乐时的律动模式", 14f, Color.parseColor("#c9d1c9")))

        val modeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val modeButtons = mutableListOf<Button>()
        val modeKeys = listOf(
            ReactorState.IDLE_OFF to "关闭",
            ReactorState.IDLE_WAVE to "波浪",
            ReactorState.IDLE_BREATH to "呼吸",
            ReactorState.IDLE_PULSE to "脉冲",
            ReactorState.IDLE_CHAOS to "失控"
        )
        val refreshModes: () -> Unit = {
            modeButtons.forEachIndexed { i, b ->
                val on = modeKeys[i].first == ReactorState.idleMode
                b.setTextColor(
                    if (on) Color.parseColor("#0a0d0b") else Color.parseColor("#c9d1c9")
                )
                b.setBackgroundColor(
                    if (on) Color.parseColor("#dded84") else Color.parseColor("#1a201c")
                )
            }
        }
        for ((key, name) in modeKeys) {
            val b = Button(this).apply {
                text = name
                textSize = 12f
                isAllCaps = false
                setOnClickListener {
                    ReactorState.idleMode = key
                    prefs.edit().putString("idle_mode", key).apply()
                    refreshModes()
                }
            }
            modeRow.addView(
                b,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            )
            modeButtons.add(b)
        }
        root.addView(modeRow)
        refreshModes()

        root.addView(
            hint(
                "仅在无音乐时生效。\n" +
                        "波浪：从中心向外扩散的柔和波纹\n" +
                        "呼吸：整场缓慢同步起伏\n" +
                        "脉冲：快速向外扫过的同心波\n" +
                        "失控：每根棒独立无规律抖动 + 偶发爆发（模拟反应堆失控）"
            )
        )

        val forceSw = Switch(this).apply {
            text = "强制演示（忽略音频，只用律动模式）"
            textSize = 14f
            setTextColor(Color.parseColor("#c9d1c9"))
            isChecked = ReactorState.forceIdle
            setOnCheckedChangeListener { _, checked ->
                ReactorState.forceIdle = checked
                prefs.edit().putBoolean("force_idle", checked).apply()
            }
        }
        root.addView(
            forceSw, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        )
        root.addView(spacer(dp(14)))

        // ---------------- 参数 ----------------
        root.addView(
            label(
                "参数 · 调整后立刻生效（此区域可单独上下滑动）",
                14f, Color.parseColor("#c9d1c9")
            )
        )

        // 参数区单独滚动：高度固定为屏幕的 42%，这样上面的预览和频谱
        // 始终留在视野里，可以一边看图一边划参数。
        val paramScroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = true
            scrollBarStyle = View.SCROLLBARS_INSIDE_INSET
            isFillViewport = true
        }
        // 触摸参数区时禁止外层 ScrollView 抢事件，
        // 保证这个区域能独立上下滑动（否则外层会先滚）。
        paramScroll.setOnTouchListener { v, _ ->
            v.parent?.requestDisallowInterceptTouchEvent(true)
            false
        }
        val paramBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        paramScroll.addView(
            paramBox,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        for (p in ReactorState.PARAMS) {
            paramBox.addView(slider(p))
        }
        root.addView(
            paramScroll,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (resources.displayMetrics.heightPixels * 0.42f).toInt()
            )
        )
        root.addView(spacer(dp(10)))

        // ---------------- 调色 ----------------
        root.addView(label("配色 · 点色块调整", 14f, Color.parseColor("#c9d1c9")))

        val colorSlots = listOf(
            ColorSlot("color_primary", "顶面主色", { ReactorState.colorPrimary }) {
                ReactorState.colorPrimary = it
            },
            ColorSlot("color_accent_a", "点缀色 A（红棕）", { ReactorState.colorAccentA }) {
                ReactorState.colorAccentA = it
            },
            ColorSlot("color_accent_b", "点缀色 B（蓝绿）", { ReactorState.colorAccentB }) {
                ReactorState.colorAccentB = it
            },
            ColorSlot("color_side", "侧面", { ReactorState.colorSide }) {
                ReactorState.colorSide = it
            },
            ColorSlot("color_cap", "顶盖", { ReactorState.colorCap }) {
                ReactorState.colorCap = it
            },
            ColorSlot("color_highlight", "高光", { ReactorState.colorHighlight }) {
                ReactorState.colorHighlight = it
            },
            ColorSlot("color_ring", "外圈", { ReactorState.colorRing }) {
                ReactorState.colorRing = it
            },
        )

        for (slot in colorSlots) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(
                label(slot.name, 13f, Color.parseColor("#c9d1c9")),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            )
            val swatch = Button(this).apply {
                text = ""
                isAllCaps = false
                setBackgroundColor(slot.get())
            }
            swatch.setOnClickListener {
                pickColor(slot.name, slot.get()) { picked ->
                    slot.set(picked)
                    swatch.setBackgroundColor(picked)
                    ReactorConfig.saveColor(this@MainActivity, slot.key, picked)
                }
            }
            row.addView(
                swatch,
                LinearLayout.LayoutParams(dp(72), dp(42)).apply { topMargin = dp(4) }
            )
            root.addView(row)
        }

        root.addView(button("恢复默认配色") {
            ReactorState.resetColors()
            ReactorConfig.saveAllColors(this)
            recreate()
        })
        root.addView(spacer(dp(14)))

        // ---------------- 分组配色 ----------------
        root.addView(label("分组 · 控制棒按组上色", 14f, Color.parseColor("#c9d1c9")))

        val groupModeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val groupModeButtons = mutableListOf<Button>()
        val groupModeKeys = listOf(
            ReactorState.GROUP_RANDOM to "随机",
            ReactorState.GROUP_RINGS to "同心环",
            ReactorState.GROUP_SECTORS to "扇形",
            ReactorState.GROUP_MANUAL to "手动"
        )
        val refreshGroupModes: () -> Unit = {
            groupModeButtons.forEachIndexed { i, b ->
                val on = groupModeKeys[i].first == ReactorState.groupMode
                b.setTextColor(
                    if (on) Color.parseColor("#0a0d0b") else Color.parseColor("#c9d1c9")
                )
                b.setBackgroundColor(
                    if (on) Color.parseColor("#dded84") else Color.parseColor("#1a201c")
                )
            }
        }
        for ((key, name) in groupModeKeys) {
            val b = Button(this).apply {
                text = name
                textSize = 12f
                isAllCaps = false
                setOnClickListener {
                    val prevMode = ReactorState.groupMode
                    ReactorState.groupMode = key
                    if (key == ReactorState.GROUP_MANUAL &&
                        ReactorState.manualGroups.length != RodGroups.SIDE * RodGroups.SIDE
                    ) {
                        // 用切换前的模式生成底稿，而不是用手动模式自己算
                        RodGroups.fillManualFromMode(prevMode)
                    }
                    saveGroupConfig()
                    refreshGroupModes()
                    previewView?.invalidate()
                }
            }
            groupModeRow.addView(
                b,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            )
            groupModeButtons.add(b)
        }
        root.addView(groupModeRow)
        refreshGroupModes()

        // 分组数
        root.addView(label("分组数量", 13f, Color.parseColor("#c9d1c9")))
        val countRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val countButtons = mutableListOf<Button>()
        val refreshCounts: () -> Unit = {
            countButtons.forEachIndexed { i, b ->
                val n = i + 2
                val on = n == ReactorState.groupCount
                b.setTextColor(
                    if (on) Color.parseColor("#0a0d0b") else Color.parseColor("#c9d1c9")
                )
                b.setBackgroundColor(
                    if (on) Color.parseColor("#dded84") else Color.parseColor("#1a201c")
                )
            }
        }
        for (n in 2..ReactorState.GROUP_MAX) {
            val b = Button(this).apply {
                text = "$n"
                textSize = 12f
                isAllCaps = false
                setOnClickListener {
                    ReactorState.groupCount = n
                    saveGroupConfig()
                    refreshCounts()
                    previewView?.invalidate()
                }
            }
            countRow.addView(
                b,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            )
            countButtons.add(b)
        }
        root.addView(countRow)
        refreshCounts()

        // 俯视图
        root.addView(
            hint("俯视图：直观看到分组结果（手动模式下可直接点/拖刷格）")
        )
        val topView = TopView(this)
        topView.onPainted = { saveGroupConfig() }
        val topFrame = android.widget.FrameLayout(this)
        topFrame.addView(
            topView,
            android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.CENTER_HORIZONTAL
            )
        )
        root.addView(
            topFrame, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        root.addView(button("以当前模式填充手动分组") {
            RodGroups.fillManualFromMode(ReactorState.groupMode)
            saveGroupConfig()
            topView.invalidate()
            previewView?.invalidate()
            Toast.makeText(this, "已填充", Toast.LENGTH_SHORT).show()
        })
        root.addView(button("清空手动分组（全部归到组 1）") {
            RodGroups.clearManual()
            saveGroupConfig()
            topView.invalidate()
            previewView?.invalidate()
            Toast.makeText(this, "已清空", Toast.LENGTH_SHORT).show()
        })

        // 手动刷格时选组
        root.addView(label("手动刷格：选择要刷的组", 13f, Color.parseColor("#c9d1c9")))
        val paintRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val paintButtons = mutableListOf<Button>()
        val refreshPaint: () -> Unit = {
            paintButtons.forEachIndexed { i, b ->
                val on = i == ReactorState.paintingGroup
                b.setTextColor(
                    if (on) Color.parseColor("#0a0d0b") else Color.parseColor("#c9d1c9")
                )
                b.setBackgroundColor(
                    if (on) ReactorState.groupColor(i) else Color.parseColor("#1a201c")
                )
            }
        }
        for (g in 0 until ReactorState.GROUP_MAX) {
            val b = Button(this).apply {
                text = "组${g + 1}"
                textSize = 11f
                isAllCaps = false
                setOnClickListener {
                    ReactorState.paintingGroup = g
                    refreshPaint()
                }
            }
            paintRow.addView(
                b,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            )
            paintButtons.add(b)
        }
        root.addView(paintRow)
        refreshPaint()

        // 组颜色
        root.addView(label("各组颜色 · 点色块调整", 13f, Color.parseColor("#c9d1c9")))
        val groupColorRefresh = mutableListOf<() -> Unit>()
        for (rowIdx in 0 until 2) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (col in 0 until 3) {
                val g = rowIdx * 3 + col
                val cell = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(4), 0, dp(4), 0)
                }
                cell.addView(label("组${g + 1}", 11f, Color.parseColor("#9aa596")))
                val swatch = Button(this).apply {
                    text = ""
                    isAllCaps = false
                    setBackgroundColor(ReactorState.groupColor(g))
                }
                swatch.setOnClickListener {
                    pickColor("组${g + 1} 颜色", ReactorState.groupColor(g)) { picked ->
                        ReactorState.setGroupColor(g, picked)
                        swatch.setBackgroundColor(picked)
                        ReactorConfig.saveColor(this@MainActivity, groupColorKey(g), picked)
                        refreshPaint()
                        topView.invalidate()
                        previewView?.invalidate()
                    }
                }
                cell.addView(
                    swatch,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(38)
                    )
                )
                groupColorRefresh.add {
                    swatch.setBackgroundColor(ReactorState.groupColor(g))
                }
                row.addView(
                    cell,
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                )
            }
            root.addView(row)
        }
        root.addView(spacer(dp(14)))

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

        // ============================================================
        // 二、音频
        // ============================================================
        root.addView(sectionTitle("二、音频"))
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
        root.addView(spacer(dp(20)))

        // ============================================================
        // 三、歌名
        // ============================================================
        root.addView(sectionTitle("三、歌名"))
        notifBtn = button("打开通知使用权设置") { openNotificationAccess() }
        root.addView(notifBtn)
        root.addView(hint("在列表里找到「RBMK-1000」并打开。未授权只是不显示歌名，不影响其他功能。"))
        root.addView(spacer(dp(20)))

        // ============================================================
        // 四、壁纸
        // ============================================================
        root.addView(sectionTitle("四、壁纸"))
        root.addView(button("设为动态壁纸") { setLiveWallpaper() })
        root.addView(button("选择背景图（可选）") { pickBackground() })
        root.addView(button("清除背景图") {
            prefs.edit().putString("background_uri", "").apply()
            previewView?.reloadBackground()
            Toast.makeText(this, "已清除，壁纸会变纯黑底", Toast.LENGTH_SHORT).show()
        })
        root.addView(button("还原为普通壁纸") { restoreStaticWallpaper() })
        root.addView(
            hint(
                "动态壁纸会取代原来的静态壁纸。想换回去点「还原为普通壁纸」，" +
                        "再挑一张图即可 —— 系统不会保存原来的壁纸，所以需要重新选。"
            )
        )
        root.addView(spacer(dp(20)))

        // ============================================================
        // 五、关于
        // ============================================================
        root.addView(sectionTitle("五、关于"))
        root.addView(button("开源许可") {
            startActivity(Intent(this, LicensesActivity::class.java))
        })
        root.addView(
            hint(
                "基于 Andy Wang 的 reactor-audio-visualizer（MIT 许可证）的 Android 移植。\n" +
                        "原项目：https://github.com/wzk112/reactor-audio-visualizer"
            )
        )

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

    /**
     * 简易 HSV 取色器：色相 / 饱和度 / 明度 三个滑杆 + 实时预览块。
     * 不引第三方库，保持零依赖。
     */
    private fun pickColor(title: String, initial: Int, onPicked: (Int) -> Unit) {
        val hsv = FloatArray(3)
        Color.colorToHSV(initial, hsv)

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(14), dp(20), dp(4))
        }

        val previewSwatch = View(this).apply { setBackgroundColor(initial) }
        box.addView(
            previewSwatch,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56))
        )

        fun addSlider(name: String, max: Int, init: Float, apply: (Float) -> Unit) {
            val tv = label("$name：${init.toInt()}", 12f, Color.parseColor("#c9d1c9"))
            box.addView(tv)
            val seek = SeekBar(this).apply {
                this.max = max
                progress = init.toInt().coerceIn(0, max)
            }
            seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    tv.text = "$name：$p"
                    apply(p.toFloat())
                    previewSwatch.setBackgroundColor(Color.HSVToColor(hsv))
                }

                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
            box.addView(seek)
        }

        addSlider("色相", 360, hsv[0]) { hsv[0] = it }
        addSlider("饱和度", 100, hsv[1] * 100f) { hsv[1] = it / 100f }
        addSlider("明度", 100, hsv[2] * 100f) { hsv[2] = it / 100f }

        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(box)
            .setPositiveButton("确定") { _, _ -> onPicked(Color.HSVToColor(hsv)) }
            .setNegativeButton("取消", null)
            .show()
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
                previewView?.reloadBackground()
                Toast.makeText(
                    this,
                    "背景图已设置。预览已更新，壁纸需重新点「设为动态壁纸」才生效",
                    Toast.LENGTH_LONG
                ).show()
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
