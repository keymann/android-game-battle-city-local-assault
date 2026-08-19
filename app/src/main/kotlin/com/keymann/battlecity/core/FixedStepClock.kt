package com.keymann.battlecity.core

/**
 * 고정 timestep 누산기. (계획서 §25.3, §41-9)
 *
 * 렌더 프레임 간격을 받아 실행해야 할 로직 틱 수를 돌려준다.
 * 렌더 FPS 가 흔들려도 게임 로직은 항상 같은 간격으로 진행되므로
 * 시뮬레이션이 결정론적으로 유지된다. (계획서 §41-4)
 */
class FixedStepClock(
    val tickNanos: Long = Constants.NANOS_PER_TICK,
    val maxCatchUpTicks: Int = Constants.MAX_CATCH_UP_TICKS,
) {
    init {
        require(tickNanos > 0) { "tickNanos 는 0보다 커야 한다" }
        require(maxCatchUpTicks > 0) { "maxCatchUpTicks 는 0보다 커야 한다" }
    }

    private var accumulatorNanos: Long = 0

    /** 지금까지 실행한 총 틱 수. 게임 시간의 기준값이다. */
    var totalTicks: Long = 0
        private set

    /**
     * 따라잡기 상한을 넘겨 버린 틱 수.
     * 0이 아니면 기기가 목표 FPS 를 못 내고 있다는 신호다. (계획서 §39)
     */
    var droppedTicks: Long = 0
        private set

    /** 마지막 틱 이후 경과 비율 0..1. 렌더 보간에 쓴다. */
    val alpha: Float
        get() = accumulatorNanos.toFloat() / tickNanos

    /**
     * @param frameNanos 직전 프레임에서 흐른 시간(ns)
     * @return 이번 프레임에 실행해야 할 틱 수
     */
    fun advance(frameNanos: Long): Int {
        if (frameNanos <= 0) return 0

        val capNanos = tickNanos * maxCatchUpTicks
        var delta = frameNanos
        if (delta > capNanos) {
            droppedTicks += (delta - capNanos) / tickNanos
            delta = capNanos
        }

        accumulatorNanos += delta
        val ticks = (accumulatorNanos / tickNanos).toInt()
        accumulatorNanos -= ticks * tickNanos
        totalTicks += ticks
        return ticks
    }

    fun reset() {
        accumulatorNanos = 0
        totalTicks = 0
        droppedTicks = 0
    }
}
