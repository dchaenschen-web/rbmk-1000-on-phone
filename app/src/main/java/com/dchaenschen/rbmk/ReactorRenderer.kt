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
 * 配色是从桌面版移植的「金属色」方案：
 *   顶面按 seed 分三档 —— 红棕 / 蓝绿 / 金属银（红棕与蓝绿保持原样）
 *   侧面与顶盖用钢灰系
 *   外圈是原版的装饰环
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

    // 视角参数（和网页版默认一致）
    private val angle = -0.32f
    private val tilt = 0.58f
    private val zoom = 1f

    private var lastFrameNanos = 0L

    /** 每帧调用；返回是否需要继续绘制 */
    fun draw(canvas: Canvas, width: Float, height: Float, nowNanos: Long) {
        val dt = if (lastFrameNanos == 0L) 1f else
            ((nowNanos - lastFrameNanos) / 16_666_667f).coerceIn(0.2f, 3f)
        lastFrameNanos = nowNanos

        val bins = ReactorState.spectrum
        rods.step(bins, ReactorState.sensitivity, dt, ReactorState.smoothing)

        canvas.drawColor(Color.TRANSPARENT)

        drawRings(canvas, width, height)
        drawRods(canvas, width, height)
        if (ReactorState.showTitle) drawTitle(canvas, width, height)
    }

    // ---------------------------------------------------------------- 外圈
    private fun drawRings(canvas: Canvas, w: Float, h: Float) {
        strokePaint.color = Color.parseColor("#060b07")
        strokePaint.strokeWidth = 20f * rods.scale(w, h, zoom) / 15f
        circle(canvas, 16.4f, w, h)

        strokePaint.color = Color.parseColor("#505948")
        strokePaint.strokeWidth = 2f
        circle(canvas, 16.5f, w, h)

        strokePaint.color = Color.parseColor("#878969")
        strokePaint.strokeWidth = 1f
        circle(canvas, 16f, w, h)
    }

    private fun circle(canvas: Canvas, r: Float, w: Float, h: Float) {
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
    private fun drawRods(canvas: Canvas, w: Float, h: Float) {
        // 按 rz 从远到近排序，保证遮挡关系正确
        val sorted = rods.rods.sortedBy { r ->
            val rz = r.x * sin(angle) + r.z * cos(angle)
            rz
        }

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

            // amp 用于侧面随音量变亮（和网页版一致）
            val amp = ((hh - 0.07f) / 4.8f).coerceIn(0f, 1f)

            quad(canvas, a, a0, b0, b, Color.parseColor("#4e565e"))
            quad(
                canvas, b, b0, c0, c,
                Color.rgb(72 + (amp * 34).toInt(), 78 + (amp * 34).toInt(), 86 + (amp * 34).toInt())
            )
            quad(canvas, c, c0, d0, d, Color.parseColor("#3b434b"))
            quad(canvas, d, d0, a0, a, Color.parseColor("#454d55"))

            // 顶面：保持红棕 / 蓝绿两档，其余是金属银
            val base: IntArray = when {
                rod.seed > .92f -> intArrayOf(143, 109, 81)
                rod.seed > .85f -> intArrayOf(97, 118, 104)
                else -> intArrayOf(
                    150 + (rod.seed * 38).toInt(),
                    157 + (rod.seed * 38).toInt(),
                    166 + (rod.seed * 38).toInt(),
                )
            }
            val top = Color.rgb(
                (base[0] + amp * 43).toInt().coerceIn(0, 255),
                (base[1] + amp * 40).toInt().coerceIn(0, 255),
                (base[2] + amp * 24).toInt().coerceIn(0, 255),
            )
            quad(canvas, a, b, c, d, top)

            // 中心帽盖
            val p = rods.project(
                rod.x.toFloat(), rod.z.toFloat(), hh + .01f, w, h, angle, tilt, zoom
            )
            val s = rods.scale(w, h, zoom)
            paint.color = Color.parseColor("#5a636b")
            canvas.drawOval(
                p[0] - s * .12f, p[1] - s * .12f * tilt,
                p[0] + s * .12f, p[1] + s * .12f * tilt, paint
            )
            if (s > 13 && rod.seed > .6f) {
                paint.color = Color.parseColor("#eef2f6")
                canvas.drawRect(p[0] - s * .3f, p[1] - s * .19f, p[0] - s * .3f + 1.5f,
                    p[1] - s * .19f + 1.5f, paint)
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
