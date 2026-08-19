package com.kophas.battlecity.gameplay

import com.kophas.battlecity.core.Constants
import com.kophas.battlecity.core.Direction
import com.kophas.battlecity.map.StageData
import com.kophas.battlecity.map.StageTheme
import com.kophas.battlecity.map.TileType
import com.kophas.battlecity.render.AssetSource
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 특수기 3종과 쿨타임. (계획서 §6) */
class SpecialMoveTest {

    private val tick = Constants.TICK_SECONDS

    private val assetsDir: File = sequenceOf(
        File("src/main/assets"),
        File("app/src/main/assets"),
    ).firstOrNull { it.isDirectory } ?: error("assets 디렉터리를 찾지 못했다")

    private val balance = BalanceConfig.load(AssetSource.ofDirectory(assetsDir))

    private fun emptyStage(blocksX: Int = 12, blocksY: Int = 9): StageData {
        val cellsX = blocksX * Constants.CELLS_PER_BLOCK
        val cellsY = blocksY * Constants.CELLS_PER_BLOCK
        val cells = ByteArray(cellsX * cellsY) { TileType.EMPTY.id }
        return StageData(
            blocksX = blocksX,
            blocksY = blocksY,
            cells = cells,
            cellSprite = ShortArray(cells.size) { -1 },
            spriteNames = arrayOf("town_052"),
            ground = ShortArray(blocksX * blocksY),
            groundNames = arrayOf("town_000"),
            decor = emptyList(),
            explosiveCells = emptySet(),
            baseBlock = (blocksY - 2) * blocksX + blocksX / 2,
            comSpawnBlocks = intArrayOf(0),
            playerSpawnBlocks = intArrayOf((blocksY - 1) * blocksX),
            theme = StageTheme(
                biome = StageTheme.Biome.GRASS,
                roadStyle = StageTheme.RoadStyle.NONE,
                structureDensity = 0f,
                waterWeight = 0f,
                iceWeight = 0f,
                forestWeight = 0f,
                propPalette = emptyList(),
                mirrorX = false,
            ),
            seed = 1L,
        )
    }

    private fun world(): GameWorld = GameWorld(emptyStage(), balance)

    private fun GameWorld.addTank(
        blockX: Int,
        blockY: Int,
        type: Tank.Type,
        direction: Direction = Direction.UP,
        faction: Tank.Faction = Tank.Faction.PLAYER,
    ): Tank {
        val tank = spawnTank(
            faction = faction,
            type = type,
            colorSlot = 0,
            blockIndex = blockY * stage.blocksX + blockX,
            direction = direction,
        )!!
        tank.spawnGuardRemaining = 0f
        return tank
    }

    // --- 공통 규칙 ---------------------------------------------------------

    @Test
    fun `탱크 타입마다 정해진 특수기를 가진다`() {
        val world = world()
        assertEquals(BalanceConfig.Special.PIERCING, world.addTank(1, 1, Tank.Type.ATTACK).special)
        assertEquals(BalanceConfig.Special.SHIELD, world.addTank(3, 1, Tank.Type.DEFENSE).special)
        assertEquals(BalanceConfig.Special.DASH, world.addTank(5, 1, Tank.Type.SPEED).special)
    }

    @Test
    fun `스폰 직후에는 바로 쓸 수 있다`() {
        val tank = world().addTank(3, 3, Tank.Type.SPEED)
        assertTrue(tank.canUseSpecial)
        assertEquals(1f, tank.specialReadyRatio, 1e-4f)
    }

    @Test
    fun `쿨타임 중에는 다시 쓸 수 없다`() {
        val world = world()
        val tank = world.addTank(3, 3, Tank.Type.SPEED)

        assertTrue(world.activateSpecial(tank))
        assertFalse("쿨타임 중이라 실패해야 한다", world.activateSpecial(tank))
        assertFalse(tank.canUseSpecial)
    }

    @Test
    fun `쿨타임이 지나면 다시 쓸 수 있다`() {
        val world = world()
        val tank = world.addTank(3, 3, Tank.Type.SPEED)
        world.activateSpecial(tank)

        val cooldownTicks = (tank.specialCooldown / tick).toInt() + 2
        repeat(cooldownTicks) { world.update(tick) }

        assertTrue(tank.canUseSpecial)
        assertTrue(world.activateSpecial(tank))
    }

    @Test
    fun `쿨타임 게이지는 0에서 1로 차오른다`() {
        val world = world()
        val tank = world.addTank(3, 3, Tank.Type.DEFENSE)
        world.activateSpecial(tank)
        assertEquals(0f, tank.specialReadyRatio, 0.02f)

        val half = (tank.specialCooldown / tick / 2).toInt()
        repeat(half) { world.update(tick) }
        assertEquals("절반쯤 차 있어야 한다", 0.5f, tank.specialReadyRatio, 0.05f)

        repeat(half + 5) { world.update(tick) }
        assertEquals(1f, tank.specialReadyRatio, 1e-4f)
    }

    @Test
    fun `죽은 탱크는 특수기를 쓸 수 없다`() {
        val world = world()
        val tank = world.addTank(3, 3, Tank.Type.SPEED)
        world.destroyTank(tank, killerId = -1)
        assertFalse(world.activateSpecial(tank))
    }

    // --- 공격형: 관통탄 (계획서 §6.1) --------------------------------------

    @Test
    fun `관통탄은 발사 쿨타임과 무관하게 나간다`() {
        val world = world()
        val tank = world.addTank(3, 5, Tank.Type.ATTACK, Direction.UP)

        // 먼저 일반 포탄을 쏴 발사 쿨타임을 걸어 둔다.
        assertNotNull(world.fire(tank))
        assertNull("발사 쿨타임 중", world.fire(tank))

        assertTrue("특수기는 별도 쿨타임을 쓴다", world.activateSpecial(tank))
        assertEquals(2, world.projectiles.activeCount)
        assertTrue("한 발은 관통탄이어야 한다", world.projectiles.active.any { it.piercing })
    }

    @Test
    fun `관통탄은 강철을 부순다`() {
        val world = world()
        val tank = world.addTank(3, 5, Tank.Type.ATTACK, Direction.UP)
        val steelX = 3 * Constants.CELLS_PER_BLOCK
        val steelY = 3 * Constants.CELLS_PER_BLOCK + 1
        world.map.setType(steelX, steelY, TileType.STEEL, 0)

        world.activateSpecial(tank)
        repeat(60) { world.update(tick) }

        assertEquals(TileType.EMPTY, world.map.typeAt(steelX, steelY))
    }

    @Test
    fun `일반 포탄은 같은 강철을 부수지 못한다`() {
        // 관통탄 테스트가 실제로 특수기 덕분인지 확인한다.
        val world = world()
        val tank = world.addTank(3, 5, Tank.Type.ATTACK, Direction.UP)
        val steelX = 3 * Constants.CELLS_PER_BLOCK
        val steelY = 3 * Constants.CELLS_PER_BLOCK + 1
        world.map.setType(steelX, steelY, TileType.STEEL, 0)

        world.fire(tank)
        repeat(60) { world.update(tick) }

        assertEquals(TileType.STEEL, world.map.typeAt(steelX, steelY))
    }

    @Test
    fun `관통탄은 탱크를 뚫고 지나간다`() {
        val world = world()
        val shooter = world.addTank(3, 6, Tank.Type.ATTACK, Direction.UP)
        val near = world.addTank(3, 4, Tank.Type.SPEED, faction = Tank.Faction.ENEMY)
        val far = world.addTank(3, 2, Tank.Type.SPEED, faction = Tank.Faction.ENEMY)
        val nearHp = near.hp
        val farHp = far.hp

        world.activateSpecial(shooter)
        repeat(90) { world.update(tick) }

        assertTrue("앞의 탱크가 맞아야 한다", near.hp < nearHp || !near.alive)
        assertTrue("뒤의 탱크도 맞아야 한다", far.hp < farHp || !far.alive)
    }

    @Test
    fun `일반 포탄은 첫 탱크에서 멈춘다`() {
        val world = world()
        val shooter = world.addTank(3, 6, Tank.Type.ATTACK, Direction.UP)
        world.addTank(3, 4, Tank.Type.SPEED, faction = Tank.Faction.ENEMY)
        val far = world.addTank(3, 2, Tank.Type.SPEED, faction = Tank.Faction.ENEMY)
        val farHp = far.hp

        world.fire(shooter)
        repeat(90) { world.update(tick) }

        assertEquals("뒤의 탱크는 멀쩡해야 한다", farHp, far.hp)
    }

    // --- 방어형: 방어막 (계획서 §6.2) --------------------------------------

    @Test
    fun `방어막은 받는 피해를 줄인다`() {
        val stats = balance.playerTanks.getValue(Tank.Type.DEFENSE)

        val plain = world()
        val target = plain.addTank(3, 3, Tank.Type.DEFENSE)
        val plainDamage = plain.applyDamage(target, attackPower = 3, attackerId = -1)

        val shielded = world()
        val guarded = shielded.addTank(3, 3, Tank.Type.DEFENSE)
        shielded.activateSpecial(guarded)
        val shieldedDamage = shielded.applyDamage(guarded, attackPower = 3, attackerId = -1)

        assertTrue("방어막이 켜져 있어야 한다", guarded.specialActive)
        assertTrue(
            "방어막 중 피해 $shieldedDamage 가 평소 $plainDamage 보다 적어야 한다",
            shieldedDamage < plainDamage,
        )
        assertEquals(stats.damageReduction, guarded.damageReduction, 1e-4f)
    }

    @Test
    fun `방어막은 지속 시간이 지나면 꺼진다`() {
        val world = world()
        val tank = world.addTank(3, 3, Tank.Type.DEFENSE)
        world.activateSpecial(tank)
        assertTrue(tank.specialActive)

        val ticks = (tank.specialDuration / tick).toInt() + 2
        repeat(ticks) { world.update(tick) }

        assertFalse(tank.specialActive)
        assertEquals("피해 감소가 원래대로 돌아가야 한다", 0f, tank.damageReduction, 1e-4f)
    }

    @Test
    fun `방어막이 꺼진 뒤에는 평소대로 맞는다`() {
        val world = world()
        val tank = world.addTank(3, 3, Tank.Type.DEFENSE)
        world.activateSpecial(tank)
        repeat((tank.specialDuration / tick).toInt() + 2) { world.update(tick) }

        val plain = world().addTank(3, 3, Tank.Type.DEFENSE)
        assertEquals(plain.damageReduction, tank.damageReduction, 1e-4f)
    }

    // --- 스피드형: 대시 (계획서 §6.3) --------------------------------------

    @Test
    fun `대시는 이동속도를 배수만큼 올린다`() {
        val world = world()
        val tank = world.addTank(3, 3, Tank.Type.SPEED)
        val base = tank.moveSpeed

        world.activateSpecial(tank)

        assertTrue(tank.specialActive)
        assertEquals(base * tank.dashSpeedMultiplier, tank.effectiveMoveSpeed, 1e-3f)
        assertTrue("실제로 빨라져야 한다", tank.effectiveMoveSpeed > base)
    }

    @Test
    fun `대시 중에는 같은 시간에 더 멀리 간다`() {
        val plain = world()
        val slow = plain.addTank(1, 3, Tank.Type.SPEED, Direction.RIGHT)
        slow.moving = true

        val dashing = world()
        val fast = dashing.addTank(1, 3, Tank.Type.SPEED, Direction.RIGHT)
        fast.moving = true
        dashing.activateSpecial(fast)

        val startSlow = slow.x
        val startFast = fast.x
        repeat(20) {
            plain.update(tick)
            dashing.update(tick)
        }

        assertTrue(
            "대시가 ${fast.x - startFast}, 평소가 ${slow.x - startSlow}",
            (fast.x - startFast) > (slow.x - startSlow) * 1.5f,
        )
    }

    @Test
    fun `대시는 지속 시간이 지나면 원래 속도로 돌아온다`() {
        val world = world()
        val tank = world.addTank(3, 3, Tank.Type.SPEED)
        val base = tank.moveSpeed
        world.activateSpecial(tank)

        repeat((tank.specialDuration / tick).toInt() + 2) { world.update(tick) }

        assertFalse(tank.specialActive)
        assertEquals(base, tank.effectiveMoveSpeed, 1e-3f)
    }

    // --- 리스너 ------------------------------------------------------------

    @Test
    fun `발동과 종료를 알린다`() {
        val world = world()
        val activated = ArrayList<BalanceConfig.Special>()
        val expired = ArrayList<BalanceConfig.Special>()
        world.listener = object : GameWorld.Listener {
            override fun onSpecialActivated(tank: Tank, special: BalanceConfig.Special) {
                activated += special
            }

            override fun onSpecialExpired(tank: Tank, special: BalanceConfig.Special) {
                expired += special
            }
        }

        val tank = world.addTank(3, 3, Tank.Type.DEFENSE)
        world.activateSpecial(tank)
        assertEquals(listOf(BalanceConfig.Special.SHIELD), activated)
        assertTrue("아직 지속 중이라 종료 통보가 없어야 한다", expired.isEmpty())

        repeat((tank.specialDuration / tick).toInt() + 2) { world.update(tick) }
        assertEquals(listOf(BalanceConfig.Special.SHIELD), expired)
    }

    @Test
    fun `즉발형은 종료 통보가 없다`() {
        val world = world()
        val expired = ArrayList<BalanceConfig.Special>()
        world.listener = object : GameWorld.Listener {
            override fun onSpecialExpired(tank: Tank, special: BalanceConfig.Special) {
                expired += special
            }
        }

        val tank = world.addTank(3, 5, Tank.Type.ATTACK, Direction.UP)
        world.activateSpecial(tank)
        repeat(120) { world.update(tick) }

        assertTrue("관통탄은 지속형이 아니다", expired.isEmpty())
    }

    // --- 리스폰 ------------------------------------------------------------

    @Test
    fun `다시 스폰하면 쿨타임과 효과가 초기화된다`() {
        val world = world()
        val tank = world.addTank(3, 3, Tank.Type.DEFENSE)
        world.activateSpecial(tank)
        assertTrue(tank.specialActive)

        tank.spawnAt(0f, 0f, Direction.UP)

        assertEquals(0f, tank.specialCooldownRemaining, 1e-4f)
        assertEquals(0f, tank.specialActiveRemaining, 1e-4f)
        assertEquals(0f, tank.damageReduction, 1e-4f)
    }
}
