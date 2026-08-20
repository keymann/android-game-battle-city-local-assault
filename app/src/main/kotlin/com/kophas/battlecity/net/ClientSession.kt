package com.kophas.battlecity.net

/**
 * 방에 들어가는 쪽. (계획서 §35, §36, §37)
 *
 * 게임 규칙을 굴리지 않는다. 조종 입력만 올리고 Host 가 내려 준 상태를 그린다.
 * 상태는 20Hz 로 오는데 화면은 60Hz 이므로, 마지막 두 장 사이를 메워 그린다.
 * 그러지 않으면 초당 스무 번씩 뚝뚝 끊겨 보인다.
 */
class ClientSession(
    private val transport: Transport,
    private val playerName: String,
    private val clock: () -> Long,
) {
    enum class State {
        /** 방을 찾는 중. */
        SEARCHING,

        /** 들어가겠다고 보내고 답을 기다리는 중. */
        JOINING,

        /** 로비에서 기다리는 중. */
        LOBBY,

        /** 판이 돌아가는 중. */
        PLAYING,

        /** 방이 닫혔거나 소식이 끊겼다. */
        DISCONNECTED,
    }

    interface Listener {
        fun onHostFound(peer: Peer, announce: Messages.Announce) = Unit
        fun onJoined(slot: Int) = Unit
        fun onDenied(reason: Int) = Unit
        fun onLobby(update: Messages.LobbyUpdate) = Unit
        fun onMatchStart(start: Messages.Start) = Unit
        fun onDisconnected() = Unit
    }

    var state: State = State.SEARCHING
        private set

    var slot: Int = -1
        private set

    var listener: Listener? = null

    var host: Peer = Peer.NONE
        private set

    /**
     * Host 까지의 왕복 시간. 아직 못 쟀으면 -1.
     *
     * 화면에 보여 주는 값이라 튀는 것을 그대로 두면 숫자가 쉴 새 없이 바뀌어
     * 읽을 수 없다. 새 값을 조금씩만 반영해 흔들림을 눌러 준다.
     */
    var latencyMs: Int = -1
        private set

    /** 최근 두 장. 그 사이를 메워 그린다. */
    var previous: Messages.Snapshot? = null
        private set

    var latest: Messages.Snapshot? = null
        private set

    private val writer = PacketWriter(ByteArray(Protocol.MAX_PACKET))
    private var lastHeardMs = 0L
    private var lastHeartbeatMs = 0L
    private var lastDiscoverMs = 0L
    private var lastJoinMs = 0L
    private var lastSnapshotMs = 0L
    private var previousSnapshotMs = 0L
    private var lastPingMs = 0L
    private var tankType = 0
    private var colorIndex = 0
    private var chosenName = playerName

    /** 같은 망에 방이 있는지 물어본다. */
    fun search() {
        state = State.SEARCHING
        host = Peer.NONE
        slot = -1
    }

    /** 찾은 방에 들어간다. */
    fun join(peer: Peer, tankType: Int, colorIndex: Int = 0) {
        host = peer
        this.tankType = tankType
        this.colorIndex = colorIndex
        state = State.JOINING
        lastHeardMs = clock()
        sendJoin()
    }

    /** 준비 상태와 고른 것을 함께 올린다. */
    fun setReady(ready: Boolean, tankType: Int, colorIndex: Int = this.colorIndex, name: String = chosenName) {
        this.tankType = tankType
        this.colorIndex = colorIndex
        this.chosenName = name
        if (host == Peer.NONE) return
        send(Messages.writeReady(writer, Messages.Ready(ready, tankType, colorIndex, name)))
    }

    /** 이번 틱의 조종 입력. 바뀐 것이 없어도 보낸다. UDP 는 잃어버리기 때문이다. */
    fun sendInput(input: Messages.Input) {
        if (state != State.PLAYING) return
        send(Messages.writeInput(writer, input))
    }

    /** 결과 화면에 있는지 알린다. 방장이 PLAY AGAIN 을 열어 둘지 정한다. (계획서 §33) */
    fun sendPresence(present: Boolean) {
        if (host == Peer.NONE) return
        send(Messages.writePresence(writer, present))
    }

    /** 판이 끝나고 로비로 돌아간다. 다음 START 를 기다린다. */
    fun returnToLobby() {
        if (state == State.PLAYING) state = State.LOBBY
    }

    fun leave() {
        if (host != Peer.NONE) send(Protocol.header(writer, Protocol.Type.LEAVE))
        state = State.DISCONNECTED
    }

    // -----------------------------------------------------------------------

    fun update() {
        val now = clock()
        transport.poll { from, data, length -> receive(from, data, length, now) }

        when (state) {
            State.SEARCHING -> discover(now)
            // 답이 없으면 다시 보낸다. 첫 패킷이 사라지면 영영 기다리게 된다.
            State.JOINING -> if (now - lastJoinMs >= JOIN_RETRY_MS) sendJoin()
            State.LOBBY, State.PLAYING -> {
                heartbeat(now)
                ping(now)
            }
            State.DISCONNECTED -> Unit
        }

        if (state == State.LOBBY || state == State.PLAYING) {
            if (now - lastHeardMs > Protocol.TIMEOUT_MS) disconnect()
        }
    }

    /**
     * 두 스냅샷 사이 어디쯤을 그릴지. 0 이면 이전, 1 이면 최신.
     *
     * 방금 받은 장면을 곧바로 그리지 않고 **한 간격 뒤에서 따라간다.** 최신 것을
     * 바로 그리면 다음 장이 올 때까지 그릴 것이 없어 멈췄다가 튄다. 한 장 늦게
     * 따라가면 그 사이를 메울 수 있다. 대신 화면이 50ms 만큼 과거다.
     *
     * 다음 장이 늦으면 1 에서 멈춘다. 앞질러 갔다가 되돌아오는 것보다 덜 거슬린다.
     */
    fun interpolation(): Float {
        val span = lastSnapshotMs - previousSnapshotMs
        if (span <= 0L) return 1f
        return ((clock() - lastSnapshotMs).toFloat() / span).coerceIn(0f, 1f)
    }

    // -----------------------------------------------------------------------

    private fun receive(from: Peer, data: ByteArray, length: Int, now: Long) {
        val reader = PacketReader(data, length)
        when (Protocol.readType(reader)) {
            Protocol.Type.ANNOUNCE -> if (state == State.SEARCHING) {
                listener?.onHostFound(from, Messages.readAnnounce(reader))
            }

            Protocol.Type.JOIN_ACK -> {
                val ack = Messages.readJoinAck(reader)
                slot = ack.slot
                // 색은 Host 가 정한 것을 따른다. 원한 색을 남이 먼저 쥐었을 수 있다.
                colorIndex = ack.colorIndex
                host = from
                state = State.LOBBY
                lastHeardMs = now
                listener?.onJoined(ack.slot)
            }

            Protocol.Type.JOIN_DENY -> {
                state = State.SEARCHING
                listener?.onDenied(reader.byte())
            }

            Protocol.Type.LOBBY -> {
                lastHeardMs = now
                listener?.onLobby(Messages.readLobby(reader))
            }

            Protocol.Type.START -> {
                lastHeardMs = now
                state = State.PLAYING
                listener?.onMatchStart(Messages.readStart(reader))
            }

            Protocol.Type.SNAPSHOT -> {
                lastHeardMs = now
                previous = latest
                previousSnapshotMs = lastSnapshotMs
                latest = Messages.readSnapshot(reader)
                lastSnapshotMs = now
            }

            Protocol.Type.HEARTBEAT -> lastHeardMs = now

            Protocol.Type.PING -> {
                lastHeardMs = now
                send(Messages.writePong(writer, Messages.readStamp(reader)))
            }

            Protocol.Type.PONG -> {
                lastHeardMs = now
                val sample = (now - Messages.readStamp(reader)).toInt().coerceIn(0, MAX_LATENCY_MS)
                latencyMs = if (latencyMs < 0) sample else {
                    (latencyMs * (1f - SMOOTHING) + sample * SMOOTHING).toInt()
                }
            }

            Protocol.Type.HOST_CLOSED -> disconnect()

            else -> Unit
        }
    }

    private fun discover(now: Long) {
        if (now - lastDiscoverMs < DISCOVER_INTERVAL_MS) return
        lastDiscoverMs = now
        val packet = Protocol.header(writer, Protocol.Type.DISCOVER)
        transport.broadcast(Protocol.PORT, packet.buffer, packet.length)
    }

    private fun sendJoin() {
        lastJoinMs = clock()
        send(Messages.writeJoin(writer, Messages.Join(chosenName, tankType, colorIndex)))
    }

    private fun ping(now: Long) {
        if (host == Peer.NONE) return
        if (now - lastPingMs < Protocol.PING_INTERVAL_MS) return
        lastPingMs = now
        send(Messages.writePing(writer, now))
    }

    private fun heartbeat(now: Long) {
        if (now - lastHeartbeatMs < Protocol.HEARTBEAT_MS) return
        lastHeartbeatMs = now
        send(Protocol.header(writer, Protocol.Type.HEARTBEAT))
    }

    private fun disconnect() {
        if (state == State.DISCONNECTED) return
        state = State.DISCONNECTED
        listener?.onDisconnected()
    }

    private fun send(packet: PacketWriter) {
        transport.send(host, packet.buffer, packet.length)
    }

    private companion object {
        const val DISCOVER_INTERVAL_MS = 700L
        const val JOIN_RETRY_MS = 400L
        const val MAX_LATENCY_MS = 9999

        /** 새 측정값을 이만큼만 반영한다. 숫자가 덜 흔들린다. */
        const val SMOOTHING = 0.3f
    }
}
