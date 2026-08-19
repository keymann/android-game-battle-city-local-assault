package com.kophas.battlecity.game

import android.util.Log
import com.kophas.battlecity.core.Constants
import com.kophas.battlecity.core.Direction
import com.kophas.battlecity.gameplay.GameWorld
import com.kophas.battlecity.gameplay.Tank
import com.kophas.battlecity.map.Rng
import com.kophas.battlecity.map.StageGenerator
import com.kophas.battlecity.render.GameAssets
import com.kophas.battlecity.render.SpriteBatch
import com.kophas.battlecity.render.SpriteCatalog
import com.kophas.battlecity.render.Viewport
import com.kophas.battlecity.render.WorldRenderer

/**
 * Phase 2 검증용 씬.
 *
 * 스테이지가 새로 구성될 때마다 맵을 seed 로 랜덤 생성하고, 탱크들이 실제로
 * 돌아다니며 벽을 부수고 서로를 맞히도록 굴린다. 확인하려는 것은 다음과 같다.
 *
 *  - TileMap 8종 타일이 원작 규칙대로 판정되는가 (물은 포탄만 통과, 숲은 은폐 등)
 *  - 탱크 이동 / 격자 스냅 / 얼음 미끄러짐
 *  - 포탄 -> 벽돌 파괴 -> 폭발, 폭발성 프롭 연쇄
 *  - 본진 파괴 시 즉시 GAME OVER
 *  - 매 스테이지 맵이 실제로 달라지는가
 *
 * 조작 입력은 Phase 7, COM AI 는 Phase 5 에서 들어온다. 여기서는 그 자리를
 * 임시 배회 드라이버가 채운다.
 */
class Phase2Scene(
    assets: GameAssets,
    private val playerCount: Int = DEFAULT_PLAYERS,
    startSeed: Long = DEFAULT_SEED,
) {
    private val catalog = SpriteCatalog(assets)
    private val generator = StageGenerator(assets.manifest)
    private val renderer = WorldRenderer(catalog)

    private var seed: Long = startSeed
    private var stageIndex: Int = 0
    private var elapsedSeconds: Float = 0f
    private var stageAgeSeconds: Float = 0f

    private lateinit var world: GameWorld
    private lateinit var rng: Rng
    private val drivers = HashMap<Int, WanderDriver>()

    var logicalWidth: Float = 0f
        private set

    var logicalHeight: Float = 0f
        private set

    init {
        buildStage()
    }

    /** 새 스테이지를 구성한다. seed 만 바뀌면 완전히 다른 맵이 나온다. */
    private fun buildStage() {
        val stage = generator.generate(seed, playerCount, stageIndex)
        world = GameWorld(stage)
        rng = Rng(seed xor STAGE_RNG_SALT)
        drivers.clear()
        stageAgeSeconds = 0f
        logicalWidth = stage.widthPx
        logicalHeight = stage.heightPx
        renderer.bind(stage)

        stage.playerSpawnBlocks.forEachIndexed { index, block ->
            spawn(Tank.Faction.PLAYER, Tank.Type.entries[index % 3], index, block, Direction.UP)
        }
        stage.comSpawnBlocks.forEachIndexed { index, block ->
            spawn(Tank.Faction.ENEMY, Tank.Type.entries[index % 3], -1, block, Direction.DOWN)
        }

        Log.i(
            TAG,
            "스테이지 #$stageIndex 생성: ${stage.blocksX}x${stage.blocksY} 블록(4:3), " +
                "바이옴=${stage.theme.biome} 도로=${stage.theme.roadStyle} " +
                "프롭=${stage.theme.propPalette} 해시=${stage.gridHash}",
        )
    }

    private fun spawn(
        faction: Tank.Faction,
        type: Tank.Type,
        colorSlot: Int,
        block: Int,
        direction: Direction,
    ) {
        val speed = when (type) {
            Tank.Type.ATTACK -> Constants.BLOCK_PX * 2.2f
            Tank.Type.DEFENSE -> Constants.BLOCK_PX * 1.6f
            Tank.Type.SPEED -> Constants.BLOCK_PX * 3.4f
        }
        val cooldown = when (type) {
            Tank.Type.SPEED -> 0.35f
            else -> 0.55f
        }
        val tank = world.spawnTank(faction, type, colorSlot, block, direction, speed, cooldown)
            ?: return
        drivers[tank.id] = WanderDriver(Rng(seed + tank.id * DRIVER_SALT))
    }

    // -----------------------------------------------------------------------

    fun update(tickSeconds: Float) {
        elapsedSeconds += tickSeconds
        stageAgeSeconds += tickSeconds

        for (tank in world.tanks) {
            if (!tank.alive) continue
            drivers[tank.id]?.update(world, tank, tickSeconds)
        }
        world.update(tickSeconds)

        // 죽은 탱크는 잠시 뒤 같은 스폰에서 다시 나온다.
        val dead = world.tanks.filter { !it.alive }
        for (tank in dead) {
            drivers.remove(tank.id)
            world.despawn(tank)
        }
        if (dead.isNotEmpty()) respawnMissing()

        val livePlayers = world.tanks.count { it.faction == Tank.Faction.PLAYER && it.alive }
        if (world.gameOver || stageAgeSeconds > STAGE_SECONDS || livePlayers == 0) {
            stageIndex++
            seed = Rng.advanceSeed(seed)
            buildStage()
        }
    }

    private fun respawnMissing() {
        val stage = world.stage
        val players = world.tanks.count { it.faction == Tank.Faction.PLAYER }
        val enemies = world.tanks.count { it.faction == Tank.Faction.ENEMY }

        if (players < stage.playerSpawnBlocks.size) {
            val index = players
            spawn(
                Tank.Faction.PLAYER,
                Tank.Type.entries[index % 3],
                index,
                stage.playerSpawnBlocks[index],
                Direction.UP,
            )
        }
        if (enemies < stage.comSpawnBlocks.size) {
            val index = enemies
            spawn(
                Tank.Faction.ENEMY,
                Tank.Type.entries[index % 3],
                -1,
                stage.comSpawnBlocks[index],
                Direction.DOWN,
            )
        }
    }

    fun render(batch: SpriteBatch, viewport: Viewport) {
        renderer.render(world, batch, viewport, elapsedSeconds)
    }

    /**
     * Phase 5 의 COM AI 자리를 채우는 임시 배회 드라이버.
     *
     * 벽에 막히면 방향을 바꾸고, 가끔 쏜다. 이동/충돌/파괴가 실제로 도는지
     * 눈으로 확인하는 것이 목적이라 전술적 판단은 하지 않는다.
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
        const val STAGE_RNG_SALT = 0x5DEECE66DL
        const val DRIVER_SALT = 104729L

        /** 검증용으로 일정 시간이 지나면 다음 스테이지를 랜덤 생성한다. */
        const val STAGE_SECONDS = 25f
    }
}
