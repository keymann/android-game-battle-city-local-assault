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
class BattleScene(
    assets: GameAssets,
    private val balance: BalanceConfig,
    private val playerCount: Int = DEFAULT_PLAYERS,
    startSeed: Long = DEFAULT_SEED,
) {
    private val catalog = SpriteCatalog(assets)
    private val generator = StageGenerator(assets.manifest)
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
                "바이옴=${stage.theme.biome} 도로=${stage.theme.roadStyle} " +
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
        director.attach(tank)
    }

    // -----------------------------------------------------------------------

    fun update(tickSeconds: Float) {
        elapsedSeconds += tickSeconds

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
