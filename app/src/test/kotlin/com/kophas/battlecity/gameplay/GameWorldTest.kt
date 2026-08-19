package com.kophas.battlecity.gameplay

import com.kophas.battlecity.core.Constants
import com.kophas.battlecity.core.Direction
import com.kophas.battlecity.map.StageData
import com.kophas.battlecity.map.StageTheme
import com.kophas.battlecity.map.TileType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 규칙 검증. 손으로 만든 작은 맵을 써서 판정을 정확히 겨눈다.
 * 생성기를 거치지 않으므로 실패했을 때 원인이 규칙인지 생성기인지 헷갈리지 않는다.
 */
class GameWorldTest {

    private val tick = Constants.TICK_SECONDS

    /** 12x9 블록(24x18 셀)의 빈 4:3 맵. 본진은 하단 중앙. */
    private fun emptyStage(blocksX: Int = 12, blocksY: Int = 9): StageData {
        val cellsX = blocksX * Constants.CELLS_PER_BLOCK
        val cellsY = blocksY * Constants.CELLS_PER_BLOCK
        val cells = ByteArray(cellsX * cellsY) { TileType.EMPTY.id }
        val baseBlock = (blocksY - 2) * blocksX + blocksX / 2
        return StageData(
            blocksX = blocksX,
            blocksY = blocksY,
            cells = cells,
            cellSprite = ShortArray(cells.size) { -1 },
            spriteNames = arrayOf("crateWood"),
            ground = ShortArray(blocksX * blocksY),
            groundNames = arrayOf("tileGrass1"),
            decor = emptyList(),
            explosiveCells = emptySet(),
            baseBlock = baseBlock,
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

    private fun world(): GameWorld = GameWorld(emptyStage())

    private fun GameWorld.addTank(
        blockX: Int,
        blockY: Int,
        direction: Direction = Direction.UP,
        faction: Tank.Faction = Tank.Faction.PLAYER,
        speed: Float = Constants.BLOCK_PX * 2f,
    ): Tank {
        val tank = spawnTank(
            faction = faction,
            type = Tank.Type.ATTACK,
            colorSlot = 0,
            blockIndex = blockY * stage.blocksX + blockX,
            direction = direction,
            moveSpeed = speed,
            fireCooldown = 0.2f,
        )!!
        tank.spawnGuardRemaining = 0f
        return tank
    }

    // --- 이동 -------------------------------------------------------------

    @Test
    fun `이동 입력이 없으면 제자리다`() {
        val world = world()
        val tank = world.addTank(3, 3)
        val startX = tank.x
        val startY = tank.y

        repeat(10) { world.update(tick) }

        assertEquals(startX, tank.x, 1e-4f)
        assertEquals(startY, tank.y, 1e-4f)
    }

    @Test
    fun `이동 입력이 있으면 방향대로 나아간다`() {
        val world = world()
        val tank = world.addTank(3, 3, Direction.RIGHT)
        tank.moving = true
        val startX = tank.x

        repeat(30) { world.update(tick) }

        assertTrue("오른쪽으로 움직여야 한다", tank.x > startX)
        assertEquals("세로는 그대로여야 한다", 3 * Constants.BLOCK_PX, tank.y, 1e-3f)
    }

    @Test
    fun `맵 경계를 넘어가지 않는다`() {
        val world = world()
        val tank = world.addTank(0, 3, Direction.LEFT, speed = Constants.BLOCK_PX * 10f)
        tank.moving = true

        repeat(60) { world.update(tick) }

        assertEquals(0f, tank.x, 1e-3f)
    }

    @Test
    fun `강철 벽에 막힌다`() {
        val world = world()
        val tank = world.addTank(2, 3, Direction.RIGHT)
        tank.moving = true
        // 탱크 오른쪽에 강철 기둥을 세운다.
        val wallCellX = 4 * Constants.CELLS_PER_BLOCK
        for (dy in 0 until Constants.CELLS_PER_BLOCK) {
            world.map.setType(wallCellX, 3 * Constants.CELLS_PER_BLOCK + dy, TileType.STEEL)
        }

        repeat(120) { world.update(tick) }

        val rightEdge = tank.x + Tank.SIZE
        assertTrue("벽을 통과했다 (rightEdge=$rightEdge)", rightEdge <= wallCellX * Constants.CELL_PX + 1e-3f)
    }

    @Test
    fun `물은 탱크를 막는다`() {
        val world = world()
        val tank = world.addTank(2, 3, Direction.RIGHT)
        tank.moving = true
        val waterCellX = 4 * Constants.CELLS_PER_BLOCK
        for (dy in 0 until Constants.CELLS_PER_BLOCK) {
            world.map.setType(waterCellX, 3 * Constants.CELLS_PER_BLOCK + dy, TileType.WATER)
        }

        repeat(120) { world.update(tick) }

        assertTrue(tank.x + Tank.SIZE <= waterCellX * Constants.CELL_PX + 1e-3f)
    }

    @Test
    fun `숲은 탱크를 막지 않는다`() {
        val world = world()
        val tank = world.addTank(2, 3, Direction.RIGHT)
        tank.moving = true
        val forestCellX = 4 * Constants.CELLS_PER_BLOCK
        for (dy in 0 until Constants.CELLS_PER_BLOCK) {
            world.map.setType(forestCellX, 3 * Constants.CELLS_PER_BLOCK + dy, TileType.FOREST)
        }

        repeat(120) { world.update(tick) }

        assertTrue("숲을 통과해야 한다", tank.x > forestCellX * Constants.CELL_PX)
    }

    @Test
    fun `탱크끼리 겹치지 않는다`() {
        val world = world()
        val mover = world.addTank(2, 3, Direction.RIGHT)
        val blocker = world.addTank(4, 3)
        mover.moving = true

        repeat(120) { world.update(tick) }

        assertFalse("두 탱크가 겹쳤다", mover.overlaps(blocker))
    }

    @Test
    fun `수직으로 꺾으면 셀 격자에 맞춰진다`() {
        val world = world()
        val tank = world.addTank(3, 3, Direction.RIGHT)
        tank.moving = true
        repeat(7) { world.update(tick) }
        assertTrue("먼저 격자에서 벗어나야 의미가 있다", tank.x % Constants.CELL_PX != 0f)

        world.steer(tank, Direction.UP)

        val remainder = tank.x % Constants.CELL_PX
        assertTrue("셀 격자에 스냅되지 않았다 (x=${tank.x})", remainder < 1e-3f || remainder > Constants.CELL_PX - 1e-3f)
    }

    @Test
    fun `같은 축으로 반대 방향 전환은 스냅하지 않는다`() {
        val world = world()
        val tank = world.addTank(3, 3, Direction.RIGHT)
        tank.moving = true
        repeat(7) { world.update(tick) }
        val beforeX = tank.x

        world.steer(tank, Direction.LEFT)

        assertEquals(beforeX, tank.x, 1e-4f)
    }

    @Test
    fun `얼음 위에서는 입력을 놓아도 미끄러진다`() {
        val world = world()
        val tank = world.addTank(2, 3, Direction.RIGHT)
        // 이동 경로를 전부 얼음으로 만든다.
        for (cy in 0 until world.map.cellsY) {
            for (cx in 0 until world.map.cellsX) {
                world.map.setType(cx, cy, TileType.ICE)
            }
        }
        tank.moving = true
        repeat(20) { world.update(tick) }

        tank.moving = false
        val releasedX = tank.x
        repeat(5) { world.update(tick) }

        assertTrue("얼음 위에서 즉시 멈추면 안 된다", tank.x > releasedX)
    }

    // --- 발사와 포탄 ------------------------------------------------------

    @Test
    fun `쿨타임 중에는 다시 쏠 수 없다`() {
        val world = world()
        val tank = world.addTank(3, 3)

        assertNotNull(world.fire(tank))
        assertNull("쿨타임 중이라 실패해야 한다", world.fire(tank))

        repeat(20) { world.update(tick) }
        assertNotNull("쿨타임이 지나면 다시 쏠 수 있어야 한다", world.fire(tank))
    }

    @Test
    fun `포탄이 벽돌을 부순다`() {
        val world = world()
        val tank = world.addTank(3, 5, Direction.UP)
        val brickY = 3 * Constants.CELLS_PER_BLOCK + 1
        val brickX = 3 * Constants.CELLS_PER_BLOCK
        world.map.setType(brickX, brickY, TileType.BRICK, 0)
        world.map.setType(brickX + 1, brickY, TileType.BRICK, 0)

        world.fire(tank)
        repeat(60) { world.update(tick) }

        assertEquals(TileType.EMPTY, world.map.typeAt(brickX, brickY))
        assertEquals(TileType.EMPTY, world.map.typeAt(brickX + 1, brickY))
    }

    @Test
    fun `일반 포탄은 강철을 부수지 못한다`() {
        val world = world()
        val tank = world.addTank(3, 5, Direction.UP)
        val steelX = 3 * Constants.CELLS_PER_BLOCK
        val steelY = 3 * Constants.CELLS_PER_BLOCK + 1
        world.map.setType(steelX, steelY, TileType.STEEL, 0)

        world.fire(tank)
        repeat(60) { world.update(tick) }

        assertEquals(TileType.STEEL, world.map.typeAt(steelX, steelY))
    }

    @Test
    fun `관통탄은 강철을 부순다`() {
        val world = world()
        val tank = world.addTank(3, 5, Direction.UP)
        val steelX = 3 * Constants.CELLS_PER_BLOCK
        val steelY = 3 * Constants.CELLS_PER_BLOCK + 1
        world.map.setType(steelX, steelY, TileType.STEEL, 0)

        world.fire(tank, power = 3, piercing = true)
        repeat(60) { world.update(tick) }

        assertEquals(TileType.EMPTY, world.map.typeAt(steelX, steelY))
    }

    @Test
    fun `포탄은 물 위를 지나간다`() {
        val world = world()
        val tank = world.addTank(3, 5, Direction.UP)
        val waterY = 4 * Constants.CELLS_PER_BLOCK
        for (cx in 0 until world.map.cellsX) {
            world.map.setType(cx, waterY, TileType.WATER)
        }
        // 물 너머에 벽돌을 두고, 그게 부서지면 포탄이 물을 통과한 것이다.
        val brickX = 3 * Constants.CELLS_PER_BLOCK
        val brickY = 2 * Constants.CELLS_PER_BLOCK
        world.map.setType(brickX, brickY, TileType.BRICK, 0)

        world.fire(tank)
        repeat(90) { world.update(tick) }

        assertEquals(TileType.EMPTY, world.map.typeAt(brickX, brickY))
    }

    @Test
    fun `포탄이 적 탱크를 파괴한다`() {
        val world = world()
        val shooter = world.addTank(3, 5, Direction.UP)
        val target = world.addTank(3, 3, faction = Tank.Faction.ENEMY)

        world.fire(shooter)
        repeat(60) { world.update(tick) }

        assertFalse("적 탱크가 살아 있다", target.alive)
    }

    @Test
    fun `자기 포탄에는 맞지 않는다`() {
        val world = world()
        val tank = world.addTank(3, 5, Direction.UP)

        world.fire(tank)
        repeat(10) { world.update(tick) }

        assertTrue(tank.alive)
    }

    @Test
    fun `스폰 무적 중에는 맞지 않는다`() {
        val world = world()
        val shooter = world.addTank(3, 5, Direction.UP)
        val target = world.addTank(3, 3, faction = Tank.Faction.ENEMY)
        target.spawnGuardRemaining = Tank.SPAWN_GUARD_SECONDS

        world.fire(shooter)
        repeat(30) { world.update(tick) }

        assertTrue("무적 중에는 살아 있어야 한다", target.alive)
    }

    @Test
    fun `포탄은 맵 밖으로 나가면 사라진다`() {
        val world = world()
        val tank = world.addTank(3, 0, Direction.UP)

        world.fire(tank)
        assertEquals(1, world.projectiles.activeCount)

        repeat(60) { world.update(tick) }

        assertEquals(0, world.projectiles.activeCount)
    }

    // --- 본진 -------------------------------------------------------------

    @Test
    fun `본진이 파괴되면 즉시 GAME OVER 다`() {
        val world = world()
        val (baseCellX, baseCellY) = world.stage.blockToCell(world.stage.baseBlock)
        for (dy in 0 until Constants.CELLS_PER_BLOCK) {
            for (dx in 0 until Constants.CELLS_PER_BLOCK) {
                world.map.setType(baseCellX + dx, baseCellY + dy, TileType.BASE)
            }
        }
        val baseBlockY = world.stage.baseBlock / world.stage.blocksX
        val baseBlockX = world.stage.baseBlock % world.stage.blocksX
        val shooter = world.addTank(baseBlockX, baseBlockY - 3, Direction.DOWN)

        world.fire(shooter)
        repeat(90) { world.update(tick) }

        assertTrue("본진이 파괴돼야 한다", world.map.baseDestroyed)
        assertTrue("GAME OVER 여야 한다", world.gameOver)
    }

    @Test
    fun `아군 포탄으로도 본진이 파괴된다`() {
        // 계획서 §15.2 - 본진은 아군/적군 구분 없이 파괴된다.
        val world = world()
        val (baseCellX, baseCellY) = world.stage.blockToCell(world.stage.baseBlock)
        world.map.setType(baseCellX, baseCellY, TileType.BASE)

        val result = world.map.damageCell(baseCellX, baseCellY, piercing = false)

        assertEquals(com.kophas.battlecity.map.TileMap.DamageResult.BASE_HIT, result)
        assertTrue(world.map.baseDestroyed)
    }

    @Test
    fun `GAME OVER 이후에는 상태가 더 진행되지 않는다`() {
        val world = world()
        val (baseCellX, baseCellY) = world.stage.blockToCell(world.stage.baseBlock)
        world.map.setType(baseCellX, baseCellY, TileType.BASE)
        world.map.damageCell(baseCellX, baseCellY, piercing = false)
        world.update(tick)

        val tank = world.addTank(3, 3, Direction.RIGHT)
        tank.moving = true
        val startX = tank.x
        repeat(30) { world.update(tick) }

        assertEquals(startX, tank.x, 1e-4f)
    }

    // --- 폭발 -------------------------------------------------------------

    @Test
    fun `탱크가 파괴되면 폭발이 생긴다`() {
        val world = world()
        val tank = world.addTank(3, 3)
        val before = world.explosions.activeCount

        world.destroyTank(tank, killerId = -1)

        assertTrue(world.explosions.activeCount > before)
    }

    @Test
    fun `폭발은 재생이 끝나면 회수된다`() {
        val world = world()
        world.spawnExplosion(Explosion.Kind.BULLET_HIT, 100f, 100f, 32f)
        assertEquals(1, world.explosions.activeCount)

        repeat(60) { world.update(tick) }

        assertEquals(0, world.explosions.activeCount)
    }

    @Test
    fun `폭발성 프롭은 주변 벽돌까지 날린다`() {
        val blocksX = 12
        val blocksY = 9
        val cellsX = blocksX * Constants.CELLS_PER_BLOCK
        val cells = ByteArray(cellsX * blocksY * Constants.CELLS_PER_BLOCK) { TileType.EMPTY.id }

        // 위로 쏜 포탄이 가장 먼저 닿는 줄(hitY)에 폭발물을 둔다.
        // 벽돌 덩어리는 그 위쪽으로만 쌓아 첫 충돌 지점이 확실히 폭발물이 되게 한다.
        val hitX = 3 * Constants.CELLS_PER_BLOCK
        val hitY = 4 * Constants.CELLS_PER_BLOCK
        for (dy in -4..0) {
            for (dx in -2..2) {
                cells[(hitY + dy) * cellsX + (hitX + dx)] = TileType.BRICK.id
            }
        }
        val explosiveIndex = hitY * cellsX + hitX

        val base = emptyStage(blocksX, blocksY)
        val stage = StageData(
            blocksX = base.blocksX,
            blocksY = base.blocksY,
            cells = cells,
            cellSprite = ShortArray(cells.size) { 0 },
            spriteNames = arrayOf("specialBarrel1"),
            ground = base.ground,
            groundNames = base.groundNames,
            decor = emptyList(),
            explosiveCells = setOf(explosiveIndex),
            baseBlock = base.baseBlock,
            comSpawnBlocks = base.comSpawnBlocks,
            playerSpawnBlocks = base.playerSpawnBlocks,
            theme = base.theme,
            seed = base.seed,
        )
        val world = GameWorld(stage)
        val shooter = world.spawnTank(
            Tank.Faction.PLAYER, Tank.Type.ATTACK, 0,
            6 * blocksX + 3, Direction.UP, Constants.BLOCK_PX * 2f, 0.2f,
        )!!
        shooter.spawnGuardRemaining = 0f

        world.fire(shooter)
        repeat(90) { world.update(tick) }

        // 맞은 자리뿐 아니라 폭발 반경 안의 벽돌도 사라져야 한다.
        assertEquals(TileType.EMPTY, world.map.typeAt(hitX, hitY))
        assertEquals(TileType.EMPTY, world.map.typeAt(hitX - 2, hitY))
        assertEquals(TileType.EMPTY, world.map.typeAt(hitX + 2, hitY))
        assertEquals(TileType.EMPTY, world.map.typeAt(hitX, hitY - 2))
    }
}
