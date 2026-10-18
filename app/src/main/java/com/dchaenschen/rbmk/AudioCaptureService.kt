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
import kotlin.math.pow

/**
 * 用 AudioPlaybackCapture 采集「本机正在播放的声音」并实时算频谱。
 *
 * 踩过的坑
 * --------
 * 1) **启动顺序**：Android 14 要求先拿到有效的 MediaProjection token，
 *    才能 startForeground(type=mediaProjection)。顺序反了会抛 SecurityException，
 *    表现就是应用「停止运行」。
 * 2) **read 出错后必须重建**：AudioRecord.read 返回负值（ERROR_DEAD_OBJECT 等）时
 *    如果只是 sleep 后 continue，录音器其实已经废了，采集会永久失效。
 *    这里改成自动重建 AudioRecord。
 * 3) 所有异常都兜住并写进 ReactorState.lastError，不让整个应用崩掉。
 */
class AudioCaptureService : Service() {

    companion object {
        private const val CHANNEL_ID = "reactor_capture"
        private const val NOTIF_ID = 1001
        const val EXTRA_CODE = "result_code"
        const val EXTRA_DATA = "result_data"

        /** 供设置页判断当前是否在采集 */
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
                lastError = "启动采集服务失败：${e.javaClass.simpleName}"
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
        // START_STICKY 重启时 intent 为 null，没有 token 就没法采集，直接收工
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
            lastError = "缺少授权数据"
            stopSelf()
            return START_NOT_STICKY
        }

        // ---- 1. 先拿 projection（Android 14 要求先于声明 FGS 类型）----
        val proj = try {
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE)
                    as MediaProjectionManager
            mpm.getMediaProjection(code, data)
        } catch (e: Exception) {
            lastError = "获取 MediaProjection 失败：${e.javaClass.simpleName}"
            null
        }
        if (proj == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        // ---- 2. 再启动前台服务并声明 mediaProjection 类型 ----
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(
                    NOTIF_ID, buildNotification(),
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                )
            } else {
                startForeground(NOTIF_ID, buildNotification())
            }
        } catch (e: Exception) {
            lastError = "前台服务启动失败：${e.javaClass.simpleName}"
            try {
                proj.stop()
            } catch (_: Exception) {
            }
            stopSelf()
            return START_NOT_STICKY
        }

        // ---- 3. 注册回调并开始采集 ----
        releaseRecorder()
        projection = proj
        proj.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                // 用户在系统通知里点了「停止」，或系统回收了投射
                lastError = "投射已被系统或用户停止，需要重新授权"
                shutdown()
            }
        }, null)

        startCapture()
        return START_STICKY
    }

    private fun startCapture() {
        val proj = projection ?: return
        val rec = try {
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

            val minBuf = AudioRecord.getMinBufferSize(
                sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(fftSize * 4)

            AudioRecord.Builder()
                .setAudioFormat(fmt)
                .setBufferSizeInBytes(minBuf)
                .setAudioPlaybackCaptureConfig(cfg)
                .build()
        } catch (e: SecurityException) {
            lastError = "缺少录音权限，请到系统设置里允许录音权限"
            stopSelf()
            return
        } catch (e: Exception) {
            lastError = "创建 AudioRecord 失败：${e.javaClass.simpleName}"
            stopSelf()
            return
        }

        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            lastError = "AudioRecord 初始化失败（可能被其他应用占用）"
            stopSelf()
            return
        }

        record = rec
        try {
            rec.startRecording()
        } catch (e: Exception) {
            lastError = "开始录音失败：${e.javaClass.simpleName}"
            rec.release()
            record = null
            stopSelf()
            return
        }

        running = true
        ReactorState.capturing = true
        lastError = ""

        stopped = false
        thread = Thread { loop() }.also { it.isDaemon = true; it.start() }
    }

    private fun loop() {
        val re = FloatArray(fftSize)
        val im = FloatArray(fftSize)
        val window = FloatArray(fftSize) { i ->
            (0.5 - 0.5 * kotlin.math.cos(2.0 * Math.PI * i / (fftSize - 1))).toFloat()
        }
        val pcm = ShortArray(fftSize)
        val smoothed = FloatArray(binCount)

        var errorStreak = 0

        while (!stopped) {
            val rec = record
            if (rec == null) break

            val n = try {
                rec.read(pcm, 0, fftSize)
            } catch (e: Exception) {
                -1
            }

            if (n <= 0) {
                // read 返回负值说明录音器已损坏，只 sleep 救不回来，必须重建
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
                // 幅值 -> dB -> 宽区间归一化（-100..0dB）。
                // 真正的动态范围压缩交给渲染层 AGC，见 RodArray.step。
                val db = 20.0 * kotlin.math.log10(avg + 1e-9)
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

    /** 尝试重建 AudioRecord（沿用现有 projection） */
    private fun rebuildRecorder(): Boolean {
        if (stopped) return false
        releaseRecorder()
        startCapture()
        return record != null
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
                CHANNEL_ID, "音频采集", NotificationManager.IMPORTANCE_LOW
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
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
