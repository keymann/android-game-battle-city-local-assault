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

    private val manifest = AssetManifest.load(AssetSource.ofDirectory(assetsDir))

    private val generator = StageGenerator(manifest)

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
        assertEquals(16, generator.blocksXFor(2))
        assertEquals(20, generator.blocksXFor(3))
        assertEquals(24, generator.blocksXFor(4))
    }

    @Test
    fun `모든 플레이어 수에서 논리 해상도가 가로 4 대 세로 3 이다`() {
        for (players in 2..4) {
            val stage = generator.generate(players.toLong(), players)
            assertEquals(
                "players=$players 비율이 4:3 이 아니다 (${stage.blocksX}x${stage.blocksY})",
                4f / 3f,
                stage.aspect,
                1e-4f,
            )
            assertTrue("가로가 세로보다 길어야 한다", stage.widthPx > stage.heightPx)
        }
    }

    @Test
    fun `2인 맵은 16x12 블록 1024x768 이다`() {
        val stage = generator.generate(1L, playerCount = 2)
        assertEquals(16, stage.blocksX)
        assertEquals(12, stage.blocksY)
        assertEquals(32, stage.cellsX)
        assertEquals(24, stage.cellsY)
        assertEquals(1024f, stage.widthPx, 1e-3f)
        assertEquals(768f, stage.heightPx, 1e-3f)
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
    fun `장식 오브젝트가 매 스테이지 배치된다`() {
        for (seed in 1L..20L) {
            val stage = generator.generate(seed, playerCount = 4)
            assertTrue("seed=$seed 장식이 하나도 없다", stage.decor.isNotEmpty())
        }
    }

    @Test
    fun `참조하는 스프라이트 이름이 모두 매니페스트에서 온 것이다`() {
        val known = buildSet {
            addAll(manifest.tileSprites("BRICK"))
            addAll(manifest.tileSprites("STEEL"))
            addAll(manifest.tileSprites("FOREST"))
            for (group in manifest.propGroupIds) {
                addAll(manifest.propGroup(group)?.get("sprites")?.asStringList.orEmpty())
            }
            val terrain = manifest.root["terrain"]?.asObject.orEmpty()
            for ((groupName, node) in terrain) {
                if (groupName == "tint") continue
                addAll(node["base"]?.asStringList.orEmpty())
                for ((key, value) in node.asObject) {
                    if (key == "atlas" || key == "base" || key == "comment") continue
                    value.asString?.let { add(it) }
                }
            }
        }

        for (seed in 1L..10L) {
            val stage = generator.generate(seed, playerCount = 4)
            val used = stage.spriteNames.toSet() + stage.groundNames.toSet()
            assertTrue("매니페스트 밖 스프라이트: ${used - known}", known.containsAll(used))
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
