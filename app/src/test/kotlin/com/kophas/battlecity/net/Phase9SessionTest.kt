package com.kophas.battlecity.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 지연 측정 · 결과 화면 재석 · 방 재개. (계획서 §33, §44.2)
 *
 * 시계와 통로를 손으로 돌린다. 왕복 시간을 "실제로 1초 기다려서" 재면 시험이
 * 느려지기만 하고 정확해지지도 않는다.
 */
class Phase9SessionTest {

    private val network = LoopbackNetwork()
    private var now = 1_000L
    private val clock = { now }

    private val hostTransport = network.open("10.0.0.1", Protocol.PORT)
    private val host = HostSession(hostTransport, "거실", 0, clock)
    private val hostPeer = Peer("10.0.0.1", Protocol.PORT)

    private fun pump(vararg clients: ClientSession, times: Int = 4, stepMs: Long = 50) {
        repeat(times) {
            host.update()
            for (client in clients) client.update()
            now += stepMs
        }
    }

    private fun joinedClient(port: Int, name: String): ClientSession {
        val client = ClientSession(network.open("10.0.0.$port", port), name, clock)
        client.join(hostPeer, 0)
        pump(client)
        return client
    }

    // --- 지연 ------------------------------------------------------------

    @Test
    fun `왕복 시간을 잰다`() {
        val client = joinedClient(50001, "손님")
        // 한 번 오가는 데 시계가 두 번 흐른다. 재는 값도 그만큼이다.
        pump(client, times = 40, stepMs = 10)

        assertTrue("아직 못 쟀다", client.latencyMs >= 0)
        assertTrue("왕복 시간이 터무니없다: ${client.latencyMs}", client.latencyMs < 200)
    }

    @Test
    fun `호스트도 참가자까지의 왕복 시간을 잰다`() {
        val client = joinedClient(50002, "손님")
        pump(client, times = 40, stepMs = 10)

        assertTrue("호스트가 못 쟀다", host.latencyMs >= 0)
    }

    @Test
    fun `아무도 없으면 잴 것이 없다`() {
        pump(times = 10)
        assertEquals(-1, host.latencyMs)
    }

    // --- 결과 화면 재석 ---------------------------------------------------

    @Test
    fun `결과 화면에 남아 있다고 알리면 호스트가 센다`() {
        val client = joinedClient(50003, "손님")
        assertEquals(0, host.resultPeerCount)

        client.sendPresence(true)
        pump(client)
        assertEquals(1, host.resultPeerCount)
    }

    @Test
    fun `결과 화면을 떠나면 호스트의 셈에서 빠진다`() {
        val client = joinedClient(50004, "손님")
        client.sendPresence(true)
        pump(client)

        client.sendPresence(false)
        pump(client)
        assertEquals(0, host.resultPeerCount)
    }

    @Test
    fun `접속이 끊기면 결과 화면 재석도 사라진다`() {
        val client = joinedClient(50005, "손님")
        client.sendPresence(true)
        pump(client)
        assertEquals(1, host.resultPeerCount)

        client.leave()
        pump(client)
        assertEquals(0, host.resultPeerCount)
    }

    // --- 방 재개 ----------------------------------------------------------

    @Test
    fun `판이 끝나고 로비를 다시 열면 사람은 남고 준비만 풀린다`() {
        val client = joinedClient(50006, "손님")
        client.setReady(true, 0, 1, "GST")
        pump(client)
        host.prepareMatch(Messages.Start(7L, 0, 0, 0L, 0))
        assertTrue(host.requestStart())
        repeat(Protocol.COUNTDOWN_SECONDS * 60 + 4) { host.update() }
        pump(client)
        assertTrue(host.lobby.started)

        host.reopenLobby(now)

        assertFalse("다시 로비다", host.lobby.started)
        assertEquals("사람은 그대로 있다", 2, host.lobby.connectedCount)
        assertTrue("방장은 준비된 채로 둔다", host.lobby.slots[0].ready)
        assertFalse("참가자 준비는 풀린다", host.lobby.slots[1].ready)
    }

    @Test
    fun `다시 연 방에서 두 번째 판을 시작할 수 있다`() {
        val client = joinedClient(50007, "손님")
        client.setReady(true, 0, 1, "GST")
        pump(client)
        host.prepareMatch(Messages.Start(7L, 0, 0, 0L, 0))
        host.requestStart()
        repeat(Protocol.COUNTDOWN_SECONDS * 60 + 4) { host.update() }
        pump(client)

        host.reopenLobby(now)
        client.setReady(true, 0, 1, "GST")
        pump(client)

        var second: Messages.Start? = null
        client.listener = object : ClientSession.Listener {
            override fun onMatchStart(start: Messages.Start) {
                second = start
            }
        }
        host.prepareMatch(Messages.Start(9L, 1, 0, 0L, 0, mapSize = 2, friendlyFire = false))
        assertTrue("다시 시작할 수 있어야 한다", host.requestStart())
        repeat(Protocol.COUNTDOWN_SECONDS * 60 + 4) { host.update() }
        pump(client)

        assertNotNull("두 번째 START 가 오지 않았다", second)
        assertEquals(9L, second!!.seed)
        assertEquals("스테이지가 넘어간다", 1, second!!.stageIndex)
    }

    // --- PLAY AGAIN -------------------------------------------------------

    /** 한 판을 끝까지 돌린다. 결과 화면 앞까지 가는 준비 과정이다. */
    private fun playOneMatch(client: ClientSession) {
        client.setReady(true, 0, 1, "GST")
        pump(client)
        host.prepareMatch(Messages.Start(7L, 0, 0, 0L, 0))
        host.requestStart()
        repeat(Protocol.COUNTDOWN_SECONDS * 60 + 4) { host.update() }
        pump(client)
    }

    @Test
    fun `결과 화면에 남은 사람과 다음 판을 연다`() {
        val client = joinedClient(50010, "손님")
        playOneMatch(client)

        client.sendPresence(true)
        pump(client)

        host.prepareMatch(Messages.Start(11L, 1, 0, 0L, 0))
        assertTrue("남은 사람이 있으면 다시 시작할 수 있다", host.playAgain(now))

        var second: Messages.Start? = null
        client.listener = object : ClientSession.Listener {
            override fun onMatchStart(start: Messages.Start) {
                second = start
            }
        }
        repeat(Protocol.COUNTDOWN_SECONDS * 60 + 4) { host.update() }
        pump(client)

        assertNotNull("두 번째 판이 열리지 않았다", second)
        assertEquals(11L, second!!.seed)
        // 준비를 다시 받지 않는다. 남아 있는 것이 곧 다시 하겠다는 뜻이다.
        assertEquals(2, second!!.playerCount)
    }

    @Test
    fun `아무도 안 남으면 다시 시작할 수 없다`() {
        val client = joinedClient(50011, "손님")
        playOneMatch(client)

        // 결과 화면에 들어왔다가 나갔다. 방장 혼자 남았다.
        client.sendPresence(true)
        pump(client)
        client.sendPresence(false)
        pump(client)

        assertFalse("혼자서는 판을 열 수 없다", host.playAgain(now))
    }

    @Test
    fun `결과 화면을 떠난 사람은 다음 판에서 빠진다`() {
        val stay = joinedClient(50012, "남는이")
        val leaver = joinedClient(50013, "떠난이")
        stay.setReady(true, 0, 1, "STY")
        leaver.setReady(true, 0, 2, "LVE")
        pump(stay, leaver)
        host.prepareMatch(Messages.Start(7L, 0, 0, 0L, 0))
        host.requestStart()
        repeat(Protocol.COUNTDOWN_SECONDS * 60 + 4) { host.update() }
        pump(stay, leaver)

        stay.sendPresence(true)
        pump(stay, leaver)

        host.prepareMatch(Messages.Start(13L, 1, 0, 0L, 0))
        assertTrue(host.playAgain(now))
        repeat(Protocol.COUNTDOWN_SECONDS * 60 + 4) { host.update() }

        // 방장 + 남은 사람. 떠난 사람은 자리에서 빠진다.
        assertEquals(2, host.lobby.connectedCount)
    }

    // --- 방 규칙 전달 -----------------------------------------------------

    @Test
    fun `방 규칙은 판이 열리기 전에도 로비 현황에 실려 온다`() {
        var latest: Messages.LobbyUpdate? = null
        val client = ClientSession(network.open("10.0.0.50014", 50014), "손님", clock)
        client.listener = object : ClientSession.Listener {
            override fun onLobby(update: Messages.LobbyUpdate) {
                latest = update
            }
        }
        client.join(hostPeer, 0)
        pump(client)

        // 방장이 규칙을 바꾸면 START 를 기다리지 않고 곧바로 내려간다.
        host.prepareMatch(
            Messages.Start(
                seed = 0, stageIndex = 0, playerCount = 0, gridHash = 0, startTick = 0,
                mapSize = 0, friendlyFire = false, maxActiveEnemies = 9, baseProtection = false,
            ),
        )
        pump(client, times = 8, stepMs = 300)

        assertNotNull("로비 현황이 오지 않았다", latest)
        assertEquals(0, latest!!.mapSize)
        assertFalse(latest!!.friendlyFire)
        assertEquals(9, latest!!.maxActiveEnemies)
        assertFalse(latest!!.baseProtection)
    }

    @Test
    fun `방 규칙이 START 에 실려 참가자에게 간다`() {
        val client = joinedClient(50008, "손님")
        client.setReady(true, 0, 1, "GST")
        pump(client)

        var received: Messages.Start? = null
        client.listener = object : ClientSession.Listener {
            override fun onMatchStart(start: Messages.Start) {
                received = start
            }
        }
        host.prepareMatch(
            Messages.Start(
                seed = 42L,
                stageIndex = 0,
                playerCount = 0,
                gridHash = 0L,
                startTick = 0,
                mapSize = 2,
                friendlyFire = false,
                maxActiveEnemies = 11,
                baseProtection = false,
            ),
        )
        host.requestStart()
        repeat(Protocol.COUNTDOWN_SECONDS * 60 + 4) { host.update() }
        pump(client)

        assertNotNull("START 가 오지 않았다", received)
        assertEquals(2, received!!.mapSize)
        assertFalse(received!!.friendlyFire)
        assertEquals(11, received!!.maxActiveEnemies)
        assertFalse(received!!.baseProtection)
        // 인원은 방장이 세어 채운다. 참가자가 스스로 셀 수 없다.
        assertEquals(2, received!!.playerCount)
    }
}
