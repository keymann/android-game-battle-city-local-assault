package com.kophas.battlecity.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FixedStepClockTest {

    private val tick = Constants.NANOS_PER_TICK

    @Test
    fun `정확히 한 틱만큼 흐르면 한 틱을 실행한다`() {
        val clock = FixedStepClock()
        assertEquals(1, clock.advance(tick))
        assertEquals(1L, clock.totalTicks)
        assertEquals(0f, clock.alpha, 1e-6f)
    }

    @Test
    fun `틱보다 짧게 흐르면 실행하지 않고 누산만 한다`() {
        val clock = FixedStepClock()
        assertEquals(0, clock.advance(tick / 2))
        assertEquals(0L, clock.totalTicks)
        assertEquals(0.5f, clock.alpha, 0.01f)
    }

    @Test
    fun `잔여 시간은 다음 프레임으로 넘어간다`() {
        val clock = FixedStepClock()
        clock.advance(tick / 2)
        assertEquals(1, clock.advance(tick / 2 + tick / 2))
        assertEquals(1L, clock.totalTicks)
    }

    @Test
    fun `느린 프레임은 여러 틱을 따라잡는다`() {
        val clock = FixedStepClock()
        assertEquals(3, clock.advance(tick * 3))
        assertEquals(3L, clock.totalTicks)
    }

    @Test
    fun `따라잡기 상한을 넘으면 초과분을 버려 죽음의 나선을 막는다`() {
        val clock = FixedStepClock(maxCatchUpTicks = 5)
        val ticks = clock.advance(tick * 100)
        assertEquals(5, ticks)
        assertTrue("초과분이 droppedTicks 로 기록돼야 한다", clock.droppedTicks > 90)
    }

    @Test
    fun `0 이하의 경과 시간은 무시한다`() {
        val clock = FixedStepClock()
        assertEquals(0, clock.advance(0))
        assertEquals(0, clock.advance(-tick))
        assertEquals(0L, clock.totalTicks)
    }

    @Test
    fun `60Hz 로 1초를 돌리면 정확히 60틱이다`() {
        val clock = FixedStepClock()
        var total = 0
        // 실제 기기처럼 프레임 간격을 흔들어도 총 틱 수는 유지돼야 한다.
        val jitter = longArrayOf(15_000_000, 17_000_000, 16_000_000, 18_000_000, 14_000_000)
        var elapsed = 0L
        var index = 0
        while (elapsed < 1_000_000_000L) {
            val step = jitter[index % jitter.size]
            val remaining = 1_000_000_000L - elapsed
            val delta = if (step > remaining) remaining else step
            total += clock.advance(delta)
            elapsed += delta
            index++
        }
        assertEquals(60, total)
        assertEquals(0L, clock.droppedTicks)
    }

    @Test
    fun `reset 은 상태를 초기화한다`() {
        val clock = FixedStepClock()
        clock.advance(tick * 3)
        clock.reset()
        assertEquals(0L, clock.totalTicks)
        assertEquals(0L, clock.droppedTicks)
        assertEquals(0f, clock.alpha, 1e-6f)
    }
}
