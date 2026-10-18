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
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import kotlin.math.pow

/**
 * 音频采集服务，支持两种来源：
 *
 *   SYSTEM —— AudioPlaybackCapture，精确捕捉本机播放的音乐
 *             （需要一次 MediaProjection「录屏」授权，授权是会话级的）
 *   MIC    —— 麦克风，不需要授权弹窗、不怕被系统回收
 *             （外放时效果好，戴耳机/蓝牙时听不到）
 *
 * 踩过的坑
 * --------
 * 1) **启动顺序**：Android 14 对「先 getMediaProjection 还是先 startForeground」
 *    在部分机型上要求不一致：有的机型先启动 mediaProjection 类型的前台服务会抛
 *    SecurityException，有的机型反过来会失败。这里**两种顺序都试**，
 *    哪个成功用哪个。
 * 2) **read 出错后必须重建**：AudioRecord.read 返回负值说明录音器已损坏，
 *    只 sleep 救不回来，必须重建。
 * 3) 所有异常都兜住并写进 lastError，避免整个应用崩掉。
 */
class AudioCaptureService : Service() {

    companion object {
        private const val CHANNEL_ID = "reactor_capture"
        private const val NOTIF_ID = 1001
        const val EXTRA_CODE = "result_code"
        const val EXTRA_DATA = "result_data"
        const val EXTRA_SOURCE = "source"

        @Volatile
        var running: Boolean = false
            private set

        @Volatile
        var lastError: String = ""

        /** 启动系统音频采集（需要 MediaProjection 的授权结果） */
        fun startSystem(context: Context, resultCode: Int, data: Intent) {
            val i = Intent(context, AudioCaptureService::class.java).apply {
                putExtra(EXTRA_SOURCE, ReactorState.SOURCE_SYSTEM)
                putExtra(EXTRA_CODE, resultCode)
                putExtra(EXTRA_DATA, data)
            }
            try {
                context.startForegroundService(i)
            } catch (e: Exception) {
                lastError = "启动采集服务失败：${e.javaClass.simpleName}"
            }
        }

        /** 启动麦克风采集（不需要任何授权弹窗） */
        fun startMic(context: Context) {
            val i = Intent(context, AudioCaptureService::class.java).apply {
                putExtra(EXTRA_SOURCE, ReactorState.SOURCE_MIC)
            }
            try {
                context.startForegroundService(i)
            } catch (e: Exception) {
                lastError = "启动麦克风失败：${e.javaClass.simpleName}"
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
    private var source: String = ReactorState.SOURCE_NONE

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

        source = intent.getStringExtra(EXTRA_SOURCE) ?: ReactorState.SOURCE_MIC

        if (source == ReactorState.SOURCE_SYSTEM) {
            return startSystemCapture(intent)
        }
        return startMicCapture()
    }

    // ---------------------------------------------------------------- 麦克风
    private fun startMicCapture(): Int {
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(
                    NOTIF_ID, buildNotification("正在读取麦克风"),
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            } else {
                startForeground(NOTIF_ID, buildNotification("正在读取麦克风"))
            }
        } catch (e: Exception) {
            lastError = "麦克风前台服务启动失败：${e.javaClass.simpleName}"
            stopSelf()
            return START_NOT_STICKY
        }

        val rec = try {
            buildRecord(null, MediaRecorder.AudioSource.MIC)
        } catch (e: SecurityException) {
            lastError = "缺少录音权限，请在系统设置里允许"
            stopSelf()
            return START_NOT_STICKY
        } catch (e: Exception) {
            lastError = "创建麦克风录音失败：${e.javaClass.simpleName}"
            stopSelf()
            return START_NOT_STICKY
        } ?: run {
            lastError = "麦克风初始化失败"
            stopSelf()
            return START_NOT_STICKY
        }

        startLoop(rec)
        ReactorState.audioSource = ReactorState.SOURCE_MIC
        return START_STICKY
    }

    // ---------------------------------------------------------------- 系统音频
    private fun startSystemCapture(intent: Intent): Int {
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

        // ---- 顺序 A：先拿 projection，再起前台服务 ----
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

        // ---- 顺序 B：A 失败时，先起前台服务再拿 projection ----
        if (proj == null) {
            if (!fgDone) fgDone = tryStartForeground()
            try {
                proj = mpm.getMediaProjection(code, data)
            } catch (e: Exception) {
                lastError = "获取系统音频失败：${e.javaClass.simpleName}"
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
            buildRecord(proj, -1)
        } catch (e: SecurityException) {
            lastError = "缺少录音权限，请在系统设置里允许"
            stopSelf()
            return START_NOT_STICKY
        } catch (e: Exception) {
            lastError = "创建系统音频录音失败：${e.javaClass.simpleName}"
            stopSelf()
            return START_NOT_STICKY
        } ?: run {
            lastError = "系统音频录音初始化失败（可能被其他应用占用）"
            stopSelf()
            return START_NOT_STICKY
        }

        startLoop(rec)
        ReactorState.audioSource = ReactorState.SOURCE_SYSTEM
        return START_STICKY
    }

    private fun tryStartForeground(): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(
                    NOTIF_ID, buildNotification("正在读取系统音频（不读取画面）"),
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                )
            } else {
                startForeground(NOTIF_ID, buildNotification("正在读取系统音频（不读取画面）"))
            }
            true
        } catch (e: Exception) {
            lastError = "前台服务启动失败：${e.javaClass.simpleName}"
            false
        }
    }

    // ---------------------------------------------------------------- 录音器构造
    /**
     * @param proj 非空时用 AudioPlaybackCapture（系统音频）；为空时用麦克风
     * @param audioSource 麦克风场景的音频源
     */
    private fun buildRecord(proj: MediaProjection?, audioSource: Int): AudioRecord? {
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(fftSize * 4)

        val builder = AudioRecord.Builder()

        if (proj != null) {
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
            builder
                .setAudioFormat(fmt)
                .setBufferSizeInBytes(minBuf)
                .setAudioPlaybackCaptureConfig(cfg)
        } else {
            builder
                .setAudioSource(audioSource)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build()
                )
                .setBufferSizeInBytes(minBuf)
        }

        val rec = builder.build()
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
                errorStreak++
                if (errorStreak >= 8) {
                    errorStreak = 0
                    lastError = "录音器中断，正在重建…"
                    if (!rebuildRecorder()) {
                        lastError = "录音器无法重建，请重新开始采集"
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

    private fun rebuildRecorder(): Boolean {
        if (stopped) return false
        releaseRecorder()
        val rec = try {
            if (source == ReactorState.SOURCE_SYSTEM) {
                buildRecord(projection, -1)
            } else {
                buildRecord(null, MediaRecorder.AudioSource.MIC)
            }
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
                CHANNEL_ID, "音频采集", NotificationManager.IMPORTANCE_LOW
            )
            ch.setShowBadge(false)
            nm.createNotificationChannel(ch)
        } catch (_: Exception) {
        }
    }

    private fun buildNotification(text: String): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("控制棒")
            .setContentText(text)
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
