package com.kophas.battlecity.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host 와 Client 를 한 시험 안에 놓고 실제로 주고받게 한다. (계획서 §28, §35, §37)
 *
 * 시간과 통로를 모두 손으로 돌린다. 그래야 "4초 동안 소식이 없으면" 같은 규칙을
 * 4초를 기다리지 않고 확인할 수 있고, 패킷을 일부러 잃어버리게 할 수도 있다.
 */
class SessionTest {

    private val network = LoopbackNetwork()
    private var now = 1_000L
    private val clock = { now }

    private val hostTransport = network.open("10.0.0.1", Protocol.PORT)
    private val host = HostSession(hostTransport, "거실", 0, clock)

    private fun newClient(port: Int, name: String): ClientSession =
        ClientSession(network.open("10.0.0.$port", port), name, clock)

    /** 양쪽을 몇 번 굴린다. 한 번에 오가는 패킷은 한 방향뿐이라 여러 번 돌려야 한다. */
    private fun pump(vararg clients: ClientSession, times: Int = 4, stepMs: Long = 50) {
        repeat(times) {
            host.update()
            for (client in clients) client.update()
            now += stepMs
        }
    }

    private fun joinedClient(port: Int, name: String, tankType: Int = 1): ClientSession {
        val client = newClient(port, name)
        client.join(host.let { Peer("10.0.0.1", Protocol.PORT) }, tankType)
        pump(client)
        return client
    }

    // --- 방 찾기와 입장 ---------------------------------------------------

    @Test
    fun `카운트다운 중에는 새로 들어올 수 없다`() {
        val client = joinedClient(50021, "손님")
        client.setReady(true, 0, 1, "GST")
        pump(client)
        host.prepareMatch(Messages.Start(1L, 0, 0, 0L, 0))
        assertTrue(host.requestStart())

        // 세는 중에 인원이 늘면 서로 다른 크기의 맵을 만든다. (계획서 §35)
        var denied = -1
        val late = newClient(50022, "지각")
        late.listener = object : ClientSession.Listener {
            override fun onDenied(reason: Int) {
                denied = reason
            }
        }
        late.join(hostPeerAddress(), 0)
        pump(client, late)

        assertEquals(Protocol.Deny.ALREADY_STARTED, denied)
        assertEquals("자리를 내주지 않아야 한다", 2, host.lobby.connectedCount)
    }

    private fun hostPeerAddress() = Peer("10.0.0.1", Protocol.PORT)

    @Test
    fun `브로드캐스트로 방을 찾는다`() {
        val client = newClient(50001, "손님")
        var found: Messages.Announce? = null
        client.listener = object : ClientSession.Listener {
            override fun onHostFound(peer: Peer, announce: Messages.Announce) {
                found = announce
            }
        }
        client.search()
        pump(client)

        assertNotNull("방을 못 찾았다", found)
        assertEquals("거실", found!!.hostName)
        assertEquals(4, found!!.maxPlayers)
    }

    @Test
    fun `들어가면 자리를 배정받는다`() {
        val client = joinedClient(50002, "손님")
        assertEquals(ClientSession.State.LOBBY, client.state)
        assertEquals(1, client.slot)
        assertEquals(2, host.lobby.connectedCount)
        assertEquals("손님", host.lobby.slots[1].name)
    }

    @Test
    fun `호스트는 언제나 0번이고 준비된 상태다`() {
        assertTrue(host.lobby.slots[0].host)
        assertTrue(host.lobby.slots[0].ready)
        assertEquals("거실", host.lobby.slots[0].name)
    }

    @Test
    fun `방이 차면 더 받지 않는다`() {
        val guests = (2..4).map { joinedClient(50000 + it, "손님$it") }
        assertEquals(4, host.lobby.connectedCount)

        val late = newClient(50009, "지각")
        var reason = -1
        late.listener = object : ClientSession.Listener {
            override fun onDenied(value: Int) {
                reason = value
            }
        }
        late.join(Peer("10.0.0.1", Protocol.PORT), 0)
        pump(late, *guests.toTypedArray())

        assertEquals(Protocol.Deny.ROOM_FULL, reason)
        assertEquals(4, host.lobby.connectedCount)
    }

    @Test
    fun `입장 패킷이 중복돼도 자리를 두 번 주지 않는다`() {
        // UDP 는 같은 패킷이 두 번 도착하는 일이 흔하다.
        val client = joinedClient(50010, "손님")
        client.join(Peer("10.0.0.1", Protocol.PORT), 1)
        pump(client)
        assertEquals(2, host.lobby.connectedCount)
        assertEquals(1, client.slot)
    }

    @Test
    fun `첫 입장 패킷을 잃어버려도 다시 보내 들어간다`() {
        val client = newClient(50011, "손님")
        network.dropNext = 1
        client.join(Peer("10.0.0.1", Protocol.PORT), 0)
        pump(client, times = 12, stepMs = 100)

        assertEquals(ClientSession.State.LOBBY, client.state)
        assertEquals(1, client.slot)
    }

    // --- 로비와 시작 ------------------------------------------------------

    @Test
    fun `혼자서는 시작할 수 없다`() {
        // 최소 인원은 두 명이다. (계획서 §28)
        assertFalse(host.lobby.canStart)
        assertFalse(host.requestStart())
    }

    @Test
    fun `모두 준비해야 시작할 수 있다`() {
        val client = joinedClient(50003, "손님")
        assertFalse("아직 준비 안 했다", host.lobby.canStart)

        client.setReady(true, 2, 3, "GST")
        pump(client)
        assertTrue(host.lobby.canStart)
        assertTrue(host.lobby.slots[1].ready)
        assertEquals(2, host.lobby.slots[1].tankType)
    }

    @Test
    fun `준비를 물리면 카운트다운이 멈춘다`() {
        val client = joinedClient(50004, "손님")
        client.setReady(true, 0, 1, "GST")
        pump(client)
        assertTrue(host.requestStart())
        assertTrue(host.lobby.countdownTicks > 0)

        client.setReady(false, 0, 1, "GST")
        pump(client)
        assertEquals(0, host.lobby.countdownTicks)
    }

    @Test
    fun `카운트다운이 끝나면 양쪽이 같은 seed 로 시작한다`() {
        val client = joinedClient(50005, "손님")
        client.setReady(true, 0, 1, "GST")
        pump(client)

        host.prepareMatch(Messages.Start(seed = 20260819L, stageIndex = 3, playerCount = 0, gridHash = -777L, startTick = 0))
        assertTrue(host.requestStart())

        var hostStart: Messages.Start? = null
        var clientStart: Messages.Start? = null
        host.listener = object : HostSession.Listener {
            override fun onMatchStart(start: Messages.Start) {
                hostStart = start
            }
        }
        client.listener = object : ClientSession.Listener {
            override fun onMatchStart(start: Messages.Start) {
                clientStart = start
            }
        }
        // 3초 x 60틱. 시험에서는 한 번에 한 틱씩 세지 않고 곧바로 굴린다.
        repeat(Protocol.COUNTDOWN_SECONDS * 60 + 4) { host.update() }
        pump(client)

        assertNotNull("호스트가 시작을 못 알렸다", hostStart)
        assertEquals(hostStart, clientStart)
        assertEquals(20260819L, clientStart!!.seed)
        assertEquals(-777L, clientStart!!.gridHash)
        assertEquals(ClientSession.State.PLAYING, client.state)
    }

    @Test
    fun `시작한 뒤에는 들어올 수 없다`() {
        val client = joinedClient(50006, "손님")
        client.setReady(true, 0, 1, "GST")
        pump(client)
        host.requestStart()
        repeat(Protocol.COUNTDOWN_SECONDS * 60 + 4) { host.update() }

        val late = newClient(50012, "지각")
        var reason = -1
        late.listener = object : ClientSession.Listener {
            override fun onDenied(value: Int) {
                reason = value
            }
        }
        late.join(Peer("10.0.0.1", Protocol.PORT), 0)
        pump(late, client)
        assertEquals(Protocol.Deny.ALREADY_STARTED, reason)
    }

    // --- 게임 중 ----------------------------------------------------------

    @Test
    fun `조종 입력이 호스트에 그대로 도착한다`() {
        val client = startedClient(50007)
        val received = ArrayList<Pair<Int, Messages.Input>>()
        host.listener = object : HostSession.Listener {
            override fun onInput(slot: Int, input: Messages.Input) {
                received += slot to input
            }
        }

        val input = Messages.Input(tick = 42L, direction = 1, moving = true, fire = true, special = false)
        client.sendInput(input)
        pump(client)

        assertEquals(1, received.size)
        assertEquals(1, received[0].first)
        assertEquals(input, received[0].second)
    }

    @Test
    fun `상태 스냅샷이 클라이언트에 쌓인다`() {
        val client = startedClient(50008)
        val first = snapshot(tick = 10L, x = 100f)
        val second = snapshot(tick = 13L, x = 132f)

        host.sendSnapshot(first)
        pump(client, times = 1)
        host.sendSnapshot(second)
        pump(client, times = 1)

        assertEquals(second, client.latest)
        assertEquals(first, client.previous)
    }

    @Test
    fun `두 스냅샷 사이를 메워 그린다`() {
        // 상태는 20Hz 로 오는데 화면은 60Hz 다. 그대로 그리면 뚝뚝 끊긴다.
        val client = startedClient(50014)
        host.sendSnapshot(snapshot(tick = 10L, x = 100f))
        client.update()
        now += 50 // 20Hz 한 간격
        host.sendSnapshot(snapshot(tick = 13L, x = 200f))
        client.update()

        assertEquals("막 받은 직후에는 한 장 뒤에서 출발한다", 0f, client.interpolation(), 0.02f)
        now += 25
        assertEquals("반쯤 왔다", 0.5f, client.interpolation(), 0.05f)
        now += 500
        assertEquals("다음 장이 늦어도 앞질러 가지 않는다", 1f, client.interpolation(), 0.001f)
    }

    // --- 끊김 (계획서 §37) ------------------------------------------------

    @Test
    fun `소식이 끊긴 손님을 호스트가 내보낸다`() {
        val client = joinedClient(50021, "손님")
        var left = -1
        host.listener = object : HostSession.Listener {
            override fun onPlayerLeft(slot: Int) {
                left = slot
            }
        }

        client.leave() // 더 이상 아무것도 보내지 않는다
        now += Protocol.TIMEOUT_MS + 100
        host.update()

        assertEquals(1, left)
        assertEquals("남은 사람은 계속한다", 1, host.lobby.connectedCount)
        assertFalse(host.lobby.slots[1].connected)
    }

    @Test
    fun `나간다고 알리면 곧바로 자리가 빈다`() {
        val client = joinedClient(50015, "손님")
        client.leave()
        pump(client)
        assertEquals(1, host.lobby.connectedCount)
    }

    @Test
    fun `호스트가 방을 닫으면 손님이 알아차린다`() {
        val client = joinedClient(50016, "손님")
        var disconnected = false
        client.listener = object : ClientSession.Listener {
            override fun onDisconnected() {
                disconnected = true
            }
        }
        host.close()
        client.update()

        assertTrue(disconnected)
        assertEquals(ClientSession.State.DISCONNECTED, client.state)
    }

    @Test
    fun `호스트가 조용해지면 손님이 끊긴 것으로 본다`() {
        val client = joinedClient(50017, "손님")
        hostTransport.close()

        now += Protocol.TIMEOUT_MS + 100
        client.update()
        assertEquals(ClientSession.State.DISCONNECTED, client.state)
    }

    @Test
    fun `숨소리만 보내도 끊기지 않는다`() {
        val client = joinedClient(50018, "손님")
        repeat(20) {
            now += Protocol.TIMEOUT_MS / 4
            host.update()
            client.update()
        }
        assertEquals(ClientSession.State.LOBBY, client.state)
        assertEquals(2, host.lobby.connectedCount)
    }

    // --- 헬퍼 -------------------------------------------------------------

    private fun startedClient(port: Int): ClientSession {
        val client = joinedClient(port, "손님")
        client.setReady(true, 0, 1, "GST")
        pump(client)
        host.prepareMatch(Messages.Start(1L, 0, 0, 0L, 0))
        host.requestStart()
        repeat(Protocol.COUNTDOWN_SECONDS * 60 + 4) { host.update() }
        pump(client)
        assertEquals(ClientSession.State.PLAYING, client.state)
        return client
    }

    private fun snapshot(tick: Long, x: Float) = Messages.Snapshot(
        tick = tick,
        phase = 0,
        enemiesRemaining = 40,
        baseDestroyed = false,
        tanks = listOf(Messages.TankState(1, 0, 0, 0, x, 64f, 0, 100, false)),
        projectiles = emptyList(),
        scores = listOf(Messages.ScoreState(0, 0, 3, false)),
    )

    @Test
    fun `자리가 비면 다시 채울 수 있다`() {
        val client = joinedClient(50019, "손님")
        client.leave()
        pump(client)
        assertNull(host.lobby.slots.getOrNull(1)?.name?.takeIf { host.lobby.slots[1].connected })

        val next = joinedClient(50020, "다음")
        assertEquals(1, next.slot)
        assertEquals("다음", host.lobby.slots[1].name)
    }
}
