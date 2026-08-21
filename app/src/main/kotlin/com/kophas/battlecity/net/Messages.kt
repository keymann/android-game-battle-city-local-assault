package com.kophas.battlecity.net

/**
 * 주고받는 내용. (계획서 §35)
 *
 * 값 객체와 인코딩을 한곳에 둔다. 읽는 쪽과 쓰는 쪽이 떨어져 있으면 필드 하나를
 * 더할 때 한쪽만 고치는 일이 생기고, 그런 어긋남은 실행해 봐야 드러난다.
 */
object Messages {

    // --- 방 찾기 ----------------------------------------------------------

    data class Announce(
        val hostName: String,
        val players: Int,
        val maxPlayers: Int,
        val started: Boolean,
        /** 방을 연 시각. 방 목록에서 언제 열린 방인지 보여 준다. */
        val createdAt: Long = 0L,
    )

    fun writeAnnounce(writer: PacketWriter, value: Announce): PacketWriter =
        Protocol.header(writer, Protocol.Type.ANNOUNCE)
            .text(value.hostName)
            .byte(value.players)
            .byte(value.maxPlayers)
            .bool(value.started)
            .long(value.createdAt)

    fun readAnnounce(reader: PacketReader) = Announce(
        hostName = reader.text(),
        players = reader.byte(),
        maxPlayers = reader.byte(),
        started = reader.bool(),
        createdAt = reader.long(),
    )

    // --- 입장 -------------------------------------------------------------

    data class Join(val name: String, val tankType: Int, val colorIndex: Int)

    fun writeJoin(writer: PacketWriter, value: Join): PacketWriter =
        Protocol.header(writer, Protocol.Type.JOIN)
            .text(value.name)
            .byte(value.tankType)
            .byte(value.colorIndex)

    fun readJoin(reader: PacketReader) = Join(reader.text(), reader.byte(), reader.byte())

    /**
     * 배정 결과.
     *
     * 색은 Host 가 정해 준 것을 그대로 따른다. 먼저 들어온 사람이 이미 그 색을
     * 쓰고 있으면 남은 색으로 바꿔서 준다. 같은 색이 둘이면 누가 누구인지 모른다.
     */
    data class JoinAck(val slot: Int, val tankType: Int, val colorIndex: Int)

    fun writeJoinAck(writer: PacketWriter, value: JoinAck): PacketWriter =
        Protocol.header(writer, Protocol.Type.JOIN_ACK)
            .byte(value.slot)
            .byte(value.tankType)
            .byte(value.colorIndex)

    fun readJoinAck(reader: PacketReader) = JoinAck(reader.byte(), reader.byte(), reader.byte())

    // --- 로비 -------------------------------------------------------------

    data class LobbySlot(
        val index: Int,
        val name: String,
        val tankType: Int,
        val colorIndex: Int,
        val ready: Boolean,
        val connected: Boolean,
        val host: Boolean,
    )

    /**
     * 로비 현황. 방 규칙도 함께 실린다.
     *
     * 규칙을 판이 시작될 때만 보내면, 아직 판이 안 열린 로비에서 참가자가 설정 화면을
     * 열었을 때 **제 기기의 기본값**을 방 규칙인 양 보게 된다. 방장이 본진 보호를
     * 껐는데 켜져 있다고 읽히면 안 된다.
     */
    data class LobbyUpdate(
        val slots: List<LobbySlot>,
        val countdownTicks: Int,
        val mapSize: Int = 1,
        val friendlyFire: Boolean = true,
        val maxActiveEnemies: Int = 0,
        val baseProtection: Boolean = true,
    )

    fun writeLobby(writer: PacketWriter, value: LobbyUpdate): PacketWriter {
        Protocol.header(writer, Protocol.Type.LOBBY)
            .short(value.countdownTicks)
            .byte(value.mapSize)
            .byte(value.maxActiveEnemies)
            .byte(flags(value.friendlyFire, value.baseProtection, false))
            .byte(value.slots.size)
        for (slot in value.slots) {
            writer.byte(slot.index)
                .text(slot.name)
                .byte(slot.tankType)
                .byte(slot.colorIndex)
                .byte(flags(slot.ready, slot.connected, slot.host))
        }
        return writer
    }

    fun readLobby(reader: PacketReader): LobbyUpdate {
        val countdown = reader.short()
        val mapSize = reader.byte()
        val maxActive = reader.byte()
        val ruleFlags = reader.byte()
        val count = reader.byte()
        val slots = ArrayList<LobbySlot>(count)
        repeat(count) {
            val index = reader.byte()
            val name = reader.text()
            val type = reader.byte()
            val color = reader.byte()
            val flags = reader.byte()
            slots += LobbySlot(
                index = index,
                name = name,
                tankType = type,
                colorIndex = color,
                ready = flags and 1 != 0,
                connected = flags and 2 != 0,
                host = flags and 4 != 0,
            )
        }
        return LobbyUpdate(
            slots = slots,
            countdownTicks = countdown,
            mapSize = mapSize,
            friendlyFire = ruleFlags and 1 != 0,
            maxActiveEnemies = maxActive,
            baseProtection = ruleFlags and 2 != 0,
        )
    }

    /**
     * 준비 상태와 고른 것을 함께 보낸다. (계획서 §28, §29)
     *
     * 이름 · 탱크 · 색을 따로 보내지 않는다. 로비에서 무엇을 만지든 결국 "지금 내
     * 상태는 이렇다" 를 알리면 되고, 패킷이 하나면 순서가 뒤바뀔 일도 없다.
     */
    data class Ready(
        val ready: Boolean,
        val tankType: Int,
        val colorIndex: Int,
        val name: String,
    )

    fun writeReady(writer: PacketWriter, value: Ready): PacketWriter =
        Protocol.header(writer, Protocol.Type.READY)
            .bool(value.ready)
            .byte(value.tankType)
            .byte(value.colorIndex)
            .text(value.name)

    fun readReady(reader: PacketReader) =
        Ready(reader.bool(), reader.byte(), reader.byte(), reader.text())

    // --- 시작 -------------------------------------------------------------

    /**
     * 맵은 보내지 않는다. seed 만 있으면 어느 기기에서나 같은 맵이 나온다.
     * [gridHash] 는 정말 같은 맵이 나왔는지 대조하는 값이다.
     */
    data class Start(
        val seed: Long,
        val stageIndex: Int,
        val playerCount: Int,
        val gridHash: Long,
        val startTick: Long,
        /** 방장이 정한 규칙. 판정에 영향을 주므로 함께 보낸다. */
        val mapSize: Int = 1,
        val friendlyFire: Boolean = true,
        val maxActiveEnemies: Int = 0,
        val baseProtection: Boolean = true,
    )

    fun writeStart(writer: PacketWriter, value: Start): PacketWriter =
        Protocol.header(writer, Protocol.Type.START)
            .long(value.seed)
            .short(value.stageIndex)
            .byte(value.playerCount)
            .long(value.gridHash)
            .long(value.startTick)
            .byte(value.mapSize)
            .byte(value.maxActiveEnemies)
            .byte(flags(value.friendlyFire, value.baseProtection, false))

    fun readStart(reader: PacketReader): Start {
        val seed = reader.long()
        val stageIndex = reader.short()
        val playerCount = reader.byte()
        val gridHash = reader.long()
        val startTick = reader.long()
        val mapSize = reader.byte()
        val maxActive = reader.byte()
        val rules = reader.byte()
        return Start(
            seed = seed,
            stageIndex = stageIndex,
            playerCount = playerCount,
            gridHash = gridHash,
            startTick = startTick,
            mapSize = mapSize,
            friendlyFire = rules and 1 != 0,
            maxActiveEnemies = maxActive,
            baseProtection = rules and 2 != 0,
        )
    }

    // --- 조종 입력 --------------------------------------------------------

    /** 방향은 0~3, 없으면 4. (계획서 §35 Client -> Host) */
    data class Input(
        val tick: Long,
        val direction: Int,
        val moving: Boolean,
        val fire: Boolean,
        val special: Boolean,
    ) {
        companion object {
            const val NO_DIRECTION = 4
        }
    }

    fun writeInput(writer: PacketWriter, value: Input): PacketWriter =
        Protocol.header(writer, Protocol.Type.INPUT)
            .long(value.tick)
            .byte(value.direction)
            .byte(flags(value.moving, value.fire, value.special))

    fun readInput(reader: PacketReader): Input {
        val tick = reader.long()
        val direction = reader.byte()
        val flags = reader.byte()
        return Input(
            tick = tick,
            direction = direction,
            moving = flags and 1 != 0,
            fire = flags and 2 != 0,
            special = flags and 4 != 0,
        )
    }

    // --- 게임 상태 --------------------------------------------------------

    data class TankState(
        val id: Int,
        val slot: Int,
        val faction: Int,
        val type: Int,
        val x: Float,
        val y: Float,
        val direction: Int,
        val hp: Int,
        val specialActive: Boolean,
    )

    data class ProjectileState(
        val id: Int,
        val x: Float,
        val y: Float,
        val direction: Int,
        val piercing: Boolean,
    )

    data class ScoreState(val slot: Int, val kills: Int, val lives: Int, val eliminated: Boolean)

    /**
     * 바뀐 셀 하나. [type] 은 변화량이 아니라 **지금 종류**다.
     *
     * 순서가 뒤바뀌어 도착해도 다음 스냅샷이 바로잡는다. 변화량을 보내면 한 번만
     * 어긋나도 그 뒤가 전부 틀어진다.
     */
    data class TileChange(val index: Int, val type: Int)

    data class Snapshot(
        val tick: Long,
        val phase: Int,
        val enemiesRemaining: Int,
        val baseDestroyed: Boolean,
        /**
         * 본진에 남은 발수. 0 이면 부서졌다.
         *
         * Client 는 규칙을 굴리지 않아 몇 발 남았는지 스스로 알 수 없다. 알려 주지
         * 않으면 멀쩡한 본진을 그리다가 갑자기 파괴로 건너뛴다.
         */
        val baseHits: Int = 1,
        val tanks: List<TankState>,
        val projectiles: List<ProjectileState>,
        val scores: List<ScoreState>,
        /** 본진 보호막이 아직 남아 있는가. (계획서 §14) */
        val baseShielded: Boolean = false,
        /** 최근에 바뀐 셀. Client 는 이것으로만 맵을 고친다. */
        val tiles: List<TileChange> = emptyList(),
    )

    fun writeSnapshot(writer: PacketWriter, value: Snapshot): PacketWriter {
        Protocol.header(writer, Protocol.Type.SNAPSHOT)
            .long(value.tick)
            .byte(value.phase)
            .short(value.enemiesRemaining)
            .byte(flags(value.baseDestroyed, value.baseShielded, false))
            .byte(value.baseHits.coerceIn(0, 255))
            .byte(value.tanks.size)
        for (tank in value.tanks) {
            writer.short(tank.id)
                .byte((tank.slot + 1) or (tank.faction shl 4) or (tank.type shl 5))
                .position(tank.x)
                .position(tank.y)
                .byte(tank.direction or (if (tank.specialActive) 0x10 else 0))
                .byte(tank.hp)
        }
        writer.byte(value.projectiles.size)
        for (projectile in value.projectiles) {
            writer.short(projectile.id)
                .position(projectile.x)
                .position(projectile.y)
                .byte(projectile.direction or (if (projectile.piercing) 0x10 else 0))
        }
        writer.byte(value.scores.size)
        for (score in value.scores) {
            writer.byte(score.slot).short(score.kills).byte(score.lives).bool(score.eliminated)
        }
        writer.byte(value.tiles.size)
        for (tile in value.tiles) {
            writer.short(tile.index).byte(tile.type)
        }
        return writer
    }

    fun readSnapshot(reader: PacketReader): Snapshot {
        val tick = reader.long()
        val phase = reader.byte()
        val enemies = reader.short()
        val baseFlags = reader.byte()
        val baseHits = reader.byte()

        val tankCount = reader.byte()
        val tanks = ArrayList<TankState>(tankCount)
        repeat(tankCount) {
            val id = reader.short()
            val packed = reader.byte()
            val x = reader.position()
            val y = reader.position()
            val heading = reader.byte()
            val hp = reader.byte()
            tanks += TankState(
                id = id,
                slot = (packed and 0xF) - 1,
                faction = (packed ushr 4) and 0x1,
                type = (packed ushr 5) and 0x7,
                x = x,
                y = y,
                direction = heading and 0xF,
                hp = hp,
                specialActive = heading and 0x10 != 0,
            )
        }

        val projectileCount = reader.byte()
        val projectiles = ArrayList<ProjectileState>(projectileCount)
        repeat(projectileCount) {
            val id = reader.short()
            val x = reader.position()
            val y = reader.position()
            val heading = reader.byte()
            projectiles += ProjectileState(id, x, y, heading and 0xF, heading and 0x10 != 0)
        }

        val scoreCount = reader.byte()
        val scores = ArrayList<ScoreState>(scoreCount)
        repeat(scoreCount) {
            scores += ScoreState(reader.byte(), reader.short(), reader.byte(), reader.bool())
        }

        val tileCount = reader.byte()
        val tiles = ArrayList<TileChange>(tileCount)
        repeat(tileCount) {
            tiles += TileChange(reader.short(), reader.byte())
        }

        return Snapshot(
            tick = tick,
            phase = phase,
            enemiesRemaining = enemies,
            baseDestroyed = baseFlags and 1 != 0,
            baseHits = baseHits,
            tanks = tanks,
            projectiles = projectiles,
            scores = scores,
            baseShielded = baseFlags and 2 != 0,
            tiles = tiles,
        )
    }

    // ---------------------------------------------------------------------

    private fun flags(a: Boolean, b: Boolean, c: Boolean): Int =
        (if (a) 1 else 0) or (if (b) 2 else 0) or (if (c) 4 else 0)

    // --- 지연 측정 · 결과 화면 -------------------------------------------

    /**
     * 왕복 시간 측정. 보낸 쪽의 시계를 그대로 실어 보내고 받은 쪽은 되돌려만 준다.
     *
     * 두 기기의 시계를 맞출 필요가 없다. 값을 해석하는 쪽이 언제나 보낸 쪽이라,
     * 돌아온 값을 제 시계에서 빼면 그것이 곧 왕복 시간이다.
     */
    fun writePing(writer: PacketWriter, stamp: Long): PacketWriter =
        Protocol.header(writer, Protocol.Type.PING).long(stamp)

    fun writePong(writer: PacketWriter, stamp: Long): PacketWriter =
        Protocol.header(writer, Protocol.Type.PONG).long(stamp)

    fun readStamp(reader: PacketReader): Long = reader.long()

    /** 결과 화면에 있는지. 방장이 PLAY AGAIN 을 열어 둘지 정하는 데 쓴다. */
    fun writePresence(writer: PacketWriter, present: Boolean): PacketWriter =
        Protocol.header(writer, Protocol.Type.PRESENCE).bool(present)

    fun readPresence(reader: PacketReader): Boolean = reader.bool()
}
