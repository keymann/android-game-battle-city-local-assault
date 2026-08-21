package com.kophas.battlecity.game

import android.util.Log
import com.kophas.battlecity.ai.AiDirector
import com.kophas.battlecity.ai.AiSettings
import com.kophas.battlecity.audio.AudioDirector
import com.kophas.battlecity.core.Direction
import com.kophas.battlecity.gameplay.BalanceConfig
import com.kophas.battlecity.gameplay.GameWorld
import com.kophas.battlecity.gameplay.MatchState
import com.kophas.battlecity.gameplay.PlayerProfile
import com.kophas.battlecity.gameplay.Tank
import com.kophas.battlecity.map.StageData
import com.kophas.battlecity.map.StageGenerator
import com.kophas.battlecity.net.Messages
import com.kophas.battlecity.net.Protocol
import com.kophas.battlecity.net.SnapshotBridge
import com.kophas.battlecity.render.GameAssets
import com.kophas.battlecity.render.HudRenderer
import com.kophas.battlecity.render.SpriteBatch
import com.kophas.battlecity.render.SpriteCatalog
import com.kophas.battlecity.render.Viewport
import com.kophas.battlecity.render.WorldRenderer

/**
 * 한 판을 굴리는 씬.
 *
 * 스테이지가 새로 구성될 때마다 맵을 seed 로 랜덤 생성하고, [MatchState] 가
 * Life / 점수 / 승패를, [GameWorld] 가 이동 / 충돌 / 파괴를 맡는다.
 *
 * COM 은 [AiDirector] 가 내보내고 굴린다. 플레이어 탱크에도 같은 AI 를 붙여 둔다.
 * Phase 7 에서 사람이 조종간을 잡으면 그 자리에 사람 입력이 들어올 뿐, 규칙은
 * 하나도 달라지지 않는다.
 */
/** 이 기기가 판에서 맡은 역할. (계획서 §4.2 Authority) */
enum class NetRole {
    /** 혼자 돌린다. 규칙도 여기서 굴린다. */
    LOCAL,

    /** 방을 열었다. 규칙을 굴리고 결과를 내려보낸다. */
    HOST,

    /** 방에 들어갔다. 입력만 올리고 받은 상태를 그린다. */
    CLIENT,
}

class BattleScene(
    assets: GameAssets,
    private val balance: BalanceConfig,
    private var playerCount: Int = DEFAULT_PLAYERS,
    startSeed: Long = DEFAULT_SEED,
    private val role: NetRole = NetRole.LOCAL,
    /** 사람이 조종하는 자리. 여기에는 AI 를 붙이지 않는다. */
    private val humanSlots: Set<Int> = emptySet(),
    /**
     * 만들 때부터 아는 프로필. 혼자 하는 판이 쓴다.
     *
     * 방을 거쳐 열리는 판은 START 를 받고서야 프로필을 알게 되므로 [profiles] 에
     * 나중에 넣는다. 혼자 하는 판은 로비에서 고른 것을 그대로 들고 들어오므로
     * 처음부터 알고 있다.
     */
    profiles: List<PlayerProfile> = emptyList(),
) {
    /**
     * 로비에서 고른 이름 · 탱크 · 색. 판을 열기 전에 채워 넣는다.
     *
     * 비어 있으면 자리 번호로 기본값을 만든다. 혼자 하는 판에는 로비가 없기 때문이다.
     */
    var profiles: List<PlayerProfile> = profiles

    /** 방장이 정한 규칙. 판을 열 때 반영한다. */
    var room: RoomSettings = RoomSettings()

    /** 소리와 진동. 없으면 조용히 돌아간다. (계획서 §34) */
    var audio: AudioDirector? = null

    /** 이 기기가 조종하는 자리. 진동은 이 사람에게만 준다. */
    var localSlot: Int = 0

    /** 이 기기의 왕복 지연. HUD 구석에 ms 로 나간다. (계획서 §44.2) */
    var networkLatencyMs: Int = -1

    var networkOnline: Boolean = true

    /** 네트워크 표시를 그릴지. 혼자 하는 판에는 뜻이 없다. */
    var showNetwork: Boolean = false

    /**
     * 저사양 기기에서 연출을 줄인다. (계획서 §24)
     *
     * 규칙은 건드리지 않는다. 대시 잔상처럼 **없어도 게임이 되는 것**만 뺀다.
     * 판정을 바꾸면 같은 seed 로 다른 결과가 나와 네트워크가 어긋난다.
     */
    var reducedEffects: Boolean = false

    /** 승패 소리는 한 번만 낸다. */
    private var resultAnnounced = false

    /**
     * 판이 끝나고 결과를 보여 줄 때가 되면 부른다. (계획서 §33)
     *
     * 씬이 스스로 다음 스테이지를 열지 않는다. 다음에 무엇을 할지는 결과 화면에서
     * 사람이 고르고, 그 결정은 방장 한 사람의 것이다.
     */
    var onMatchFinished: (() -> Unit)? = null

    /** 결과를 이미 넘겼는가. 매 틱 다시 부르면 결과 화면이 계속 초기화된다. */
    private var finishReported = false

    private val catalog = SpriteCatalog(assets)
    private val generator = StageGenerator(assets.manifest, assets.mapGen)
    private val worldRenderer = WorldRenderer(catalog)
    private val hudRenderer = HudRenderer(catalog)

    private var seed: Long = startSeed
    private var stageIndex: Int = 0
    private var elapsedSeconds: Float = 0f
    private var resultHoldRemaining: Float = 0f

    private lateinit var world: GameWorld
    private lateinit var match: MatchState

    private val aiSettings = AiSettings.from(balance)
    private var director = AiDirector(balance, aiSettings, startSeed)

    /** 탱크 id -> 플레이어 슬롯. 파괴된 뒤에도 처치자를 되짚어야 해서 따로 둔다. */
    private val slotOfTank = HashMap<Int, Int>()

    var logicalWidth: Float = 0f
        private set

    var logicalHeight: Float = 0f
        private set

    /**
     * 자리마다 마지막으로 입력이 닿은 시각. 유령 이동을 막는 데 쓴다.
     *
     * 참가자는 매 틱 입력을 보낸다. 그 흐름이 끊기면 연결이 끊긴 것이므로 탱크를
     * 세워야 한다. 세우지 않으면 자리가 비기까지 4초 동안 혼자 달린다. (계획서 §37)
     */
    private val lastInputAt = FloatArray(Protocol.MAX_PLAYERS) { -1f }

    init {
        buildStage()
    }

    // -----------------------------------------------------------------------

    private fun buildStage() {
        val stage = takeStage(seed, stageIndex, playerCount)
        match = MatchState(playerCount, balance, profiles, room.maxActiveEnemies)
        world = GameWorld(stage, balance, GameWorld.Config(friendlyFire = room.friendlyFire))
        // 본진 내구도는 balance.json, 보호막은 방 설정이 정한다.
        world.map.configureBase(balance.rules.baseHits, room.baseProtection)
        world.listener = MatchBridge()

        resultAnnounced = false
        finishReported = false
        lastInputAt.fill(-1f)
        director = AiDirector(balance, aiSettings, seed)
        director.bind(world)
        slotOfTank.clear()
        resultHoldRemaining = 0f
        logicalWidth = stage.widthPx
        logicalHeight = stage.heightPx
        worldRenderer.bind(stage)

        for (slot in match.players) {
            spawnPlayer(slot)
        }
        director.fillInitialEnemies(world, match)

        Log.i(
            TAG,
            "스테이지 #$stageIndex: ${stage.blocksX}x${stage.blocksY} 블록(16:9), " +
                "바이옴=${stage.theme.biome} " +
                "연결=${stage.report?.hardConnectedRatio} 출구=${stage.report?.baseExitCount} " +
                "공정=${stage.report?.fairnessScore} 점수=${stage.report?.score} | " +
                "COM ${match.totalEnemies}기(동시 ${match.maxActiveEnemies}) 해시=${stage.gridHash}",
        )
    }

    private fun spawnPlayer(slot: MatchState.Slot) {
        if (slot.eliminated) return
        val stage = world.stage
        val block = stage.playerSpawnBlocks[slot.index % stage.playerSpawnBlocks.size]
        val tank = world.spawnTank(
            faction = Tank.Faction.PLAYER,
            type = slot.tankType,
            // 색은 자리 번호가 아니라 **고른 색**이다. 화면과 HUD 가 같은 색을 쓴다.
            colorSlot = slot.colorIndex,
            blockIndex = block,
            direction = Direction.UP,
            ownerSlot = slot.index,
        ) ?: return

        slotOfTank[tank.id] = slot.index
        match.onPlayerSpawned(slot.index, tank.id)
        // 사람이 잡은 자리에는 AI 를 붙이지 않는다. 붙이면 조종간이 둘이 된다.
        if (slot.index !in humanSlots) director.attach(tank)
    }

    // -----------------------------------------------------------------------
    // 네트워크 (계획서 §35)
    // -----------------------------------------------------------------------

    val stageGridHash: Long get() = world.stage.gridHash

    /** 미리 만들어 둔 스테이지. 해시를 먼저 알아야 해서 판보다 앞서 만든다. */
    private var preparedStage: StageData? = null
    private var preparedKey: Triple<Long, Int, Int>? = null

    /**
     * 판을 열기 전에 스테이지를 미리 만들고 해시를 돌려준다. (계획서 §35)
     *
     * Host 는 START 에 **이번 판** 해시를 실어야 한다. 판을 연 뒤에 해시를 담으면
     * 이미 늦고, 판을 열기 전 해시는 지난 판 것이다. 만들어 둔 스테이지는 판을 열 때
     * 그대로 쓰므로 같은 맵을 두 번 만들지 않는다.
     */
    fun prepareStage(seed: Long, stageIndex: Int, playerCount: Int): Long {
        val key = Triple(seed, stageIndex, playerCount)
        if (preparedKey != key) {
            preparedStage = generator.generate(seed, room.gridPlayerCount(playerCount), stageIndex)
            preparedKey = key
        }
        return preparedStage?.gridHash ?: 0L
    }

    private fun takeStage(seed: Long, stageIndex: Int, playerCount: Int): StageData {
        val key = Triple(seed, stageIndex, playerCount)
        val ready = preparedStage?.takeIf { preparedKey == key }
        preparedStage = null
        preparedKey = null
        // 맵 크기는 방 설정이 한 단계 좁히거나 넓힌다.
        return ready ?: generator.generate(seed, room.gridPlayerCount(playerCount), stageIndex)
    }

    /** Host 가 정한 seed 로 판을 다시 연다. Client 가 START 를 받았을 때 부른다. */
    fun beginStage(seed: Long, stageIndex: Int, playerCount: Int) {
        this.seed = seed
        this.stageIndex = stageIndex
        this.playerCount = playerCount
        buildStage()
    }

    /** Client 가 올린 조종 입력을 그 자리의 탱크에 그대로 먹인다. */
    fun applyRemoteInput(slot: Int, input: Messages.Input) {
        if (slot in lastInputAt.indices) lastInputAt[slot] = elapsedSeconds
        val tank = world.tanks.firstOrNull { it.ownerSlot == slot && it.alive } ?: return
        if (input.direction != Messages.Input.NO_DIRECTION) {
            world.steer(tank, Direction.VALUES[input.direction.coerceIn(0, 3)])
        }
        tank.moving = input.moving
        if (input.fire) world.fire(tank)
        if (input.special) world.activateSpecial(tank)
    }

    fun captureSnapshot(tick: Long): Messages.Snapshot =
        SnapshotBridge.capture(world, match, tick)

    /** Host 가 보낸 상태를 그대로 얹는다. 규칙은 굴리지 않는다. */
    fun applySnapshot(latest: Messages.Snapshot, previous: Messages.Snapshot?, alpha: Float) {
        // 색은 패킷에 없다. START 로 받은 프로필에서 되짚는다. 자리 번호를 색으로 쓰면
        // 로비에서 색을 바꾼 사람이 남의 색으로 보인다.
        SnapshotBridge.apply(world, latest, previous, alpha) { slot ->
            match.players.getOrNull(slot)?.colorIndex ?: -1
        }
        match.applyRemote(
            phaseOrdinal = latest.phase,
            enemiesRemaining = latest.enemiesRemaining,
            scores = latest.scores.map {
                MatchState.RemoteScore(it.slot, it.kills, it.lives, it.eliminated)
            },
        )
    }

    // -----------------------------------------------------------------------

    fun update(tickSeconds: Float) {
        elapsedSeconds += tickSeconds

        // Client 는 규칙을 굴리지 않는다. 폭발 같은 연출만 흘려보낸다. (계획서 §4.2)
        if (role == NetRole.CLIENT) {
            world.updateEffectsOnly(tickSeconds)
            updateAudio()
            // 승패는 Host 가 스냅샷으로 알려 준다. 결과 화면으로 넘어가는 길은 같다.
            checkFinished(tickSeconds)
            return
        }

        director.update(world, match, tickSeconds)
        stopIdleTanks()
        world.update(tickSeconds)
        match.update(tickSeconds)

        reapDestroyedTanks()
        respawnPlayers()
        updateAudio()

        checkFinished(tickSeconds)
    }

    /**
     * 승패가 갈린 뒤 잠깐 멈춘다.
     *
     * 마지막 폭발이 화면에 남아 있는 채로 결과 화면이 덮이면 무슨 일이 일어났는지
     * 못 본다. 터지는 것을 끝까지 보여 주고 넘긴다.
     */
    private fun checkFinished(tickSeconds: Float) {
        if (match.phase == MatchState.Phase.PLAYING || finishReported) return
        if (resultHoldRemaining <= 0f) {
            resultHoldRemaining = RESULT_HOLD_SECONDS
            logResult()
        }
        resultHoldRemaining -= tickSeconds
        if (resultHoldRemaining > 0f) return
        finishReported = true
        onMatchFinished?.invoke()
    }

    /** 결과 화면이 읽어 갈 성적표. */
    val matchState: MatchState get() = match

    /** 지금 스테이지 번호. 0부터 센다. */
    val stage: Int get() = stageIndex

    /** 이 판에 선 사람 수. 다시 시작할 때 같은 인원으로 열어야 난이도가 같다. */
    val players: Int get() = playerCount

    /**
     * 이어지는 소리와 배경음. (계획서 §34)
     *
     * 매 틱 부르지만 [AudioDirector] 가 같은 것을 두 번 시작하지 않으므로
     * 여기서는 "지금 상태가 이렇다" 만 알려 주면 된다.
     */
    private fun updateAudio() {
        val sound = audio ?: return
        val tank = tankOfSlot(localSlot)
        sound.setLoop(AudioDirector.Loop.TANK_MOVE, tank != null && tank.moving)

        // 본진 가까이 적이 오면 경고음이 돈다. 화면 밖에서 벌어지는 일을 알린다.
        val (baseX, baseY) = world.baseCellCenter()
        val warning = !world.map.baseDestroyed && world.tanks.any { enemy ->
            enemy.alive && enemy.faction == Tank.Faction.ENEMY &&
                kotlin.math.abs(enemy.centerX - baseX) < BASE_ALERT_PX &&
                kotlin.math.abs(enemy.centerY - baseY) < BASE_ALERT_PX
        }
        sound.setLoop(AudioDirector.Loop.BASE_WARNING, warning)

        sound.updateBattleMusic(match.enemiesRemaining, match.totalEnemies)

        if (!resultAnnounced && match.phase != MatchState.Phase.PLAYING) {
            resultAnnounced = true
            sound.stopAllLoops()
            sound.play(
                if (match.phase == MatchState.Phase.VICTORY) {
                    AudioDirector.Event.VICTORY
                } else {
                    AudioDirector.Event.GAME_OVER
                },
            )
        }
    }

    /**
     * 입력이 끊긴 사람의 탱크를 세운다.
     *
     * 사람이 잡은 자리만 본다. COM 자리는 AI 가 매 틱 방향을 정하므로 여기서 손대면
     * 움직이지 못한다.
     */
    private fun stopIdleTanks() {
        val limit = Protocol.INPUT_IDLE_MS / 1000f
        for (slot in humanSlots) {
            val stamp = lastInputAt.getOrNull(slot) ?: continue
            if (stamp < 0f || elapsedSeconds - stamp <= limit) continue
            world.tanks.firstOrNull { it.ownerSlot == slot && it.alive }?.moving = false
        }
    }

    /**
     * 연결이 끊긴 자리를 정리한다. (계획서 §37)
     *
     * 탱크를 걷어내고 탈락으로 굳힌다. 남은 사람은 그대로 판을 이어 간다.
     */
    fun dropSlot(slot: Int) {
        world.tanks.firstOrNull { it.ownerSlot == slot && it.alive }?.let { tank ->
            director.detach(tank.id)
            slotOfTank.remove(tank.id)
            world.despawn(tank)
        }
        if (slot in lastInputAt.indices) lastInputAt[slot] = -1f
        match.onPlayerDisconnected(slot)
    }

    /** 부서진 탱크를 풀로 돌려보낸다. 파괴 통보는 리스너에서 이미 끝났다. */
    private fun reapDestroyedTanks() {
        val dead = world.tanks.filter { !it.alive }
        for (tank in dead) {
            director.detach(tank.id)
            slotOfTank.remove(tank.id)
            world.despawn(tank)
        }
    }

    private fun respawnPlayers() {
        if (match.phase != MatchState.Phase.PLAYING) return
        for (slot in match.players) {
            if (slot.canRespawn) spawnPlayer(slot)
        }
    }

    private fun logResult() {
        val ranking = match.ranking().joinToString(" / ") {
            "P${it.index + 1} ${it.kills}킬 ♥${it.lives}"
        }
        Log.i(
            TAG,
            "${match.phase} (${match.endReason}) 승자=${match.winners.map { it + 1 }} | $ranking",
        )
    }

    // -----------------------------------------------------------------------

    fun render(batch: SpriteBatch, viewport: Viewport) {
        worldRenderer.reducedEffects = reducedEffects
        worldRenderer.render(world, batch, viewport, elapsedSeconds)
        hudRenderer.latencyMs = networkLatencyMs
        hudRenderer.online = networkOnline
        hudRenderer.showNetwork = showNetwork
        hudRenderer.render(batch, viewport, match, world)
    }

    /** 판이 시작되고 흐른 시간. 조작 UI 의 맥동에 쓴다. */
    val elapsed: Float get() = elapsedSeconds

    /** 이 기기가 조종하는 탱크. 조작 UI 가 특수기 쿨타임을 보여 줄 때 쓴다. */
    fun tankOfSlot(slot: Int): Tank? =
        world.tanks.firstOrNull { it.ownerSlot == slot && it.alive }

    // -----------------------------------------------------------------------

    /** [GameWorld] 의 판정 결과를 [MatchState] 규칙으로 옮긴다. */
    private inner class MatchBridge : GameWorld.Listener {

        override fun onTankSpawned(tank: Tank) {
            if (tank.faction == Tank.Faction.ENEMY) audio?.play(AudioDirector.Event.ENEMY_SPAWN)
        }

        override fun onFired(tank: Tank) {
            audio?.play(AudioDirector.Event.TANK_FIRE)
            if (tank.ownerSlot == localSlot) audio?.vibrate(AudioDirector.Haptic.TANK_FIRE)
        }

        override fun onSteelHit(x: Float, y: Float) {
            audio?.play(AudioDirector.Event.BULLET_HIT_STEEL)
        }

        override fun onProjectileHit(x: Float, y: Float) {
            audio?.play(AudioDirector.Event.BULLET_HIT_BRICK)
        }

        override fun onSpecialActivated(tank: Tank, special: BalanceConfig.Special) {
            audio?.play(
                when (special) {
                    BalanceConfig.Special.PIERCING -> AudioDirector.Event.SPECIAL_PIERCING
                    BalanceConfig.Special.SHIELD -> AudioDirector.Event.SPECIAL_SHIELD
                    BalanceConfig.Special.DASH -> AudioDirector.Event.SPECIAL_DASH
                    BalanceConfig.Special.NONE -> return
                },
            )
            if (tank.ownerSlot == localSlot) audio?.vibrate(AudioDirector.Haptic.SPECIAL)
        }

        override fun onTankDamaged(tank: Tank, amount: Int, attackerId: Int) {
            if (tank.ownerSlot == localSlot) audio?.vibrate(AudioDirector.Haptic.TAKE_DAMAGE)
            val dealer = slotOfTank[attackerId] ?: -1
            if (tank.faction == Tank.Faction.ENEMY) {
                match.onEnemyDamaged(dealer, amount)
            } else {
                match.onPlayerDamaged(tank.ownerSlot, dealer, amount, tank.hp)
            }
        }

        override fun onTankDestroyed(tank: Tank, killerId: Int) {
            if (tank.faction == Tank.Faction.PLAYER) {
                audio?.play(AudioDirector.Event.PLAYER_DEATH)
                if (tank.ownerSlot == localSlot) audio?.vibrate(AudioDirector.Haptic.PLAYER_DEATH)
            } else {
                audio?.play(AudioDirector.Event.TANK_EXPLOSION)
            }
            val killerSlot = slotOfTank[killerId] ?: -1
            if (tank.faction == Tank.Faction.ENEMY) {
                match.onEnemyDestroyed(killerSlot)
            } else {
                match.onPlayerDestroyed(tank.ownerSlot, killerSlot)
            }
        }

        override fun onBrickDestroyed(cellX: Int, cellY: Int) {
            audio?.play(AudioDirector.Event.BRICK_DESTROY)
            // 벽이 사라지면 길이 바뀐다. AI 격자를 낡은 것으로 표시한다.
            director.onMapChanged()
        }

        override fun onBaseShieldHit() {
            // 막아 냈다는 것을 알려야 한다. 조용하면 그냥 빗나간 줄 안다.
            audio?.play(AudioDirector.Event.BULLET_HIT_STEEL)
            audio?.vibrate(AudioDirector.Haptic.TAKE_DAMAGE)
        }

        override fun onBaseDamaged(hitsRemaining: Int) {
            // 본진이 깎였다는 것은 판이 기울었다는 뜻이다. 조용히 넘기면 모르고 지나간다.
            // 파괴음(BASE_DESTROY)은 쓰지 않는다. 아직 끝난 것이 아니다.
            audio?.play(AudioDirector.Event.BRICK_DESTROY)
            audio?.vibrate(AudioDirector.Haptic.TAKE_DAMAGE)
        }

        override fun onBaseDestroyed() {
            audio?.play(AudioDirector.Event.BASE_DESTROY)
            audio?.vibrate(AudioDirector.Haptic.BASE_DESTROY)
            match.onBaseDestroyed()
        }
    }

    companion object {
        /** 혼자 하는 판의 기본 seed. 밖에서 다른 seed 를 넘길 수 있다. */
        const val DEFAULT_SEED = 20260819L

        private const val TAG = "BattleCity"
        private const val DEFAULT_PLAYERS = 4

        /** 승패가 갈린 뒤 결과 화면으로 넘어가기까지 기다리는 시간. */
        const val RESULT_HOLD_SECONDS = 2.5f

        /** 본진에서 이 거리 안에 적이 오면 경고음이 돈다. */
        const val BASE_ALERT_PX = 64f * 5f
    }
}
