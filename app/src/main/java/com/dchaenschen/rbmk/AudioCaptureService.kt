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
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * 用 AudioPlaybackCapture 采集「本机正在播放的声音」并实时算频谱。
 *
 * 为什么用它而不是麦克风
 * ----------------------
 * 麦克风会混入环境噪音，而且外放时还可能有回声；AudioPlaybackCapture 直接
 * 拿系统混音里的媒体流，和桌面版 WASAPI loopback 是同一类做法。
 *
 * 代价
 * ----
 * Android 10+ 要求用户通过 MediaProjection 授权一次，且必须跑前台服务。
 * 授权结果由 MainActivity 申请后通过 Intent 传进来。
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

        fun start(context: Context, resultCode: Int, data: Intent) {
            val i = Intent(context, AudioCaptureService::class.java).apply {
                putExtra(EXTRA_CODE, resultCode)
                putExtra(EXTRA_DATA, data)
            }
            context.startForegroundService(i)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, AudioCaptureService::class.java))
        }
    }

    private var projection: MediaProjection? = null
    private var record: AudioRecord? = null
    private var thread: Thread? = null
    @Volatile private var stopped = false

    // FFT 参数：1024 点、48kHz、单声道
    private val fftSize = 1024
    private val sampleRate = 48000
    private val binCount = ReactorState.BINS

    // 对数分频的索引区间（预先算好，运行时不再分配）
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
        // Android 14 起前台服务必须声明类型，否则抛异常
        startForeground(
            NOTIF_ID, buildNotification(),
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        )

        val code = intent.getIntExtra(EXTRA_CODE, 0)
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_DATA)
        }
        if (data == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = mpm.getMediaProjection(code, data)
        projection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                shutdown()
            }
        }, null)

        startCapture()
        return START_STICKY
    }

    private fun startCapture() {
        val proj = projection ?: return
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

        val rec = AudioRecord.Builder()
            .setAudioFormat(fmt)
            .setBufferSizeInBytes(minBuf)
            .setAudioPlaybackCaptureConfig(cfg)
            .build()

        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            stopSelf()
            return
        }
        record = rec
        rec.startRecording()
        running = true
        ReactorState.capturing = true

        stopped = false
        thread = Thread { loop(rec) }.also { it.isDaemon = true; it.start() }
    }

    private fun loop(rec: AudioRecord) {
        val re = FloatArray(fftSize)
        val im = FloatArray(fftSize)
        val window = FloatArray(fftSize) { i ->
            // 汉宁窗，减少频谱泄漏
            (0.5 - 0.5 * kotlin.math.cos(2.0 * Math.PI * i / (fftSize - 1))).toFloat()
        }
        val pcm = ShortArray(fftSize)
        val smoothed = FloatArray(binCount)

        while (!stopped) {
            val n = rec.read(pcm, 0, fftSize)
            if (n <= 0) {
                Thread.sleep(5)
                continue
            }
            for (i in 0 until fftSize) {
                val v = if (i < n) pcm[i] / 32768f else 0f
                re[i] = v * window[i]
                im[i] = 0f
            }
            Fft.forward(re, im)
            val mag = Fft.magnitudes(re, im)

            // 按对数分频把 512 个线性频点压成 256 段
            val out = FloatArray(binCount)
            for (b in 0 until binCount) {
                val lo = bandLo[b]
                val hi = bandHi[b].coerceAtLeast(lo + 1)
                var sum = 0f
                for (k in lo until hi.coerceAtMost(mag.size)) sum += mag[k]
                val avg = sum / (hi - lo).coerceAtLeast(1)
                // 幅值 -> dB -> 归一化。
                // 这里故意用**很宽**的区间（-100..0dB），只做粗归一化，
                // 保证音量大时不会所有频段一起饱和 —— 真正的动态范围压缩
                // 交给渲染层的 AGC（见 RodArray.step）。
                val db = 20.0 * kotlin.math.log10(avg + 1e-9)
                out[b] = ((db + 100.0) / 100.0).coerceIn(0.0, 1.0).toFloat()
            }

            // 采集层只做轻平滑（防止逐帧跳变），
            // 用户可调的「平滑度」在渲染层再叠一次
            for (b in 0 until binCount) {
                smoothed[b] = smoothed[b] * 0.35f + out[b] * 0.65f
            }
            ReactorState.spectrum = smoothed.copyOf()
        }
    }

    private fun precomputeBands() {
        val freqsPerBin = sampleRate.toDouble() / fftSize      // 每个频点多少 Hz
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
        val nm = getSystemService(NotificationManager::class.java)
        val ch = NotificationChannel(
            CHANNEL_ID, "音频采集", NotificationManager.IMPORTANCE_LOW
        )
        ch.setShowBadge(false)
        nm.createNotificationChannel(ch)
    }

    private fun buildNotification(): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("控制棒")
            .setContentText("正在读取系统音频")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build()

    private fun shutdown() {
        stopped = true
        thread?.join(300)
        thread = null
        try {
            record?.stop()
        } catch (_: Exception) {
        }
        record?.release()
        record = null
        projection?.stop()
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
