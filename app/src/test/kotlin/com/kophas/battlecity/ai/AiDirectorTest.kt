package com.kophas.battlecity.ai

import com.kophas.battlecity.ai.AiFixture.readyTank
import com.kophas.battlecity.core.Constants
import com.kophas.battlecity.gameplay.GameWorld
import com.kophas.battlecity.gameplay.MatchState
import com.kophas.battlecity.gameplay.Tank
import com.kophas.battlecity.map.TileType
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** COM 생성 규칙과 생성 위치. (계획서 §9, §11, §44.2) */
class AiDirectorTest {

    private val tick = Constants.TICK_SECONDS

    private fun directorFor(world: GameWorld, seed: Long = 11L): AiDirector =
        AiDirector(AiFixture.balance, AiFixture.settings, seed).also { it.bind(world) }

    private fun blockOf(world: GameWorld, tank: Tank): Int {
        val bx = (tank.x / Constants.BLOCK_PX).toInt()
        val by = (tank.y / Constants.BLOCK_PX).toInt()
        return by * world.stage.blocksX + bx
    }

    // --- 총량 (계획서 §9) --------------------------------------------------

    @Test
    fun `총 COM 수는 플레이어 수 곱하기 스무 기다`() {
        for (players in 2..4) {
            val match = MatchState(players, AiFixture.balance)
            assertEquals(players * 20, match.totalEnemies)
        }
    }

    @Test
    fun `총량을 넘겨 내보내지 않는다`() {
        val world = AiFixture.world()
        val match = MatchState(2, AiFixture.balance)
        val director = directorFor(world)

        var spawned = 0
        repeat(60 * 200) {
            director.update(world, match, tick)
            // 나온 COM 을 곧바로 치워 자리를 비운다. 총량 규칙만 보기 위해서다.
            for (tank in world.tanks.filter { it.faction == Tank.Faction.ENEMY }) {
                spawned++
                director.detach(tank.id)
                match.onEnemyDestroyed(-1)
                world.despawn(tank)
            }
            world.update(tick)
        }

        assertEquals(match.totalEnemies, spawned)
        assertEquals(0, match.enemiesPending)
    }

    @Test
    fun `동시에 존재하는 COM 이 한도를 넘지 않는다`() {
        // 총량과 동시 출현 수는 분리한다. (계획서 §44.2)
        val world = AiFixture.world()
        val match = MatchState(2, AiFixture.balance)
        val director = directorFor(world)

        var peak = 0
        repeat(60 * 120) {
            director.update(world, match, tick)
            world.update(tick)
            peak = maxOf(peak, match.enemiesActive)
            assertTrue("동시 ${match.enemiesActive}기", match.enemiesActive <= match.maxActiveEnemies)
        }
        assertTrue("한 기도 안 나왔다", peak > 0)
    }

    @Test
    fun `나온 COM 에는 AI 가 붙는다`() {
        val world = AiFixture.world()
        val match = MatchState(2, AiFixture.balance)
        val director = directorFor(world)

        val tank = director.spawnEnemy(world, match)
        assertNotNull(tank)
        assertNotNull("AI 가 없으면 가만히 서 있는다", director.aiOf(tank!!.id))
        assertEquals(Tank.Faction.ENEMY, tank.faction)
    }

    // --- 생성 위치 (계획서 §11) --------------------------------------------

    @Test
    fun `장애물 안에는 내보내지 않는다`() {
        // 상단 좌 / 중앙 / 우 세 지점 중 좌·중앙을 강철로 막는다.
        val stage = AiFixture.stage(comSpawnBlocks = intArrayOf(0, 6, 11)) { set ->
            for (cx in 0 until 2) for (cy in 0 until 2) set(cx, cy, TileType.STEEL)
            for (cx in 12 until 14) for (cy in 0 until 2) set(cx, cy, TileType.STEEL)
        }
        val world = AiFixture.world(stage)
        val match = MatchState(2, AiFixture.balance)
        val director = directorFor(world)

        repeat(5) {
            val tank = director.spawnEnemy(world, match) ?: return@repeat
            assertEquals("막히지 않은 지점만 써야 한다", 11, blockOf(world, tank))
            director.detach(tank.id)
            match.onEnemyDestroyed(-1)
            world.despawn(tank)
        }
    }

    @Test
    fun `플레이어가 가까운 지점은 피한다`() {
        val stage = AiFixture.stage(comSpawnBlocks = intArrayOf(0, 11))
        val world = AiFixture.world(stage)
        val match = MatchState(2, AiFixture.balance)
        val director = directorFor(world)

        // 왼쪽 지점 코앞에 플레이어를 세운다. 최소 거리는 5블록이다.
        world.readyTank(1, 1, faction = Tank.Faction.PLAYER)

        repeat(5) {
            val tank = director.spawnEnemy(world, match) ?: return@repeat
            assertEquals("플레이어에게서 먼 지점을 골라야 한다", 11, blockOf(world, tank))
            director.detach(tank.id)
            match.onEnemyDestroyed(-1)
            world.despawn(tank)
        }
    }

    @Test
    fun `모든 지점이 플레이어와 가까우면 거리 조건만 푼다`() {
        // 이 조건까지 지키다 아무도 못 나오면 판이 멈춘다. 겹침·장애물은 그대로 지킨다.
        val stage = AiFixture.stage(comSpawnBlocks = intArrayOf(3))
        val world = AiFixture.world(stage)
        val match = MatchState(2, AiFixture.balance)
        val director = directorFor(world)
        world.readyTank(3, 3, faction = Tank.Faction.PLAYER)

        val tank = director.spawnEnemy(world, match)
        assertNotNull("자리가 하나뿐이면 거리를 양보하고서라도 내보낸다", tank)
        assertEquals(3, blockOf(world, tank!!))
    }

    @Test
    fun `이미 탱크가 서 있는 지점에는 겹쳐 내보내지 않는다`() {
        val stage = AiFixture.stage(comSpawnBlocks = intArrayOf(3))
        val world = AiFixture.world(stage)
        val match = MatchState(2, AiFixture.balance)
        val director = directorFor(world)
        world.readyTank(3, 0) // 그 자리에 COM 이 이미 있다

        assertEquals(null, director.spawnEnemy(world, match))
    }

    @Test
    fun `본진에 붙은 지점에는 내보내지 않는다`() {
        // 본진 바로 위 블록을 유일한 스폰 지점으로 준다.
        val nearBase = AiFixture.stage().baseBlock - 12
        val world = AiFixture.world(AiFixture.stage(comSpawnBlocks = intArrayOf(nearBase)))
        val match = MatchState(2, AiFixture.balance)
        val director = directorFor(world)

        assertEquals(null, director.spawnEnemy(world, match))
    }

    @Test
    fun `자리를 못 찾으면 다음 틱에 다시 시도한다`() {
        val stage = AiFixture.stage(comSpawnBlocks = intArrayOf(3))
        val world = AiFixture.world(stage)
        val match = MatchState(2, AiFixture.balance)
        val director = directorFor(world)
        val blocker = world.readyTank(3, 0)

        repeat(60) {
            director.update(world, match, tick)
            world.update(tick)
        }
        assertTrue("미룬 횟수가 쌓여야 한다", director.deferredSpawns > 0)
        assertEquals(0, match.enemiesActive)

        // 길이 열리면 그제서야 나온다.
        director.detach(blocker.id)
        world.despawn(blocker)
        director.update(world, match, tick)
        assertEquals(1, match.enemiesActive)
    }

    // --- 결정론 (계획서 §41-4) ---------------------------------------------

    @Test
    fun `같은 seed 면 같은 순서로 같은 자리에서 나온다`() {
        fun play(): List<String> {
            val world = AiFixture.world()
            val match = MatchState(2, AiFixture.balance)
            val director = directorFor(world, seed = 2026L)
            val log = ArrayList<String>()
            repeat(60 * 30) {
                director.update(world, match, tick)
                for (tank in world.tanks.filter { it.faction == Tank.Faction.ENEMY }) {
                    log += "${blockOf(world, tank)}:${tank.type}"
                    director.detach(tank.id)
                    match.onEnemyDestroyed(-1)
                    world.despawn(tank)
                }
                world.update(tick)
            }
            return log
        }
        val first = play()
        assertTrue("기록이 없다", first.isNotEmpty())
        assertEquals(first, play())
    }

    @Test
    fun `타입 비율은 balance json 의 가중치를 따른다`() {
        val world = AiFixture.world()
        val match = MatchState(4, AiFixture.balance)
        val director = directorFor(world)
        val counts = HashMap<Tank.Type, Int>()

        repeat(60 * 300) {
            director.update(world, match, tick)
            for (tank in world.tanks.filter { it.faction == Tank.Faction.ENEMY }) {
                counts[tank.type] = (counts[tank.type] ?: 0) + 1
                director.detach(tank.id)
                match.onEnemyDestroyed(-1)
                world.despawn(tank)
            }
            world.update(tick)
        }

        // 80기 표본이라 정확한 비율까지는 못 본다. 세 타입이 모두 나오는지만 본다.
        assertEquals(match.totalEnemies, counts.values.sum())
        for (type in Tank.Type.entries) {
            assertTrue("$type 이 한 번도 안 나왔다", (counts[type] ?: 0) > 0)
        }
    }

    @Test
    fun `내보낸 COM 은 서로 겹치지 않는다`() {
        val world = AiFixture.world()
        val match = MatchState(4, AiFixture.balance)
        val director = directorFor(world)

        repeat(60 * 60) {
            director.update(world, match, tick)
            world.update(tick)
            val tanks = world.tanks.filter { it.alive }
            for (i in tanks.indices) {
                for (j in i + 1 until tanks.size) {
                    val a = tanks[i]
                    val b = tanks[j]
                    assertTrue(
                        "탱크 ${a.id} 와 ${b.id} 가 겹쳤다",
                        abs(a.x - b.x) >= Tank.SIZE - 0.5f || abs(a.y - b.y) >= Tank.SIZE - 0.5f,
                    )
                }
            }
        }
    }
}
