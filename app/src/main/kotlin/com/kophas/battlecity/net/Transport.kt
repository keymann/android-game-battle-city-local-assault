package com.kophas.battlecity.net

/**
 * 패킷을 주고받는 통로.
 *
 * UDP 소켓을 세션 코드에서 직접 만지지 않는다. 그러면 테스트할 때마다 진짜 소켓과
 * 진짜 시간이 필요해져서, 접속이 끊기는 상황 같은 것을 재현하기가 어렵다.
 * 통로를 갈아 끼울 수 있게 두면 테스트에서는 메모리 안에서 주고받으면 된다.
 */
interface Transport {

    /** 이 통로의 내 주소. 로그와 자기 패킷 걸러내기에 쓴다. */
    val localPeer: Peer

    fun send(to: Peer, data: ByteArray, length: Int)

    /** 같은 망의 모두에게. 방을 찾을 때만 쓴다. */
    fun broadcast(port: Int, data: ByteArray, length: Int)

    /**
     * 받은 것을 모두 꺼내 [handler] 에 넘긴다. 막히지 않는다.
     *
     * 게임 루프가 매 틱 부르므로 절대 기다리면 안 된다.
     */
    fun poll(handler: (from: Peer, data: ByteArray, length: Int) -> Unit)

    fun close()
}

/** 상대의 주소. 무엇으로 식별하든 값이 같으면 같은 상대다. */
data class Peer(val address: String, val port: Int) {
    override fun toString(): String = "$address:$port"

    companion object {
        val NONE = Peer("", 0)
    }
}
