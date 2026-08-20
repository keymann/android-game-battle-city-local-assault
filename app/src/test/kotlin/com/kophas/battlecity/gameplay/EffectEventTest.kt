package com.kophas.battlecity.gameplay

import com.kophas.battlecity.ai.AiFixture
import com.kophas.battlecity.ai.AiFixture.readyTank
import com.kophas.battlecity.core.Constants
import com.kophas.battlecity.core.Direction
import com.kophas.battlecity.map.TileType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 무엇이 일어났는지 알리는 신호와 연출. (계획서 §34 사운드, Phase 8 이펙트)
 *
 * 소리와 진동은 이 신호에 붙는다. 신호가 안 나오면 화면은 멀쩡한데 소리만 조용해서
 * 원인을 찾기 어렵다. 여기서 신호 자체를 붙잡는다.
 */
class EffectEventTest {

    private class Spy : GameWorld.Listener {
        val fired = ArrayList<Int>()
        val spawned = ArrayList<Int>()
        val steelHits = ArrayList<Pair<Float, Float>>()
        var brickDestroyed = 0
        var projectileHits = 0
        var baseDestroyed = 0
        val specials = ArrayList<BalanceConfig.Special>()

        override fun onFired(tank: Tank) {
            fired += tank.id
        }

        override fun onTankSpawned(tank: Tank) {
            spawned += tank.id
        }

        override fun onSteelHit(x: Float, y: Float) {
            steelHits += x to y
        }

        override fun onBrickDestroyed(cellX: Int, cellY: Int) {
            brickDestroyed++
        }

        override fun onProjectileHit(x: Float, y: Float) {
            projectileHits++
        }

        override fun onSpecialActivated(tank: Tank, special: BalanceConfig.Special) {
            specials += special
        }

        override fun onBaseDestroyed() {
            baseDestroyed++
        }
    }

    private val spy = Spy()

    private fun world(paint: (set: (Int, Int, TileType) -> Unit) -> Unit = {}): GameWorld =
        AiFixture.world(AiFixture.stage(paint = paint)).also { it.listener = spy }

    private fun step(world: GameWorld, ticks: Int) {
        repeat(ticks) { world.update(Constants.TICK_SECONDS) }
    }

    // --- 스폰과 발사 ------------------------------------------------------

    @Test
    fun `탱크가 나오면 알린다`() {
        val world = world()
        val tank = world.readyTank(3, 3)
        assertEquals(listOf(tank.id), spy.spawned)
    }

    @Test
    fun `쏘면 알린다`() {
        val world = world()
        val tank = world.readyTank(3, 3, direction = Direction.RIGHT)
        world.fire(tank)
        assertEquals(listOf(tank.id), spy.fired)
    }

    @Test
    fun `쿨타임 중에는 알리지 않는다`() {
        // 소리가 연사 제한보다 자주 나면 실제보다 세게 쏘는 것처럼 들린다.
        val world = world()
        val tank = world.readyTank(3, 3, direction = Direction.RIGHT)
        world.fire(tank)
        world.fire(tank)
        assertEquals(1, spy.fired.size)
    }

    @Test
    fun `쏘면 총구 화염이 생긴다`() {
        val world = world()
        val tank = world.readyTank(3, 3, direction = Direction.RIGHT)
        world.fire(tank)

        val muzzle = world.explosions.active.filter { it.kind == Explosion.Kind.MUZZLE }
        assertEquals(1, muzzle.size)
        assertEquals("포신이 향한 쪽으로 뻗어야 한다", Direction.RIGHT, muzzle[0].direction)
    }

    @Test
    fun `총구 화염은 포신 끝에서 난다`() {
        val world = world()
        val tank = world.readyTank(3, 3, direction = Direction.RIGHT)
        world.fire(tank)

        val muzzle = world.explosions.active.first { it.kind == Explosion.Kind.MUZZLE }
        val center = muzzle.x + muzzle.size * 0.5f
        assertTrue("차체 오른쪽 밖에 있어야 한다", center > tank.centerX)
    }

    // --- 강철과 벽돌 ------------------------------------------------------

    @Test
    fun `강철에 튕기면 따로 알린다`() {
        // 부순 것과 튕긴 것은 다음에 어디를 노려야 하는지가 다르다.
        val world = world { set ->
            for (cy in 0 until 18) set(9, cy, TileType.STEEL)
        }
        val tank = world.readyTank(3, 3, direction = Direction.RIGHT)
        world.fire(tank)
        step(world, 60)

        assertEquals(1, spy.steelHits.size)
        assertEquals("강철은 부서지지 않는다", 0, spy.brickDestroyed)
        assertEquals("튕긴 것을 일반 피격으로 세지 않는다", 0, spy.projectileHits)
    }

    @Test
    fun `강철에 튕기면 튕김 연출이 나온다`() {
        val world = world { set ->
            for (cy in 0 until 18) set(9, cy, TileType.STEEL)
        }
        val tank = world.readyTank(3, 3, direction = Direction.RIGHT)
        world.fire(tank)

        // 튕김 연출은 짧다. 다 지나간 뒤에 찾으면 이미 사라지고 없다.
        var seen = false
        repeat(60) {
            world.update(Constants.TICK_SECONDS)
            if (world.explosions.active.any { it.kind == Explosion.Kind.STEEL_HIT }) seen = true
        }
        assertTrue("튕김 연출이 한 번도 나오지 않았다", seen)
    }

    @Test
    fun `벽돌은 부서졌다고 알린다`() {
        val world = world { set ->
            for (cy in 0 until 18) set(9, cy, TileType.BRICK)
        }
        val tank = world.readyTank(3, 3, direction = Direction.RIGHT)
        world.fire(tank)
        step(world, 60)

        assertTrue(spy.brickDestroyed > 0)
        assertEquals(0, spy.steelHits.size)
    }

    // --- 특수기와 본진 ----------------------------------------------------

    @Test
    fun `특수기를 쓰면 무엇을 썼는지 알린다`() {
        val world = world()
        val tank = world.readyTank(3, 3, faction = Tank.Faction.PLAYER)
        tank.special = BalanceConfig.Special.SHIELD
        tank.specialDuration = 3f
        tank.specialCooldown = 5f
        tank.specialCooldownRemaining = 0f

        assertTrue(world.activateSpecial(tank))
        assertEquals(listOf(BalanceConfig.Special.SHIELD), spy.specials)
    }

    @Test
    fun `본진이 부서지면 한 번만 알린다`() {
        val world = world()
        val (baseX, baseY) = world.baseCellCenter()
        world.map.damageCell(world.map.toCellX(baseX), world.map.toCellY(baseY), piercing = false)
        step(world, 30)

        assertEquals(1, spy.baseDestroyed)
    }
}
