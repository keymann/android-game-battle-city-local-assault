package com.kophas.battlecity.map

/**
 * xorshift128+ 난수기.
 *
 * `kotlin.random.Random` 은 구현이 바뀔 수 있어 쓰지 않는다.
 * Host 와 모든 Client 가 같은 seed 로 **완전히 같은 맵**을 만들어야 하기 때문에
 * 알고리즘을 직접 고정한다. (계획서 §41-4 / docs/STAGE_GENERATION.md §1)
 */
class Rng(seed: Long) {

    private var s0: Long
    private var s1: Long

    init {
        // SplitMix64 로 seed 를 흩뿌린다. seed 0 이 들어와도 상태가 죽지 않는다.
        var z = seed
        s0 = splitMix(z.also { z = it + GAMMA })
        s1 = splitMix(z + GAMMA)
        if (s0 == 0L && s1 == 0L) s1 = 1L
    }

    fun nextLong(): Long {
        var x = s0
        val y = s1
        s0 = y
        x = x xor (x shl 23)
        s1 = x xor y xor (x ushr 17) xor (y ushr 26)
        return s1 + y
    }

    /** 0 이상 [bound] 미만. */
    fun nextInt(bound: Int): Int {
        require(bound > 0) { "bound 는 0보다 커야 한다" }
        return ((nextLong() ushr 1) % bound).toInt()
    }

    /** [from] 이상 [untilExclusive] 미만. */
    fun nextInt(from: Int, untilExclusive: Int): Int {
        require(untilExclusive > from) { "범위가 비어 있다" }
        return from + nextInt(untilExclusive - from)
    }

    /** 0.0 이상 1.0 미만. */
    fun nextFloat(): Float = ((nextLong() ushr 40).toFloat() / (1 shl 24).toFloat())

    fun nextFloat(from: Float, untilExclusive: Float): Float =
        from + nextFloat() * (untilExclusive - from)

    fun nextBoolean(): Boolean = (nextLong() ushr 63) == 1L

    /** [probability] 확률로 true. */
    fun chance(probability: Float): Boolean = nextFloat() < probability

    fun <T> pick(items: List<T>): T = items[nextInt(items.size)]

    fun <T> pick(items: Array<T>): T = items[nextInt(items.size)]

    /** Fisher-Yates. 원본을 건드리지 않는다. */
    fun <T> shuffled(items: List<T>): List<T> {
        val result = items.toMutableList()
        for (i in result.lastIndex downTo 1) {
            val j = nextInt(i + 1)
            val tmp = result[i]
            result[i] = result[j]
            result[j] = tmp
        }
        return result
    }

    private fun splitMix(input: Long): Long {
        var z = input
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
        return z xor (z ushr 31)
    }

    companion object {
        private const val GAMMA = -0x61c8864680b583ebL

        /** 재생성 시 seed 를 결정론적으로 굴린다. */
        fun advanceSeed(seed: Long): Long = seed * 6364136223846793005L + 1442695040888963407L
    }
}
