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
 * 这一份是从网页版 app.js 的投影逻辑原样移植过来的，保证观感一致：
 *
 *   rods: x,z ∈ [-15,15]，只保留 r = hypot(x,z) ≤ 15 的点（共 709 根）
 *   s   = min(W/45, H/37) * zoom
 *   屏幕坐标 = ( W/2 + rx*s , H*0.55 + rz*s*tilt - h*s )
 *
 * 与网页版的差别：
 *   * 网页用 requestAnimationFrame，这里由壁纸引擎的绘制线程按帧率驱动
 *   * 频谱来源从 Web Audio 换成 AudioPlaybackCapture + FFT
 */
class RodArray {

    class Rod(val x: Int, val z: Int, val r: Float, val seed: Float) {
        var h: Float = 0f
    }

    val rods: List<Rod> = buildList {
        for (x in -15..15) {
            for (z in -15..15) {
                val r = hypot(x.toFloat(), z.toFloat())
                if (r <= 15f) {
                    // 用与网页版相同的伪随机种子，保证每根棒的初始高度和配色一致
                    val seed = kotlin.math.abs(sin(x * 127.1f + z * 311.7f))
                    add(Rod(x, z, r, seed))
                }
            }
        }
    }

    /** 单根棒的目标高度因子，0f 表示贴地 */
    fun targetHeight(rod: Rod, bins: FloatArray, gain: Float): Float {
        if (bins.isEmpty()) return 0.07f
        // 网页版：hz = 45 * 320^(r/15)，再按 log 映射到我们的频段表
        val hz = 45.0 * 320.0.pow(rod.r / 15.0)
        val t = ((ln(hz) - ln(ReactorState.FMIN)) /
                (ln(ReactorState.FMAX) - ln(ReactorState.FMIN))).toFloat()
        val idx = (t * (bins.size - 1)).toInt().coerceIn(1, bins.size - 2)
        // 取相邻三点平均，和网页版一致（更平滑）
        val v = (bins[idx] + bins[idx - 1] + bins[idx + 1]) / 3f
        val amp = v.coerceIn(0f, 1f).pow(1.7f) * gain
        return 0.07f + amp * 4.8f
    }

    /** 每帧推进一次高度（带平滑） */
    fun step(bins: FloatArray, gain: Float, dtScale: Float, smoothing: Float) {
        for (rod in rods) {
            val target = targetHeight(rod, bins, gain)
            // 网页版：rod.h += (target - rod.h) * (1 - 0.45+smoothing*0.53 ^ dt)
            val k = (1.0 - (0.45 + smoothing * 0.53).pow(dtScale.toDouble())).toFloat()
                .coerceIn(0.01f, 0.9f)
            rod.h += (target - rod.h) * k
        }
    }

    /** 屏幕空间缩放系数，用于画外圈、帽盖等细节尺寸 */
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
