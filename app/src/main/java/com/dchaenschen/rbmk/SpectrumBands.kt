package com.dchaenschen.rbmk

import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * 频段划分与增益曲线的**共享计算**。
 *
 * 音频服务用它把 FFT 频点聚合成 256 段；可视化控件用它画出当前的增益曲线。
 * 两边共用同一份计算，避免「看到的曲线」和「实际生效的增益」不一致。
 */
object SpectrumBands {

    val COUNT = ReactorState.BINS

    /** 每段对应的 FFT 频点范围 */
    val lo = IntArray(COUNT)
    val hi = IntArray(COUNT)

    /** 每段的线性增益（已含倾斜 + 低频搁架） */
    val gain = FloatArray(COUNT)

    /** 每段的增益 dB 值，用于画曲线 */
    val gainDb = FloatArray(COUNT)

    /** 每段的中心频率（Hz），用于画坐标轴 */
    val centerHz = FloatArray(COUNT)

    private var cachedTilt = Float.NaN
    private var cachedShelf = Float.NaN
    private var cachedLow = Float.NaN
    private var cachedHigh = Float.NaN
    private var cachedRate = 0
    private var cachedFft = 0

    /** 参数变了才重算，开销可以忽略 */
    @Synchronized
    fun ensure(sampleRate: Int, fftSize: Int) {
        if (cachedTilt == ReactorState.freqTilt &&
            cachedShelf == ReactorState.lowShelf &&
            cachedLow == ReactorState.fmin &&
            cachedHigh == ReactorState.fmax &&
            cachedRate == sampleRate &&
            cachedFft == fftSize
        ) {
            return
        }
        cachedTilt = ReactorState.freqTilt
        cachedShelf = ReactorState.lowShelf
        cachedLow = ReactorState.fmin
        cachedHigh = ReactorState.fmax
        cachedRate = sampleRate
        cachedFft = fftSize
        recompute(sampleRate, fftSize)
    }

    private fun recompute(sampleRate: Int, fftSize: Int) {
        val freqsPerBin = sampleRate.toDouble() / fftSize
        val half = fftSize / 2

        val low = ReactorState.fmin.toDouble().coerceIn(20.0, 1000.0)
        val high = ReactorState.fmax.toDouble().coerceIn(low * 4.0, 20000.0)
        val tilt = ReactorState.freqTilt.toDouble()
        val shelf = ReactorState.lowShelf.toDouble()

        for (b in 0 until COUNT) {
            val f0 = low * (high / low).pow(b.toDouble() / COUNT)
            val f1 = low * (high / low).pow((b + 1).toDouble() / COUNT)
            lo[b] = (f0 / freqsPerBin).toInt().coerceIn(1, half - 1)
            hi[b] = (f1 / freqsPerBin).toInt().coerceIn(lo[b] + 1, half)

            val fc = sqrt(f0 * f1)
            centerHz[b] = fc.toFloat()

            // 倾斜：以 1kHz 为基准，dB = tilt * log2(f / 1000)
            var gdb = tilt * (ln(fc / 1000.0) / ln(2.0))
            // 低频搁架：250Hz 以下线性叠加
            if (fc < 250.0) {
                val w = ((250.0 - fc) / 230.0).coerceIn(0.0, 1.0)
                gdb += shelf * w
            }
            gainDb[b] = gdb.toFloat()
            gain[b] = 10.0.pow(gdb / 20.0).coerceIn(0.02, 12.0).toFloat()
        }
    }
}
