package com.kophas.battlecity.gameplay

import com.kophas.battlecity.core.Constants
import com.kophas.battlecity.map.StageData
import com.kophas.battlecity.map.StageTheme
import com.kophas.battlecity.map.TileMap
import com.kophas.battlecity.map.TileType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 본진 내구도. (계획서 §14, §15)
 *
 * 한 발에 판이 끝나던 것을 두 발로 올렸다. COM 이 본진 앞에 닿는 순간 판이 끝나
 * 손 쓸 도리가 없었다. 보호막(방 설정)은 그 위에 한 발을 더 막는다.
 *
 * 실제 발수는 `balance.json` 의 `rules.baseHits` 가 정한다. 여기서는 규칙만 본다.
 */
class BaseDurabilityTest {

    private fun emptyStage(blocksX: Int = 12, blocksY: Int = 9): StageData {
        val cellsX = blocksX * Constants.CELLS_PER_BLOCK
        val cellsY = blocksY * Constants.CELLS_PER_BLOCK
        val cells = ByteArray(cellsX * cellsY) { TileType.EMPTY.id }
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
            baseBlock = (blocksY - 2) * blocksX + blocksX / 2,
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

    /** 본진을 세운 맵과 그 셀 좌표. */
    private fun mapWithBase(hits: Int, shielded: Boolean = false): Triple<TileMap, Int, Int> {
        val stage = emptyStage()
        val map = TileMap(stage)
        val (cellX, cellY) = stage.blockToCell(stage.baseBlock)
        map.setType(cellX, cellY, TileType.BASE)
        map.configureBase(hits, shielded)
        return Triple(map, cellX, cellY)
    }

    @Test
    fun `두 발을 버틴다`() {
        val (map, x, y) = mapWithBase(hits = 2)

        assertEquals(TileMap.DamageResult.BASE_DAMAGED, map.damageCell(x, y, piercing = false))
        assertFalse("첫 발에 무너지면 안 된다", map.baseDestroyed)
        assertEquals(1, map.baseHitsRemaining)
        assertTrue("맞은 흔적이 보여야 한다", map.baseDamaged)

        assertEquals(TileMap.DamageResult.BASE_HIT, map.damageCell(x, y, piercing = false))
        assertTrue("두 번째 발에는 무너진다", map.baseDestroyed)
        assertEquals(0, map.baseHitsRemaining)
        assertFalse("부서진 뒤에는 손상 그림을 쓰지 않는다", map.baseDamaged)
    }

    @Test
    fun `보호막은 내구도와 별개로 한 발을 더 막는다`() {
        val (map, x, y) = mapWithBase(hits = 2, shielded = true)

        assertEquals(TileMap.DamageResult.BASE_SHIELDED, map.damageCell(x, y, piercing = false))
        assertEquals("보호막이 막은 발은 내구도를 깎지 않는다", 2, map.baseHitsRemaining)
        assertFalse(map.baseShielded)

        assertEquals(TileMap.DamageResult.BASE_DAMAGED, map.damageCell(x, y, piercing = false))
        assertEquals(TileMap.DamageResult.BASE_HIT, map.damageCell(x, y, piercing = false))
        assertTrue("보호막 한 발 + 내구도 두 발이면 세 발이다", map.baseDestroyed)
    }

    @Test
    fun `한 발로 정한 판은 예전과 같다`() {
        val (map, x, y) = mapWithBase(hits = 1)

        assertEquals(TileMap.DamageResult.BASE_HIT, map.damageCell(x, y, piercing = false))
        assertTrue(map.baseDestroyed)
    }

    @Test
    fun `부서진 뒤에 또 맞아도 아무 일도 없다`() {
        // 같은 셀에 포탄이 여러 발 들어올 수 있다. 두 번째 발이 발수를 더 깎으면
        // 남은 발수가 음수로 내려가고, Client 에 그 값이 실려 나간다.
        val (map, x, y) = mapWithBase(hits = 1)
        map.damageCell(x, y, piercing = false)

        assertEquals(TileMap.DamageResult.NONE, map.damageCell(x, y, piercing = false))
        assertEquals(0, map.baseHitsRemaining)
    }

    @Test
    fun `폭발도 내구도를 깎는다`() {
        // 폭발성 프롭이 본진 옆에서 터지면 본진도 상한다. (계획서 §15)
        val (map, x, y) = mapWithBase(hits = 2)

        map.explode(x, y, radiusCells = 1)
        assertEquals(1, map.baseHitsRemaining)
        assertFalse("한 번 터진 것으로 무너지면 안 된다", map.baseDestroyed)

        map.explode(x, y, radiusCells = 1)
        assertTrue(map.baseDestroyed)
    }

    @Test
    fun `판을 다시 열면 내구도가 돌아온다`() {
        val (map, x, y) = mapWithBase(hits = 2)
        map.damageCell(x, y, piercing = false)
        map.damageCell(x, y, piercing = false)
        assertTrue(map.baseDestroyed)

        map.reset()

        assertFalse(map.baseDestroyed)
        assertEquals(2, map.baseHitsRemaining)
    }
}
