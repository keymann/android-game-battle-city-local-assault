package com.kophas.battlecity.net

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.StandardSocketOptions
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel

/**
 * 진짜 UDP 통로. (계획서 §4.1 동일 Wi-Fi)
 *
 * TCP 를 쓰지 않는다. 20Hz 로 흘려보내는 상태 스냅샷은 **최신 것만 쓸모가 있다.**
 * TCP 는 빠진 패킷을 다시 보내느라 그 뒤를 전부 붙잡아 두는데, 그러면 늦게 도착한
 * 옛 상태 때문에 화면이 뒤로 튄다. 잃어버린 스냅샷은 다음 것으로 덮으면 그만이다.
 *
 * `DatagramSocket` 이 아니라 `DatagramChannel` 을 쓴다. 소켓에는 진짜 논블로킹
 * 모드가 없어서 아주 짧은 타임아웃으로 흉내 내야 하는데, 그렇게 만든 첫 판은
 * 기기에서 패킷을 한 장도 못 건졌다. 채널은 받을 것이 없으면 곧바로 null 을 준다.
 * 60Hz 루프 안에서 부르는 코드라 한 순간도 붙잡히면 안 된다.
 */
class UdpTransport(port: Int) : Transport {

    private val channel: DatagramChannel = DatagramChannel.open().apply {
        configureBlocking(false)
        setOption(StandardSocketOptions.SO_REUSEADDR, true)
        setOption(StandardSocketOptions.SO_BROADCAST, true)
        bind(InetSocketAddress(port))
    }

    private val outgoing = ByteBuffer.allocateDirect(Protocol.MAX_PACKET)
    private val incoming = ByteBuffer.allocateDirect(Protocol.MAX_PACKET)
    private val scratch = ByteArray(Protocol.MAX_PACKET)

    override val localPeer: Peer =
        Peer(localAddress(), (channel.localAddress as InetSocketAddress).port)

    override fun send(to: Peer, data: ByteArray, length: Int) {
        if (to == Peer.NONE) return
        runCatching {
            outgoing.clear()
            outgoing.put(data, 0, length)
            outgoing.flip()
            channel.send(outgoing, InetSocketAddress(to.address, to.port))
        }
    }

    override fun broadcast(port: Int, data: ByteArray, length: Int) {
        // 인터페이스마다 브로드캐스트 주소가 다르다. 전부에 뿌려야 상대가 받는다.
        for (address in broadcastAddresses()) {
            runCatching {
                outgoing.clear()
                outgoing.put(data, 0, length)
                outgoing.flip()
                channel.send(outgoing, InetSocketAddress(address, port))
            }
        }
    }

    override fun poll(handler: (Peer, ByteArray, Int) -> Unit) {
        while (true) {
            incoming.clear()
            val sender = runCatching { channel.receive(incoming) }.getOrNull() ?: return
            incoming.flip()
            val length = incoming.remaining()
            if (length <= 0) return
            incoming.get(scratch, 0, length)

            val address = sender as InetSocketAddress
            handler(Peer(address.address.hostAddress ?: "", address.port), scratch, length)
        }
    }

    override fun close() {
        runCatching { channel.close() }
    }

    private companion object {
        fun localAddress(): String =
            runCatching {
                NetworkInterface.getNetworkInterfaces().toList()
                    .filter { it.isUp && !it.isLoopback }
                    .flatMap { it.inetAddresses.toList() }
                    .firstOrNull { !it.isLoopbackAddress && it.address.size == 4 }
                    ?.hostAddress
            }.getOrNull() ?: "127.0.0.1"

        fun broadcastAddresses(): List<InetAddress> = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.interfaceAddresses }
                .mapNotNull { it.broadcast }
        }.getOrElse { emptyList<InetAddress>() }
            .ifEmpty { listOf(InetAddress.getByName("255.255.255.255")) }
    }
}
