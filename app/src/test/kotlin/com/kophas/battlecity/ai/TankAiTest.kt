package com.kophas.battlecity.ai

import com.kophas.battlecity.ai.AiFixture.readyTank
import com.kophas.battlecity.core.Constants
import com.kophas.battlecity.core.Direction
import com.kophas.battlecity.gameplay.GameWorld
import com.kophas.battlecity.gameplay.Tank
import com.kophas.battlecity.map.Rng
import com.kophas.battlecity.map.TileType
import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** COM 상태 머신. (계획서 §8, §10) */
class TankAiTest {

    private val attack = AiFixture.settings.profileOf(Tank.Type.ATTACK)
    private val defense = AiFixture.settings.profileOf(Tank.Type.DEFENSE)
    private val speed = AiFixture.settings.profileOf(Tank.Type.SPEED)

    private fun aiFor(world: GameWorld, seed: Long = 7L): TankAi =
        TankAi(NavGrid(world.map), AiFixture.settings, Rng(seed))

    private fun drive(world: GameWorld, pairs: List<Pair<TankAi, Tank>>, ticks: Int) {
        AiFixture.run(world, ticks) { dt ->
            for ((ai, tank) in pairs) if (tank.alive) ai.update(world, tank, dt)
        }
    }

    private fun distance(a: Tank, b: Tank): Float =
        hypot(a.centerX - b.centerX, a.centerY - b.centerY)

    // --- 등장 -------------------------------------------------------------

    @Test
    fun `등장 무적 동안에는 움직이지도 쏘지도 않는다`() {
        val world = AiFixture.world()
        val com = world.readyTank(2, 2)
        com.spawnGuardRemaining = 1.5f
        val player = world.readyTank(2, 6, faction = Tank.Faction.PLAYER)
        val ai = aiFor(world)
        ai.attach(com, attack, attacksBase = false)

        val startX = com.x
        val startY = com.y
        drive(world, listOf(ai to com), 30)

        assertEquals(TankAi.State.SPAWN, ai.state)
        assertEquals(startX, com.x, 0.001f)
        assertEquals(startY, com.y, 0.001f)
        assertTrue(world.projectiles.active.isEmpty())
        assertTrue(player.alive)
    }

    // --- 사격 -------------------------------------------------------------

    @Test
    fun `사선이 열리면 반응 시간을 두고 쏜다`() {
        val world = AiFixture.world()
        val com = world.readyTank(2, 3)
        com.moveSpeed = 0f
        world.readyTank(7, 3, faction = Tank.Faction.PLAYER)
        val ai = aiFor(world)
        ai.attach(com, attack, attacksBase = false)

        // 반응 시간(0.15초) 안에는 아직 방아쇠를 당기지 않는다.
        drive(world, listOf(ai to com), 5)
        assertEquals(TankAi.State.ATTACK, ai.state)
        assertTrue("반응 시간 전에 쏘면 안 된다", world.projectiles.active.isEmpty())

        drive(world, listOf(ai to com), 20)
        assertTrue("반응 시간이 지나면 쏜다", world.projectiles.active.isNotEmpty())
        assertEquals(Direction.RIGHT, com.direction)
    }

    @Test
    fun `축이 어긋나면 쏘지 않는다`() {
        val world = AiFixture.world()
        val com = world.readyTank(2, 3)
        com.moveSpeed = 0f
        // 대각선. 포탄은 4방향으로만 날아가므로 맞힐 수가 없다.
        world.readyTank(7, 6, faction = Tank.Faction.PLAYER)
        val ai = aiFor(world)
        ai.attach(com, attack, attacksBase = false)

        drive(world, listOf(ai to com), 60)
        assertTrue(world.projectiles.active.isEmpty())
    }

    @Test
    fun `강철이 가로막으면 쏘지 않는다`() {
        val stage = AiFixture.stage { set ->
            for (cy in 0 until 18) set(9, cy, TileType.STEEL)
        }
        val world = AiFixture.world(stage)
        val com = world.readyTank(2, 3)
        com.moveSpeed = 0f
        world.readyTank(7, 3, faction = Tank.Faction.PLAYER)
        val ai = aiFor(world)
        ai.attach(com, attack, attacksBase = false)

        drive(world, listOf(ai to com), 60)
        assertTrue(world.projectiles.active.isEmpty())
    }

    @Test
    fun `벽돌은 부수는 성향만 쏜다`() {
        fun fired(profile: AiProfile): Boolean {
            val stage = AiFixture.stage { set ->
                for (cy in 0 until 18) set(9, cy, TileType.BRICK)
            }
            val world = AiFixture.world(stage)
            val com = world.readyTank(2, 3)
            com.moveSpeed = 0f
            world.readyTank(7, 3, faction = Tank.Faction.PLAYER)
            val ai = aiFor(world)
            ai.attach(com, profile, attacksBase = false)
            drive(world, listOf(ai to com), 60)
            val columnIntact = (0 until 18).all { world.tileAt(9, it) == TileType.BRICK }
            return world.projectiles.active.isNotEmpty() || !columnIntact
        }

        assertTrue("공격형은 벽돌을 부수며 간다", fired(attack))
        assertTrue("스피드형은 벽돌을 두고 돌아간다", !fired(speed.copy(fireRangeBlocks = 8f)))
    }

    @Test
    fun `사선에 아군이 있으면 쏘지 않는다`() {
        val world = AiFixture.world()
        val com = world.readyTank(2, 3)
        com.moveSpeed = 0f
        val ally = world.readyTank(4, 3)
        ally.moveSpeed = 0f
        world.readyTank(7, 3, faction = Tank.Faction.PLAYER)
        val ai = aiFor(world)
        ai.attach(com, attack, attacksBase = false)

        drive(world, listOf(ai to com), 60)
        assertTrue(world.projectiles.active.isEmpty())
        assertTrue(ally.alive)
    }

    @Test
    fun `숲에 숨은 탱크는 코앞이 아니면 보이지 않는다`() {
        val stage = AiFixture.stage { set ->
            for (cx in 12 until 18) {
                for (cy in 4 until 10) set(cx, cy, TileType.FOREST)
            }
        }
        val world = AiFixture.world(stage)
        val com = world.readyTank(2, 3)
        com.moveSpeed = 0f
        world.readyTank(7, 3, faction = Tank.Faction.PLAYER) // 셀 14~15, 6~7 = 숲 속
        val ai = aiFor(world)
        ai.attach(com, attack, attacksBase = false)

        drive(world, listOf(ai to com), 60)
        assertEquals(-1, ai.targetTankId)
        assertTrue(world.projectiles.active.isEmpty())
    }

    // --- 추적 -------------------------------------------------------------

    @Test
    fun `스피드형은 플레이어를 끝까지 쫓는다`() {
        val world = AiFixture.world()
        val com = world.readyTank(1, 1)
        val player = world.readyTank(9, 6, faction = Tank.Faction.PLAYER)
        player.moveSpeed = 0f
        val ai = aiFor(world)
        ai.attach(com, speed, attacksBase = false)

        val before = distance(com, player)
        drive(world, listOf(ai to com), 240)
        val after = distance(com, player)

        assertTrue("$before -> $after 로 좁혀져야 한다", after < before - Constants.BLOCK_PX)
    }

    @Test
    fun `벽을 돌아서 쫓아간다`() {
        // 가운데 강철 벽. 아래쪽 두 줄만 열려 있다. 직선으로는 못 가고 돌아가야 한다.
        val stage = AiFixture.stage { set ->
            for (cy in 0 until 14) set(9, cy, TileType.STEEL)
        }
        val world = AiFixture.world(stage)
        val com = world.readyTank(2, 2)
        val player = world.readyTank(8, 2, faction = Tank.Faction.PLAYER)
        player.moveSpeed = 0f
        val ai = aiFor(world)
        ai.attach(com, speed, attacksBase = false)

        val before = distance(com, player)
        drive(world, listOf(ai to com), 420)
        assertTrue("벽을 돌아 접근해야 한다", distance(com, player) < before - Constants.BLOCK_PX)
    }

    // --- 본진 (계획서 §8.2, §10) -------------------------------------------

    @Test
    fun `본진 임무를 받으면 본진 앞마당까지 밀고 간다`() {
        val world = AiFixture.world()
        val com = world.readyTank(1, 1)
        val ai = aiFor(world)
        ai.attach(com, defense.copy(baseFocus = 1f), attacksBase = true)
        assertTrue(ai.missionBase)

        val (baseX, baseY) = world.baseCellCenter()
        val before = hypot(com.centerX - baseX, com.centerY - baseY)
        drive(world, listOf(ai to com), 480)
        val after = hypot(com.centerX - baseX, com.centerY - baseY)

        assertTrue("$before -> $after", after < before - Constants.BLOCK_PX)
        assertTrue(
            "본진 앞마당에 붙으면 자리를 잡는다",
            ai.state == TankAi.State.SIEGE_BASE || ai.state == TankAi.State.ATTACK,
        )
    }

    @Test
    fun `본진 임무 COM 은 눈앞의 적에게 끌려다니지 않는다`() {
        // 계획서 §10 의 우선순위. 본진이 먼저다.
        val world = AiFixture.world()
        val com = world.readyTank(1, 1)
        val bait = world.readyTank(1, 5, faction = Tank.Faction.PLAYER)
        bait.moveSpeed = 0f
        val ai = aiFor(world)
        ai.attach(com, defense.copy(baseFocus = 1f), attacksBase = true)

        val (baseX, baseY) = world.baseCellCenter()
        val before = hypot(com.centerX - baseX, com.centerY - baseY)
        drive(world, listOf(ai to com), 300)
        assertTrue(hypot(com.centerX - baseX, com.centerY - baseY) < before - Constants.BLOCK_PX)
    }

    @Test
    fun `본진이 부서지면 남은 COM 은 플레이어를 노린다`() {
        // 본진이 부서지는 순간 판이 끝나므로(계획서 §15) 탱크는 더 움직이지 않는다.
        // 그래서 위치가 아니라 AI 가 어디를 목적지로 잡았는지를 본다.
        val world = AiFixture.world()
        val nav = NavGrid(world.map)
        val com = world.readyTank(1, 1)
        val player = world.readyTank(5, 2, faction = Tank.Faction.PLAYER)
        val ai = TankAi(nav, AiFixture.settings, Rng(7L))
        ai.attach(com, defense.copy(baseFocus = 1f), attacksBase = true)

        repeat(4) { ai.update(world, com, Constants.TICK_SECONDS) }
        val (baseX, baseY) = world.baseCellCenter()
        val baseNode = nav.nodeAt(
            baseX - Constants.BLOCK_PX * 0.5f,
            baseY - Constants.BLOCK_PX * 0.5f,
        )
        assertTrue("본진이 살아 있는 동안에는 본진 앞마당이 목적지다", ai.goalNode >= 0)
        assertTrue(
            "목적지 ${ai.goalNode} 가 본진 $baseNode 근처가 아니다",
            nodeDistance(nav, ai.goalNode, baseNode) <= 4,
        )

        world.map.damageCell(world.map.toCellX(baseX), world.map.toCellY(baseY), piercing = false)
        assertTrue(world.map.baseDestroyed)

        repeat(120) { ai.update(world, com, Constants.TICK_SECONDS) }
        assertEquals(nav.nodeAt(player.x, player.y), ai.goalNode)
    }

    private fun nodeDistance(nav: NavGrid, a: Int, b: Int): Int = kotlin.math.max(
        kotlin.math.abs(nav.nodeX(a) - nav.nodeX(b)),
        kotlin.math.abs(nav.nodeY(a) - nav.nodeY(b)),
    )

    // --- 본진 방어 (계획서 §10) --------------------------------------------

    @Test
    fun `본진이 위협받으면 지키는 쪽은 요격하러 간다`() {
        val world = AiFixture.world()
        val guard = world.readyTank(2, 2, faction = Tank.Faction.PLAYER)
        // 본진(하단 중앙) 코앞까지 들어온 침입자.
        val raider = world.readyTank(6, 5)
        raider.moveSpeed = 0f
        val ai = aiFor(world)
        ai.attach(guard, attack, attacksBase = false, defendsBase = true)

        val before = distance(guard, raider)
        drive(world, listOf(ai to guard), 30)

        assertTrue(
            "요격하러 가야 한다: ${ai.state}",
            ai.state == TankAi.State.DEFEND_BASE || ai.state == TankAi.State.ATTACK,
        )
        assertEquals(raider.id, ai.targetTankId)

        // 오래 굴리면 침입자를 잡아 버린다. 그것도 요격이 됐다는 뜻이다.
        drive(world, listOf(ai to guard), 240)
        assertTrue(
            "붙거나 잡거나 둘 중 하나여야 한다",
            !raider.alive || distance(guard, raider) < before,
        )
    }

    @Test
    fun `본진에서 먼 적은 굳이 쫓지 않는다`() {
        val world = AiFixture.world()
        val guard = world.readyTank(2, 2, faction = Tank.Faction.PLAYER)
        // 본진 반경(6블록) 밖. 지키는 쪽 입장에서 급한 상대가 아니다.
        world.readyTank(1, 1)
        val ai = aiFor(world)
        ai.attach(guard, attack, attacksBase = false, defendsBase = true)

        drive(world, listOf(ai to guard), 60)
        assertNotEquals(TankAi.State.DEFEND_BASE, ai.state)
    }

    @Test
    fun `여럿이 몰려오면 본진에 가장 가까운 놈부터 막는다`() {
        val world = AiFixture.world()
        val guard = world.readyTank(2, 2, faction = Tank.Faction.PLAYER)
        val far = world.readyTank(3, 4)
        val near = world.readyTank(6, 6)
        far.moveSpeed = 0f
        near.moveSpeed = 0f
        val ai = aiFor(world)
        ai.attach(guard, attack, attacksBase = false, defendsBase = true)

        drive(world, listOf(ai to guard), 10)
        assertEquals("나에게 가까운 쪽이 아니라 본진에 가까운 쪽이다", near.id, ai.targetTankId)
        assertTrue(far.alive)
    }

    @Test
    fun `본진을 노리지도 지키지도 않는 쪽은 본진을 건드리지 않는다`() {
        // 아군 포탄으로도 본진은 부서진다. (계획서 §15.2)
        val world = AiFixture.world()
        val guard = world.readyTank(6, 4, faction = Tank.Faction.PLAYER)
        val ai = aiFor(world)
        ai.attach(guard, attack, attacksBase = false, defendsBase = true)

        drive(world, listOf(ai to guard), 600)
        assertTrue("아군이 본진을 부수면 그대로 GAME OVER 다", !world.map.baseDestroyed)
    }

    // --- 회피 -------------------------------------------------------------

    @Test
    fun `체력이 바닥나면 물러난다`() {
        val world = AiFixture.world()
        val com = world.readyTank(5, 5)
        com.hp = (com.maxHp * 0.15f).toInt()
        val player = world.readyTank(5, 2, faction = Tank.Faction.PLAYER)
        player.moveSpeed = 0f
        val ai = aiFor(world)
        ai.attach(com, defense, attacksBase = false)

        val before = distance(com, player)
        drive(world, listOf(ai to com), 60)

        assertEquals(TankAi.State.AVOID, ai.state)
        assertTrue("멀어져야 한다", distance(com, player) > before)
    }

    @Test
    fun `물러날 줄 모르는 성향은 체력이 낮아도 밀어붙인다`() {
        val world = AiFixture.world()
        val com = world.readyTank(5, 5)
        com.hp = (com.maxHp * 0.1f).toInt()
        val player = world.readyTank(5, 2, faction = Tank.Faction.PLAYER)
        player.moveSpeed = 0f
        val ai = aiFor(world)
        ai.attach(com, attack, attacksBase = false) // retreatHpRatio = 0

        drive(world, listOf(ai to com), 60)
        assertNotEquals(TankAi.State.AVOID, ai.state)
    }

    // --- 배회 -------------------------------------------------------------

    @Test
    fun `목표가 없으면 배회한다`() {
        val world = AiFixture.world()
        val com = world.readyTank(3, 3)
        val ai = aiFor(world)
        ai.attach(com, attack, attacksBase = false)

        val startX = com.x
        val startY = com.y
        drive(world, listOf(ai to com), 180)

        assertEquals(TankAi.State.PATROL, ai.state)
        assertTrue(
            "가만히 서 있으면 안 된다",
            hypot(com.x - startX, com.y - startY) > Constants.BLOCK_PX,
        )
    }

    @Test
    fun `배회하다 맵 밖으로 나가지 않는다`() {
        val world = AiFixture.world()
        val com = world.readyTank(1, 1)
        val ai = aiFor(world)
        ai.attach(com, speed, attacksBase = false)

        drive(world, listOf(ai to com), 600)
        assertTrue(com.x >= 0f && com.x <= world.map.widthPx - Tank.SIZE)
        assertTrue(com.y >= 0f && com.y <= world.map.heightPx - Tank.SIZE)
    }

    // --- 결정론 (계획서 §41-4) ---------------------------------------------

    @Test
    fun `같은 seed 면 같은 판이 나온다`() {
        fun play(): Triple<Float, Float, TankAi.State> {
            val world = AiFixture.world()
            val com = world.readyTank(1, 1)
            val player = world.readyTank(8, 5, faction = Tank.Faction.PLAYER)
            val playerAi = aiFor(world, seed = 99L)
            playerAi.attach(player, attack, attacksBase = false)
            val ai = aiFor(world, seed = 42L)
            ai.attach(com, speed, attacksBase = true)
            drive(world, listOf(ai to com, playerAi to player), 300)
            return Triple(com.x, com.y, ai.state)
        }
        assertEquals(play(), play())
    }
}
