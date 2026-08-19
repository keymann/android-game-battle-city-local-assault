package com.kophas.battlecity.game

import android.util.Log
import com.kophas.battlecity.ai.AiDirector
import com.kophas.battlecity.ai.AiSettings
import com.kophas.battlecity.core.Direction
import com.kophas.battlecity.gameplay.BalanceConfig
import com.kophas.battlecity.gameplay.GameWorld
import com.kophas.battlecity.gameplay.MatchState
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
    /** 사람이 원격에서 조종하는 자리. 여기에는 AI 를 붙이지 않는다. */
    private val remoteSlots: Set<Int> = emptySet(),
) {
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
        match = MatchState(playerCount, balance)
        world = GameWorld(stage, balance)
        world.listener = MatchBridge()

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
            colorSlot = slot.index,
            blockIndex = block,
            direction = Direction.UP,
            ownerSlot = slot.index,
        ) ?: return

        slotOfTank[tank.id] = slot.index
        match.onPlayerSpawned(slot.index, tank.id)
        // 사람이 잡은 자리에는 AI 를 붙이지 않는다. 붙이면 조종간이 둘이 된다.
        if (slot.index !in remoteSlots) director.attach(tank)
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

        if (match.phase != MatchState.Phase.PLAYING) {
            if (resultHoldRemaining <= 0f) {
                resultHoldRemaining = RESULT_HOLD_SECONDS
                logResult()
            }
            resultHoldRemaining -= tickSeconds
            if (resultHoldRemaining <= 0f) nextStage()
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
        worldRenderer.render(world, batch, viewport, elapsedSeconds)
        hudRenderer.render(batch, viewport, match, world)
    }

    // -----------------------------------------------------------------------

    /** [GameWorld] 의 판정 결과를 [MatchState] 규칙으로 옮긴다. */
    private inner class MatchBridge : GameWorld.Listener {

        override fun onTankDamaged(tank: Tank, amount: Int, attackerId: Int) {
            val dealer = slotOfTank[attackerId] ?: -1
            if (tank.faction == Tank.Faction.ENEMY) {
                match.onEnemyDamaged(dealer, amount)
            } else {
                match.onPlayerDamaged(tank.ownerSlot, dealer, amount, tank.hp)
            }
        }

        override fun onTankDestroyed(tank: Tank, killerId: Int) {
            val killerSlot = slotOfTank[killerId] ?: -1
            if (tank.faction == Tank.Faction.ENEMY) {
                match.onEnemyDestroyed(killerSlot)
            } else {
                match.onPlayerDestroyed(tank.ownerSlot, killerSlot)
            }
        }

        override fun onBrickDestroyed(cellX: Int, cellY: Int) {
            // 벽이 사라지면 길이 바뀐다. AI 격자를 낡은 것으로 표시한다.
            director.onMapChanged()
        }

        override fun onBaseDestroyed() {
            match.onBaseDestroyed()
        }
    }

    private companion object {
        const val TAG = "BattleCity"
        const val DEFAULT_PLAYERS = 4
        const val DEFAULT_SEED = 20260819L

        /** 승패가 갈린 뒤 결과를 보여 주는 시간. 결과 화면은 Phase 8 에서 붙는다. */
        const val RESULT_HOLD_SECONDS = 3f
    }
}
