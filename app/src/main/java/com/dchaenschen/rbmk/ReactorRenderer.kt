package com.dchaenschen.rbmk

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import kotlin.math.cos
import kotlin.math.sin

/**
 * 把 RodArray 画到 Canvas 上。
 *
 * 配色全部来自 ReactorState，可在设置页里用取色器随意调整：
 *   顶面主色 / 点缀色 A（红棕）/ 点缀色 B（蓝绿）/ 侧面 / 顶盖 / 高光 / 外圈
 * 触发点缀色的控制棒比例由 ReactorState.accentRatio 决定。
 *
 * 所有视角与强度参数也都从 ReactorState 读，可在设置界面实时调整。
 */
class ReactorRenderer {

    private val rods = RodArray()

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
    }
    private val path = Path()

    private var lastFrameNanos = 0L

    /**
     * 每帧调用。
     *
     * @param useGeometry true  = 应用位置/缩放（位置与大小预览、壁纸本体）
     *                    false = 只演示动画，忽略 offset 与 zoom（运动预览）
     */
    fun draw(
        canvas: Canvas,
        width: Float,
        height: Float,
        nowNanos: Long,
        useGeometry: Boolean = true,
    ) {
        val dt = if (lastFrameNanos == 0L) 1f else
            ((nowNanos - lastFrameNanos) / 16_666_667f).coerceIn(0.2f, 3f)
        lastFrameNanos = nowNanos

        val bins = ReactorState.spectrum
        rods.step(bins, dt, nowNanos / 1_000_000L)
        RodGroups.ensure(rods.rods)

        canvas.drawColor(Color.TRANSPARENT)

        val angle = Math.toRadians(ReactorState.angleDeg.toDouble()).toFloat()
        val tilt = ReactorState.tilt
        val zoom = if (useGeometry) ReactorState.zoom else 1f

        // 阵列整体平移放到画布变换里，这样歌名浮层不受影响
        canvas.save()
        if (useGeometry) {
            canvas.translate(
                width * ReactorState.offsetX,
                height * ReactorState.offsetY
            )
        }
        drawRings(canvas, width, height, angle, tilt, zoom)
        drawRods(canvas, width, height, angle, tilt, zoom)
        canvas.restore()

        if (ReactorState.showTitle) drawTitle(canvas, width, height)
    }

    // ---------------------------------------------------------------- 颜色工具
    /** 整体明暗缩放 */
    private fun shade(c: Int, f: Float): Int = Color.rgb(
        (Color.red(c) * f).toInt().coerceIn(0, 255),
        (Color.green(c) * f).toInt().coerceIn(0, 255),
        (Color.blue(c) * f).toInt().coerceIn(0, 255)
    )

    /** 向白色提亮 amount（0..1） */
    private fun lift(c: Int, amount: Float): Int {
        val a = amount.coerceIn(0f, 1f)
        return Color.rgb(
            (Color.red(c) + (255 - Color.red(c)) * a).toInt().coerceIn(0, 255),
            (Color.green(c) + (255 - Color.green(c)) * a).toInt().coerceIn(0, 255),
            (Color.blue(c) + (255 - Color.blue(c)) * a).toInt().coerceIn(0, 255)
        )
    }

    // ---------------------------------------------------------------- 外圈
    private fun drawRings(
        canvas: Canvas, w: Float, h: Float,
        angle: Float, tilt: Float, zoom: Float,
    ) {
        val unit = rods.scale(w, h, zoom) / 15f

        strokePaint.color = shade(ReactorState.colorRing, 0.18f)
        strokePaint.strokeWidth = (20f * unit).coerceAtLeast(2f)
        circle(canvas, 16.4f, w, h, angle, tilt, zoom)

        strokePaint.color = ReactorState.colorRing
        strokePaint.strokeWidth = 2f
        circle(canvas, 16.5f, w, h, angle, tilt, zoom)

        strokePaint.color = lift(ReactorState.colorRing, 0.25f)
        strokePaint.strokeWidth = 1f
        circle(canvas, 16f, w, h, angle, tilt, zoom)
    }

    private fun circle(
        canvas: Canvas, r: Float, w: Float, h: Float,
        angle: Float, tilt: Float, zoom: Float,
    ) {
        path.reset()
        val steps = 120
        for (i in 0..steps) {
            val a = i.toFloat() / steps * 2 * Math.PI.toFloat()
            val p = rods.project(
                cos(a) * r, sin(a) * r, 0f, w, h, angle, tilt, zoom
            )
            if (i == 0) path.moveTo(p[0], p[1]) else path.lineTo(p[0], p[1])
        }
        canvas.drawPath(path, strokePaint)
    }

    // ---------------------------------------------------------------- 控制棒
    private fun drawRods(
        canvas: Canvas, w: Float, h: Float,
        angle: Float, tilt: Float, zoom: Float,
    ) {
        // 按 rz 从远到近排序，保证遮挡正确
        val sorted = rods.rods.sortedBy { r -> r.x * sin(angle) + r.z * cos(angle) }

        val baseH = ReactorState.baseHeight
        val maxH = ReactorState.maxHeight.coerceAtLeast(0.1f)

        // ---- 每帧取一次配色，避免每根棒重复计算 ----
        val sideBase = ReactorState.colorSide
        val sideLit = lift(shade(sideBase, 1.0f), 0.16f)
        val sideDark = shade(sideBase, 0.72f)
        val sideMid = shade(sideBase, 0.86f)
        val capColor = ReactorState.colorCap
        val hiColor = ReactorState.colorHighlight
        // 预取各组颜色，避免每根棒都调 groupColor()
        val groupColors = IntArray(ReactorState.GROUP_MAX) { ReactorState.groupColor(it) }

        for (rod in sorted) {
            val hh = rod.h
            val a = rods.project(rod.x - .44f, rod.z - .44f, hh, w, h, angle, tilt, zoom)
            val b = rods.project(rod.x + .44f, rod.z - .44f, hh, w, h, angle, tilt, zoom)
            val c = rods.project(rod.x + .44f, rod.z + .44f, hh, w, h, angle, tilt, zoom)
            val d = rods.project(rod.x - .44f, rod.z + .44f, hh, w, h, angle, tilt, zoom)
            val a0 = rods.project(rod.x - .44f, rod.z - .44f, 0f, w, h, angle, tilt, zoom)
            val b0 = rods.project(rod.x + .44f, rod.z - .44f, 0f, w, h, angle, tilt, zoom)
            val c0 = rods.project(rod.x + .44f, rod.z + .44f, 0f, w, h, angle, tilt, zoom)
            val d0 = rods.project(rod.x - .44f, rod.z + .44f, 0f, w, h, angle, tilt, zoom)

            // 用当前高度反推振幅，做侧面亮度变化
            val amp = ((hh - baseH) / maxH).coerceIn(0f, 1f)

            quad(canvas, a, a0, b0, b, sideBase)
            quad(canvas, b, b0, c0, c, lift(sideLit, amp * 0.18f))
            quad(canvas, c, c0, d0, d, sideDark)
            quad(canvas, d, d0, a0, a, sideMid)

            // 顶面颜色由「组」决定，再用 seed 做轻微明暗纹理，避免整块死板
            val groupColor = groupColors[rod.group.coerceIn(0, ReactorState.GROUP_MAX - 1)]
            val top = shade(groupColor, 0.90f + rod.seed * 0.20f)
            quad(canvas, a, b, c, d, lift(top, amp * 0.16f))

            // 中心帽盖
            val p = rods.project(
                rod.x.toFloat(), rod.z.toFloat(), hh + .01f, w, h, angle, tilt, zoom
            )
            val s = rods.scale(w, h, zoom)
            paint.color = capColor
            canvas.drawOval(
                p[0] - s * .12f, p[1] - s * .12f * tilt,
                p[0] + s * .12f, p[1] + s * .12f * tilt, paint
            )
            if (s > 13 && rod.seed > .6f) {
                paint.color = hiColor
                canvas.drawRect(
                    p[0] - s * .3f, p[1] - s * .19f,
                    p[0] - s * .3f + 1.5f, p[1] - s * .19f + 1.5f, paint
                )
            }
        }
    }

    private fun quad(
        canvas: Canvas,
        p1: FloatArray, p2: FloatArray, p3: FloatArray, p4: FloatArray,
        color: Int,
    ) {
        path.reset()
        path.moveTo(p1[0], p1[1])
        path.lineTo(p2[0], p2[1])
        path.lineTo(p3[0], p3[1])
        path.lineTo(p4[0], p4[1])
        path.close()
        paint.color = color
        canvas.drawPath(path, paint)
    }

    // ---------------------------------------------------------------- 歌名
    private fun drawTitle(canvas: Canvas, w: Float, h: Float) {
        if (!ReactorState.hasMusic && ReactorState.title.isBlank()) return
        val title = ReactorState.title
        val artist = ReactorState.artist

        textPaint.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        textPaint.textSize = w * 0.042f
        textPaint.color = Color.WHITE
        textPaint.setShadowLayer(6f, 0f, 2f, Color.BLACK)

        var y = h * 0.10f
        canvas.drawText(ellipsize(title, textPaint, w * 0.86f), w / 2f, y, textPaint)

        if (artist.isNotBlank()) {
            textPaint.typeface = Typeface.SANS_SERIF
            textPaint.textSize = w * 0.032f
            textPaint.color = Color.parseColor("#c9d1c9")
            y += textPaint.textSize * 1.35f
            canvas.drawText(ellipsize(artist, textPaint, w * 0.86f), w / 2f, y, textPaint)
        }
        textPaint.clearShadowLayer()
    }

    private fun ellipsize(s: String, p: Paint, maxWidth: Float): String {
        if (p.measureText(s) <= maxWidth) return s
        var end = s.length
        while (end > 1 && p.measureText(s.substring(0, end) + "…") > maxWidth) end--
        return s.substring(0, end) + "…"
    }
}
