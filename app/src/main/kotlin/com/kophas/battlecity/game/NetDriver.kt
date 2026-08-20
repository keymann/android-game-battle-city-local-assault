package com.kophas.battlecity.game

import android.util.Log
import com.kophas.battlecity.map.Rng
import com.kophas.battlecity.net.ClientSession
import com.kophas.battlecity.net.HostSession
import com.kophas.battlecity.net.Messages
import com.kophas.battlecity.net.Peer
import com.kophas.battlecity.net.Protocol
import com.kophas.battlecity.net.Transport
import com.kophas.battlecity.net.UdpTransport

/**
 * 게임 루프와 네트워크 세션을 잇는다. (계획서 §35, §36)
 *
 * 세션은 규칙을 모르고 씬은 네트워크를 모른다. 그 둘을 이 자리에서만 잇는다.
 * 그래야 세션은 통로만 갈아 끼워 시험할 수 있고, 씬은 혼자서도 돌아간다.
 *
 * 상태는 20Hz 로 내보낸다. 게임 로직 60Hz 를 3틱마다 한 번이다. (계획서 §36)
 */
class NetDriver(
    private val role: NetRole,
    private val playerName: String,
    private val hostAddress: String?,
    transportFactory: (Int) -> Transport = { UdpTransport(it) },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private var host: HostSession? = null
    private var client: ClientSession? = null
    private var scene: BattleScene? = null
    private var tick = 0L

    /** 이 기기가 조종할 자리. Host 는 0번, Client 는 배정받는다. */
    var localSlot: Int = 0
        private set

    /** 사람이 잡은 자리. 여기에는 AI 를 붙이지 않는다. */
    val remoteSlots = HashSet<Int>()

    private val transport: Transport? = when (role) {
        NetRole.LOCAL -> null
        NetRole.HOST -> runCatching { transportFactory(Protocol.PORT) }.getOrNull()
        NetRole.CLIENT -> runCatching { transportFactory(0) }.getOrNull()
    }

    init {
        when (role) {
            NetRole.LOCAL -> Unit
            NetRole.HOST -> transport?.let { openRoom(it) }
            NetRole.CLIENT -> transport?.let { joinRoom(it) }
        }
        if (role != NetRole.LOCAL && transport == null) {
            Log.e(TAG, "소켓을 열지 못했다. 혼자 진행한다.")
        }
    }

    fun attachScene(scene: BattleScene) {
        this.scene = scene
    }

    /** 게임 루프가 매 틱 부른다. 절대 기다리지 않는다. */
    fun onTick() {
        tick++
        when (role) {
            NetRole.LOCAL -> Unit
            NetRole.HOST -> driveHost()
            NetRole.CLIENT -> driveClient()
        }
    }

    fun close() {
        host?.close()
        client?.leave()
        transport?.close()
    }

    // -----------------------------------------------------------------------

    private fun openRoom(transport: Transport) {
        val session = HostSession(transport, playerName, 0, clock)
        session.listener = object : HostSession.Listener {
            override fun onPlayerJoined(slot: Int, name: String) {
                remoteSlots += slot
                Log.i(TAG, "$name 님이 ${slot + 1}번 자리에 들어왔다")
            }

            override fun onPlayerLeft(slot: Int) {
                remoteSlots -= slot
                Log.i(TAG, "${slot + 1}번 자리가 끊겼다. 남은 사람은 계속한다")
            }

            override fun onMatchStart(start: Messages.Start) {
                Log.i(TAG, "판 시작 seed=${start.seed} 인원=${start.playerCount}")
                scene?.beginStage(start.seed, start.stageIndex, start.playerCount)
            }

            override fun onInput(slot: Int, input: Messages.Input) {
                scene?.applyRemoteInput(slot, input)
            }
        }
        host = session
        localSlot = 0
    }

    private fun joinRoom(transport: Transport) {
        val session = ClientSession(transport, playerName, clock)
        session.listener = object : ClientSession.Listener {
            override fun onHostFound(peer: Peer, announce: Messages.Announce) {
                Log.i(TAG, "방을 찾았다: ${announce.hostName} (${announce.players}명)")
                session.join(peer, 0)
            }

            override fun onJoined(slot: Int) {
                localSlot = slot
                Log.i(TAG, "${slot + 1}번 자리를 받았다")
                // 조작 UI 는 Phase 7 에서 들어온다. 그때까지는 들어가면 곧 준비된 것으로 본다.
                session.setReady(true, 0)
            }

            override fun onDenied(reason: Int) {
                Log.w(TAG, "입장을 거절당했다 (사유 $reason)")
            }

            override fun onMatchStart(start: Messages.Start) {
                scene?.beginStage(start.seed, start.stageIndex, start.playerCount)
                val expected = scene?.stageGridHash
                if (expected != null && expected != start.gridHash) {
                    // 같은 seed 로 다른 맵이 나왔다는 뜻이다. 그대로 두면 서로 다른
                    // 벽에 부딪히며 판이 어긋난다. 조용히 넘기면 안 된다.
                    Log.e(TAG, "맵이 어긋났다 host=${start.gridHash} client=$expected")
                }
            }

            override fun onDisconnected() {
                Log.w(TAG, "호스트와 끊겼다. 로비로 돌아간다")
            }
        }
        client = session
        if (hostAddress != null) {
            session.join(Peer(hostAddress, Protocol.PORT), 0)
        } else {
            session.search()
        }
    }

    private fun driveHost() {
        val session = host ?: return
        val currentScene = scene

        session.update()

        // 조작 UI 가 없는 지금은 인원이 차고 모두 준비되면 곧바로 시작한다.
        if (!session.lobby.started && session.lobby.canStart && session.lobby.countdownTicks == 0) {
            session.prepareMatch(
                seed = Rng.advanceSeed(clock()),
                stageIndex = 0,
                gridHash = currentScene?.stageGridHash ?: 0L,
            )
            session.requestStart()
        }

        if (currentScene != null && tick % Protocol.TICKS_PER_SNAPSHOT == 0L) {
            session.sendSnapshot(currentScene.captureSnapshot(tick))
        }
    }

    private fun driveClient() {
        val session = client ?: return
        session.update()

        val currentScene = scene ?: return
        val latest = session.latest ?: return
        currentScene.applySnapshot(latest, session.previous, session.interpolation())
    }

    private companion object {
        const val TAG = "BattleCity"
    }
}
