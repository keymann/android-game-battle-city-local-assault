package com.kophas.battlecity.ai

import com.kophas.battlecity.core.Constants
import com.kophas.battlecity.core.Direction
import com.kophas.battlecity.gameplay.BalanceConfig
import com.kophas.battlecity.gameplay.GameWorld
import com.kophas.battlecity.gameplay.MatchState
import com.kophas.battlecity.gameplay.Tank
import com.kophas.battlecity.map.Rng
import kotlin.math.abs

/**
 * COM 을 내보내고 모든 탱크의 AI 를 굴린다. (계획서 §9, §10, §11)
 *
 * 총 COM 수와 동시 출현 수를 나누는 규칙은 [MatchState] 가 이미 들고 있다.
 * 여기서는 **어디에 내보낼지** 를 정한다. (계획서 §11)
 *
 * 안드로이드 의존이 없고 난수는 전부 [Rng] 라, 같은 seed 면 같은 판이 나온다.
 * Phase 6 에서 Host 가 이 객체를 권위 있게 굴린다. (계획서 §41-4)
 */
class AiDirector(
    private val balance: BalanceConfig,
    val settings: AiSettings,
    seed: Long,
) {
    private val rng = Rng(seed xor SEED_SALT)

    private lateinit var nav: NavGrid

    private val agents = HashMap<Int, TankAi>()
    private val idle = ArrayDeque<TankAi>()

    private var spawnTimer = 0f
    private var spawnSequence = 0

    /** 스폰 자리를 못 찾아 미룬 횟수. 로그로 확인하려고 센다. */
    var deferredSpawns: Int = 0
        private set

    fun bind(world: GameWorld) {
        nav = NavGrid(world.map)
        agents.clear()
        idle.clear()
        spawnTimer = 0f
        spawnSequence = 0
        deferredSpawns = 0
    }

    /** 벽이 부서지면 길이 바뀐다. [GameWorld.Listener] 에서 불러 준다. */
    fun onMapChanged() {
        if (::nav.isInitialized) nav.markDirty()
    }

    fun aiOf(tankId: Int): TankAi? = agents[tankId]

    // -----------------------------------------------------------------------

    /**
     * 탱크 한 대에 AI 를 붙인다.
     *
     * 플레이어 탱크에도 붙는다. Phase 7 에서 사람이 조종간을 잡으면 그 자리에
     * 사람 입력이 들어올 뿐, 규칙은 하나도 달라지지 않는다.
     */
    fun attach(tank: Tank) {
        val ai = idle.removeLastOrNull() ?: TankAi(nav, settings, Rng(rng.nextLong()))
        ai.attach(
            tank = tank,
            profile = settings.profileOf(tank.type),
            // 본진은 플레이어가 지키는 대상이다. 플레이어 AI 가 노릴 이유가 없다.
            // (계획서 §14, §15.2 — 아군 포탄으로도 부서지므로 더더욱)
            attacksBase = tank.faction == Tank.Faction.ENEMY,
            defendsBase = tank.faction == Tank.Faction.PLAYER,
        )
        agents[tank.id] = ai
    }

    fun detach(tankId: Int) {
        agents.remove(tankId)?.let { idle += it }
    }

    // -----------------------------------------------------------------------

    fun update(world: GameWorld, match: MatchState, deltaSeconds: Float) {
        spawnTimer -= deltaSeconds
        if (spawnTimer <= 0f && match.canSpawnEnemy()) {
            if (spawnEnemy(world, match) != null) {
                spawnTimer = balance.rules.enemySpawnIntervalSeconds
            } else {
                // 자리가 없으면 다음 틱에 다시 본다. 간격을 새로 잡지 않는다.
                deferredSpawns++
            }
        }

        for (tank in world.tanks) {
            if (!tank.alive) continue
            agents[tank.id]?.update(world, tank, deltaSeconds)
        }
    }

    /** 판이 시작될 때 동시 출현 한도까지 한 번에 채운다. */
    fun fillInitialEnemies(world: GameWorld, match: MatchState) {
        while (match.canSpawnEnemy()) {
            if (spawnEnemy(world, match) == null) break
        }
        spawnTimer = balance.rules.enemySpawnIntervalSeconds
    }

    // -----------------------------------------------------------------------
    // COM 생성 (계획서 §11)
    // -----------------------------------------------------------------------

    /**
     * COM 한 기를 내보낸다. 자리를 못 찾으면 null 을 주고 다음 틱에 다시 시도한다.
     *
     * 계획서 §11 의 네 가지 검사를 그대로 한다.
     *   - 장애물 내부인가
     *   - 다른 탱크와 겹치는가
     *   - 플레이어와 너무 가까운가
     *   - 본진과 겹치는가
     *
     * 전부 만족하는 지점이 없으면 **플레이어 거리 조건만 풀고** 다시 본다.
     * 이 조건까지 지키다 아무도 못 나오면 판이 멈추기 때문이다. 나머지 셋은
     * 지키지 않으면 탱크가 벽에 끼거나 겹쳐서 버그처럼 보인다.
     */
    fun spawnEnemy(world: GameWorld, match: MatchState): Tank? {
        if (!match.canSpawnEnemy()) return null

        val stage = world.stage
        if (stage.comSpawnBlocks.isEmpty()) return null

        val order = rng.shuffled(stage.comSpawnBlocks.toList())
        val block = order.firstOrNull { isSpawnUsable(world, it, keepPlayerDistance = true) }
            ?: order.firstOrNull { isSpawnUsable(world, it, keepPlayerDistance = false) }
            ?: return null

        val typeRng = Rng(rng.nextLong())
        val tank = world.spawnTank(
            faction = Tank.Faction.ENEMY,
            type = rollEnemyType(typeRng),
            colorSlot = -1,
            blockIndex = block,
            direction = Direction.DOWN,
        ) ?: return null

        spawnSequence++
        match.onEnemySpawned()
        attach(tank)
        return tank
    }

    private fun isSpawnUsable(world: GameWorld, block: Int, keepPlayerDistance: Boolean): Boolean {
        val (px, py) = world.stage.blockToPx(block)

        // 장애물 내부인가
        if (world.map.blocksTank(px, py, Tank.SIZE, Tank.SIZE)) return false

        val centerX = px + Tank.SIZE * 0.5f
        val centerY = py + Tank.SIZE * 0.5f

        // 본진과 겹치는가
        val (baseX, baseY) = world.baseCellCenter()
        val baseGap = settings.spawn.minBaseDistanceBlocks * Constants.BLOCK_PX
        if (abs(centerX - baseX) < baseGap && abs(centerY - baseY) < baseGap) return false

        val clearance = settings.spawn.clearanceBlocks * Constants.BLOCK_PX
        val playerGap = settings.spawn.minPlayerDistanceBlocks * Constants.BLOCK_PX

        for (other in world.tanks) {
            if (!other.alive) continue
            val dx = abs(other.centerX - centerX)
            val dy = abs(other.centerY - centerY)

            // 다른 탱크와 겹치는가
            if (dx < clearance && dy < clearance) return false

            // 플레이어와 너무 가까운가
            if (keepPlayerDistance && other.faction == Tank.Faction.PLAYER &&
                dx < playerGap && dy < playerGap
            ) {
                return false
            }
        }
        return true
    }

    /** COM 타입은 balance.json 의 가중치로 뽑는다. (계획서 §8) */
    private fun rollEnemyType(rng: Rng): Tank.Type {
        val mix = balance.enemyMix
        if (mix.isEmpty()) return Tank.Type.entries[rng.nextInt(Tank.Type.entries.size)]
        val total = mix.values.sum()
        if (total <= 0) return mix.keys.first()
        var roll = rng.nextInt(total)
        for ((type, weight) in mix) {
            roll -= weight
            if (roll < 0) return type
        }
        return mix.keys.first()
    }

    private companion object {
        const val SEED_SALT = 0x5f3759dfL
    }
}
