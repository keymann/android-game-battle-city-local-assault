package com.kophas.battlecity.net

/**
 * 로컬 Wi-Fi 대전 프로토콜. (계획서 §4, §35, §36)
 *
 * 인터넷 서버를 쓰지 않는다. 같은 공유기 아래에서 **Host 가 권위**를 갖고,
 * Client 는 입력만 보내고 상태를 받아 그린다. (계획서 §4.2)
 *
 * ```
 * Client -> Host   INPUT      방향 / 발사 / 특수기            60Hz 중 바뀔 때만
 * Host -> Client   SNAPSHOT   탱크 / 포탄 / 본진 / 점수 / 국면  20Hz
 * ```
 *
 * ### 맵은 보내지 않는다
 *
 * 스테이지는 seed 하나로 어느 기기에서나 똑같이 만들어진다. 그래서 START 에
 * seed 와 [com.kophas.battlecity.map.StageData.gridHash] 만 실어 보내고, Client 가
 * 같은 맵을 스스로 만든 뒤 해시를 대조한다. 수백 KB 를 아끼고, 어긋나면 바로 안다.
 */
object Protocol {

    /** 패킷 앞머리. 같은 포트를 쓰는 남의 트래픽을 걸러 낸다. */
    const val MAGIC: Int = 0x42544C43 // "BTLC"

    /** 프로토콜이 바뀌면 올린다. 다르면 접속을 거절한다. */
    const val VERSION: Int = 1

    /** 호스트가 여는 포트. */
    const val PORT: Int = 47654

    /** 호스트를 찾을 때 쓰는 브로드캐스트 포트. */
    const val DISCOVERY_PORT: Int = 47655

    /** 한 패킷의 최대 크기. 이더넷 MTU 안에 들어가야 쪼개지지 않는다. */
    const val MAX_PACKET: Int = 1200

    const val MAX_NAME: Int = 24

    /** 좌표를 접는 배율. 1 논리 px 을 4단계로 나눈다. */
    const val POSITION_SCALE: Float = 4f

    /** 상태 동기화 주기. 게임 로직 60Hz 를 3틱마다 한 번 내보낸다. (계획서 §36) */
    const val SNAPSHOT_HZ: Int = 20

    const val TICKS_PER_SNAPSHOT: Int = 60 / SNAPSHOT_HZ

    /** 이만큼 소식이 없으면 끊긴 것으로 본다. (계획서 §37) */
    const val TIMEOUT_MS: Long = 4000

    /** 살아 있다는 신호를 보내는 주기. */
    const val HEARTBEAT_MS: Long = 800

    /** 왕복 시간을 재는 주기. (계획서 §44.2 네트워크 상태 표시) */
    const val PING_INTERVAL_MS: Long = 1000

    /** 이 왕복 시간까지는 쾌적하다고 본다. */
    const val LATENCY_GOOD_MS: Int = 60

    /** 이 왕복 시간을 넘으면 끊길락 말락 한 것으로 본다. */
    const val LATENCY_POOR_MS: Int = 180

    /** 로비에서 START 를 누른 뒤 세는 시간. (계획서 §38) */
    const val COUNTDOWN_SECONDS: Int = 3

    /** 최소 인원. (계획서 §28) */
    const val MIN_PLAYERS: Int = 2

    const val MAX_PLAYERS: Int = 4

    object Type {
        /** Client -> 브로드캐스트. 이 근처에 방이 있나? */
        const val DISCOVER: Int = 1

        /** Host -> Client. 여기 방이 있다. */
        const val ANNOUNCE: Int = 2

        /** Client -> Host. 들어가겠다. */
        const val JOIN: Int = 3

        /** Host -> Client. 몇 번 자리를 쓰라. */
        const val JOIN_ACK: Int = 4

        /** Host -> Client. 못 들어온다. */
        const val JOIN_DENY: Int = 5

        /** Host -> Client. 로비 현황. */
        const val LOBBY: Int = 6

        /** Client -> Host. 준비됐다 / 탱크를 골랐다. */
        const val READY: Int = 7

        /** Host -> Client. 시작한다. seed 와 시작 틱. */
        const val START: Int = 8

        /** Client -> Host. 조종 입력. */
        const val INPUT: Int = 9

        /** Host -> Client. 게임 상태. */
        const val SNAPSHOT: Int = 10

        /** 양쪽. 살아 있다. */
        const val HEARTBEAT: Int = 11

        /** Client -> Host. 나간다. */
        const val LEAVE: Int = 12

        /** Host -> Client. 방을 닫는다. (계획서 §37 Host 연결 종료) */
        const val HOST_CLOSED: Int = 13

        /** 양쪽. 보낸 시각을 실어 보낸다. 받은 쪽은 그대로 되돌려 준다. */
        const val PING: Int = 14

        /** 양쪽. PING 에 실려 온 시각을 그대로 돌려준다. 왕복 시간을 잰다. */
        const val PONG: Int = 15

        /** Client -> Host. 결과 화면에 있는가 / 떠났는가. (계획서 §33) */
        const val PRESENCE: Int = 16
    }

    object Deny {
        const val VERSION_MISMATCH: Int = 1
        const val ROOM_FULL: Int = 2
        const val ALREADY_STARTED: Int = 3
    }

    /** 패킷 머리를 쓴다. 모든 패킷이 같은 머리로 시작한다. */
    fun header(writer: PacketWriter, type: Int): PacketWriter =
        writer.reset().int(MAGIC).byte(VERSION).byte(type)

    /**
     * 머리를 검사하고 종류를 돌려준다. 우리 패킷이 아니면 -1.
     *
     * @return 패킷 종류. 판별 실패 시 -1
     */
    fun readType(reader: PacketReader): Int {
        if (reader.remaining < HEADER_SIZE) return -1
        if (reader.int() != MAGIC) return -1
        if (reader.byte() != VERSION) return -1
        return reader.byte()
    }

    const val HEADER_SIZE: Int = 6
}
