package com.kophas.battlecity.map

import com.kophas.battlecity.core.Constants
import com.kophas.battlecity.render.AssetManifest
import com.kophas.battlecity.render.AssetSource
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 실제 매니페스트로 스테이지를 생성해 검증한다.
 * 생성기가 지켜야 하는 계약은 두 가지다: **결정론**과 **플레이 가능성**.
 */
class StageGeneratorTest {

    private val assetsDir: File = sequenceOf(
        File("src/main/assets"),
        File("app/src/main/assets"),
    ).firstOrNull { it.isDirectory } ?: error("assets 디렉터리를 찾지 못했다")

    private val source = AssetSource.ofDirectory(assetsDir)

    private val manifest = AssetManifest.load(source)

    private val profile = MapGenProfile.load(source)

    private val generator = StageGenerator(manifest, profile)

    // --- 결정론 -----------------------------------------------------------

    @Test
    fun `같은 seed 는 완전히 같은 맵을 만든다`() {
        val a = generator.generate(777L, playerCount = 4)
        val b = generator.generate(777L, playerCount = 4)

        assertEquals(a.gridHash, b.gridHash)
        assertTrue(a.cells.contentEquals(b.cells))
        assertTrue(a.ground.contentEquals(b.ground))
        assertEquals(a.decor.size, b.decor.size)
        assertEquals(a.baseBlock, b.baseBlock)
        assertTrue(a.comSpawnBlocks.contentEquals(b.comSpawnBlocks))
    }

    @Test
    fun `다른 seed 는 다른 맵을 만든다`() {
        val hashes = (1L..20L).map { generator.generate(it, playerCount = 4).gridHash }.toSet()
        assertTrue("20개 중 최소 18개는 서로 달라야 한다", hashes.size >= 18)
    }

    @Test
    fun `stageIndex 가 다르면 같은 seed 라도 다른 맵이다`() {
        val first = generator.generate(500L, playerCount = 2, stageIndex = 0)
        val second = generator.generate(500L, playerCount = 2, stageIndex = 1)
        assertNotEquals(first.gridHash, second.gridHash)
    }

    // --- 맵 규격 ----------------------------------------------------------

    @Test
    fun `맵 크기는 플레이어 수에 따라 커진다`() {
        assertEquals(23, generator.blocksXFor(2))
        assertEquals(28, generator.blocksXFor(3))
        assertEquals(32, generator.blocksXFor(4))
    }

    @Test
    fun `모든 플레이어 수에서 논리 해상도가 16 대 9 에 붙어 있다`() {
        // 블록이 정수라 정확히 16:9 가 되는 조합은 (16,9)와 (32,18) 뿐이다.
        // 그 사이가 너무 벌어져서 가장 가까운 격자를 골라 쓴다. 오차는 0.5% 아래다.
        for (players in 2..4) {
            val stage = generator.generate(players.toLong(), players)
            val error = kotlin.math.abs(stage.aspect / (16f / 9f) - 1f)
            assertTrue(
                "players=$players 비율이 16:9 에서 너무 멀다 " +
                    "(${stage.blocksX}x${stage.blocksY}, 오차 ${error * 100}%)",
                error < 0.017f,
            )
            assertTrue("가로가 세로보다 길어야 한다", stage.widthPx > stage.heightPx)
        }
    }

    @Test
    fun `4인 맵은 32x18 블록으로 정확히 16 대 9 다`() {
        val stage = generator.generate(1L, playerCount = 4)
        assertEquals(32, stage.blocksX)
        assertEquals(18, stage.blocksY)
        assertEquals(64, stage.cellsX)
        assertEquals(36, stage.cellsY)
        assertEquals(2048f, stage.widthPx, 1e-3f)
        assertEquals(1152f, stage.heightPx, 1e-3f)
        assertEquals(16f / 9f, stage.aspect, 1e-4f)
    }

    @Test
    fun `셀 배열 크기가 블록 수와 맞는다`() {
        for (players in 2..4) {
            val stage = generator.generate(players.toLong(), players)
            val expected = stage.cellsX * stage.cellsY
            assertEquals(expected, stage.cells.size)
            assertEquals(expected, stage.cellSprite.size)
            assertEquals(stage.blocksX * stage.blocksY, stage.ground.size)
        }
    }

    // --- 앵커 -------------------------------------------------------------

    @Test
    fun `본진은 하단 중앙에 있고 벽돌 방벽이 감싼다`() {
        val stage = generator.generate(31L, playerCount = 4)
        val baseX = stage.baseBlock % stage.blocksX
        val baseY = stage.baseBlock / stage.blocksX

        assertEquals(stage.blocksX / 2, baseX)
        assertEquals(stage.blocksY - 2, baseY)

        val (cx, cy) = stage.blockToCell(stage.baseBlock)
        for (dy in 0 until Constants.CELLS_PER_BLOCK) {
            for (dx in 0 until Constants.CELLS_PER_BLOCK) {
                assertEquals(TileType.BASE, stage.typeAt(cx + dx, cy + dy))
            }
        }

        // 위쪽과 좌우는 막혀 있고 아래는 열려 있어야 한다 (원작의 ㄷ자 방벽).
        assertEquals(TileType.BRICK, stage.typeAt(cx, cy - 1))
        assertEquals(TileType.BRICK, stage.typeAt(cx - 1, cy))
        assertEquals(TileType.BRICK, stage.typeAt(cx + Constants.CELLS_PER_BLOCK, cy))
    }

    @Test
    fun `COM 스폰 3곳은 모두 최상단이다`() {
        val stage = generator.generate(88L, playerCount = 3)
        assertEquals(3, stage.comSpawnBlocks.size)
        for (block in stage.comSpawnBlocks) {
            assertEquals("COM 스폰은 0행이어야 한다", 0, block / stage.blocksX)
        }
    }

    @Test
    fun `플레이어 스폰 수는 플레이어 수와 같고 최하단이다`() {
        for (players in 2..4) {
            val stage = generator.generate(players * 13L, players)
            assertEquals(players, stage.playerSpawnBlocks.size)
            for (block in stage.playerSpawnBlocks) {
                assertEquals(stage.blocksY - 1, block / stage.blocksX)
            }
        }
    }

    @Test
    fun `모든 스폰 자리는 비어 있다`() {
        for (seed in 1L..30L) {
            val stage = generator.generate(seed, playerCount = 4)
            for (block in stage.comSpawnBlocks + stage.playerSpawnBlocks) {
                val (cx, cy) = stage.blockToCell(block)
                for (dy in 0 until Constants.CELLS_PER_BLOCK) {
                    for (dx in 0 until Constants.CELLS_PER_BLOCK) {
                        val type = stage.typeAt(cx + dx, cy + dy)
                        assertTrue(
                            "seed=$seed 스폰 셀이 막혀 있다: $type",
                            type.isWalkable,
                        )
                    }
                }
            }
        }
    }

    // --- 플레이 가능성 ----------------------------------------------------

    @Test
    fun `모든 스폰에서 본진까지 갈 수 있다`() {
        for (seed in 1L..40L) {
            val stage = generator.generate(seed, playerCount = 4)
            for (block in stage.comSpawnBlocks + stage.playerSpawnBlocks) {
                assertTrue(
                    "seed=$seed 에서 스폰 $block 이 본진에 닿지 못한다",
                    reachesBase(stage, block),
                )
            }
        }
    }

    @Test
    fun `맵이 벽으로 꽉 차지 않는다`() {
        for (seed in 1L..20L) {
            val stage = generator.generate(seed, playerCount = 4)
            val walkable = stage.cells.count { TileType.fromId(it).isWalkable }
            val ratio = walkable.toFloat() / stage.cells.size
            assertTrue("seed=$seed 통행 가능 비율이 $ratio 로 너무 낮다", ratio > 0.45f)
        }
    }

    // --- 리소스 활용 ------------------------------------------------------

    @Test
    fun `매 스테이지 프롭 팔레트가 달라진다`() {
        val palettes = (1L..20L).map {
            generator.generate(it, playerCount = 4).theme.propPalette.toSet()
        }.toSet()
        assertTrue("팔레트가 최소 8종류는 나와야 한다", palettes.size >= 8)
    }

    @Test
    fun `프롭 팔레트는 매니페스트 그룹에서만 뽑는다`() {
        val known = manifest.propGroupIds.toSet()
        for (seed in 1L..20L) {
            val stage = generator.generate(seed, playerCount = 4)
            assertTrue(
                "알 수 없는 프롭 그룹: ${stage.theme.propPalette - known}",
                known.containsAll(stage.theme.propPalette),
            )
        }
    }

    @Test
    fun `스테이지마다 여러 종류의 타일이 실제로 쓰인다`() {
        var sawWater = 0
        var sawForest = 0
        var sawSteel = 0
        var sawIce = 0
        for (seed in 1L..30L) {
            val stage = generator.generate(seed, playerCount = 4)
            val types = stage.cells.map { TileType.fromId(it) }.toSet()
            if (TileType.WATER in types) sawWater++
            if (TileType.FOREST in types) sawForest++
            if (TileType.STEEL in types) sawSteel++
            if (TileType.ICE in types) sawIce++
        }
        assertTrue("물이 거의 안 나온다 ($sawWater/30)", sawWater >= 20)
        assertTrue("숲이 거의 안 나온다 ($sawForest/30)", sawForest >= 20)
        assertTrue("강철이 거의 안 나온다 ($sawSteel/30)", sawSteel >= 20)
        assertTrue("얼음이 한 번도 안 나온다", sawIce >= 1)
    }

    @Test
    fun `환경 오브젝트가 매 스테이지 배치된다`() {
        // 소품은 블록보다 작아 셀 하나에 통째로 그린다. (가이드 §4.8)
        for (seed in 1L..20L) {
            val stage = generator.generate(seed, playerCount = 4)
            assertTrue("seed=$seed 소품이 하나도 없다", stage.wholeSpriteCells.isNotEmpty())
        }
    }

    @Test
    fun `참조하는 스프라이트가 모두 프로필이 허용한 것이다`() {
        // 규칙의 출처가 매니페스트에서 mapgen.json 으로 옮겨 왔다. 이제 그림 후보는
        // variation 표가 정한다. 표 밖의 이름이 나오면 렌더러가 못 찾아 터진다.
        val allowed = buildSet {
            for (type in listOf(
                "DIRT", "CRACKED_DIRT", "SCORCHED_DIRT", "DRY_GRASS", "LUSH_GRASS",
                "ICE", "BRICK", "STEEL", "FOREST",
            )) {
                addAll(profile.variation(type)?.names.orEmpty())
            }
            for (group in manifest.propGroupIds) {
                addAll(manifest.propGroup(group)?.get("sprites")?.asStringList.orEmpty())
            }
        }

        for (seed in 1L..10L) {
            val stage = generator.generate(seed, playerCount = 4)
            val used = (stage.spriteNames + stage.groundNames).toSet()
            assertTrue("seed=$seed 프로필 밖 스프라이트: ${used - allowed}", allowed.containsAll(used))
        }
    }

    @Test
    fun `초기 배치에 런타임 상태 그림을 쓰지 않는다`() {
        // 가이드 §13. 금 간 벽이나 파괴된 본진을 처음부터 깔면 폐허처럼 보인다.
        assertTrue("금지 목록이 비어 있다", profile.runtimeStateOnly.isNotEmpty())
        for (seed in 1L..10L) {
            val stage = generator.generate(seed, playerCount = 4)
            val used = (stage.spriteNames + stage.groundNames).toSet()
            val forbidden = used intersect profile.runtimeStateOnly
            assertTrue("seed=$seed 런타임 상태 그림이 깔렸다: $forbidden", forbidden.isEmpty())
        }
    }

    // --- 헬퍼 -------------------------------------------------------------

    /** 스폰 블록에서 본진 인접 칸까지 BFS 로 닿는지. */
    private fun reachesBase(stage: StageData, spawnBlock: Int): Boolean {
        val w = stage.cellsX
        val h = stage.cellsY
        val (startX, startY) = stage.blockToCell(spawnBlock)
        val (baseX, baseY) = stage.blockToCell(stage.baseBlock)

        val visited = BooleanArray(w * h)
        val queue = ArrayDeque<Int>()
        val start = startY * w + startX
        visited[start] = true
        queue += start

        val neighbours = arrayOf(
            intArrayOf(0, -1), intArrayOf(1, 0), intArrayOf(0, 1), intArrayOf(-1, 0),
        )

        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            val cx = current % w
            val cy = current / w

            // 본진 블록에 인접했으면 도달로 본다.
            for (dy in 0 until Constants.CELLS_PER_BLOCK) {
                for (dx in 0 until Constants.CELLS_PER_BLOCK) {
                    if (kotlin.math.abs(cx - (baseX + dx)) + kotlin.math.abs(cy - (baseY + dy)) <= 1) {
                        return true
                    }
                }
            }

            for (dir in neighbours) {
                val nx = cx + dir[0]
                val ny = cy + dir[1]
                if (nx !in 0 until w || ny !in 0 until h) continue
                val next = ny * w + nx
                if (visited[next] || !stage.typeAt(nx, ny).isWalkable) continue
                visited[next] = true
                queue += next
            }
        }
        return false
    }
}
