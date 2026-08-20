package com.kophas.battlecity.game

import com.kophas.battlecity.net.LoopbackNetwork
import com.kophas.battlecity.net.Peer
import com.kophas.battlecity.net.Protocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 로비에서 참가자가 고른 것이 방장에게 닿는가. (계획서 §28, §29)
 *
 * [NetDriver] 두 개를 한 시험 안에 놓고 실제로 주고받게 한다. 화면 없이 돌리므로
 * "눌렀는데 아무 일도 없다" 가 씬 탓인지 세션 탓인지 가른다.
 */
class LobbyFlowTest {

    private val network = LoopbackNetwork()
    private var now = 1_000L
    private val clock = { now }

    private val hostDriver = NetDriver(
        role = NetRole.HOST,
        playerName = "HST",
        hostAddress = null,
        transportFactory = { network.open("10.0.0.1", it) },
        clock = clock,
    )

    private val clientDriver = NetDriver(
        role = NetRole.CLIENT,
        playerName = "GST",
        hostAddress = "10.0.0.1",
        transportFactory = { network.open("10.0.0.2", 50101) },
        clock = clock,
    )

    /** 양쪽을 굴린다. 한 번에 오가는 패킷은 한 방향뿐이라 여러 번 돌려야 한다. */
    private fun pump(times: Int = 8, stepMs: Long = 60) {
        repeat(times) {
            hostDriver.onTick()
            clientDriver.onTick()
            now += stepMs
        }
    }

    private fun hostView(): LobbyScene.View =
        LobbyScene.View().also { hostDriver.fillLobbyView(it) }

    private fun clientView(): LobbyScene.View =
        LobbyScene.View().also { clientDriver.fillLobbyView(it) }

    @Test
    fun `참가자가 로비 현황을 받는다`() {
        pump()
        val view = clientView()
        assertEquals("자리를 받았다", 1, view.localSlot)
        assertEquals("두 사람이 보인다", 2, view.slots.count { it.connected })
    }

    @Test
    fun `참가자가 고른 탱크가 방장에게 닿는다`() {
        pump()
        val before = clientView().slots[1].tankType

        clientDriver.cycleTankType()
        pump()

        val after = hostView().slots[1].tankType
        assertTrue("탱크가 그대로다", after != before)
        assertEquals("참가자 화면도 같은 값이어야 한다", after, clientView().slots[1].tankType)
    }

    @Test
    fun `참가자가 고른 색이 방장에게 닿는다`() {
        pump()
        val before = clientView().slots[1].colorIndex

        clientDriver.cycleColor()
        pump()

        val after = hostView().slots[1].colorIndex
        assertTrue("색이 그대로다", after != before)
    }

    @Test
    fun `참가자가 고친 이름이 방장에게 닿는다`() {
        pump()

        clientDriver.setName("ZZZ")
        pump()

        assertEquals("ZZZ", hostView().slots[1].name)
    }

    @Test
    fun `참가자가 준비하면 방장 화면에 보인다`() {
        pump()
        assertFalse(hostView().slots[1].ready)

        clientDriver.toggleReady()
        pump()

        assertTrue("준비가 닿지 않았다", hostView().slots[1].ready)
    }

    @Test
    fun `모두 준비하면 방장이 시작할 수 있다`() {
        pump()
        clientDriver.toggleReady()
        pump()

        assertTrue("시작할 수 없다", hostDriver.startMatch(null))
        assertTrue("카운트다운이 돌지 않는다", hostDriver.countdownTicks > 0)
    }

    @Test
    fun `준비되지 않은 사람이 있으면 시작할 수 없다`() {
        pump()
        assertFalse(hostDriver.startMatch(null))
    }

    @Test
    fun `카운트다운은 참가자 화면에도 내려간다`() {
        pump()
        clientDriver.toggleReady()
        pump()
        hostDriver.startMatch(null)
        pump(times = 4)

        assertTrue("참가자가 카운트다운을 못 본다", clientView().countdownTicks > 0)
    }

    // --- 아래 큰 단추 (계획서 §28) --------------------------------------

    @Test
    fun `참가자에게는 준비 단추다`() {
        // 눌렀는데 아무 일도 없으면 고장으로 읽힌다. 참가자가 준비할 곳은 여기뿐이다.
        assertEquals(
            LobbyScene.Action.ToggleReady,
            LobbyScene.bottomAction(isHost = false, canStart = false),
        )
        assertEquals(
            LobbyScene.Action.ToggleReady,
            LobbyScene.bottomAction(isHost = false, canStart = true),
        )
    }

    @Test
    fun `방장에게는 시작 단추다`() {
        assertEquals(LobbyScene.Action.Start, LobbyScene.bottomAction(isHost = true, canStart = true))
        // 아직 못 시작할 때는 눌러도 아무 일이 없다. 대신 왜 못 누르는지 써 준다.
        assertEquals(LobbyScene.Action.None, LobbyScene.bottomAction(isHost = true, canStart = false))
    }

    @Test
    fun `단추에 쓰인 말이 지금 하는 일과 같다`() {
        assertEquals("READY", LobbyScene.bottomLabel(0, isHost = false, ready = false, canStart = false))
        assertEquals("CANCEL READY", LobbyScene.bottomLabel(0, isHost = false, ready = true, canStart = false))
        assertEquals("WAIT PLAYERS", LobbyScene.bottomLabel(0, isHost = true, ready = true, canStart = false))
        assertEquals("START GAME", LobbyScene.bottomLabel(0, isHost = true, ready = true, canStart = true))
        assertEquals(
            "3",
            LobbyScene.bottomLabel(180, isHost = true, ready = true, canStart = false) { "3" },
        )
    }

    // --- 현황이 아직 안 왔을 때 -------------------------------------------

    @Test
    fun `로비 현황이 오기 전에 눌러도 사라지지 않는다`() {
        // 아직 아무것도 주고받지 않은 상태. 예전에는 여기서 조용히 무시했다.
        clientDriver.cycleTankType()
        clientDriver.setName("ABC")
        pump()

        val slot = hostView().slots[1]
        assertEquals("ABC", slot.name)
        assertEquals(1, slot.tankType)
    }

    @Test
    fun `참가자가 방을 나가면 방장 화면에서 빠진다`() {
        pump()
        assertEquals(2, hostView().slots.count { it.connected })

        clientDriver.close()
        pump()

        assertEquals(1, hostView().slots.count { it.connected })
    }

    private companion object {
        init {
            // 시험이 쓰는 포트가 겹치지 않게만 해 두면 된다.
            check(Protocol.PORT != 50101)
            check(Peer("10.0.0.1", Protocol.PORT).port == Protocol.PORT)
        }
    }
}
