package com.kophas.battlecity.net

import com.kophas.battlecity.core.Constants
import com.kophas.battlecity.gameplay.BalanceConfig
import com.kophas.battlecity.gameplay.GameWorld
import com.kophas.battlecity.gameplay.MatchState
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
 * 부서진 자리가 Client 에 닿는가. (계획서 §35, 감사 `10-MAP-04` · `10-BASE-06`)
 *
 * Client 는 규칙을 굴리지 않는다. Host 가 알려 주지 않으면 사라진 벽이 화면에 그대로
 * 남는다. 여기서는 Host 의 맵과 Client 의 맵을 나란히 놓고 같아지는지 본다.
 */
class TileSyncTest {

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

    private fun world() = GameWorld(emptyStage(), balance)

    private fun match() = MatchState(2, balance)

    // --- 변경 셀 모으기 ---------------------------------------------------

    @Test
    fun `바뀐 셀만 모은다`() {
        val map = TileMap(emptyStage())
        map.setType(3, 4, TileType.BRICK)

        val changes = map.collectChanges(Protocol.TILE_REDUNDANCY, Protocol.MAX_TILE_CHANGES)
        assertEquals(listOf(4 * map.cellsX + 3), changes)
    }

    @Test
    fun `같은 값으로 덮어쓰면 변경이 아니다`() {
        val map = TileMap(emptyStage())
        map.setType(3, 4, TileType.EMPTY)

        assertTrue(map.collectChanges(Protocol.TILE_REDUNDANCY, Protocol.MAX_TILE_CHANGES).isEmpty())
    }

    @Test
    fun `같은 셀을 거듭 보내 유실을 견딘다`() {
        val map = TileMap(emptyStage())
        map.setType(3, 4, TileType.BRICK)

        // 스냅샷 세 장에 걸쳐 같은 셀이 실린다. 연속 두 번 유실까지 견딘다.
        repeat(Protocol.TILE_REDUNDANCY) {
            assertEquals(
                "${it + 1}번째 스냅샷에 실려야 한다",
                1,
                map.collectChanges(Protocol.TILE_REDUNDANCY, Protocol.MAX_TILE_CHANGES).size,
            )
        }
        assertTrue(
            "네 번째부터는 보내지 않는다",
            map.collectChanges(Protocol.TILE_REDUNDANCY, Protocol.MAX_TILE_CHANGES).isEmpty(),
        )
    }

    @Test
    fun `한 번에 실을 수 있는 수를 넘으면 나눠 보낸다`() {
        val map = TileMap(emptyStage())
        val total = Protocol.MAX_TILE_CHANGES + 20
        var placed = 0
        var index = 0
        while (placed < total) {
            val x = index % map.cellsX
            val y = index / map.cellsX
            index++
            if (y >= map.cellsY) break
            map.setType(x, y, TileType.BRICK)
            placed++
        }

        val first = map.collectChanges(Protocol.TILE_REDUNDANCY, Protocol.MAX_TILE_CHANGES)
        assertEquals(Protocol.MAX_TILE_CHANGES, first.size)

        // 남은 것은 다음 차례에 나간다. 창 안에 있으므로 사라지지 않는다.
        val second = map.collectChanges(Protocol.TILE_REDUNDANCY, Protocol.MAX_TILE_CHANGES)
        assertTrue("나머지가 다음 스냅샷에 실려야 한다", second.size >= 20)
    }

    // --- 스냅샷을 거쳐 Client 맵까지 ---------------------------------------

    @Test
    fun `Host 가 부순 벽이 Client 맵에서도 사라진다`() {
        val host = world()
        val client = world()
        val hostMatch = match()

        host.map.setType(5, 6, TileType.BRICK)
        host.map.collectChanges(Protocol.TILE_REDUNDANCY, Protocol.MAX_TILE_CHANGES)
        // 이제 진짜로 부순다.
        host.map.setType(5, 6, TileType.EMPTY)
        client.map.setType(5, 6, TileType.BRICK)

        val snapshot = SnapshotBridge.capture(host, hostMatch, tick = 1)
        SnapshotBridge.apply(client, snapshot, previous = null, alpha = 1f)

        assertEquals(TileType.EMPTY, client.map.typeAt(5, 6))
    }

    @Test
    fun `본진 파괴와 보호막이 Client 에 닿는다`() {
        val host = world()
        val client = world()
        host.map.enableBaseShield(true)
        client.map.enableBaseShield(true)

        val (cellX, cellY) = host.stage.blockToCell(host.stage.baseBlock)
        host.map.setType(cellX, cellY, TileType.BASE)
        host.map.damageCell(cellX, cellY, piercing = false)

        val afterShield = SnapshotBridge.capture(host, match(), tick = 1)
        SnapshotBridge.apply(client, afterShield, previous = null, alpha = 1f)
        assertFalse("보호막이 닳은 것이 닿아야 한다", client.map.baseShielded)
        assertFalse(client.map.baseDestroyed)

        host.map.damageCell(cellX, cellY, piercing = false)
        val afterHit = SnapshotBridge.capture(host, match(), tick = 2)
        SnapshotBridge.apply(client, afterHit, previous = null, alpha = 1f)
        assertTrue("본진 파괴가 닿아야 한다", client.map.baseDestroyed)
    }

    @Test
    fun `스냅샷을 거쳐도 셀 종류가 그대로다`() {
        val writer = PacketWriter(ByteArray(Protocol.MAX_PACKET))
        val original = Messages.Snapshot(
            tick = 7,
            phase = 0,
            enemiesRemaining = 12,
            baseDestroyed = true,
            tanks = emptyList(),
            projectiles = emptyList(),
            scores = emptyList(),
            baseShielded = true,
            tiles = listOf(Messages.TileChange(1234, TileType.EMPTY.id.toInt())),
        )

        val packet = Messages.writeSnapshot(writer, original)
        val reader = PacketReader(packet.buffer, packet.length)
        Protocol.readType(reader)
        val decoded = Messages.readSnapshot(reader)

        assertEquals(original.tiles, decoded.tiles)
        assertTrue(decoded.baseDestroyed)
        assertTrue(decoded.baseShielded)
    }
}
