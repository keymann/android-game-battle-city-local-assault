package com.kophas.battlecity.gameplay

import com.kophas.battlecity.core.Constants
import com.kophas.battlecity.core.Direction
import com.kophas.battlecity.map.StageData
import com.kophas.battlecity.map.StageTheme
import com.kophas.battlecity.map.TileMap
import com.kophas.battlecity.map.TileType
import com.kophas.battlecity.render.AssetSource
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 방 설정이 실제 판정을 바꾸는지. (계획서 §44.2)
 *
 * 설정 화면에서 토글을 누르는 것만으로는 아무것도 증명되지 않는다. 켰을 때와
 * 껐을 때 **판정이 실제로 달라지는지**가 규칙이다.
 */
class RoomRuleTest {

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

    // --- 본진 보호 --------------------------------------------------------

    /**
     * 본진을 세운 맵. 손으로 만든 스테이지에는 본진 타일이 없다.
     *
     * 생성기를 거치지 않는 이유는, 여기서 보려는 것이 보호막 판정 하나이기 때문이다.
     * 생성기까지 끼우면 실패했을 때 어느 쪽 잘못인지 헷갈린다.
     */
    private fun mapWithBase(shielded: Boolean): Triple<TileMap, Int, Int> {
        val stage = emptyStage()
        val map = TileMap(stage)
        val (cellX, cellY) = stage.blockToCell(stage.baseBlock)
        map.setType(cellX, cellY, TileType.BASE)
        // 내구도 한 발이 예전 규칙이다. 여기서 보려는 것은 보호막 판정뿐이다.
        map.configureBase(hits = 1, shielded = shielded)
        return Triple(map, cellX, cellY)
    }

    @Test
    fun `보호막은 첫 발을 막고 사라진다`() {
        val (map, x, y) = mapWithBase(shielded = true)

        assertEquals(TileMap.DamageResult.BASE_SHIELDED, map.damageCell(x, y, piercing = false))
        assertFalse("보호막이 막았으면 본진은 멀쩡하다", map.baseDestroyed)
        assertFalse("한 번 막으면 보호막은 사라진다", map.baseShielded)

        // 두 번째 발은 그대로 들어간다.
        assertEquals(TileMap.DamageResult.BASE_HIT, map.damageCell(x, y, piercing = false))
        assertTrue(map.baseDestroyed)
    }

    @Test
    fun `보호막을 끄면 첫 발에 본진이 무너진다`() {
        val (map, x, y) = mapWithBase(shielded = false)

        assertEquals(TileMap.DamageResult.BASE_HIT, map.damageCell(x, y, piercing = false))
        assertTrue(map.baseDestroyed)
    }

    @Test
    fun `폭발도 보호막을 먼저 소모한다`() {
        val (map, x, y) = mapWithBase(shielded = true)

        map.explode(x, y, radiusCells = 2)
        assertFalse("폭발 한 번은 보호막이 받아 낸다", map.baseDestroyed)
        assertFalse(map.baseShielded)

        map.explode(x, y, radiusCells = 2)
        assertTrue("보호막이 없으면 폭발에 무너진다", map.baseDestroyed)
    }

    // --- 아군 오사 --------------------------------------------------------

    private fun GameWorld.addTank(
        blockX: Int,
        blockY: Int,
        direction: Direction,
        faction: Tank.Faction,
        ownerSlot: Int,
    ): Tank {
        val tank = spawnTank(
            faction = faction,
            type = Tank.Type.ATTACK,
            colorSlot = ownerSlot.coerceAtLeast(0),
            blockIndex = blockY * stage.blocksX + blockX,
            direction = direction,
            ownerSlot = ownerSlot,
        ) ?: error("탱크를 만들지 못했다")
        // 스폰 무적을 걷는다. 여기서 보려는 것은 아군 판정 하나다.
        tank.spawnGuardRemaining = 0f
        return tank
    }

    /** 나란히 선 아군 둘. 왼쪽이 오른쪽을 향해 쏜다. */
    private fun fireAtAlly(friendlyFire: Boolean): Int {
        val world = GameWorld(
            emptyStage(),
            balance,
            GameWorld.Config(friendlyFire = friendlyFire),
        )
        val shooter = world.addTank(3, 5, Direction.UP, Tank.Faction.PLAYER, 0)
        val ally = world.addTank(3, 3, Direction.DOWN, Tank.Faction.PLAYER, 1)
        val before = ally.hp

        world.fire(shooter)
        repeat(60) { world.update(tick) }
        return before - ally.hp
    }

    @Test
    fun `아군 오사를 켜면 아군에게 피해가 들어간다`() {
        assertTrue("포탄이 아군을 지나쳤다", fireAtAlly(friendlyFire = true) > 0)
    }

    @Test
    fun `아군 오사를 끄면 아군은 다치지 않는다`() {
        assertEquals(0, fireAtAlly(friendlyFire = false))
    }

    @Test
    fun `아군 오사를 꺼도 적에게는 피해가 들어간다`() {
        val world = GameWorld(emptyStage(), balance, GameWorld.Config(friendlyFire = false))
        val shooter = world.addTank(3, 5, Direction.UP, Tank.Faction.PLAYER, 0)
        val enemy = world.addTank(3, 3, Direction.DOWN, Tank.Faction.ENEMY, -1)
        val before = enemy.hp

        world.fire(shooter)
        repeat(60) { world.update(tick) }
        assertTrue("적에게는 그대로 들어가야 한다", enemy.hp < before)
    }

    // --- 동시 COM 수 ------------------------------------------------------

    @Test
    fun `방 설정이 동시 COM 수를 덮어쓴다`() {
        val fromBalance = MatchState(2, balance)
        val overridden = MatchState(2, balance, maxActiveOverride = 11)
        assertEquals(11, overridden.maxActiveEnemies)
        assertEquals(
            "총량은 건드리지 않는다",
            fromBalance.totalEnemies,
            overridden.totalEnemies,
        )
    }

    @Test
    fun `덮어쓰지 않으면 balance 값을 그대로 쓴다`() {
        val plain = MatchState(2, balance)
        assertEquals(plain.maxActiveEnemies, MatchState(2, balance, maxActiveOverride = 0).maxActiveEnemies)
    }
}
