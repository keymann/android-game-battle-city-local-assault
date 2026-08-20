package com.kophas.battlecity.game

import android.util.Log
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
 * 조작 입력은 Phase 7, COM AI 는 Phase 5 에서 들어온다. 그때까지는 임시
 * 배회 드라이버가 그 자리를 채운다.
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
    private var enemySpawnTimer: Float = 0f
    private var resultHoldRemaining: Float = 0f

    private lateinit var world: GameWorld
    private lateinit var match: MatchState

    private val drivers = HashMap<Int, WanderDriver>()

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

        drivers.clear()
        slotOfTank.clear()
        enemySpawnTimer = 0f
        resultHoldRemaining = 0f
        logicalWidth = stage.widthPx
        logicalHeight = stage.heightPx
        worldRenderer.bind(stage)

        for (slot in match.players) {
            spawnPlayer(slot)
        }
        repeat(minOf(match.maxActiveEnemies, stage.comSpawnBlocks.size)) { spawnEnemy() }

        Log.i(
            TAG,
            "스테이지 #$stageIndex: ${stage.blocksX}x${stage.blocksY} 블록(4:3), " +
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
        drivers[tank.id] = WanderDriver(Rng(seed + tank.id * DRIVER_SALT))
    }

    private fun spawnEnemy() {
        if (!match.canSpawnEnemy()) return
        val stage = world.stage
        val rng = Rng(seed + match.enemiesPending * ENEMY_SALT)
        val block = stage.comSpawnBlocks[rng.nextInt(stage.comSpawnBlocks.size)]

        val tank = world.spawnTank(
            faction = Tank.Faction.ENEMY,
            type = rollEnemyType(rng),
            colorSlot = -1,
            blockIndex = block,
            direction = Direction.DOWN,
        ) ?: return

        match.onEnemySpawned()
        drivers[tank.id] = WanderDriver(Rng(seed + tank.id * DRIVER_SALT))
    }

    /** COM 타입은 balance.json 의 가중치로 뽑는다. */
    private fun rollEnemyType(rng: Rng): Tank.Type {
        val mix = balance.enemyMix
        if (mix.isEmpty()) return Tank.Type.entries[rng.nextInt(Tank.Type.entries.size)]
        val total = mix.values.sum()
        var roll = rng.nextInt(total)
        for ((type, weight) in mix) {
            roll -= weight
            if (roll < 0) return type
        }
        return mix.keys.first()
    }

    // -----------------------------------------------------------------------

    fun update(tickSeconds: Float) {
        elapsedSeconds += tickSeconds

        for (tank in world.tanks) {
            if (!tank.alive) continue
            drivers[tank.id]?.update(world, tank, tickSeconds)
        }
        world.update(tickSeconds)
        match.update(tickSeconds)

        reapDestroyedTanks()
        respawnPlayers(tickSeconds)

        enemySpawnTimer -= tickSeconds
        if (enemySpawnTimer <= 0f && match.canSpawnEnemy()) {
            enemySpawnTimer = balance.rules.enemySpawnIntervalSeconds
            spawnEnemy()
        }

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
            drivers.remove(tank.id)
            slotOfTank.remove(tank.id)
            world.despawn(tank)
        }
    }

    private fun respawnPlayers(tickSeconds: Float) {
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
        hudRenderer.render(batch, viewport, match)
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

        override fun onBaseDestroyed() {
            match.onBaseDestroyed()
        }
    }

    /**
     * Phase 5 의 COM AI 자리를 채우는 임시 배회 드라이버.
     *
     * 벽에 막히면 방향을 바꾸고, 가끔 쏜다. 이동/충돌/파괴가 실제로 도는지
     * 확인하는 것이 목적이라 전술적 판단은 하지 않는다.
     */
    private class WanderDriver(private val rng: Rng) {
        private var decisionTimer = 0f
        private var lastX = Float.NaN
        private var lastY = Float.NaN

        fun update(world: GameWorld, tank: Tank, deltaSeconds: Float) {
            decisionTimer -= deltaSeconds

            // 움직이려는데 제자리면 막힌 것이다. 즉시 다른 방향을 고른다.
            val stuck = tank.moving &&
                !lastX.isNaN() &&
                kotlin.math.abs(tank.x - lastX) < STUCK_EPSILON &&
                kotlin.math.abs(tank.y - lastY) < STUCK_EPSILON

            if (decisionTimer <= 0f || stuck) {
                decisionTimer = rng.nextFloat(0.4f, 1.6f)
                world.steer(tank, rng.pick(Direction.VALUES))
                tank.moving = rng.chance(0.85f)
            }

            lastX = tank.x
            lastY = tank.y

            if (tank.canFire && rng.chance(FIRE_CHANCE_PER_TICK)) {
                world.fire(tank)
            }
        }

        private companion object {
            const val STUCK_EPSILON = 0.05f
            const val FIRE_CHANCE_PER_TICK = 0.02f
        }
    }

    private companion object {
        const val TAG = "BattleCity"
        const val DEFAULT_PLAYERS = 4
        const val DEFAULT_SEED = 20260819L
        const val DRIVER_SALT = 104729L
        const val ENEMY_SALT = 15485863L

        /** 승패가 갈린 뒤 결과를 보여 주는 시간. 결과 화면은 Phase 8 에서 붙는다. */
        const val RESULT_HOLD_SECONDS = 3f
    }
}
