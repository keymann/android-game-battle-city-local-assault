package com.kophas.battlecity.game

import java.util.concurrent.ConcurrentLinkedQueue

/**
 * 손가락이 만든 일감을 **게임 루프 스레드**로 넘긴다. (계획서 §27, §35)
 *
 * 손가락은 UI 스레드로 오고 세션은 루프 스레드가 굴린다. 그런데 UI 스레드에서
 * 소켓을 만지면 패킷이 나가지 않는다 — 안드로이드가 메인 스레드의 네트워크 호출에
 * `NetworkOnMainThreadException` 을 던진다(`DatagramChannelImpl.send` 가 보내기
 * 직전에 `BlockGuard.getThreadPolicy().onNetwork()` 를 부른다). 예외는
 * [com.kophas.battlecity.net.UdpTransport] 의 `runCatching` 이 받아 삼켰으므로,
 * 로비에서 참가자가 탱크·색·준비를 눌러도 아무 일도 없고 로그도 남지 않았다.
 *
 * 그래서 규칙을 하나 둔다. **세션은 루프 스레드만 만진다.** 손가락은 "무엇을
 * 눌렀다" 만 여기 쌓고, 루프가 다음 틱에 꺼내 실행한다. 늦어지는 것은 한 틱
 * (16ms) 이라 손끝으로 느낄 수 없다.
 *
 * 서피스 이벤트를 명령으로 쌓아 두는 것과 같은 방식이다. 그쪽은 컨텍스트가 만든
 * 스레드에 묶여 있어서, 이쪽은 소켓이 메인 스레드를 거부해서 그렇게 한다.
 */
class LoopQueue {

    private val queue = ConcurrentLinkedQueue<() -> Unit>()

    /** 아직 실행되지 않은 일감 수. 시험이 "쌓였지만 아직 안 돌았다" 를 볼 때 쓴다. */
    val pending: Int get() = queue.size

    /** 어느 스레드에서나 쌓을 수 있다. */
    fun post(work: () -> Unit) {
        queue += work
    }

    /**
     * 쌓인 것을 순서대로 실행한다. 루프 스레드에서만 부른다.
     *
     * 이번에 꺼낼 몫을 먼저 세어 두고 그만큼만 돈다. 실행 중에 새로 쌓인 일감까지
     * 이어서 처리하면, 스스로 다시 쌓는 일감 하나가 이 프레임을 붙잡을 수 있다.
     */
    fun drain() {
        repeat(queue.size) {
            (queue.poll() ?: return).invoke()
        }
    }

    /** 화면을 접을 때. 남은 일감은 갈 곳이 없다. */
    fun clear() = queue.clear()
}
