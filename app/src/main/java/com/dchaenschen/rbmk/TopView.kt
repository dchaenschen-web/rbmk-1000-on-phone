package com.dchaenschen.rbmk

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot
import kotlin.math.min

/**
 * 控制棒阵列的**俯视图**。
 *
 * 用途：
 *   * 直观看清当前分组方式把阵列分成了哪些块（每组一种颜色）
 *   * 手动分组模式下，直接在图上点/拖，把格子刷成「当前选中的组」
 *
 * 坐标：x、z 都在 -15..15，只显示 r ≤ 15 的格子（共 709 个）。
 */
class TopView(context: Context) : View(context) {

    private val cellPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.2f)
        color = Color.parseColor("#59634f")
    }
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        color = Color.parseColor("#3a4438")
    }

    private var cell = 0f
    private var originX = 0f
    private var originY = 0f

    /** 手动刷格时回调，用于落盘 */
    var onPainted: (() -> Unit)? = null

    init {
        setBackgroundColor(Color.parseColor("#0e1210"))
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        // 正方形，最多 260dp 高
        val maxH = dp(260f).toInt()
        val side = min(w, maxH)
        setMeasuredDimension(side, side)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        cell = min(w, h) / (RodGroups.SIDE + 1f)
        originX = w / 2f
        originY = h / 2f

        // 背景
        canvas.drawColor(Color.parseColor("#0e1210"))

        // 外圈（半径 15 格）
        canvas.drawCircle(originX, originY, 15.6f * cell, outlinePaint)

        // 单元格
        for (x in -15..15) {
            for (z in -15..15) {
                val r = hypot(x.toFloat(), z.toFloat())
                if (r > 15f) continue
                val g = RodGroups.groupAt(x, z, r)
                cellPaint.color = ReactorState.groupColor(g)
                val left = originX + x * cell - cell * 0.42f
                val top = originY + z * cell - cell * 0.42f
                canvas.drawRect(left, top, left + cell * 0.84f, top + cell * 0.84f, cellPaint)
            }
        }

        // 中心十字参考线
        canvas.drawLine(originX - 15.6f * cell, originY, originX + 15.6f * cell, originY, ringPaint)
        canvas.drawLine(originX, originY - 15.6f * cell, originX, originY + 15.6f * cell, ringPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (ReactorState.groupMode != ReactorState.GROUP_MANUAL) {
            return super.onTouchEvent(event)
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                val gx = Math.round((event.x - originX) / cell)
                val gz = Math.round((event.y - originY) / cell)
                if (RodGroups.inGrid(gx, gz) &&
                    hypot(gx.toFloat(), gz.toFloat()) <= 15f
                ) {
                    RodGroups.paint(gx, gz, ReactorState.paintingGroup)
                    invalidate()
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                onPainted?.invoke()
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
