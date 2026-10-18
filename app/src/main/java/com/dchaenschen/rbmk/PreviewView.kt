package com.dchaenschen.rbmk

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

/**
 * 设置页里的实时预览。
 *
 * 交互：
 *   * 单指拖动 -> 调整阵列在屏幕上的位置（offsetX / offsetY）
 *   * 双指捏合 -> 调整缩放（zoom）
 *
 * 直接用 ReactorRenderer 绘制，所以预览效果与壁纸完全一致。
 * 参数改动会实时保存到 SharedPreferences，壁纸那边立刻生效。
 */
class PreviewView(context: Context) : View(context) {

    private val renderer = ReactorRenderer()

    private var lastX = 0f
    private var lastY = 0f
    private var lastDist = 0f
    private var dragging = false

    /** 拖动/缩放结束后回调，用于落盘保存 */
    var onChanged: (() -> Unit)? = null

    init {
        setBackgroundColor(Color.BLACK)
        isClickable = true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return
        renderer.draw(canvas, width.toFloat(), height.toFloat(), System.nanoTime())
        // 持续重绘（预览也是动画）
        postInvalidateOnAnimation()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x
                lastY = event.y
                dragging = true
                lastDist = 0f
                return true
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount >= 2) {
                    lastDist = distance(event)
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount >= 2) {
                    // 双指缩放
                    val d = distance(event)
                    if (lastDist > 10f && d > 10f) {
                        val factor = d / lastDist
                        ReactorState.zoom = (ReactorState.zoom * factor)
                            .coerceIn(0.5f, 2f)
                        lastDist = d
                    }
                } else if (dragging) {
                    // 单指拖动位置
                    val dx = (event.x - lastX) / width
                    val dy = (event.y - lastY) / height
                    if (abs(dx) > 0.0005f || abs(dy) > 0.0005f) {
                        ReactorState.offsetX =
                            (ReactorState.offsetX + dx).coerceIn(-0.5f, 0.5f)
                        ReactorState.offsetY =
                            (ReactorState.offsetY + dy).coerceIn(-0.5f, 0.5f)
                        lastX = event.x
                        lastY = event.y
                    }
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) onChanged?.invoke()
                dragging = false
                lastDist = 0f
                return true
            }

            MotionEvent.ACTION_POINTER_UP -> {
                lastDist = 0f
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun distance(e: MotionEvent): Float {
        if (e.pointerCount < 2) return 0f
        val dx = e.getX(0) - e.getX(1)
        val dy = e.getY(0) - e.getY(1)
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }
}
