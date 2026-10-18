package com.dchaenschen.rbmk

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow

/**
 * 用 AudioPlaybackCapture 采集「本机正在播放的声音」并实时算频谱。
 *
 * 只做系统音频，不使用麦克风。
 *
 * 为什么必须经过 MediaProjection
 * -------------------------------
 * Android 把「采集本机播放的音频」绑定在 MediaProjection（媒体投射）接口上，
 * 系统的授权框统一写成「录制或投射屏幕」。本应用只使用其中的**音频通道**，
 * 不读取画面、不录屏、不保存、不上传。
 * 普通应用没有别的办法拿系统音频（CAPTURE_AUDIO_OUTPUT 是系统签名权限）。
 *
 * 踩过的坑
 * --------
 * 1. 启动顺序：Android 14 对「先 getMediaProjection 还是先 startForeground」在不同
 *    机型上要求不一致，有的机型先启动 mediaProjection 类型的前台服务会抛
 *    SecurityException（表现为应用停止运行），有的反过来会失败。
 *    这里两种顺序都试，哪个成功用哪个。
 * 2. read 出错后必须重建：AudioRecord.read 返回负值说明录音器已损坏，
 *    只 sleep 救不回来，必须重建。
 * 3. 所有异常都兜住并写进 lastError，避免整个应用崩掉。
 */
class AudioCaptureService : Service() {

    companion object {
        private const val CHANNEL_ID = "reactor_capture"
        private const val NOTIF_ID = 1001
        const val EXTRA_CODE = "result_code"
        const val EXTRA_DATA = "result_data"

        @Volatile
        var running: Boolean = false
            private set

        @Volatile
        var lastError: String = ""

        fun start(context: Context, resultCode: Int, data: Intent) {
            val i = Intent(context, AudioCaptureService::class.java).apply {
                putExtra(EXTRA_CODE, resultCode)
                putExtra(EXTRA_DATA, data)
            }
            try {
                context.startForegroundService(i)
            } catch (e: Exception) {
                lastError = "启动采集服务失败：" + e.javaClass.simpleName
            }
        }

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, AudioCaptureService::class.java))
            } catch (_: Exception) {
            }
        }
    }

    private var projection: MediaProjection? = null
    private var record: AudioRecord? = null
    private var thread: Thread? = null

    @Volatile
    private var stopped = false

    private val fftSize = 1024
    private val sampleRate = 48000
    private val binCount = ReactorState.BINS

    private val bandLo = IntArray(binCount)
    private val bandHi = IntArray(binCount)

    override fun onCreate() {
        super.onCreate()
        createChannel()
        precomputeBands()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        val code = intent.getIntExtra(EXTRA_CODE, 0)
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_DATA)
        }
        if (data == null) {
            lastError = "缺少 MediaProjection 授权数据"
            stopSelf()
            return START_NOT_STICKY
        }

        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        // ---------- 顺序 A：先拿 projection，再起前台服务 ----------
        var proj: MediaProjection? = null
        try {
            proj = mpm.getMediaProjection(code, data)
        } catch (_: Exception) {
        }

        var fgDone = false
        if (proj != null) {
            fgDone = tryStartForeground()
            if (!fgDone) {
                try {
                    proj.stop()
                } catch (_: Exception) {
                }
                proj = null
            }
        }

        // ---------- 顺序 B：A 失败时先起前台服务，再拿 projection ----------
        if (proj == null) {
            if (!fgDone) fgDone = tryStartForeground()
            try {
                proj = mpm.getMediaProjection(code, data)
            } catch (e: Exception) {
                lastError = "获取系统音频失败：" + e.javaClass.simpleName
            }
        }
        if (proj == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        releaseRecorder()
        projection = proj
        try {
            proj.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    lastError = "系统音频授权已被停止，需要重新授权"
                    shutdown()
                }
            }, null)
        } catch (_: Exception) {
        }

        val rec = try {
            buildRecord(proj)
        } catch (e: SecurityException) {
            lastError = "缺少录音权限，请在系统设置里允许"
            stopSelf()
            return START_NOT_STICKY
        } catch (e: Exception) {
            lastError = "创建录音器失败：" + e.javaClass.simpleName
            stopSelf()
            return START_NOT_STICKY
        }

        if (rec == null) {
            lastError = "录音器初始化失败（可能被其他应用占用）"
            stopSelf()
            return START_NOT_STICKY
        }

        startLoop(rec)
        return START_STICKY
    }

    private fun tryStartForeground(): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(
                    NOTIF_ID,
                    buildNotification(),
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                )
            } else {
                startForeground(NOTIF_ID, buildNotification())
            }
            true
        } catch (e: Exception) {
            lastError = "前台服务启动失败：" + e.javaClass.simpleName
            false
        }
    }

    /** 用 AudioPlaybackCapture 构造录音器；失败返回 null */
    private fun buildRecord(proj: MediaProjection): AudioRecord? {
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(fftSize * 4)

        val fmt = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()

        val cfg = AudioPlaybackCaptureConfiguration.Builder(proj)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()

        val rec = AudioRecord.Builder()
            .setAudioFormat(fmt)
            .setBufferSizeInBytes(minBuf)
            .setAudioPlaybackCaptureConfig(cfg)
            .build()

        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            return null
        }
        return rec
    }

    private fun startLoop(rec: AudioRecord) {
        record = rec
        try {
            rec.startRecording()
        } catch (e: Exception) {
            lastError = "开始录音失败：" + e.javaClass.simpleName
            rec.release()
            record = null
            stopSelf()
            return
        }

        running = true
        ReactorState.capturing = true
        ReactorState.audioSource = ReactorState.SOURCE_SYSTEM
        lastError = ""

        stopped = false
        thread = Thread { loop() }.also {
            it.isDaemon = true
            it.start()
        }
    }

    private fun loop() {
        val re = FloatArray(fftSize)
        val im = FloatArray(fftSize)
        val window = FloatArray(fftSize) { i ->
            (0.5 - 0.5 * cos(2.0 * Math.PI * i / (fftSize - 1))).toFloat()
        }
        val pcm = ShortArray(fftSize)
        val smoothed = FloatArray(binCount)

        var errorStreak = 0

        while (!stopped) {
            val rec = record
            if (rec == null) break

            val n = try {
                rec.read(pcm, 0, fftSize)
            } catch (_: Exception) {
                -1
            }

            if (n <= 0) {
                errorStreak++
                if (errorStreak >= 8) {
                    errorStreak = 0
                    lastError = "录音器中断，正在重建…"
                    if (!rebuildRecorder()) {
                        lastError = "录音器无法重建，请重新授权音频采集"
                        break
                    }
                }
                Thread.sleep(40)
                continue
            }
            errorStreak = 0

            for (i in 0 until fftSize) {
                val v = if (i < n) pcm[i] / 32768f else 0f
                re[i] = v * window[i]
                im[i] = 0f
            }
            Fft.forward(re, im)
            val mag = Fft.magnitudes(re, im)

            val out = FloatArray(binCount)
            for (b in 0 until binCount) {
                val lo = bandLo[b]
                val hi = bandHi[b].coerceAtLeast(lo + 1)
                var sum = 0f
                for (k in lo until hi.coerceAtMost(mag.size)) sum += mag[k]
                val avg = sum / (hi - lo).coerceAtLeast(1)
                // 宽区间归一化（-100..0dB），动态范围压缩交给渲染层 AGC
                val db = 20.0 * log10(avg + 1e-9)
                out[b] = ((db + 100.0) / 100.0).coerceIn(0.0, 1.0).toFloat()
            }

            for (b in 0 until binCount) {
                smoothed[b] = smoothed[b] * 0.35f + out[b] * 0.65f
            }
            ReactorState.spectrum = smoothed.copyOf()
        }

        running = false
        ReactorState.capturing = false
    }

    private fun rebuildRecorder(): Boolean {
        if (stopped) return false
        val proj = projection ?: return false
        releaseRecorder()
        val rec = try {
            buildRecord(proj)
        } catch (_: Exception) {
            null
        } ?: return false

        record = rec
        return try {
            rec.startRecording()
            true
        } catch (_: Exception) {
            try {
                rec.release()
            } catch (_: Exception) {
            }
            record = null
            false
        }
    }

    private fun precomputeBands() {
        val freqsPerBin = sampleRate.toDouble() / fftSize
        val half = fftSize / 2
        for (b in 0 until binCount) {
            val f0 = ReactorState.FMIN *
                    (ReactorState.FMAX / ReactorState.FMIN).pow(b.toDouble() / binCount)
            val f1 = ReactorState.FMIN *
                    (ReactorState.FMAX / ReactorState.FMIN).pow((b + 1).toDouble() / binCount)
            bandLo[b] = (f0 / freqsPerBin).toInt().coerceIn(1, half - 1)
            bandHi[b] = (f1 / freqsPerBin).toInt().coerceIn(bandLo[b] + 1, half)
        }
    }

    private fun createChannel() {
        try {
            val nm = getSystemService(NotificationManager::class.java)
            val ch = NotificationChannel(
                CHANNEL_ID,
                "音频采集",
                NotificationManager.IMPORTANCE_LOW
            )
            ch.setShowBadge(false)
            nm.createNotificationChannel(ch)
        } catch (_: Exception) {
        }
    }

    private fun buildNotification(): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("控制棒")
            .setContentText("正在读取系统音频（不读取画面）")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build()

    private fun releaseRecorder() {
        try {
            record?.stop()
        } catch (_: Exception) {
        }
        try {
            record?.release()
        } catch (_: Exception) {
        }
        record = null
    }

    private fun shutdown() {
        stopped = true
        thread?.join(400)
        thread = null
        releaseRecorder()
        try {
            projection?.stop()
        } catch (_: Exception) {
        }
        projection = null
        running = false
        ReactorState.capturing = false
        ReactorState.audioSource = ReactorState.SOURCE_NONE
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
