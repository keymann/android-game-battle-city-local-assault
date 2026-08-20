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
    private var lastPingMs = 0L

    /**
     * 시작할 판의 내용. 카운트다운이 끝날 때 인원만 채워 그대로 실어 보낸다.
     *
     * 방 규칙(맵 크기 · 아군 오사 · 본진 보호 · COM 수)도 여기에 담긴다. 방을 열 때
     * 정한 것을 START 한 통에 실어야 모두가 같은 규칙으로 판을 만든다. (계획서 §44.2)
     */
    var pendingStart: Messages.Start = Messages.Start(0, 0, 0, 0, 0)
        private set

    /** 자리 번호 -> 마지막으로 잰 왕복 시간. 아직 못 쟀으면 없다. */
    private val latency = HashMap<Int, Int>()

    /** 결과 화면에 남아 있는 자리. Host 자신은 세지 않는다. (계획서 §33) */
    private val inResult = HashSet<Int>()

    /**
     * 이 기기가 겪는 지연. 가장 느린 상대를 따른다.
     *
     * 평균이 아니라 최댓값인 이유는, 넷이 붙어 있을 때 한 사람만 늦어도 그 사람의
     * 화면이 늦기 때문이다. 방장이 보는 숫자는 "가장 나쁜 사람" 이어야 한다.
     */
    val latencyMs: Int get() = latency.values.maxOrNull() ?: -1

    /** 결과 화면에 아직 남아 있는 다른 사람 수. */
    val resultPeerCount: Int get() = inResult.count { lobby.slots.getOrNull(it)?.connected == true }

    /** 그 자리 사람이 아직 결과 화면에 있는가. 방장 자신은 언제나 있다. */
    fun isInResult(slot: Int): Boolean =
        slot == HOST_SLOT || (slot in inResult && lobby.slots.getOrNull(slot)?.connected == true)

    /** 방을 연 시각. 목록에 "언제 열린 방인가" 로 나간다. */
    private val createdAtMs: Long = clock()

    init {
        lobby.openAsHost(hostName, hostTankType, createdAtMs)
    }

    /** 판을 시작할 때 쓸 값. 맵을 만들어 본 뒤 해시까지 넣어 준다. */
    fun prepareMatch(start: Messages.Start) {
        pendingStart = start
    }

    fun requestStart(): Boolean = lobby.beginCountdown()

    // -----------------------------------------------------------------------

    fun update() {
        val now = clock()
        transport.poll { from, data, length -> receive(from, data, length, now) }

        for (slot in lobby.dropTimedOut(now)) {
            peers.remove(slot)
            latency.remove(slot)
            inResult -= slot
            listener?.onPlayerLeft(slot)
            broadcastLobby(now, force = true)
        }

        if (lobby.tickCountdown()) {
            val start = pendingStart.copy(playerCount = lobby.connectedCount)
            pendingStart = start
            inResult.clear()
            sendToAll(Messages.writeStart(writer, start))
            listener?.onMatchStart(start)
        }

        broadcastLobby(now, force = false)
        announce(now)
        ping(now)
    }

    /**
     * 판이 끝나고 로비를 다시 연다. PLAY AGAIN 과 LOBBY 가 함께 쓴다.
     *
     * 준비 상태는 모두 푼다. 앞 판의 준비를 그대로 들고 오면 아직 화면을 보고
     * 있는 사람을 두고 다음 판이 열릴 수 있다.
     */
    fun reopenLobby(now: Long) {
        lobby.reopen(now)
        inResult.clear()
        broadcastLobby(now, force = true)
    }

    /**
     * 결과 화면의 PLAY AGAIN. 남아 있는 사람들과 곧바로 다음 판을 연다. (계획서 §33)
     *
     * 로비를 거치지 않는다. 결과 화면에 남아 있다는 것 자체가 "다시 하겠다" 는
     * 뜻이므로 준비를 다시 누르게 할 이유가 없다. 떠난 사람은 애초에 세지 않는다.
     *
     * @return 다음 판을 열었으면 true. 남은 사람이 없어 못 열면 false.
     */
    fun playAgain(now: Long): Boolean {
        val staying = inResult.filter { lobby.slots.getOrNull(it)?.connected == true }
        if (staying.isEmpty()) return false

        lobby.reopen(now)
        for (slot in staying) lobby.markReady(slot, true)
        // 결과 화면에 남지 않은 사람은 이번 판에서 빠진다. 준비를 기다리지 않는다.
        for (slot in lobby.slots) {
            if (slot.connected && !slot.host && slot.index !in staying) lobby.leave(slot.index)
        }
        inResult.clear()
        broadcastLobby(now, force = true)
        return requestStart()
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
                lobby.setReady(slot, ready.ready, ready.tankType, ready.colorIndex, ready.name, now)
                broadcastLobby(now, force = true)
            }

            Protocol.Type.INPUT -> slotOf(from)?.let { slot ->
                lobby.touch(slot, now)
                listener?.onInput(slot, Messages.readInput(reader))
            }

            Protocol.Type.HEARTBEAT -> slotOf(from)?.let { lobby.touch(it, now) }

            // 온 그대로 되돌린다. 해석은 보낸 쪽이 한다.
            Protocol.Type.PING -> send(from, Messages.writePong(writer, Messages.readStamp(reader)))

            Protocol.Type.PONG -> slotOf(from)?.let { slot ->
                lobby.touch(slot, now)
                latency[slot] = (now - Messages.readStamp(reader)).toInt().coerceIn(0, MAX_LATENCY_MS)
            }

            Protocol.Type.PRESENCE -> slotOf(from)?.let { slot ->
                lobby.touch(slot, now)
                if (Messages.readPresence(reader)) inResult += slot else inResult -= slot
            }

            Protocol.Type.LEAVE -> slotOf(from)?.let { slot ->
                lobby.leave(slot)
                peers.remove(slot)
                latency.remove(slot)
                inResult -= slot
                listener?.onPlayerLeft(slot)
                broadcastLobby(now, force = true)
            }

            else -> Unit
        }
    }

    private fun handleJoin(from: Peer, join: Messages.Join, now: Long) {
        // 같은 사람이 두 번 보냈으면 자리를 새로 주지 않는다. 패킷은 흔히 중복된다.
        slotOf(from)?.let { existing ->
            val color = lobby.slots[existing].colorIndex
            send(from, Messages.writeJoinAck(writer, Messages.JoinAck(existing, join.tankType, color)))
            return
        }
        if (lobby.started) {
            send(from, deny(Protocol.Deny.ALREADY_STARTED))
            return
        }
        val slot = lobby.join(join.name, join.tankType, join.colorIndex, now)
        if (slot == null) {
            send(from, deny(Protocol.Deny.ROOM_FULL))
            return
        }
        peers[slot.index] = from
        send(
            from,
            Messages.writeJoinAck(writer, Messages.JoinAck(slot.index, join.tankType, slot.colorIndex)),
        )
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
        // 방 규칙을 함께 싣는다. 로비에서 설정을 열어 본 참가자가 제 기본값을
        // 방 규칙으로 오해하지 않도록.
        val update = lobby.snapshot().copy(
            mapSize = pendingStart.mapSize,
            friendlyFire = pendingStart.friendlyFire,
            maxActiveEnemies = pendingStart.maxActiveEnemies,
            baseProtection = pendingStart.baseProtection,
        )
        sendToAll(Messages.writeLobby(writer, update))
    }

    /** 아직 방을 못 찾은 사람을 위해 주기적으로 알린다. */
    private fun announce(now: Long) {
        if (lobby.started) return
        if (now - lastAnnounceMs < ANNOUNCE_INTERVAL_MS) return
        lastAnnounceMs = now
        val packet = Messages.writeAnnounce(writer, announcement())
        transport.broadcast(Protocol.DISCOVERY_PORT, packet.buffer, packet.length)
    }

    /** 모두에게 왕복 시간을 물어본다. 답이 돌아오면 [latencyMs] 가 갱신된다. */
    private fun ping(now: Long) {
        if (peers.isEmpty()) return
        if (now - lastPingMs < Protocol.PING_INTERVAL_MS) return
        lastPingMs = now
        sendToAll(Messages.writePing(writer, now))
    }

    private fun sendAnnounceTo(peer: Peer) {
        send(peer, Messages.writeAnnounce(writer, announcement()))
    }

    /**
     * 목록에 나갈 방 소개.
     *
     * 이름은 생성자에 받은 것이 아니라 **로비가 다듬은 것**을 쓴다. 비트맵 폰트에는
     * A~Z 와 숫자밖에 없어서, 다듬지 않은 이름은 남의 화면에서 빈칸으로 보인다.
     */
    private fun announcement() = Messages.Announce(
        hostName = lobby.slots[HOST_SLOT].name,
        players = lobby.connectedCount,
        maxPlayers = Protocol.MAX_PLAYERS,
        started = lobby.started,
        createdAt = createdAtMs,
    )

    private fun send(peer: Peer, packet: PacketWriter) {
        transport.send(peer, packet.buffer, packet.length)
    }

    private fun sendToAll(packet: PacketWriter) {
        for (peer in peers.values) transport.send(peer, packet.buffer, packet.length)
    }

    private companion object {
        const val HOST_SLOT = 0
        const val LOBBY_INTERVAL_MS = 500L
        const val ANNOUNCE_INTERVAL_MS = 1000L

        /** 이보다 오래 걸리면 숫자를 더 키워 봐야 의미가 없다. */
        const val MAX_LATENCY_MS = 9999
    }
}
