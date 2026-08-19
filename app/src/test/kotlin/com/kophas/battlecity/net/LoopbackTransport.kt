package com.kophas.battlecity.net

/**
 * 메모리 안에서 주고받는 통로.
 *
 * 진짜 소켓 없이 Host 와 Client 를 같은 시험 안에 놓기 위한 것이다. 패킷을 일부러
 * 잃어버리게 할 수도 있어서, UDP 에서만 벌어지는 일을 재현할 수 있다.
 */
class LoopbackNetwork {

    private val ports = HashMap<Int, LoopbackTransport>()

    /** 다음 몇 개의 패킷을 버릴지. 유실을 흉내 낸다. */
    var dropNext: Int = 0

    fun open(address: String, port: Int): LoopbackTransport =
        LoopbackTransport(this, Peer(address, port)).also { ports[port] = it }

    internal fun deliver(from: Peer, to: Peer, data: ByteArray, length: Int) {
        if (dropNext > 0) {
            dropNext--
            return
        }
        ports[to.port]?.enqueue(from, data.copyOf(length))
    }

    internal fun deliverBroadcast(port: Int, from: Peer, data: ByteArray, length: Int) {
        for ((openPort, transport) in ports) {
            if (openPort != port || transport.localPeer == from) continue
            transport.enqueue(from, data.copyOf(length))
        }
    }
}

class LoopbackTransport(
    private val network: LoopbackNetwork,
    override val localPeer: Peer,
) : Transport {

    private class Entry(val from: Peer, val data: ByteArray)

    private val inbox = ArrayDeque<Entry>()
    private var closed = false

    override fun send(to: Peer, data: ByteArray, length: Int) {
        if (closed || to == Peer.NONE) return
        network.deliver(localPeer, to, data, length)
    }

    override fun broadcast(port: Int, data: ByteArray, length: Int) {
        if (closed) return
        network.deliverBroadcast(port, localPeer, data, length)
    }

    override fun poll(handler: (Peer, ByteArray, Int) -> Unit) {
        while (inbox.isNotEmpty()) {
            val entry = inbox.removeFirst()
            handler(entry.from, entry.data, entry.data.size)
        }
    }

    override fun close() {
        closed = true
        inbox.clear()
    }

    internal fun enqueue(from: Peer, data: ByteArray) {
        if (!closed) inbox += Entry(from, data)
    }
}
