package com.kophas.battlecity.ai

import com.kophas.battlecity.core.Constants
import com.kophas.battlecity.map.TileMap
import com.kophas.battlecity.map.TileType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 길찾기 격자. (계획서 §10) */
class NavGridTest {

    private val out = IntArray(64)

    @Test
    fun `노드는 셀보다 한 줄씩 적다`() {
        // 노드는 탱크가 설 자리다. 탱크가 2x2 셀을 덮으니 마지막 줄에는 설 수 없다.
        val stage = AiFixture.stage()
        val nav = AiFixture.navOf(stage)
        assertEquals(stage.cellsX - 1, nav.width)
        assertEquals(stage.cellsY - 1, nav.height)
    }

    @Test
    fun `빈칸은 FREE 벽돌은 SOFT 강철과 물은 HARD 다`() {
        val stage = AiFixture.stage { set ->
            set(4, 4, TileType.BRICK)
            set(8, 4, TileType.STEEL)
            set(12, 4, TileType.WATER)
        }
        val nav = AiFixture.navOf(stage)
        assertEquals(NavGrid.FREE, nav.kindOf(nav.nodeOf(2, 2)))
        assertEquals(NavGrid.SOFT, nav.kindOf(nav.nodeOf(4, 4)))
        assertEquals(NavGrid.HARD, nav.kindOf(nav.nodeOf(8, 4)))
        assertEquals(NavGrid.HARD, nav.kindOf(nav.nodeOf(12, 4)))
    }

    @Test
    fun `본진 자리에는 설 수 없다`() {
        // 본진은 부술 수 있지만 그 위에 올라설 수는 없다. 옆에서 쏴야 한다.
        val stage = AiFixture.stage()
        val nav = AiFixture.navOf(stage)
        val bx = (stage.baseBlock % stage.blocksX) * Constants.CELLS_PER_BLOCK
        val by = (stage.baseBlock / stage.blocksX) * Constants.CELLS_PER_BLOCK
        assertEquals(NavGrid.HARD, nav.kindOf(nav.nodeOf(bx, by)))
    }

    @Test
    fun `빈 맵에서는 맨해튼 거리가 곧 경로 길이다`() {
        val nav = AiFixture.navOf(AiFixture.stage())
        val start = nav.nodeOf(2, 2)
        val goal = nav.nodeOf(7, 5)
        val length = nav.findPath(start, intArrayOf(goal), allowSoft = false, out = out)
        assertEquals((7 - 2) + (5 - 2), length)
        assertEquals(goal, out[length - 1])
    }

    @Test
    fun `한 칸짜리 틈은 경로가 되지 않는다`() {
        // 셀 단위로 길을 찾으면 여기를 지나갈 수 있다고 착각한다. 탱크는 두 칸을
        // 차지하므로 실제로는 못 지나간다. 격자를 노드 단위로 만든 이유가 이것이다.
        val stage = AiFixture.stage { set ->
            for (cy in 0 until 18) set(10, cy, TileType.STEEL)
            set(10, 5, TileType.EMPTY)
        }
        val nav = AiFixture.navOf(stage)
        val length = nav.findPath(
            nav.nodeOf(4, 5),
            intArrayOf(nav.nodeOf(14, 5)),
            allowSoft = false,
            out = out,
        )
        assertEquals(0, length)
    }

    @Test
    fun `두 칸짜리 틈은 지나간다`() {
        val stage = AiFixture.stage { set ->
            for (cy in 0 until 18) set(10, cy, TileType.STEEL)
            set(10, 5, TileType.EMPTY)
            set(10, 6, TileType.EMPTY)
        }
        val nav = AiFixture.navOf(stage)
        val length = nav.findPath(
            nav.nodeOf(4, 5),
            intArrayOf(nav.nodeOf(14, 5)),
            allowSoft = false,
            out = out,
        )
        assertTrue("두 칸이 열렸으면 지나갈 수 있어야 한다", length > 0)
    }

    @Test
    fun `벽돌로 막히면 부수는 경로만 남는다`() {
        val stage = AiFixture.stage { set ->
            for (cy in 0 until 18) set(10, cy, TileType.BRICK)
        }
        val nav = AiFixture.navOf(stage)
        val start = nav.nodeOf(4, 5)
        val goal = intArrayOf(nav.nodeOf(14, 5))

        assertEquals("돌아갈 길이 없다", 0, nav.findPath(start, goal, allowSoft = false, out = out))
        assertTrue("부수면 갈 수 있다", nav.findPath(start, goal, allowSoft = true, out = out) > 0)
    }

    @Test
    fun `돌아갈 길이 있으면 벽을 뚫지 않는다`() {
        // 아래쪽 두 줄이 열려 있다. 부수는 경로를 쓰지 않고도 닿아야 한다.
        val stage = AiFixture.stage { set ->
            for (cy in 0 until 14) set(10, cy, TileType.BRICK)
        }
        val nav = AiFixture.navOf(stage)
        val length = nav.findPath(
            nav.nodeOf(4, 5),
            intArrayOf(nav.nodeOf(14, 5)),
            allowSoft = false,
            out = out,
        )
        assertTrue("우회로가 있으면 그쪽으로 가야 한다", length > 0)
        for (i in 0 until length) {
            assertEquals(NavGrid.FREE, nav.kindOf(out[i]))
        }
    }

    @Test
    fun `벽이 부서지면 다시 길이 생긴다`() {
        val stage = AiFixture.stage { set ->
            for (cy in 0 until 18) set(10, cy, TileType.STEEL)
        }
        val map = TileMap(stage)
        val nav = NavGrid(map)
        val start = nav.nodeOf(4, 5)
        val goal = intArrayOf(nav.nodeOf(14, 5))
        assertEquals(0, nav.findPath(start, goal, allowSoft = false, out = out))

        map.setType(10, 5, TileType.EMPTY)
        map.setType(10, 6, TileType.EMPTY)
        nav.markDirty()
        assertTrue(nav.findPath(start, goal, allowSoft = false, out = out) > 0)
    }

    @Test
    fun `경로는 담을 수 있는 길이를 넘지 않는다`() {
        // 배열 길이가 곧 탐색 깊이 상한이다. 넘치면 엉뚱한 칸이 경로로 남는다.
        val nav = AiFixture.navOf(AiFixture.stage(blocksX = 24, blocksY = 18))
        val short = IntArray(4)
        val length = nav.findPath(
            nav.nodeOf(1, 1),
            intArrayOf(nav.nodeOf(40, 30)),
            allowSoft = false,
            out = short,
        )
        assertEquals("깊이 상한 밖의 목적지는 찾지 못한다", 0, length)
    }

    @Test
    fun `목적지에 이미 서 있으면 경로가 비어 있다`() {
        val nav = AiFixture.navOf(AiFixture.stage())
        val node = nav.nodeOf(3, 3)
        assertEquals(0, nav.findPath(node, intArrayOf(node), allowSoft = false, out = out))
    }

    @Test
    fun `주변 칸 목록에는 설 수 있는 칸만 들어간다`() {
        val stage = AiFixture.stage { set ->
            for (cx in 0 until 24) set(cx, 3, TileType.STEEL)
        }
        val nav = AiFixture.navOf(stage)
        val around = nav.freeNodesAround(nav.nodeOf(6, 6), radius = 3)
        assertTrue(around.isNotEmpty())
        for (node in around) {
            assertEquals(NavGrid.FREE, nav.kindOf(node))
        }
    }

    @Test
    fun `픽셀 좌표와 노드가 서로 맞물린다`() {
        val nav = AiFixture.navOf(AiFixture.stage())
        val node = nav.nodeOf(5, 7)
        assertEquals(5 * Constants.CELL_PX, nav.nodePxX(node), 0.001f)
        assertEquals(7 * Constants.CELL_PX, nav.nodePxY(node), 0.001f)
        assertEquals(node, nav.nodeAt(nav.nodePxX(node), nav.nodePxY(node)))
    }
}
