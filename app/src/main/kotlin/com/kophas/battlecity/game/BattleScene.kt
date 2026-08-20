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
import com.kophas.battlecity.map.Rng
import com.kophas.battlecity.map.StageGenerator
import com.kophas.battlecity.net.Messages
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
) {
    /**
     * 로비에서 고른 이름 · 탱크 · 색. 판을 열기 전에 채워 넣는다.
     *
     * 비어 있으면 자리 번호로 기본값을 만든다. 혼자 하는 판에는 로비가 없기 때문이다.
     */
    var profiles: List<PlayerProfile> = emptyList()

    /** 소리와 진동. 없으면 조용히 돌아간다. (계획서 §34) */
    var audio: AudioDirector? = null

    /** 이 기기가 조종하는 자리. 진동은 이 사람에게만 준다. */
    var localSlot: Int = 0

    /**
     * 저사양 기기에서 연출을 줄인다. (계획서 §24)
     *
     * 규칙은 건드리지 않는다. 대시 잔상처럼 **없어도 게임이 되는 것**만 뺀다.
     * 판정을 바꾸면 같은 seed 로 다른 결과가 나와 네트워크가 어긋난다.
     */
    var reducedEffects: Boolean = false

    /** 승패 소리는 한 번만 낸다. */
    private var resultAnnounced = false

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

    init {
        buildStage()
    }

    // -----------------------------------------------------------------------

    private fun buildStage() {
        val stage = generator.generate(seed, playerCount, stageIndex)
        match = MatchState(playerCount, balance, profiles)
        world = GameWorld(stage, balance)
        world.listener = MatchBridge()

        resultAnnounced = false
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

    /** Host 가 정한 seed 로 판을 다시 연다. Client 가 START 를 받았을 때 부른다. */
    fun beginStage(seed: Long, stageIndex: Int, playerCount: Int) {
        this.seed = seed
        this.stageIndex = stageIndex
        this.playerCount = playerCount
        buildStage()
    }

    /** Client 가 올린 조종 입력을 그 자리의 탱크에 그대로 먹인다. */
    fun applyRemoteInput(slot: Int, input: Messages.Input) {
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
        SnapshotBridge.apply(world, latest, previous, alpha)
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
            return
        }

        director.update(world, match, tickSeconds)
        world.update(tickSeconds)
        match.update(tickSeconds)

        reapDestroyedTanks()
        respawnPlayers()
        updateAudio()

        if (match.phase != MatchState.Phase.PLAYING) {
            if (resultHoldRemaining <= 0f) {
                resultHoldRemaining = RESULT_HOLD_SECONDS
                logResult()
            }
            resultHoldRemaining -= tickSeconds
            if (resultHoldRemaining <= 0f) nextStage()
        }
    }

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

    private fun nextStage() {
        stageIndex++
        seed = Rng.advanceSeed(seed)
        buildStage()
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

        override fun onBaseDestroyed() {
            audio?.play(AudioDirector.Event.BASE_DESTROY)
            audio?.vibrate(AudioDirector.Haptic.BASE_DESTROY)
            match.onBaseDestroyed()
        }
    }

    private companion object {
        const val TAG = "BattleCity"
        const val DEFAULT_PLAYERS = 4
        const val DEFAULT_SEED = 20260819L

        /** 승패가 갈린 뒤 결과를 보여 주는 시간. 결과 화면은 Phase 8 에서 붙는다. */
        const val RESULT_HOLD_SECONDS = 3f

        /** 본진에서 이 거리 안에 적이 오면 경고음이 돈다. */
        const val BASE_ALERT_PX = 64f * 5f
    }
}
