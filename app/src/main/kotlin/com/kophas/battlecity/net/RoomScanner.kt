package com.kophas.battlecity.net

/**
 * 같은 망에 열려 있는 방을 살핀다. (계획서 §27, §35)
 *
 * [ClientSession] 도 방을 찾지만 그쪽은 찾는 즉시 들어간다. 여기서는 들어가지 않고
 * **목록만 만든다.** 사람이 어느 방에 들어갈지 고를 수 있어야 하기 때문이다.
 *
 * 방이 닫혔다는 통보는 오지 않는다. UDP 라 그런 것이 없다. 그래서 한동안 답이 없는
 * 방은 사라진 것으로 본다.
 */
class RoomScanner(
    private val transport: Transport,
    private val clock: () -> Long,
) {
    /** 목록에 한 줄로 나가는 방 하나. */
    class Room(
        val peer: Peer,
        val hostName: String,
        val maxPlayers: Int,
        /** 방을 연 시각. 방장 기기의 시계다. */
        val createdAt: Long,
    ) {
        var players: Int = 0
            internal set

        var lastSeenMs: Long = 0
            internal set

        /** 정원이 찼는가. 찬 방은 골라도 들어갈 수 없다. */
        val full: Boolean get() = players >= maxPlayers
    }

    private val rooms = LinkedHashMap<Peer, Room>()

    private val writer = PacketWriter(ByteArray(Protocol.MAX_PACKET))
    private var lastProbeMs = 0L

    /**
     * 지금 들어갈 수 있는 방들. 먼저 열린 방이 위로 온다.
     *
     * 응답 순서로 두면 목록이 매번 뒤바뀌어, 누르려던 줄이 손가락 아래에서 다른
     * 방으로 바뀐다. 열린 시각은 방이 사라질 때까지 변하지 않는다.
     */
    val list: List<Room> get() = rooms.values.sortedBy { it.createdAt }

    val roomCount: Int get() = rooms.size

    /** 가장 먼저 열린 방. 목록 없이 바로 붙을 때 쓴다. */
    val firstRoom: Peer? get() = list.firstOrNull()?.peer

    fun update() {
        val now = clock()
        transport.poll { from, data, length ->
            val reader = PacketReader(data, length)
            if (Protocol.readType(reader) == Protocol.Type.ANNOUNCE) {
                accept(from, Messages.readAnnounce(reader), now)
            }
        }

        rooms.entries.removeAll { now - it.value.lastSeenMs > STALE_MS }

        if (now - lastProbeMs < PROBE_INTERVAL_MS) return
        lastProbeMs = now
        val packet = Protocol.header(writer, Protocol.Type.DISCOVER)
        transport.broadcast(Protocol.PORT, packet.buffer, packet.length)
    }

    /**
     * 목록을 비우고 처음부터 다시 받는다. (REFRESH)
     *
     * 사라진 방을 지우는 데에는 시간이 걸린다(답이 끊긴 것을 알아채야 한다). 사람이
     * 직접 새로 고치겠다고 하면 기다릴 이유가 없으므로 통째로 버리고 다시 묻는다.
     */
    fun refresh() {
        rooms.clear()
        lastProbeMs = 0L
    }

    /** 그 방을 목록에서 지운다. 이미 시작했거나 사라진 방이다. */
    fun remove(peer: Peer) {
        rooms.remove(peer)
    }

    /** 그 방을 정원이 찬 것으로 표시한다. 들어가려다 거절당했을 때. */
    fun markFull(peer: Peer) {
        rooms[peer]?.players = rooms[peer]?.maxPlayers ?: return
    }

    fun close() = transport.close()

    // -----------------------------------------------------------------------

    private fun accept(from: Peer, announce: Messages.Announce, now: Long) {
        // 이미 시작한 방은 들어갈 수 없다. 목록에 두면 눌러 보고 나서야 알게 된다.
        if (announce.started) {
            rooms.remove(from)
            return
        }
        val room = rooms.getOrPut(from) {
            Room(from, announce.hostName, announce.maxPlayers, announce.createdAt)
        }
        room.players = announce.players
        room.lastSeenMs = now
    }

    private companion object {
        const val PROBE_INTERVAL_MS = 900L

        /** 이 시간 동안 답이 없으면 사라진 방으로 본다. */
        const val STALE_MS = 3000L
    }
}
