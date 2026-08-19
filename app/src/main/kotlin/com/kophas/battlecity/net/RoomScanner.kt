package com.kophas.battlecity.net

/**
 * 메인 메뉴에서 같은 망에 방이 있는지 살핀다. (계획서 §27, §35)
 *
 * [ClientSession] 도 방을 찾지만 그쪽은 찾는 즉시 들어간다. 메뉴에서는 들어가지
 * 않고 세어만 본다. 방이 하나도 없는데 JOIN 을 눌러 빈 화면을 보고 있는 일을
 * 줄이려는 것이다.
 *
 * 답이 끊기면 방이 닫힌 것으로 본다. UDP 라 방이 닫혔다는 통보가 오지 않는다.
 */
class RoomScanner(
    private val transport: Transport,
    private val clock: () -> Long,
) {
    /** 마지막으로 답한 시각. 주소가 키다. 같은 방이 여러 번 답해도 하나로 센다. */
    private val seen = HashMap<Peer, Long>()

    private val writer = PacketWriter(ByteArray(Protocol.MAX_PACKET))
    private var lastProbeMs = 0L

    /** 방금까지 답한 방의 수. */
    val roomCount: Int get() = seen.size

    /** 가장 먼저 답한 방의 주소. JOIN 을 누르면 이리로 붙는다. */
    var firstRoom: Peer? = null
        private set

    fun update() {
        val now = clock()
        transport.poll { from, data, length ->
            val reader = PacketReader(data, length)
            if (Protocol.readType(reader) == Protocol.Type.ANNOUNCE) {
                val announce = Messages.readAnnounce(reader)
                // 이미 시작한 방은 세지 않는다. 들어갈 수 없는 방이다.
                if (!announce.started) seen[from] = now
            }
        }

        seen.entries.removeAll { now - it.value > STALE_MS }
        firstRoom = seen.keys.firstOrNull()

        if (now - lastProbeMs < PROBE_INTERVAL_MS) return
        lastProbeMs = now
        val packet = Protocol.header(writer, Protocol.Type.DISCOVER)
        transport.broadcast(Protocol.PORT, packet.buffer, packet.length)
    }

    fun close() = transport.close()

    private companion object {
        const val PROBE_INTERVAL_MS = 900L

        /** 이 시간 동안 답이 없으면 목록에서 지운다. */
        const val STALE_MS = 3000L
    }
}
