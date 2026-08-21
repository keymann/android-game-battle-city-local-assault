package com.kophas.battlecity.net

import com.kophas.battlecity.core.Constants
import com.kophas.battlecity.core.Direction
import com.kophas.battlecity.gameplay.BalanceConfig
import com.kophas.battlecity.gameplay.GameWorld
import com.kophas.battlecity.gameplay.MatchState
import com.kophas.battlecity.gameplay.PlayerProfile
import com.kophas.battlecity.gameplay.Tank
import com.kophas.battlecity.map.StageData
import com.kophas.battlecity.map.StageTheme
import com.kophas.battlecity.map.TileType
import com.kophas.battlecity.render.AssetSource
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * 다시 나온 탱크가 고른 모습 그대로인가. (계획서 §29, §35)
 *
 * 기기 두 대로 시험하다 나온 결함이다. 참가자 탱크가 부서진 뒤 다시 나오면 **남의
 * 탱크 모습**으로 그려졌다. 두 가지가 겹쳤다.
 *
 *  - 탱크 번호는 풀 자리 번호라 **돌려 쓴다.** 부서진 탱크가 쓰던 번호를 다음 탱크가
 *    물려받는데, 받은 스냅샷을 얹을 때 갈래 · 종류 · 색을 다시 맞추지 않았다.
 *  - 색은 패킷에 실리지 않는다. 자리 번호를 색으로 쓰면 로비에서 색을 바꾼 사람이
 *    남의 색으로 보인다.
 */
class RespawnLookTest {

    private val assetsDir: File = sequenceOf(
        File("src/main/assets"),
        File("app/src/main/assets"),
    ).firstOrNull { it.isDirectory } ?: error("assets 디렉터리를 찾지 못했다")

    private val balance = BalanceConfig.load(AssetSource.ofDirectory(assetsDir))

    /** 로비에서 고른 것. 자리 번호와 색을 일부러 어긋나게 둔다. */
    private val profiles = listOf(
        PlayerProfile(name = "HST", type = Tank.Type.DEFENSE, colorIndex = 3),
        PlayerProfile(name = "GST", type = Tank.Type.SPEED, colorIndex = 0),
    )

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
            playerSpawnBlocks = intArrayOf(0, blocksX - 1),
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

    private fun match() = MatchState(2, balance, profiles)

    /** 화면에 쓰는 색. 자리 번호가 아니라 로비에서 고른 색이다. */
    private fun colorOf(slot: Int): Int = profiles.getOrNull(slot)?.colorIndex ?: -1

    /** Host 가 사람 탱크를 세운다. 자리에 맞는 종류와 색으로. */
    private fun spawnPlayer(world: GameWorld, slot: Int): Tank {
        val profile = profiles[slot]
        return world.spawnTank(
            faction = Tank.Faction.PLAYER,
            type = profile.type,
            colorSlot = profile.colorIndex,
            blockIndex = world.stage.playerSpawnBlocks[slot],
            direction = Direction.UP,
            ownerSlot = slot,
        )!!
    }

    private fun sync(host: GameWorld, client: GameWorld) {
        val snapshot = SnapshotBridge.capture(host, match(), tick = 1)
        SnapshotBridge.apply(client, snapshot, previous = null, alpha = 1f) { colorOf(it) }
    }

    @Test
    fun `참가자 화면에도 고른 종류와 색으로 나온다`() {
        val host = world()
        val client = world()
        spawnPlayer(host, 0)
        spawnPlayer(host, 1)

        sync(host, client)

        for (slot in 0..1) {
            val tank = client.tanks.first { it.ownerSlot == slot }
            assertEquals("$slot 번 자리 탱크 종류", profiles[slot].type, tank.type)
            assertEquals("$slot 번 자리 탱크 색", profiles[slot].colorIndex, tank.colorSlot)
        }
    }

    @Test
    fun `부서진 뒤 다시 나와도 같은 모습이다`() {
        val host = world()
        val client = world()
        spawnPlayer(host, 0)
        val victim = spawnPlayer(host, 1)
        sync(host, client)

        // 부서진다. 번호가 풀로 돌아간다.
        host.despawn(victim)
        sync(host, client)

        // 다시 나온다. 풀이 같은 번호를 다시 내줄 수 있다.
        val reborn = spawnPlayer(host, 1)
        sync(host, client)

        val tank = client.tanks.first { it.ownerSlot == 1 }
        assertEquals("번호가 달라도 종류는 그대로여야 한다", profiles[1].type, tank.type)
        assertEquals("색도 그대로여야 한다", profiles[1].colorIndex, tank.colorSlot)
        assertEquals(reborn.id, tank.id)
    }

    @Test
    fun `같은 번호로 다른 탱크가 오면 모습을 갈아 끼운다`() {
        // 이 결함의 핵심이다. 번호는 풀 자리 번호라 부서진 탱크의 것을 다음 탱크가
        // 물려받는다. 갈래 · 종류 · 색을 다시 맞추지 않으면 참가자 화면에서 COM 이
        // 사람 탱크 모습으로 그려진다.
        val host = world()
        val client = world()
        val victim = spawnPlayer(host, 0)
        sync(host, client)
        val recycled = victim.id

        // Host 에서 그 번호가 COM 에게 넘어간 상황을 손으로 만든다. 풀이 번호를
        // 언제 돌려주는지에 시험이 매이지 않게 한다.
        val handover = SnapshotBridge.capture(host, match(), tick = 2).copy(
            tanks = listOf(
                Messages.TankState(
                    id = recycled,
                    slot = -1,
                    faction = Tank.Faction.ENEMY.ordinal,
                    type = Tank.Type.ATTACK.ordinal,
                    x = 40f,
                    y = 40f,
                    direction = Direction.DOWN.ordinal,
                    hp = 100,
                    specialActive = false,
                ),
            ),
        )
        SnapshotBridge.apply(client, handover, previous = null, alpha = 1f) { colorOf(it) }

        val tank = client.tanks.first { it.id == recycled }
        assertNotNull(tank)
        assertEquals("갈래가 COM 으로 바뀌어야 한다", Tank.Faction.ENEMY, tank.faction)
        assertEquals(Tank.Type.ATTACK, tank.type)
        assertEquals("COM 은 자리가 없다", -1, tank.ownerSlot)
        assertEquals("COM 색으로 그려야 한다", -1, tank.colorSlot)
    }

    @Test
    fun `본진 남은 발수가 참가자 화면에 닿는다`() {
        val host = world()
        val client = world()
        host.map.configureBase(hits = 2, shielded = false)
        client.map.configureBase(hits = 2, shielded = false)
        val (cellX, cellY) = host.stage.blockToCell(host.stage.baseBlock)
        host.map.setType(cellX, cellY, TileType.BASE)

        host.map.damageCell(cellX, cellY, piercing = false)
        sync(host, client)

        assertEquals("남은 발수가 닿지 않았다", 1, client.map.baseHitsRemaining)
        assertEquals("손상 그림으로 갈아 끼워야 한다", true, client.map.baseDamaged)
    }
}
