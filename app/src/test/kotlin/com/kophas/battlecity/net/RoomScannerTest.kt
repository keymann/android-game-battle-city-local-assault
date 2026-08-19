package com.kophas.battlecity.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 메인 메뉴의 방 찾기. (계획서 §27)
 *
 * 세어만 보고 들어가지 않는다. JOIN 을 눌렀는데 아무 방도 없어 빈 화면을 보고 있는
 * 일을 없애려는 것이므로, "없을 때 없다고 하는가" 가 이 시험의 핵심이다.
 */
class RoomScannerTest {

    private val network = LoopbackNetwork()
    private var now = 1_000L
    private val clock = { now }

    private val scanner = RoomScanner(network.open("10.0.0.9", 50100), clock)

    private fun tick(times: Int = 4, stepMs: Long = 300, host: HostSession? = null) {
        repeat(times) {
            scanner.update()
            host?.update()
            now += stepMs
        }
    }

    @Test
    fun `방이 없으면 0을 센다`() {
        tick()
        assertEquals(0, scanner.roomCount)
        assertNull(scanner.firstRoom)
    }

    @Test
    fun `열린 방을 찾아낸다`() {
        val host = HostSession(network.open("10.0.0.1", Protocol.PORT), "거실", 0, clock)
        tick(host = host)

        assertEquals(1, scanner.roomCount)
        assertNotNull(scanner.firstRoom)
        assertEquals(Protocol.PORT, scanner.firstRoom!!.port)
    }

    @Test
    fun `방이 닫히면 목록에서 사라진다`() {
        val host = HostSession(network.open("10.0.0.1", Protocol.PORT), "거실", 0, clock)
        tick(host = host)
        assertEquals(1, scanner.roomCount)

        // 방이 사라졌다는 통보는 오지 않는다. 답이 끊긴 것으로 알아채야 한다.
        host.close()
        tick(times = 6, stepMs = 700)
        assertEquals(0, scanner.roomCount)
    }

    @Test
    fun `이미 시작한 방은 세지 않는다`() {
        val host = HostSession(network.open("10.0.0.1", Protocol.PORT), "거실", 0, clock)
        tick(host = host)
        assertEquals(1, scanner.roomCount)

        host.lobby.markStarted()
        tick(times = 6, stepMs = 700, host = host)
        assertEquals("들어갈 수 없는 방은 없는 것과 같다", 0, scanner.roomCount)
    }
}
