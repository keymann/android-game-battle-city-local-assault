package com.kophas.battlecity.net

import android.util.Log

/**
 * 진짜 UDP 소켓으로 Host 와 Client 를 한 기기 안에서 붙여 본다.
 *
 * 단위 테스트는 메모리 통로로 돌기 때문에 프로토콜과 세션 규칙만 확인한다.
 * 소켓을 여는 것, 바이트가 실제로 오가는 것, 안드로이드 권한이 맞는 것은 확인하지
 * 못한다. 기기 두 대를 붙이기 전에 그 부분만 여기서 미리 걸러 낸다.
 *
 * 되돌아오는 주소로만 통신하므로 브로드캐스트는 쓰지 않는다. 에뮬레이터는 서로를
 * 볼 수 없어서 브로드캐스트로는 어차피 확인이 안 된다.
 *
 * 반드시 **별도 스레드에서 실제 시간을 두고** 돌린다. 쉬지 않고 돌리면 커널이 패킷을
 * 넘길 틈이 없어 한 장도 못 받는다. 처음에 그렇게 만들었다가 통로는 멀쩡한데
 * 점검만 실패하는 상태로 한참을 헤맸다.
 */
object UdpSelfTest {

    private const val TAG = "BattleCity"

    /** 게임을 붙잡지 않도록 따로 돌린다. 결과는 로그로만 남는다. */
    fun runAsync() {
        Thread({ run() }, "udp-self-test").apply { isDaemon = true }.start()
    }

    fun run(): Boolean {
        val hostTransport = runCatching { UdpTransport(Protocol.PORT) }.getOrElse {
            Log.e(TAG, "자체 점검 실패: 소켓을 열지 못했다 ($it)")
            return false
        }
        val clientTransport = runCatching { UdpTransport(0) }.getOrElse {
            hostTransport.close()
            Log.e(TAG, "자체 점검 실패: 손님 소켓을 열지 못했다 ($it)")
            return false
        }

        val host = HostSession(hostTransport, "자체점검", 0, System::currentTimeMillis)
        val client = ClientSession(clientTransport, "손님", System::currentTimeMillis)

        var joinedSlot = -1
        var started = false
        var snapshotTanks = -1

        client.listener = object : ClientSession.Listener {
            override fun onJoined(slot: Int) {
                joinedSlot = slot
                client.setReady(true, 0)
            }

            override fun onMatchStart(start: Messages.Start) {
                started = start.seed == PROBE_SEED
            }
        }

        try {
            client.join(Peer(LOOPBACK, hostTransport.localPeer.port), 0)
            pump(host, client, rounds = 30)

            host.prepareMatch(Messages.Start(PROBE_SEED, 0, 0, PROBE_HASH, 0))
            host.requestStart()
            // 카운트다운은 update 한 번에 한 틱씩 줄어든다. 오갈 시간은 따로 필요 없다.
            repeat(Protocol.COUNTDOWN_SECONDS * 60) { host.update() }
            pump(host, client, rounds = 20)

            host.sendSnapshot(probeSnapshot())
            pump(host, client, rounds = 20)
            snapshotTanks = client.latest?.tanks?.size ?: -1
        } finally {
            host.close()
            client.leave()
            hostTransport.close()
            clientTransport.close()
        }

        val ok = joinedSlot == 1 && started && snapshotTanks == 2
        Log.i(
            TAG,
            "UDP 자체 점검 ${if (ok) "통과" else "실패"} " +
                "자리=$joinedSlot 시작=$started 스냅샷탱크=$snapshotTanks",
        )
        return ok
    }

    private fun pump(host: HostSession, client: ClientSession, rounds: Int) {
        repeat(rounds) {
            host.update()
            client.update()
            // 진짜 소켓이라 오가는 데 시간이 든다. 쉬지 않으면 받을 것이 생기지 않는다.
            Thread.sleep(STEP_MS)
        }
    }

    private fun probeSnapshot() = Messages.Snapshot(
        tick = 1L,
        phase = 0,
        enemiesRemaining = 80,
        baseDestroyed = false,
        tanks = listOf(
            Messages.TankState(0, 0, 0, 0, 64f, 64f, 0, 100, false),
            Messages.TankState(1, -1, 1, 2, 320.5f, 128.25f, 2, 50, true),
        ),
        projectiles = listOf(Messages.ProjectileState(0, 96f, 96f, 1, false)),
        scores = listOf(Messages.ScoreState(0, 3, 2, false)),
    )

    private const val STEP_MS = 3L
    private const val LOOPBACK = "127.0.0.1"
    private const val PROBE_SEED = 20260819L
    private const val PROBE_HASH = -424242L
}
