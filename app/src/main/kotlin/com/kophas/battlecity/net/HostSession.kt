package com.kophas.battlecity.net

/**
 * 방을 여는 쪽. (계획서 §4.2, §28, §35)
 *
 * 게임 상태의 **권위**를 갖는다. COM 생성과 AI, 충돌 판정, 탄환 판정을 전부 여기서
 * 하고 결과만 내려보낸다. Client 는 입력만 올린다.
 *
 * 게임 규칙은 하나도 모른다. 누가 들어왔고 무엇을 보냈는지만 안다. 규칙은
 * [Listener] 를 받는 쪽이 처리한다. 그래야 통로 없이도 로비와 접속 처리를 시험할 수 있다.
 */
class HostSession(
    private val transport: Transport,
    private val hostName: String,
    hostTankType: Int,
    private val clock: () -> Long,
) {
    interface Listener {
        fun onPlayerJoined(slot: Int, name: String) = Unit

        /** 접속이 끊겼다. 남은 사람은 계속한다. (계획서 §37) */
        fun onPlayerLeft(slot: Int) = Unit

        /** 카운트다운이 끝났다. 이 순간의 seed 로 판을 연다. */
        fun onMatchStart(start: Messages.Start) = Unit

        /** Client 가 보낸 조종 입력. */
        fun onInput(slot: Int, input: Messages.Input) = Unit
    }

    val lobby = LobbyState()

    var listener: Listener? = null

    /** 자리 번호 -> 그 사람의 주소. Host 자신은 없다. */
    private val peers = HashMap<Int, Peer>()

    private val writer = PacketWriter(ByteArray(Protocol.MAX_PACKET))
    private var lastLobbyBroadcastMs = 0L
    private var lastAnnounceMs = 0L

    /** 시작할 판의 seed. 카운트다운이 끝날 때 그대로 실어 보낸다. */
    var pendingSeed: Long = 0
        private set

    var pendingStageIndex: Int = 0
        private set

    var pendingGridHash: Long = 0
        private set

    init {
        lobby.openAsHost(hostName, hostTankType, clock())
    }

    /** 판을 시작할 때 쓸 값. 맵을 만들어 본 뒤 해시까지 넣어 준다. */
    fun prepareMatch(seed: Long, stageIndex: Int, gridHash: Long) {
        pendingSeed = seed
        pendingStageIndex = stageIndex
        pendingGridHash = gridHash
    }

    fun requestStart(): Boolean = lobby.beginCountdown()

    // -----------------------------------------------------------------------

    fun update() {
        val now = clock()
        transport.poll { from, data, length -> receive(from, data, length, now) }

        for (slot in lobby.dropTimedOut(now)) {
            peers.remove(slot)
            listener?.onPlayerLeft(slot)
            broadcastLobby(now, force = true)
        }

        if (lobby.tickCountdown()) {
            val start = Messages.Start(
                seed = pendingSeed,
                stageIndex = pendingStageIndex,
                playerCount = lobby.connectedCount,
                gridHash = pendingGridHash,
                startTick = 0,
            )
            sendToAll(Messages.writeStart(writer, start))
            listener?.onMatchStart(start)
        }

        broadcastLobby(now, force = false)
        announce(now)
    }

    /** 상태 스냅샷을 모두에게. 20Hz 로 부른다. (계획서 §36) */
    fun sendSnapshot(snapshot: Messages.Snapshot) {
        sendToAll(Messages.writeSnapshot(writer, snapshot))
    }

    /** 방을 닫는다. Client 는 로비로 돌아간다. (계획서 §37) */
    fun close() {
        sendToAll(Protocol.header(writer, Protocol.Type.HOST_CLOSED))
        transport.close()
    }

    // -----------------------------------------------------------------------

    private fun receive(from: Peer, data: ByteArray, length: Int, now: Long) {
        val reader = PacketReader(data, length)
        when (Protocol.readType(reader)) {
            Protocol.Type.DISCOVER -> sendAnnounceTo(from)

            Protocol.Type.JOIN -> handleJoin(from, Messages.readJoin(reader), now)

            Protocol.Type.READY -> slotOf(from)?.let { slot ->
                val ready = Messages.readReady(reader)
                lobby.setReady(slot, ready.ready, ready.tankType, now)
                broadcastLobby(now, force = true)
            }

            Protocol.Type.INPUT -> slotOf(from)?.let { slot ->
                lobby.touch(slot, now)
                listener?.onInput(slot, Messages.readInput(reader))
            }

            Protocol.Type.HEARTBEAT -> slotOf(from)?.let { lobby.touch(it, now) }

            Protocol.Type.LEAVE -> slotOf(from)?.let { slot ->
                lobby.leave(slot)
                peers.remove(slot)
                listener?.onPlayerLeft(slot)
                broadcastLobby(now, force = true)
            }

            else -> Unit
        }
    }

    private fun handleJoin(from: Peer, join: Messages.Join, now: Long) {
        // 같은 사람이 두 번 보냈으면 자리를 새로 주지 않는다. 패킷은 흔히 중복된다.
        slotOf(from)?.let { existing ->
            send(from, Messages.writeJoinAck(writer, Messages.JoinAck(existing, join.tankType)))
            return
        }
        if (lobby.started) {
            send(from, deny(Protocol.Deny.ALREADY_STARTED))
            return
        }
        val slot = lobby.join(join.name, join.tankType, now)
        if (slot == null) {
            send(from, deny(Protocol.Deny.ROOM_FULL))
            return
        }
        peers[slot.index] = from
        send(from, Messages.writeJoinAck(writer, Messages.JoinAck(slot.index, join.tankType)))
        listener?.onPlayerJoined(slot.index, join.name)
        broadcastLobby(now, force = true)
    }

    private fun deny(reason: Int): PacketWriter =
        Protocol.header(writer, Protocol.Type.JOIN_DENY).byte(reason)

    private fun slotOf(peer: Peer): Int? =
        peers.entries.firstOrNull { it.value == peer }?.key

    private fun broadcastLobby(now: Long, force: Boolean) {
        if (!force && now - lastLobbyBroadcastMs < LOBBY_INTERVAL_MS) return
        lastLobbyBroadcastMs = now
        sendToAll(Messages.writeLobby(writer, lobby.snapshot()))
    }

    /** 아직 방을 못 찾은 사람을 위해 주기적으로 알린다. */
    private fun announce(now: Long) {
        if (lobby.started) return
        if (now - lastAnnounceMs < ANNOUNCE_INTERVAL_MS) return
        lastAnnounceMs = now
        val message = Messages.Announce(
            hostName, lobby.connectedCount, Protocol.MAX_PLAYERS, lobby.started,
        )
        val packet = Messages.writeAnnounce(writer, message)
        transport.broadcast(Protocol.DISCOVERY_PORT, packet.buffer, packet.length)
    }

    private fun sendAnnounceTo(peer: Peer) {
        val message = Messages.Announce(
            hostName, lobby.connectedCount, Protocol.MAX_PLAYERS, lobby.started,
        )
        send(peer, Messages.writeAnnounce(writer, message))
    }

    private fun send(peer: Peer, packet: PacketWriter) {
        transport.send(peer, packet.buffer, packet.length)
    }

    private fun sendToAll(packet: PacketWriter) {
        for (peer in peers.values) transport.send(peer, packet.buffer, packet.length)
    }

    private companion object {
        const val LOBBY_INTERVAL_MS = 500L
        const val ANNOUNCE_INTERVAL_MS = 1000L
    }
}
