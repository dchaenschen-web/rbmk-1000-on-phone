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

/**
 * 用 AudioPlaybackCapture 采集「本机正在播放的声音」并实时算频谱。
 *
 * 只做系统音频，不使用麦克风。
 *
 * 为什么必须经过 MediaProjection
 * -------------------------------
 * Android 把「采集本机播放的音频」绑定在 MediaProjection（媒体投射）接口上，
 * 系统的授权框统一写成「录制或投射屏幕」。本应用只使用其中的音频通道，
 * 不读取画面、不录屏、不保存、不上传。
 *
 * 频谱处理的三个关键修正
 * ----------------------
 * 1. 频率倾斜（+3dB/oct）：音乐能量天然集中在低频，中低频一直很强、高频很弱。
 *    按 1kHz 为基准做倾斜补偿，让中高频也能有可见变化。
 * 2. 每频段自适应地板：低频常有持续能量（底鼓/贝斯），会让中心区域永远顶满。
 *    对每个频段单独维护「地板」（快速下探、缓慢回升），输出时减掉地板，
 *    相当于谱减去稳态成分，只保留变化量。
 * 3. 时间平滑放在最后，渲染层再叠一次用户可调的平滑度。
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


    override fun onCreate() {
        super.onCreate()
        createChannel()
        SpectrumBands.ensure(sampleRate, fftSize)
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

        // 每频段自适应地板：快速下探、缓慢回升，用来压掉稳态能量
        val bandFloor = FloatArray(binCount)

        var errorStreak = 0

        while (!stopped) {
            // 用户改了频率参数就重算频段表（比较很便宜）
            SpectrumBands.ensure(sampleRate, fftSize)

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
                val lo = SpectrumBands.lo[b]
                val hi = SpectrumBands.hi[b].coerceAtLeast(lo + 1)

                // 取频段内**峰值**，而不是平均。
                // PC 端取的是单个 FFT bin；对数频段里取平均会把宽频段的峰值
                // 抹平（高频一个 band 覆盖几十个 bin），律动感就消失了。
                var peakMag = 0f
                for (k in lo until hi.coerceAtMost(mag.size)) {
                    if (mag[k] > peakMag) peakMag = mag[k]
                }

                // 归一化到「满幅正弦 ≈ 1.0」：
                // 汉宁窗下满幅正弦的 FFT 峰值约为 fftSize/4。
                // 之前直接用绝对幅度当 dB，音乐信号普遍 40~70dB，
                // 全部超过映射上限被拍平成 1.0 —— 这就是所有棒子一起顶满的原因。
                val norm = peakMag * 4f / fftSize

                // dBFS -> 0..1，窗口取 -100..-30dB，
                // 与 Web Audio AnalyserNode 的默认 min/maxDecibels 一致。
                val db = 20.0 * log10(norm + 1e-9) +
                        20.0 * log10(SpectrumBands.gain[b].toDouble())
                val raw = ((db + 100.0) / 70.0).coerceIn(0.0, 1.0).toFloat()

                // 每频段自适应地板：压掉稳态成分（底鼓/贝斯的持续能量）
                if (raw < bandFloor[b]) {
                    bandFloor[b] = bandFloor[b] * 0.55f + raw * 0.45f
                } else {
                    bandFloor[b] = (bandFloor[b] + 0.0018f).coerceAtMost(0.80f)
                }
                val denom = (1f - bandFloor[b]).coerceAtLeast(0.15f)
                out[b] = ((raw - bandFloor[b]) / denom).coerceIn(0f, 1f)
            }

            // 4) 轻平滑，避免逐帧跳变（用户可调的平滑度在渲染层再叠一次）
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
