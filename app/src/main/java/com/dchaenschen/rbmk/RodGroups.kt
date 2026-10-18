package com.dchaenschen.rbmk

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.sin

/**
 * 控制棒分组。
 *
 * 四种分组方式：
 *
 *   random   随机 —— 还原原项目的做法：多数棒用主色，少数随机被换成点缀色
 *   rings    同心环 —— 按到中心的距离分层，一圈一个组
 *   sectors  扇形 —— 按极角均分，一个扇区一个组
 *   manual   手动 —— 用户在俯视图上点/刷来指定每一根棒的组
 *
 * 分组结果缓存在每根 Rod 上，参数不变时不重算。
 */
object RodGroups {

    const val SIDE = 31          // 网格边长（x, z ∈ [-15, 15]）
    private const val HALF = 15

    private var cachedMode = ""
    private var cachedCount = -1
    private var cachedRing = Float.NaN
    private var cachedSector = -1
    private var cachedAccent = Float.NaN
    private var cachedRev = -1
    private var cachedManualStr = ""

    /** 手动分组的整数缓存，长度 961 */
    private val manual = IntArray(SIDE * SIDE) { 0 }

    private fun idx(x: Int, z: Int): Int = (x + HALF) * SIDE + (z + HALF)

    fun inGrid(x: Int, z: Int): Boolean = x in -HALF..HALF && z in -HALF..HALF

    // ---------------------------------------------------------------- 计算
    /**
     * 给定网格坐标与半径，返回组号。
     * 手动模式下直接查表；其他模式按规则算。
     */
    fun groupAt(x: Int, z: Int, r: Float): Int = groupFor(x, z, r, ReactorState.groupMode)

    /** 按指定分组方式计算组号（fillManualFromMode 会用到） */
    private fun groupFor(x: Int, z: Int, r: Float, mode: String): Int {
        val n = ReactorState.groupCount.coerceIn(2, ReactorState.GROUP_MAX)
        return when (mode) {
            ReactorState.GROUP_RINGS -> {
                val ring = ReactorState.ringSize.coerceIn(1f, 15f)
                ((r / ring).toInt()).coerceAtLeast(0) % n
            }

            ReactorState.GROUP_SECTORS -> {
                val sectors = ReactorState.sectorCount.coerceIn(2, 12)
                // atan2 结果在 -PI..PI，归一化到 0..sectors-1
                val ang = atan2(z.toFloat(), x.toFloat()).toDouble()
                val t = ((ang + PI) / (2 * PI) * sectors).toInt()
                ((t % sectors) + sectors) % sectors % n
            }

            ReactorState.GROUP_MANUAL -> {
                if (!inGrid(x, z)) return 0
                syncManual()
                manual[idx(x, z)].coerceIn(0, n - 1)
            }

            else -> {
                // random：还原原项目 —— 大部分主色，少数点缀
                val seed = abs(sin(x * 127.1f + z * 311.7f))
                val accent = ReactorState.accentRatio.coerceIn(0f, 0.95f)
                if (n > 1 && accent > 0.001f && seed >= 1f - accent) {
                    val t = (seed - (1f - accent)) / accent
                    (1 + (t * (n - 1)).toInt()).coerceIn(1, n - 1)
                } else {
                    0
                }
            }
        }
    }

    /** 参数变化时重算所有棒的分组 */
    fun ensure(rods: List<RodArray.Rod>) {
        val rev = if (ReactorState.groupMode == ReactorState.GROUP_MANUAL) ReactorState.manualRev else 0
        if (cachedMode == ReactorState.groupMode &&
            cachedCount == ReactorState.groupCount &&
            cachedRing == ReactorState.ringSize &&
            cachedSector == ReactorState.sectorCount &&
            cachedAccent == ReactorState.accentRatio &&
            cachedRev == rev
        ) {
            return
        }
        cachedMode = ReactorState.groupMode
        cachedCount = ReactorState.groupCount
        cachedRing = ReactorState.ringSize
        cachedSector = ReactorState.sectorCount
        cachedAccent = ReactorState.accentRatio
        cachedRev = rev

        for (rod in rods) {
            rod.group = groupAt(rod.x, rod.z, rod.r)
        }
    }

    // ---------------------------------------------------------------- 手动编辑
    /** 把字符串形式的手动数据同步到整数缓存 */
    private fun syncManual() {
        val s = ReactorState.manualGroups
        if (s == cachedManualStr && s.length == SIDE * SIDE) return
        cachedManualStr = s
        if (s.length == SIDE * SIDE) {
            for (i in 0 until SIDE * SIDE) {
                manual[i] = (s[i] - '0').coerceIn(0, ReactorState.GROUP_MAX - 1)
            }
        }
    }

    /** 先把手动数据用当前模式填满（用于进入手动模式时有个底子） */
    /**
     * 把手动数据用某个分组方式填满。
     *
     * @param mode 默认用当前模式；从别的模式切到手动时，调用方应传入
     *             **切换前**的模式，否则会用手动模式自己算，结果全是 0。
     */
    fun fillManualFromMode(mode: String = ReactorState.groupMode) {
        val useMode = if (mode == ReactorState.GROUP_MANUAL) ReactorState.GROUP_RINGS else mode
        val sb = StringBuilder(SIDE * SIDE)
        for (x in -HALF..HALF) {
            for (z in -HALF..HALF) {
                val r = hypot(x.toFloat(), z.toFloat())
                val g = if (r <= 15f) groupFor(x, z, r, useMode) else 0
                sb.append(('0' + g.coerceIn(0, 9)))
            }
        }
        ReactorState.manualGroups = sb.toString()
        ReactorState.manualRev++
        cachedManualStr = "
    }

    /** 清空手动分组（全部归到组 0） */
    fun clearManual() {
        val sb = StringBuilder(SIDE * SIDE)
        repeat(SIDE * SIDE) { sb.append('0') }
        ReactorState.manualGroups = sb.toString()
        ReactorState.manualRev++
        cachedManualStr = ""
    }

    /** 在手动分组里把某个格子设为某组 */
    fun paint(x: Int, z: Int, group: Int) {
        if (!inGrid(x, z)) return
        if (ReactorState.manualGroups.length != SIDE * SIDE) fillManualFromMode()
        val arr = ReactorState.manualGroups.toCharArray()
        arr[idx(x, z)] = ('0' + group.coerceIn(0, ReactorState.GROUP_MAX - 1))
        ReactorState.manualGroups = String(arr)
        ReactorState.manualRev++
        cachedManualStr = ""
    }
}
