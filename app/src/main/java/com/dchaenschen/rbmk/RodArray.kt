package com.dchaenschen.rbmk

import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

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

        /** 分组编号（0..5），由 RodGroups 计算 */
        var group: Int = 0

        /** 失控模式用的「活跃度」：0 = 平静，>1 = 异常尖峰 */
        var act: Float = 0f
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

        // 没有音频（静音 / 采集停止），或用户勾了「强制演示」时，
        // 交给所选律动模式驱动。
        val useIdle = ReactorState.forceIdle || peak < 0.05f
        if (useIdle) {
            ReactorState.dbgPeak = peak
            ReactorState.dbgAgc = agcPeak
            ReactorState.dbgAvgAmp = 0f
            stepIdle(timeMs, baseH, maxH, k)
            return
        }

        var ampSum = 0f
        for (rod in rods) {
            val v = sample(rod, bins)
            var a = ((v - floor) / span).coerceIn(0f, 1f)
            a = a.pow(gamma) * gain
            // 上限就是 1.0：这样「最大高度」才是真正的天花板。
            // 之前允许到 1.8，导致大部分棒子长期顶在上限，
            // 再调灵敏度看不出任何变化。
            a = a.coerceIn(0f, 1f)
            ampSum += a
            val target = baseH + a * maxH
            rod.h += (target - rod.h) * k
        }

        // 供设置页显示，便于确认参数是否真的生效
        ReactorState.dbgPeak = peak
        ReactorState.dbgAgc = agcPeak
        ReactorState.dbgAvgAmp = ampSum / rods.size
    }

    /**
     * 无音频时的律动驱动。
     *
     * off    回落并静止
     * wave   波纹（默认）
     * breath 整场同步呼吸
     * pulse  快速向外扫过的同心脉冲
     * chaos  失控：每根棒独立无规律抖动 + 全局偶发爆发
     */
    private fun stepIdle(timeMs: Long, baseH: Float, maxH: Float, k: Float) {
        val t = timeMs * 0.001f
        when (ReactorState.idleMode) {
            ReactorState.IDLE_OFF -> {
                for (rod in rods) {
                    rod.h += (baseH - rod.h) * k
                }
            }

            ReactorState.IDLE_BREATH -> {
                val v = (sin(t * 0.8f) * 0.5f + 0.5f).toFloat()
                for (rod in rods) {
                    // 每根棒相位略有差异，避免完全呆板
                    val jitter = 0.94f + 0.06f * sin(rod.x * 0.4f + rod.z * 0.3f)
                    val target = baseH + v * maxH * 0.45f * jitter
                    rod.h += (target - rod.h) * (k * 0.6f)
                }
            }

            ReactorState.IDLE_PULSE -> {
                for (rod in rods) {
                    val phase = rod.r * 0.55f - t * 3.2f
                    val v = (sin(phase) * 0.5f + 0.5f).toFloat()
                    val target = baseH + v.pow(2.2f) * maxH * 0.65f
                    rod.h += (target - rod.h) * (k * 1.4f).coerceAtMost(0.6f)
                }
            }

            ReactorState.IDLE_CHAOS -> {
                // ============================================================
                // 失控模式（高斯版）
                // ============================================================
                // 旧版的每根棒振幅权重几乎一样（只有相位差），所以整场高度
                // 看起来是"齐平的"。这里用高斯权重让中心更容易躁动：
                //   g = exp(-r² / (2σ²))   中心 ≈ 1，边缘（σ=6, r=15）≈ 0.04
                // 另外叠加低概率的「异常尖峰」，只有个别棒会突然窜满。
                val sigma = ReactorState.chaosSigma.coerceIn(1.5f, 20f)
                val sigma2 = 2f * sigma * sigma
                val spikeRate = ReactorState.chaosSpike.coerceIn(0f, 3f)
                // 尖峰出现的半径位置：概率按到该半径的距离做高斯衰减（σ=3）
                val spikePos = ReactorState.chaosSpikePos.coerceIn(0f, 15f)

                // 全局爆发：约 20 秒一次的整体躁动，保留原来的节奏感
                val burst = (sin(t * 0.31f + 1.3f) * 0.5f + 0.5f).toFloat().pow(7f)

                for (rod in rods) {
                    // 1) 高斯空间权重
                    val g = kotlin.math.exp(-(rod.r * rod.r) / sigma2)

                    // 2) 活跃度随机游走（低通随机 → 不会逐帧乱闪）
                    val noise = Random.nextFloat()
                    rod.act = (
                            rod.act * 0.97f +
                                    noise * 0.03f * (0.15f + g)
                            ).coerceIn(0f, 1f)

                    // 3) 异常尖峰：概率与 g 成正比，命中时这根棒瞬间拉满
                    val dr = rod.r - spikePos
                    val spikeW = kotlin.math.exp(-(dr * dr) / 18f)
                    if (spikeRate > 0f &&
                        Random.nextFloat() < 0.0012f * spikeRate * (0.05f + spikeW)
                    ) {
                        rod.act = 1.8f
                    }
                    // 自然衰减
                    rod.act *= 0.985f

                    // 4) 柔和底噪波浪（保留一点平滑运动，避免纯噪声）
                    val wave = sin(
                        t * (1.5f + rod.seed * 3.0f) + rod.x * 0.7f + rod.z * 0.5f
                    ) * 0.5f + 0.5f

                    val v = (
                            g * (wave * 0.35f + rod.act * 0.9f) +
                                    burst * 0.25f * g
                            ).coerceIn(0f, 1f)

                    val target = baseH + v * maxH * (0.5f + burst * 0.6f)
                    // 响应更快，显得更急躁
                    rod.h += (target - rod.h) * (k * 2.2f).coerceAtMost(0.55f)
                }
            }

            else -> {
                // IDLE_WAVE
                for (rod in rods) {
                    val wave = sin(rod.r * 0.55f - timeMs * 0.0009f + rod.x * 0.25f)
                    val target = baseH + (0.10f + 0.10f * wave) * maxH * 0.5f
                    rod.h += (target - rod.h) * k
                }
            }
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
