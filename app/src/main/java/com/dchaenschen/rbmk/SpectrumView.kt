package com.dchaenschen.rbmk

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import kotlin.math.abs
import kotlin.math.log10

/**
 * 实时频谱可视化。
 *
 * 布局：
 *   上半部分 —— 当前频谱柱状图（256 段，0..1）
 *   下半部分 —— 当前参数产生的增益曲线（±24dB，0dB 在正中间）
 *   底部     —— 频率刻度（20Hz / 100Hz / 1kHz / 10kHz）
 *
 * 用途：调「频率倾斜 / 低频增减 / 频谱下限 / 频谱上限」时，
 * 立刻能看到曲线形状怎么变、哪些频段被压、哪些被抬。
 */
class SpectrumView(context: Context) : View(context) {

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val curvePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.6f)
        color = Color.parseColor("#dded84")
    }
    private val curveFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#33dded84")
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        color = Color.parseColor("#2a332e")
    }
    private val zeroPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        color = Color.parseColor("#5c6b5f")
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#7f8a7c")
        textSize = dp(9f)
        textAlign = Paint.Align.CENTER
    }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#9aa596")
        textSize = dp(9f)
    }
    private val path = Path()

    private val barsFraction = 0.58f     // 上面 58% 画频谱柱
    private val curveTop = 0.63f         // 增益曲线区从 63% 开始
    private val curveBottom = 0.90f      // 到 90% 结束
    private val labelY = 0.985f

    /** 曲线显示范围，每帧按实际增益自动计算，避免超出边界被切平 */
    private var dbTop = 12f
    private var dbBottom = -12f

    init {
        setBackgroundColor(Color.parseColor("#0e1210"))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        SpectrumBands.ensure(48000, 1024)

        canvas.drawColor(Color.parseColor("#0e1210"))

        drawBars(canvas, w, h)
        drawCurve(canvas, w, h)
        drawLabels(canvas, w, h)

        // 持续刷新（频谱是实时数据）
        postInvalidateOnAnimation()
    }

    // ------------------------------------------------------------ 频谱柱
    private fun drawBars(canvas: Canvas, w: Float, h: Float) {
        val bins = ReactorState.spectrum
        val n = minOf(bins.size, SpectrumBands.COUNT)
        if (n <= 0) return

        val areaTop = dp(14f)
        val areaBottom = h * barsFraction
        val areaH = (areaBottom - areaTop).coerceAtLeast(1f)
        val barW = w / n

        for (i in 0 until n) {
            val v = bins[i].coerceIn(0f, 1f)
            val bh = v * areaH
            // 低频偏暖、高频偏冷，方便辨认频段
            val t = i.toFloat() / n
            val r = (200 + 40 * (1f - t)).toInt().coerceIn(0, 255)
            val g = (190 - 30 * abs(t - 0.5f) * 2).toInt().coerceIn(0, 255)
            val b = (150 + 100 * t).toInt().coerceIn(0, 255)
            barPaint.color = Color.rgb(r, g, b)
            val x0 = i * barW
            canvas.drawRect(
                x0, areaBottom - bh,
                x0 + barW - 0.6f, areaBottom,
                barPaint
            )
        }

        // 顶部标题
        canvas.drawText("当前频谱", dp(4f), dp(10f), titlePaint)
    }

    // ------------------------------------------------------------ 增益曲线
    private fun drawCurve(canvas: Canvas, w: Float, h: Float) {
        val n = SpectrumBands.COUNT
        val top = h * curveTop
        val bottom = h * curveBottom
        val mid = (top + bottom) / 2f
        val span = (bottom - top).coerceAtLeast(1f)

        // ---- 按曲线实际范围自动缩放 ----
        // 之前固定映射到 ±24dB，频率倾斜调大后曲线顶到边界就被切平，
        // 看起来像"到顶就不动了"。这里每帧统计实际增益范围并留出余量。
        var lo = 0f
        var hi = 0f
        for (i in 0 until n) {
            val v = SpectrumBands.gainDb[i]
            if (v < lo) lo = v
            if (v > hi) hi = v
        }
        val pad = 2f
        val half = maxOf(hi, -lo).coerceAtLeast(4f) + pad
        dbTop = half
        dbBottom = -half
        val dbRange = (dbTop - dbBottom).coerceAtLeast(1f)

        // 背景网格：0dB 中线
        canvas.drawLine(0f, mid, w, mid, zeroPaint)
        canvas.drawLine(0f, top, w, top, gridPaint)
        canvas.drawLine(0f, bottom, w, bottom, gridPaint)

        // 曲线（含填充）
        path.reset()
        path.moveTo(0f, mid)
        for (i in 0 until n) {
            val x = (i + 0.5f) / n * w
            val db = SpectrumBands.gainDb[i].coerceIn(dbBottom, dbTop)
            val y = mid - db / dbRange * span
            if (i == 0) {
                path.moveTo(x, y)
            } else {
                path.lineTo(x, y)
            }
        }
        canvas.drawPath(path, curvePaint)

        // 填充：曲线与 0dB 之间
        val fill = Path()
        fill.moveTo(0f, mid)
        for (i in 0 until n) {
            val x = (i + 0.5f) / n * w
            val db = SpectrumBands.gainDb[i].coerceIn(dbBottom, dbTop)
            val y = mid - db / dbRange * span
            fill.lineTo(x, y)
        }
        fill.lineTo(w, mid)
        fill.close()
        canvas.drawPath(fill, curveFill)

        // 标题与 dB 标注（显示真实范围）
        canvas.drawText("增益曲线  自动范围", dp(4f), top - dp(3f), titlePaint)
        labelPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText("+${dbTop.toInt()}", w - dp(3f), top + dp(8f), labelPaint)
        canvas.drawText("0", w - dp(3f), mid + dp(3f), labelPaint)
        canvas.drawText("${dbBottom.toInt()}", w - dp(3f), bottom - dp(2f), labelPaint)
        labelPaint.textAlign = Paint.Align.CENTER
    }

    // ------------------------------------------------------------ 频率刻度
    private fun drawLabels(canvas: Canvas, w: Float, h: Float) {
        val n = SpectrumBands.COUNT
        if (n <= 0) return
        val y = h * labelY

        val markers = intArrayOf(20, 50, 100, 200, 500, 1000, 2000, 5000, 10000)
        for (f in markers) {
            var idx = n - 1
            for (i in 0 until n) {
                if (SpectrumBands.centerHz[i] >= f) {
                    idx = i
                    break
                }
            }
            val x = ((idx + 0.5f) / n * w).coerceIn(dp(14f), w - dp(14f))
            canvas.drawLine(x, h * curveBottom, x, h * curveBottom + dp(2f), gridPaint)
            val text = if (f >= 1000) "${f / 1000}k" else "$f"
            canvas.drawText(text, x, y, labelPaint)
        }
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density
}
