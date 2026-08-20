package com.kophas.battlecity.ai

import com.kophas.battlecity.core.Constants
import com.kophas.battlecity.core.Direction
import com.kophas.battlecity.gameplay.BalanceConfig
import com.kophas.battlecity.gameplay.GameWorld
import com.kophas.battlecity.gameplay.Tank
import com.kophas.battlecity.map.StageData
import com.kophas.battlecity.map.StageTheme
import com.kophas.battlecity.map.TileMap
import com.kophas.battlecity.map.TileType
import com.kophas.battlecity.render.AssetSource
import java.io.File

/**
 * AI 테스트가 함께 쓰는 손으로 만든 맵.
 *
 * 생성기를 거치지 않는다. 실패했을 때 원인이 AI 인지 생성기인지 헷갈리지 않아야 한다.
 */
object AiFixture {

    val assetsDir: File = sequenceOf(
        File("src/main/assets"),
        File("app/src/main/assets"),
    ).firstOrNull { it.isDirectory } ?: error("assets 디렉터리를 찾지 못했다")

    val balance: BalanceConfig = BalanceConfig.load(AssetSource.ofDirectory(assetsDir))

    val settings: AiSettings = AiSettings.from(balance)

    /**
     * 빈 4:3 맵. [paint] 로 셀을 직접 칠한다. 좌표는 **셀** 단위다.
     *
     * 본진은 하단 중앙, COM 스폰은 상단, 플레이어 스폰은 최하단이다.
     * 실제 생성기가 만드는 배치와 같은 모양이라야 스폰 검사 테스트가 뜻이 있다.
     */
    fun stage(
        blocksX: Int = 12,
        blocksY: Int = 9,
        comSpawnBlocks: IntArray = intArrayOf(0, blocksX / 2, blocksX - 1),
        playerCount: Int = 2,
        paint: (set: (Int, Int, TileType) -> Unit) -> Unit = {},
    ): StageData {
        val cellsX = blocksX * Constants.CELLS_PER_BLOCK
        val cellsY = blocksY * Constants.CELLS_PER_BLOCK
        val cells = ByteArray(cellsX * cellsY) { TileType.EMPTY.id }
        paint { cx, cy, type ->
            if (cx in 0 until cellsX && cy in 0 until cellsY) {
                cells[cy * cellsX + cx] = type.id
            }
        }

        val baseBlock = (blocksY - 2) * blocksX + blocksX / 2
        val bcx = (baseBlock % blocksX) * Constants.CELLS_PER_BLOCK
        val bcy = (baseBlock / blocksX) * Constants.CELLS_PER_BLOCK
        for (dy in 0 until Constants.CELLS_PER_BLOCK) {
            for (dx in 0 until Constants.CELLS_PER_BLOCK) {
                cells[(bcy + dy) * cellsX + bcx + dx] = TileType.BASE.id
            }
        }

        return StageData(
            blocksX = blocksX,
            blocksY = blocksY,
            cells = cells,
            cellSprite = ShortArray(cells.size) { -1 },
            spriteNames = arrayOf("env_crate_0"),
            ground = ShortArray(blocksX * blocksY),
            groundNames = arrayOf("env_ground_grass"),
            decor = emptyList(),
            explosiveCells = emptySet(),
            baseBlock = baseBlock,
            comSpawnBlocks = comSpawnBlocks,
            playerSpawnBlocks = IntArray(playerCount) { (blocksY - 1) * blocksX + 2 + it * 2 },
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

    fun world(stage: StageData = stage()): GameWorld = GameWorld(stage, balance)

    fun navOf(stage: StageData): NavGrid = NavGrid(TileMap(stage))

    /** 스폰 무적을 걷어낸 탱크. AI 가 곧바로 움직이는지 봐야 하기 때문이다. */
    fun GameWorld.readyTank(
        blockX: Int,
        blockY: Int,
        faction: Tank.Faction = Tank.Faction.ENEMY,
        type: Tank.Type = Tank.Type.ATTACK,
        direction: Direction = Direction.DOWN,
    ): Tank {
        val tank = spawnTank(
            faction = faction,
            type = type,
            colorSlot = if (faction == Tank.Faction.PLAYER) 0 else -1,
            blockIndex = blockY * stage.blocksX + blockX,
            direction = direction,
            ownerSlot = if (faction == Tank.Faction.PLAYER) 0 else -1,
        )!!
        tank.spawnGuardRemaining = 0f
        return tank
    }

    /** [count] 틱만큼 AI 와 월드를 함께 굴린다. */
    fun run(world: GameWorld, count: Int, step: (Float) -> Unit) {
        repeat(count) {
            step(Constants.TICK_SECONDS)
            world.update(Constants.TICK_SECONDS)
        }
    }
}
