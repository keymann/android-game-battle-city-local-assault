package com.kophas.battlecity.map

import com.kophas.battlecity.core.Constants
import com.kophas.battlecity.render.AssetManifest
import com.kophas.battlecity.render.AssetSource
import java.io.File
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `assets/RANDOM_MAP_ASSET_GUIDE.md` §19 최종 체크리스트를 그대로 옮긴 검사다.
 *
 * 가이드는 "이 체크리스트를 모두 통과한 이후에만 맵을 게임 세션에 사용한다" 고 한다.
 * 사람이 매번 눈으로 볼 수는 없으므로 여러 seed 를 돌려 기계가 확인한다.
 */
class MapGuideTest {

    private val assetsDir: File = sequenceOf(
        File("src/main/assets"),
        File("app/src/main/assets"),
    ).firstOrNull { it.isDirectory } ?: error("assets 디렉터리를 찾지 못했다")

    private val source = AssetSource.ofDirectory(assetsDir)

    private val profile = MapGenProfile.load(source)

    private val generator = StageGenerator(AssetManifest.load(source), profile)

    /** 검사에 쓸 표본. seed 하나가 어쩌다 통과하는 것은 뜻이 없다. */
    private fun samples(players: Int = 4, count: Int = 12): List<StageData> =
        (1L..count).map { generator.generate(it * 977L, players) }

    /** 블록 좌상단 셀의 타입. 블록 단위 통계를 낼 때 쓴다. */
    private fun blockType(stage: StageData, bx: Int, by: Int): TileType =
        stage.typeAt(bx * Constants.CELLS_PER_BLOCK, by * Constants.CELLS_PER_BLOCK)

    // --- 필수 구성 요소 ---------------------------------------------------

    @Test
    fun `본진이 정확히 하나다`() {
        for (stage in samples()) {
            var count = 0
            for (by in 0 until stage.blocksY) {
                for (bx in 0 until stage.blocksX) {
                    if (blockType(stage, bx, by) == TileType.BASE) count++
                }
            }
            assertEquals("seed=${stage.seed}", 1, count)
        }
    }

    @Test
    fun `플레이어 수와 스폰 수가 같다`() {
        for (players in 2..4) {
            for (seed in 1L..6L) {
                val stage = generator.generate(seed * 331L, players)
                assertEquals("players=$players", players, stage.playerSpawnBlocks.size)
            }
        }
    }

    @Test
    fun `적 스폰이 맵 상단에 셋 있다`() {
        val expected = profile.root["zones"]?.get("enemySpawnCount")?.asInt ?: 3
        for (stage in samples()) {
            assertEquals(expected, stage.comSpawnBlocks.size)
            for (spawn in stage.comSpawnBlocks) {
                assertEquals("적 스폰은 맨 윗줄이다", 0, spawn / stage.blocksX)
            }
        }
    }

    @Test
    fun `본진이 맵 외곽과 한 칸 이상 떨어져 있다`() {
        for (stage in samples()) {
            val bx = stage.baseBlock % stage.blocksX
            val by = stage.baseBlock / stage.blocksX
            assertTrue("seed=${stage.seed}", bx in 1 until stage.blocksX - 1)
            assertTrue("seed=${stage.seed}", by in 1 until stage.blocksY - 1)
        }
    }

    // --- 스폰 안전성 (가이드 §6) ------------------------------------------

    @Test
    fun `모든 스폰 주변 3x3 이 막혀 있지 않다`() {
        for (stage in samples()) {
            val spawns = stage.playerSpawnBlocks + stage.comSpawnBlocks
            for (spawn in spawns) {
                val bx = spawn % stage.blocksX
                val by = spawn / stage.blocksX
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        val x = bx + dx
                        val y = by + dy
                        if (x !in 0 until stage.blocksX || y !in 0 until stage.blocksY) continue
                        val type = blockType(stage, x, y)
                        assertTrue(
                            "seed=${stage.seed} 스폰 ($bx,$by) 옆 ($x,$y) 이 $type 다",
                            !type.blocksTank,
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `플레이어 스폰끼리 충분히 떨어져 있다`() {
        val minimum = profile.root["zones"]?.get("minPlayerSpawnSeparation")?.asInt ?: 3
        for (stage in samples()) {
            val spawns = stage.playerSpawnBlocks
            for (i in spawns.indices) {
                for (j in i + 1 until spawns.size) {
                    val distance = manhattan(stage, spawns[i], spawns[j])
                    assertTrue("seed=${stage.seed} 거리 $distance", distance >= minimum)
                }
            }
        }
    }

    @Test
    fun `적 스폰이 본진에서 맵 높이의 절반 넘게 떨어져 있다`() {
        val ratio = profile.root["zones"]?.get("minEnemyToBaseHeightRatio")?.asFloat ?: 0.55f
        for (stage in samples()) {
            val minimum = (stage.blocksY * ratio).toInt()
            for (spawn in stage.comSpawnBlocks) {
                val distance = manhattan(stage, spawn, stage.baseBlock)
                assertTrue("seed=${stage.seed} 거리 $distance < $minimum", distance >= minimum)
            }
        }
    }

    // --- 연결성과 공정성 (가이드 §9, §10) ---------------------------------

    @Test
    fun `모든 맵이 검증을 통과한 상태로 나온다`() {
        val minScore = profile.ruleInt("minimumAcceptScore", 85)
        for (stage in samples(count = 20)) {
            val report = stage.report
            assertNotNull("seed=${stage.seed} 검증 결과가 없다", report)
            assertTrue(
                "seed=${stage.seed} 검증 실패: ${report!!.failures}",
                report.passed,
            )
            assertTrue("seed=${stage.seed} 점수 ${report.score}", report.score >= minScore)
        }
    }

    @Test
    fun `연결 비율이 기준을 넘는다`() {
        val minimum = profile.rule("minimumHardConnectedRatio", 0.90f)
        for (stage in samples()) {
            val ratio = stage.report!!.hardConnectedRatio
            assertTrue("seed=${stage.seed} 연결 $ratio < $minimum", ratio >= minimum)
        }
    }

    @Test
    fun `본진 출구가 셋 이상이다`() {
        val minimum = profile.ruleInt("minimumBaseExits", 3)
        for (stage in samples()) {
            val exits = stage.report!!.baseExitCount
            assertTrue("seed=${stage.seed} 출구 $exits < $minimum", exits >= minimum)
        }
    }

    @Test
    fun `모든 스폰에서 본진까지 탱크가 갈 수 있다`() {
        // 리포트와 별개로, 실제 탱크 발자국(2x2 셀)으로 한 번 더 확인한다.
        for (stage in samples()) {
            for (spawn in stage.playerSpawnBlocks + stage.comSpawnBlocks) {
                assertTrue("seed=${stage.seed} 스폰 $spawn 이 본진과 끊겼다", reaches(stage, spawn))
            }
        }
    }

    // --- 분포와 군집 (가이드 §7, §17) -------------------------------------

    @Test
    fun `지형 밀도가 프로필 범위 안이다`() {
        for (stage in samples()) {
            val total = stage.blocksX * stage.blocksY
            var brick = 0
            var permanent = 0
            var water = 0
            var cover = 0
            var walkable = 0
            for (by in 0 until stage.blocksY) {
                for (bx in 0 until stage.blocksX) {
                    when (blockType(stage, bx, by)) {
                        TileType.BRICK -> brick++
                        TileType.STEEL -> permanent++
                        TileType.WATER -> water++
                        TileType.FOREST -> { cover++; walkable++ }
                        TileType.BASE -> Unit
                        else -> walkable++
                    }
                }
            }
            fun ratio(value: Int) = value.toFloat() / total
            assertInRange(stage, "brick", ratio(brick))
            assertInRange(stage, "permanentObstacle", ratio(permanent))
            assertInRange(stage, "water", ratio(water))
            assertInRange(stage, "cover", ratio(cover))
            assertTrue(
                "seed=${stage.seed} 이동 가능 ${ratio(walkable)} 이 너무 좁다",
                ratio(walkable) >= profile.density("walkable").start,
            )
        }
    }

    @Test
    fun `지형이 흩뿌려지지 않고 군집을 이룬다`() {
        // 셀마다 독립 난수로 뿌리면 소금·후추가 된다. (가이드 §17)
        for (stage in samples()) {
            var filled = 0
            var clustered = 0
            for (by in 0 until stage.blocksY) {
                for (bx in 0 until stage.blocksX) {
                    val type = blockType(stage, bx, by)
                    if (!type.blocksTank || type == TileType.BASE) continue
                    filled++
                    val hasTwin = listOf(0 to -1, 1 to 0, 0 to 1, -1 to 0).any { (dx, dy) ->
                        val x = bx + dx
                        val y = by + dy
                        x in 0 until stage.blocksX && y in 0 until stage.blocksY &&
                            blockType(stage, x, y) == type
                    }
                    if (hasTwin) clustered++
                }
            }
            val ratio = clustered.toFloat() / filled
            assertTrue("seed=${stage.seed} 군집 비율 $ratio", ratio >= 0.55f)
        }
    }

    // --- 결정론 (가이드 §1-4) ---------------------------------------------

    @Test
    fun `같은 seed 는 검증 결과까지 같다`() {
        for (players in 2..4) {
            val a = generator.generate(4242L, players)
            val b = generator.generate(4242L, players)
            assertEquals(a.gridHash, b.gridHash)
            assertEquals(a.report, b.report)
            assertTrue(a.groundNames.contentEquals(b.groundNames))
        }
    }

    // --- 헬퍼 -------------------------------------------------------------

    private fun assertInRange(stage: StageData, key: String, value: Float) {
        val range = profile.density(key)
        assertTrue(
            "seed=${stage.seed} $key = $value 가 $range 밖이다",
            value in range,
        )
    }

    private fun manhattan(stage: StageData, a: Int, b: Int): Int =
        abs(a % stage.blocksX - b % stage.blocksX) + abs(a / stage.blocksX - b / stage.blocksX)

    /** 탱크 발자국(2x2 셀)으로 스폰에서 본진 앞까지 닿는지. */
    private fun reaches(stage: StageData, spawnBlock: Int): Boolean {
        val width = stage.cellsX - 1
        val height = stage.cellsY - 1
        fun open(nx: Int, ny: Int): Boolean =
            (0..1).all { dy -> (0..1).all { dx -> !stage.typeAt(nx + dx, ny + dy).blocksTank } }

        val (sx, sy) = stage.blockToCell(spawnBlock)
        val (bx, by) = stage.blockToCell(stage.baseBlock)
        val goals = HashSet<Int>()
        for (d in -2..3) {
            for (node in listOf(bx + d to by - 2, bx + d to by + 3, bx - 2 to by + d, bx + 3 to by + d)) {
                if (node.first in 0 until width && node.second in 0 until height) {
                    goals += node.second * width + node.first
                }
            }
        }

        val visited = BooleanArray(width * height)
        val queue = ArrayDeque<Int>()
        val start = sy.coerceAtMost(height - 1) * width + sx.coerceAtMost(width - 1)
        visited[start] = true
        queue += start

        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (current in goals) return true
            val cx = current % width
            val cy = current / width
            for ((dx, dy) in listOf(0 to -1, 1 to 0, 0 to 1, -1 to 0)) {
                val nx = cx + dx
                val ny = cy + dy
                if (nx < 0 || ny < 0 || nx >= width || ny >= height) continue
                val next = ny * width + nx
                if (visited[next] || !open(nx, ny)) continue
                visited[next] = true
                queue += next
            }
        }
        return false
    }
}
