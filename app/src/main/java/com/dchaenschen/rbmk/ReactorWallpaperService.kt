package com.dchaenschen.rbmk

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder

/**
 * 动态壁纸本体。
 *
 * 关于「透明背景」
 * ----------------
 * Android 的动态壁纸会**取代**原来的静态壁纸，画成透明并不会露出旧壁纸，
 * 只会是黑色（下面没有东西）。所以这里提供「背景图」选项：
 * 用户选一张图，壁纸就把它当底层，控制棒画在上面 ——
 * 等同于「浮在自己的壁纸上」。没选背景图时是纯黑底。
 */
class ReactorWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = ReactorEngine()

    inner class ReactorEngine : Engine() {

        private val renderer = ReactorRenderer()
        private var thread: Thread? = null

        @Volatile private var running = false
        @Volatile private var visible = false

        private val mainHandler = Handler(Looper.getMainLooper())

        private var background: Bitmap? = null
        private var backgroundUri: String = ""

        // 目标约 60fps
        private val frameNanos = 16_666_667L

        override fun onCreate(surfaceHolder: SurfaceHolder?) {
            super.onCreate(surfaceHolder)
            // 关键：壁纸可能在用户从未打开设置页的情况下启动，
            // 必须自己把保存的参数载入内存，否则会一直用默认值。
            mainHandler.post {
                ReactorConfig.load(this@ReactorWallpaperService)
                loadBackgroundFromPrefs()
            }
        }

        override fun onSurfaceChanged(
            holder: SurfaceHolder?,
            format: Int,
            width: Int,
            height: Int,
        ) {
            super.onSurfaceChanged(holder, format, width, height)
            if (width > 0 && height > 0) {
                ReactorState.wallpaperWidth = width
                ReactorState.wallpaperHeight = height
                try {
                    ReactorConfig.saveWallpaperSize(
                        this@ReactorWallpaperService, width, height
                    )
                } catch (_: Exception) {
                }
            }
        }

        override fun onVisibilityChanged(isVisible: Boolean) {
            visible = isVisible
            if (isVisible) {
                // 从「不可见」恢复时重新载入设置，
                // 保证在设置页里改过的配色/参数立刻反映到壁纸
                try {
                    ReactorConfig.load(this@ReactorWallpaperService)
                } catch (_: Exception) {
                }
                startLoop()
            } else {
                stopLoop()
            }
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder?) {
            stopLoop()
            super.onSurfaceDestroyed(holder)
        }

        override fun onDestroy() {
            stopLoop()
            background?.recycle()
            background = null
            super.onDestroy()
        }

        // ------------------------------------------------------------ 线程
        private fun startLoop() {
            if (running) return
            running = true
            thread = Thread { loop() }.also { it.isDaemon = true; it.start() }
        }

        private fun stopLoop() {
            running = false
            thread?.join(400)
            thread = null
        }

        private fun loop() {
            var last = 0L
            while (running) {
                if (!visible) {
                    Thread.sleep(120)
                    continue
                }
                val now = System.nanoTime()
                if (last != 0L && now - last < frameNanos) {
                    val sleepMs = (frameNanos - (now - last)) / 1_000_000
                    if (sleepMs > 0) Thread.sleep(sleepMs)
                    continue
                }
                last = System.nanoTime()

                val holder = surfaceHolder
                var canvas: Canvas? = null
                try {
                    canvas = holder.lockCanvas()
                    if (canvas != null) drawFrame(canvas)
                } catch (_: Exception) {
                } finally {
                    if (canvas != null) {
                        try {
                            holder.unlockCanvasAndPost(canvas)
                        } catch (_: Exception) {
                        }
                    }
                }
            }
        }

        private fun drawFrame(canvas: Canvas) {
            val w = canvas.width.toFloat()
            val h = canvas.height.toFloat()

            val bg = background
            if (bg != null && !bg.isRecycled) {
                val dst = fitCenter(bg.width, bg.height, canvas.width, canvas.height)
                canvas.drawBitmap(bg, null, dst, null)
            } else {
                canvas.drawColor(Color.BLACK)
            }
            renderer.draw(canvas, w, h, System.nanoTime())
        }

        private fun fitCenter(sw: Int, sh: Int, dw: Int, dh: Int): Rect {
            val scale = maxOf(dw.toFloat() / sw, dh.toFloat() / sh)
            val w = (sw * scale).toInt()
            val h = (sh * scale).toInt()
            val left = (dw - w) / 2
            val top = (dh - h) / 2
            return Rect(left, top, left + w, top + h)
        }

        // ------------------------------------------------------------ 背景图
        private fun loadBackgroundFromPrefs() {
            // Engine 不是 Context，取外层 Service 的共享首选项
            val prefs = this@ReactorWallpaperService.getSharedPreferences("reactor", MODE_PRIVATE)
            val uri = prefs.getString("background_uri", "").orEmpty()
            applyBackground(uri)
        }

        private fun applyBackground(uri: String) {
            if (uri == backgroundUri) return
            backgroundUri = uri
            background?.recycle()
            background = null
            if (uri.isBlank()) return
            try {
                this@ReactorWallpaperService.contentResolver
                    .openFileDescriptor(Uri.parse(uri), "r")?.use { pfd ->
                    background = BitmapFactory.decodeFileDescriptor(pfd.fileDescriptor)
                }
            } catch (_: Exception) {
                background = null
            }
        }
    }
}
