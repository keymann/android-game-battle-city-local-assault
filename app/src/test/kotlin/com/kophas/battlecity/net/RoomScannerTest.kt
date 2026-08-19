package com.kophas.battlecity.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
    fun `목록에 방장 이름과 인원과 연 시각이 담긴다`() {
        val opened = now
        val host = HostSession(network.open("10.0.0.1", Protocol.PORT), "거실", 0, clock)
        tick(host = host)

        val room = scanner.list.single()
        assertEquals("거실", room.hostName)
        // 방장 한 사람. 인원은 방장을 포함해 센다.
        assertEquals(1, room.players)
        assertEquals(Protocol.MAX_PLAYERS, room.maxPlayers)
        assertEquals(opened, room.createdAt)
        assertFalse("혼자 있는 방이 찼을 리 없다", room.full)
    }

    @Test
    fun `정원이 차면 찬 것으로 표시된다`() {
        val host = HostSession(network.open("10.0.0.1", Protocol.PORT), "거실", 0, clock)
        repeat(Protocol.MAX_PLAYERS - 1) { host.lobby.join("손님", 0, 0, now) }
        tick(host = host)

        val room = scanner.list.single()
        assertEquals(Protocol.MAX_PLAYERS, room.players)
        assertTrue("정원이 찼다", room.full)
    }

    @Test
    fun `새로 고치면 목록을 비우고 다시 받는다`() {
        val host = HostSession(network.open("10.0.0.1", Protocol.PORT), "거실", 0, clock)
        tick(host = host)
        assertEquals(1, scanner.roomCount)

        scanner.refresh()
        assertEquals("새로 고친 직후에는 비어 있다", 0, scanner.roomCount)

        tick(host = host)
        assertEquals("다시 받아 온다", 1, scanner.roomCount)
    }

    @Test
    fun `찬 방으로 표시하면 잠긴다`() {
        val host = HostSession(network.open("10.0.0.1", Protocol.PORT), "거실", 0, clock)
        tick(host = host)
        val room = scanner.list.single()
        assertFalse(room.full)

        // 들어가려다 거절당했을 때. 다음 알림이 오기 전에 잠가 둔다.
        scanner.markFull(room.peer)
        assertTrue(scanner.list.single().full)
    }

    @Test
    fun `지우면 목록에서 빠진다`() {
        val host = HostSession(network.open("10.0.0.1", Protocol.PORT), "거실", 0, clock)
        tick(host = host)

        scanner.remove(scanner.list.single().peer)
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
