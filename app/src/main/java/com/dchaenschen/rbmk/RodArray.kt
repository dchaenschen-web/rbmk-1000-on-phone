package com.dchaenschen.rbmk

import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 控制棒阵列的几何与动画状态。
 *
 * 几何/投影移植自网页版 app.js：
 *   rods: x,z ∈ [-15,15]，只保留 r = hypot(x,z) ≤ 15 的点（共 709 根）
 *   s   = min(W/45, H/37) * zoom
 *   屏幕坐标 = ( W/2 + rx*s , H*0.55 + rz*s*tilt - h*s )
 *
 * 关键修正：AGC（自动增益）
 * -------------------------
 * 最初的移植直接用固定 dB 窗口归一化频谱，结果音乐稍响时几乎所有频段都饱和到 1.0，
 * 709 根棒一起顶到最高 —— 表现为「全部弹出来，没有节律」。
 *
 * 现在每帧先求频谱峰值，再按 (v - noiseFloor) / (peak - noiseFloor) 归一化，
 * 于是无论音乐响还是轻，频谱都会重新铺满 0..1，节律得以保留。
 * 峰值过低（静音）时用最小跨度兜底，避免把底噪放大成满幅。
 */
class RodArray {

    class Rod(val x: Int, val z: Int, val r: Float, val seed: Float) {
        var h: Float = 0f
    }

    /** AGC 峰值包络（快升慢降） */
    private var agcPeak: Float = 0.06f

    val rods: List<Rod> = buildList {
        for (x in -15..15) {
            for (z in -15..15) {
                val r = hypot(x.toFloat(), z.toFloat())
                if (r <= 15f) {
                    // 与网页版相同的伪随机种子，保证每根棒的配色/细节一致
                    val seed = kotlin.math.abs(sin(x * 127.1f + z * 311.7f))
                    add(Rod(x, z, r, seed))
                }
            }
        }
    }

    /** 取某根棒对应的频段值（按频率对数插值） */
    private fun sample(rod: Rod, bins: FloatArray): Float {
        if (bins.isEmpty()) return 0f
        // 网页版：hz = 45 * 320^(r/15)
        val hz = 45.0 * 320.0.pow(rod.r / 15.0)
        val t = ((ln(hz) - ln(ReactorState.fmin.toDouble())) /
                (ln(ReactorState.fmax.toDouble()) - ln(ReactorState.fmin.toDouble()))).toFloat()
        val pos = (t * (bins.size - 1)).coerceIn(0f, (bins.size - 1).toFloat())
        val i0 = pos.toInt()
        val i1 = (i0 + 1).coerceAtMost(bins.size - 1)
        val f = pos - i0
        return bins[i0] * (1f - f) + bins[i1] * f
    }

    /** 每帧推进一次高度（含 AGC 与时间平滑） */
    fun step(bins: FloatArray, dtScale: Float, timeMs: Long) {
        val floor = ReactorState.noiseFloor
        val gamma = ReactorState.gamma
        val gain = ReactorState.sensitivity
        val baseH = ReactorState.baseHeight
        val maxH = ReactorState.maxHeight
        val smoothing = ReactorState.smoothing

        // AGC：以本帧峰值为参考，保证频谱始终铺满动态范围
        var peak = 0f
        for (b in bins) if (b > peak) peak = b

        // AGC 用「峰值包络」而不是当帧峰值：快升慢降。
        // 用当帧峰值的话，任何一帧里最响的那个频段都会被归一化成 1.0，
        // 于是它永远顶满、其他频段被压在低处 —— 这就是「中间一直最高」的成因之一。
        if (peak > agcPeak) {
            agcPeak = peak
        } else {
            agcPeak = (agcPeak * 0.985f).coerceAtLeast(0.06f)
        }
        val span = (agcPeak - floor).coerceAtLeast(0.06f)

        // 每帧只算一次的平滑系数
        val k = (1.0 - (0.45 + smoothing * 0.53).pow(dtScale.toDouble()))
            .toFloat().coerceIn(0.01f, 0.95f)

        // 没有音频（静音，或采集已停止/失效）时用温和的待机波动，
        // 否则画面完全静止，看起来像卡死。
        if (peak < 0.05f) {
            for (rod in rods) {
                val wave = sin(rod.r * 0.55f - timeMs * 0.0009f + rod.x * 0.25f)
                val target = baseH + (0.10f + 0.10f * wave) * maxH * 0.5f
                rod.h += (target - rod.h) * k
            }
            return
        }

        for (rod in rods) {
            val v = sample(rod, bins)
            var a = ((v - floor) / span).coerceIn(0f, 1f)
            a = a.pow(gamma) * gain
            // 允许一点过冲，但不要无限放大
            a = a.coerceIn(0f, 1.8f)
            val target = baseH + a * maxH
            rod.h += (target - rod.h) * k
        }
    }

    /** 屏幕空间缩放系数 */
    fun scale(w: Float, h: Float, zoom: Float): Float =
        min(w / 45f, h / 37f) * zoom

    /** 等距投影：世界坐标 -> 屏幕坐标 */
    fun project(
        x: Float, z: Float, height: Float,
        w: Float, h: Float, angle: Float, tilt: Float, zoom: Float,
    ): FloatArray {
        val s = scale(w, h, zoom)
        val rx = x * cos(angle) - z * sin(angle)
        val rz = x * sin(angle) + z * cos(angle)
        return floatArrayOf(
            w / 2f + rx * s,
            h * 0.55f + rz * s * tilt - height * s,
        )
    }
}

/** 迭代式基 2 FFT（原地），用于把录音 PCM 转成频谱 */
object Fft {

    /**
     * @param re 实部，长度必须是 2 的幂
     * @param im 虚部，同长度
     */
    fun forward(re: FloatArray, im: FloatArray) {
        val n = re.size
        require(n and (n - 1) == 0) { "长度必须是 2 的幂" }

        // 位反转置换
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j or bit
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }

        // 蝶形运算
        var len = 2
        while (len <= n) {
            val ang = -2.0 * Math.PI / len
            val wRe = cos(ang).toFloat()
            val wIm = sin(ang).toFloat()
            var i = 0
            while (i < n) {
                var curRe = 1f
                var curIm = 0f
                for (k in 0 until len / 2) {
                    val uRe = re[i + k]
                    val uIm = im[i + k]
                    val vRe = re[i + k + len / 2] * curRe - im[i + k + len / 2] * curIm
                    val vIm = re[i + k + len / 2] * curIm + im[i + k + len / 2] * curRe
                    re[i + k] = uRe + vRe
                    im[i + k] = uIm + vIm
                    re[i + k + len / 2] = uRe - vRe
                    im[i + k + len / 2] = uIm - vIm
                    val nRe = curRe * wRe - curIm * wIm
                    curIm = curRe * wIm + curIm * wRe
                    curRe = nRe
                }
                i += len
            }
            len = len shl 1
        }
    }

    /** 幅度谱（前 n/2 个点） */
    fun magnitudes(re: FloatArray, im: FloatArray): FloatArray {
        val half = re.size / 2
        val out = FloatArray(half)
        for (i in 0 until half) {
            out[i] = sqrt(re[i] * re[i] + im[i] * im[i])
        }
        return out
    }
}
