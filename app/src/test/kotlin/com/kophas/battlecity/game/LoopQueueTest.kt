package com.kophas.battlecity.game

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 손가락이 만든 일감을 루프 스레드로 넘기는 통로. (계획서 §27)
 *
 * 이 통로가 없던 동안 참가자가 로비에서 누른 것이 조용히 사라졌다. 손가락이 온 UI
 * 스레드에서 소켓을 만져 안드로이드가 막았고, 그 예외는 통로 코드가 삼켰다.
 * 여기서 지키는 것은 하나다 — **쌓은 일감은 drain 을 부를 때에만, 그것을 부른
 * 스레드에서 돈다.**
 */
class LoopQueueTest {

    @Test
    fun `쌓기만 하면 아직 돌지 않는다`() {
        val queue = LoopQueue()
        var ran = false
        queue.post { ran = true }

        assertEquals("쌓인 일감을 못 센다", 1, queue.pending)
        assertTrue("쌓기만 했는데 돌았다", !ran)
    }

    @Test
    fun `drain 을 부른 스레드에서 돈다`() {
        val queue = LoopQueue()
        val posted = Thread.currentThread().name
        var ranOn = ""
        queue.post { ranOn = Thread.currentThread().name }

        val loop = Thread({ queue.drain() }, "loop-probe")
        loop.start()
        loop.join()

        assertEquals("일감이 엉뚱한 스레드에서 돌았다", "loop-probe", ranOn)
        assertTrue("쌓은 스레드와 같은 곳에서 돌았다", ranOn != posted)
        assertEquals(0, queue.pending)
    }

    @Test
    fun `쌓은 순서대로 돈다`() {
        val queue = LoopQueue()
        val order = ArrayList<Int>()
        for (i in 1..5) queue.post { order += i }

        queue.drain()

        assertEquals(listOf(1, 2, 3, 4, 5), order)
    }

    @Test
    fun `도는 중에 쌓인 일감은 다음 차례로 넘긴다`() {
        // 스스로 다시 쌓는 일감 하나가 이 프레임을 붙잡으면 화면이 멈춘다.
        val queue = LoopQueue()
        var runs = 0
        lateinit var again: () -> Unit
        again = {
            runs++
            if (runs < 3) queue.post(again)
        }
        queue.post(again)

        queue.drain()

        assertEquals("한 번에 다 돌아 버렸다", 1, runs)
        assertEquals("다음 차례로 넘기지 않았다", 1, queue.pending)
    }

    @Test
    fun `다른 스레드에서 쌓아도 빠짐없이 돈다`() {
        val queue = LoopQueue()
        val count = 200
        val done = CountDownLatch(count)
        val ready = CountDownLatch(1)

        val hand = Thread {
            ready.countDown()
            repeat(count) { queue.post { done.countDown() } }
        }
        hand.start()
        ready.await(1, TimeUnit.SECONDS)

        // 루프처럼 계속 비운다. 쌓는 쪽과 비우는 쪽이 다른 스레드다.
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (done.count > 0 && System.nanoTime() < deadline) queue.drain()
        hand.join()
        queue.drain()

        assertEquals("잃어버린 일감이 있다", 0, done.count)
    }

    @Test
    fun `화면을 접으면 남은 일감을 버린다`() {
        val queue = LoopQueue()
        var ran = false
        queue.post { ran = true }

        queue.clear()
        queue.drain()

        assertTrue("접었는데도 돌았다", !ran)
        assertEquals(0, queue.pending)
    }
}
