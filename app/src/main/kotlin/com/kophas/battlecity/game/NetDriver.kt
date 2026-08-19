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

    /** 사람이 잡은 자리. 여기에는 AI 를 붙이지 않는다. 조종간이 둘이 되기 때문이다. */
    val humanSlots = HashSet<Int>()

    /** 아직 로비에 있는가. 판이 시작되면 false 가 된다. */
    val inLobby: Boolean
        get() = role != NetRole.LOCAL && !started

    private var started = false
    private var lastLobby: Messages.LobbyUpdate? = null

    /** 로비 화면에 그릴 값을 채운다. Host 는 제 로비에서, Client 는 받은 것에서. */
    fun fillLobbyView(view: LobbyScene.View) {
        val update = host?.lobby?.snapshot() ?: lastLobby
        view.slots = update?.slots.orEmpty()
        view.countdownTicks = update?.countdownTicks ?: 0
        view.localSlot = localSlot
        view.host = role == NetRole.HOST
        view.connected = client?.state != ClientSession.State.DISCONNECTED
        view.searching = client?.state == ClientSession.State.SEARCHING
    }

    /** 자기 자리의 준비 상태를 뒤집는다. */
    fun toggleReady() {
        when (role) {
            NetRole.HOST -> {
                val lobby = host?.lobby ?: return
                val slot = lobby.slots.getOrNull(localSlot) ?: return
                lobby.setReady(localSlot, !slot.ready, slot.tankType, clock())
            }

            NetRole.CLIENT -> {
                val ready = lastLobby?.slots?.getOrNull(localSlot)?.ready == true
                client?.setReady(!ready, 0)
            }

            NetRole.LOCAL -> Unit
        }
    }

    /** START. Host 만 누를 수 있다. (계획서 §28) */
    fun startMatch(currentScene: BattleScene?) {
        val session = host ?: return
        session.prepareMatch(
            seed = Rng.advanceSeed(clock()),
            stageIndex = 0,
            gridHash = currentScene?.stageGridHash ?: 0L,
        )
        session.requestStart()
    }

    /** 이 기기의 조종 입력. Host/LOCAL 은 바로 먹이고 Client 는 올려 보낸다. */
    fun submitLocalInput(scene: BattleScene, input: Messages.Input) {
        when (role) {
            NetRole.CLIENT -> client?.sendInput(input)
            else -> scene.applyRemoteInput(localSlot, input)
        }
    }

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
                humanSlots += slot
                Log.i(TAG, "$name 님이 ${slot + 1}번 자리에 들어왔다")
            }

            override fun onPlayerLeft(slot: Int) {
                humanSlots -= slot
                Log.i(TAG, "${slot + 1}번 자리가 끊겼다. 남은 사람은 계속한다")
            }

            override fun onMatchStart(start: Messages.Start) {
                Log.i(TAG, "판 시작 seed=${start.seed} 인원=${start.playerCount}")
                started = true
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
                humanSlots += slot
                Log.i(TAG, "${slot + 1}번 자리를 받았다")
            }

            override fun onLobby(update: Messages.LobbyUpdate) {
                lastLobby = update
            }

            override fun onDenied(reason: Int) {
                Log.w(TAG, "입장을 거절당했다 (사유 $reason)")
            }

            override fun onMatchStart(start: Messages.Start) {
                started = true
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
