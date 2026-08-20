package com.kophas.battlecity.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RngTest {

    @Test
    fun `같은 seed 는 같은 수열을 만든다`() {
        val a = Rng(12345L)
        val b = Rng(12345L)
        repeat(1000) { assertEquals(a.nextLong(), b.nextLong()) }
    }

    @Test
    fun `다른 seed 는 다른 수열을 만든다`() {
        val a = Rng(1L)
        val b = Rng(2L)
        var same = 0
        repeat(100) { if (a.nextLong() == b.nextLong()) same++ }
        assertTrue("수열이 사실상 같으면 안 된다", same < 5)
    }

    @Test
    fun `seed 0 도 죽지 않는다`() {
        val rng = Rng(0L)
        val values = List(100) { rng.nextLong() }
        assertTrue("모든 값이 0이면 상태가 죽은 것이다", values.any { it != 0L })
    }

    @Test
    fun `nextInt 는 범위를 벗어나지 않는다`() {
        val rng = Rng(99L)
        repeat(10_000) {
            val v = rng.nextInt(7)
            assertTrue("$v 가 0..6 을 벗어났다", v in 0..6)
        }
    }

    @Test
    fun `구간 nextInt 도 범위를 지킨다`() {
        val rng = Rng(7L)
        repeat(10_000) {
            val v = rng.nextInt(3, 9)
            assertTrue("$v 가 3..8 을 벗어났다", v in 3..8)
        }
    }

    @Test
    fun `nextFloat 는 0 이상 1 미만이다`() {
        val rng = Rng(42L)
        repeat(10_000) {
            val v = rng.nextFloat()
            assertTrue("$v 가 범위를 벗어났다", v >= 0f && v < 1f)
        }
    }

    @Test
    fun `nextInt 분포가 한쪽으로 쏠리지 않는다`() {
        val rng = Rng(2024L)
        val buckets = IntArray(10)
        repeat(100_000) { buckets[rng.nextInt(10)]++ }
        // 균등이면 각 10000. 20% 이내면 충분하다.
        buckets.forEachIndexed { index, count ->
            assertTrue("버킷 $index 가 $count 로 치우쳤다", count in 8000..12000)
        }
    }

    @Test
    fun `shuffled 는 원본을 바꾸지 않고 원소를 보존한다`() {
        val source = (1..20).toList()
        val rng = Rng(5L)
        val shuffled = rng.shuffled(source)

        assertEquals(listOf(1, 2, 3), source.take(3))
        assertEquals(source.toSet(), shuffled.toSet())
        assertNotEquals(source, shuffled)
    }

    @Test
    fun `advanceSeed 는 결정론적이다`() {
        assertEquals(Rng.advanceSeed(100L), Rng.advanceSeed(100L))
        assertNotEquals(Rng.advanceSeed(100L), Rng.advanceSeed(101L))
    }
}
