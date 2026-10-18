package com.dchaenschen.rbmk

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 设置页里的实时预览。
 *
 * 交互：
 *   * 单指拖动 -> 调整阵列在屏幕上的位置（offsetX / offsetY）
 *   * 双指捏合 -> 调整缩放（zoom）
 *
 * 直接用 ReactorRenderer 绘制，并且和壁纸一样会加载用户选的背景图，
 * 所以预览与壁纸效果一致。
 */
class PreviewView(context: Context) : View(context) {

    private val renderer = ReactorRenderer()
    private val bgPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    private var background: Bitmap? = null
    private var backgroundUri: String = ""
    private var lastLoadMs = 0L

    private var lastX = 0f
    private var lastY = 0f
    private var lastDist = 0f
    private var dragging = false

    /** 拖动/缩放结束后回调，用于落盘保存 */
    var onChanged: (() -> Unit)? = null

    init {
        setBackgroundColor(Color.BLACK)
        isClickable = true
        reloadBackground()
    }

    /** 目标屏幕的宽高比（height / width） */
    private fun targetRatio(): Float {
        return if (ReactorState.previewAspect == ReactorState.ASPECT_INNER) {
            val dm = resources.displayMetrics
            if (dm.widthPixels > 0) {
                dm.heightPixels.toFloat() / dm.widthPixels.toFloat()
            } else 1.6f
        } else {
            // Z Flip6 外屏 720 x 748
            748f / 720f
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val availW = MeasureSpec.getSize(widthMeasureSpec)
        val ratio = targetRatio()
        var w = availW
        var h = (w * ratio).toInt()
        // 竖屏比例下会很高，限制到 320dp 高度后按比例缩窄
        val maxH = (320 * resources.displayMetrics.density).toInt()
        if (h > maxH) {
            h = maxH
            w = (h / ratio).toInt()
        }
        setMeasuredDimension(w, h)
    }

    /** 重新读取 SharedPreferences 里的背景图（用户选完图后调用） */
    fun reloadBackground() {
        val prefs = context.getSharedPreferences("reactor", Context.MODE_PRIVATE)
        applyBackground(prefs.getString("background_uri", "").orEmpty())
    }

    private fun applyBackground(uri: String) {
        if (uri == backgroundUri) return
        backgroundUri = uri
        background?.recycle()
        background = null
        if (uri.isBlank()) return
        try {
            context.contentResolver.openFileDescriptor(Uri.parse(uri), "r")?.use { pfd ->
                background = BitmapFactory.decodeFileDescriptor(pfd.fileDescriptor)
            }
        } catch (_: Exception) {
            background = null
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return

        // 背景图（与壁纸相同的 fitCenter 逻辑）
        val bg = background
        if (bg != null && !bg.isRecycled) {
            val dst = fitCenter(bg.width, bg.height, width, height)
            canvas.drawBitmap(bg, null, dst, bgPaint)
        } else {
            canvas.drawColor(Color.BLACK)
            // 每 2 秒兜底重读一次，避免选图后预览还是黑的
            val now = System.currentTimeMillis()
            if (now - lastLoadMs > 2000) {
                lastLoadMs = now
                reloadBackground()
            }
        }

        renderer.draw(canvas, width.toFloat(), height.toFloat(), System.nanoTime())
        // 持续重绘（预览也是动画）
        postInvalidateOnAnimation()
    }

    private fun fitCenter(sw: Int, sh: Int, dw: Int, dh: Int): Rect {
        val scale = maxOf(dw.toFloat() / sw, dh.toFloat() / sh)
        val w = (sw * scale).toInt()
        val h = (sh * scale).toInt()
        val left = (dw - w) / 2
        val top = (dh - h) / 2
        return Rect(left, top, left + w, top + h)
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
                        ReactorState.zoom = (ReactorState.zoom * factor).coerceIn(0.5f, 2f)
                        lastDist = d
                    }
                } else if (dragging) {
                    // 单指拖动位置
                    val dx = (event.x - lastX) / width
                    val dy = (event.y - lastY) / height
                    if (abs(dx) > 0.0005f || abs(dy) > 0.0005f) {
                        ReactorState.offsetX = (ReactorState.offsetX + dx).coerceIn(-0.5f, 0.5f)
                        ReactorState.offsetY = (ReactorState.offsetY + dy).coerceIn(-0.5f, 0.5f)
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
        return sqrt(dx * dx + dy * dy)
    }
}
