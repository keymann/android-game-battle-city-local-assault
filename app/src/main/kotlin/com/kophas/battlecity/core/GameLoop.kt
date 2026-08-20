package com.kophas.battlecity.core

import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 전용 스레드에서 도는 게임 루프.
 *
 * 로직(고정 틱)과 렌더(기기 FPS)를 분리한다. (계획서 §25.3, §41-1)
 * 렌더러는 이 루프를 전혀 모르고, 루프도 렌더러 구현을 모른다.
 */
class GameLoop(
    private val callbacks: Callbacks,
    private val clock: FixedStepClock = FixedStepClock(),
    private val threadName: String = "bc-game-loop",
) {
    interface Callbacks {
        /** 고정 간격으로 호출된다. 여기서만 게임 상태를 바꾼다. */
        fun onUpdate(tickIndex: Long, tickSeconds: Float)

        /** 프레임마다 한 번. [alpha] 는 마지막 틱 이후 보간 비율 0..1. */
        fun onRender(alpha: Float)

        /** 1초마다 한 번. 디버그 HUD 용. */
        fun onStats(stats: Stats) = Unit
    }

    data class Stats(
        val fps: Int,
        val ticksPerSecond: Int,
        val droppedTicks: Long,
    )

    private val running = AtomicBoolean(false)
    private val paused = AtomicBoolean(false)
    private val pauseLock = Object()
    private var thread: Thread? = null

    val isRunning: Boolean get() = running.get()

    fun start() {
        if (!running.compareAndSet(false, true)) return
        clock.reset()
        thread = Thread(::run, threadName).also {
            it.priority = Thread.NORM_PRIORITY + 1
            it.start()
        }
    }

    fun pause() {
        paused.set(true)
    }

    fun resume() {
        if (!paused.compareAndSet(true, false)) return
        synchronized(pauseLock) { pauseLock.notifyAll() }
    }

    /** 루프 스레드가 완전히 끝날 때까지 기다린다. */
    fun stop() {
        if (!running.compareAndSet(true, false)) return
        resume()
        thread?.join(2_000)
        thread = null
    }

    private fun run() {
        var previousNanos = System.nanoTime()
        var statsWindowNanos = 0L
        var framesInWindow = 0
        var ticksInWindow = 0

        while (running.get()) {
            if (paused.get()) {
                synchronized(pauseLock) {
                    while (paused.get() && running.get()) {
                        runCatching { pauseLock.wait(100) }
                    }
                }
                // 일시정지 동안 흐른 시간은 게임 시간에 넣지 않는다.
                previousNanos = System.nanoTime()
                continue
            }

            val now = System.nanoTime()
            val frameNanos = now - previousNanos
            previousNanos = now

            val ticks = clock.advance(frameNanos)
            repeat(ticks) {
                callbacks.onUpdate(clock.totalTicks, Constants.TICK_SECONDS)
            }
            callbacks.onRender(clock.alpha)

            framesInWindow++
            ticksInWindow += ticks
            statsWindowNanos += frameNanos
            if (statsWindowNanos >= 1_000_000_000L) {
                callbacks.onStats(Stats(framesInWindow, ticksInWindow, clock.droppedTicks))
                statsWindowNanos = 0
                framesInWindow = 0
                ticksInWindow = 0
            }

            // 스왑체인이 FIFO 라 present 에서 대기하지만, 서피스가 없거나
            // 렌더가 즉시 반환하는 상황에서 CPU 를 태우지 않도록 양보한다.
            if (ticks == 0) {
                runCatching { Thread.sleep(1) }
            }
        }
        Log.i(TAG, "게임 루프 종료 (총 ${clock.totalTicks} 틱, 드롭 ${clock.droppedTicks})")
    }

    private companion object {
        const val TAG = "BattleCity"
    }
}
