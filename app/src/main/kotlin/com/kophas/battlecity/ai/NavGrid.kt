package com.kophas.battlecity.ai

import com.kophas.battlecity.core.Constants
import com.kophas.battlecity.map.TileMap
import com.kophas.battlecity.map.TileType

/**
 * AI 가 길을 찾는 격자. (계획서 §10)
 *
 * 노드는 **탱크가 설 수 있는 자리**다. 탱크는 정확히 한 블록(2x2 셀)이므로
 * 노드 `(nx, ny)` 는 좌상단이 `(nx*32, ny*32)` 인 자리를 뜻하고 셀
 * `nx..nx+1`, `ny..ny+1` 네 칸을 덮는다. 그래서 노드 수는 셀 수보다 한 줄씩 적다.
 *
 * 셀이 아니라 이 격자로 길을 찾는 이유는, 셀 단위로 찾으면 탱크가 실제로는
 * 들어갈 수 없는 한 칸짜리 틈을 경로로 잡기 때문이다.
 *
 * 노드는 세 갈래다.
 *   - [FREE] 그냥 지나간다
 *   - [SOFT] 벽돌이 막고 있다. 부수면 지나갈 수 있다
 *   - [HARD] 강철·물·본진. 어떻게 해도 못 지나간다
 *
 * 길찾기는 [FREE] 만으로 먼저 시도하고, 그래도 못 가면 [SOFT] 를 허용해 다시 찾는다.
 * 벽을 부수는 쪽이 조금 빠르다고 늘 부수면 원작의 느낌이 사라진다. 돌아갈 길이
 * 아예 없을 때만 뚫는다.
 */
class NavGrid(private val map: TileMap) {

    val width: Int = map.cellsX - 1
    val height: Int = map.cellsY - 1
    val nodeCount: Int = width * height

    private val kinds = ByteArray(nodeCount)

    /** 벽이 부서지면 격자가 낡는다. 다음 질의 직전에 한 번만 다시 만든다. */
    private var dirty = true

    // 길찾기 작업 배열. 매번 새로 잡지 않는다. (계획서 §25 GC 압력)
    private val parent = IntArray(nodeCount)
    private val depth = IntArray(nodeCount)
    private val queue = IntArray(nodeCount)
    private val stamp = IntArray(nodeCount)
    private val goalStamp = IntArray(nodeCount)
    private var generation = 0
    private var goalGeneration = 0

    fun markDirty() {
        dirty = true
    }

    fun refresh() {
        for (ny in 0 until height) {
            for (nx in 0 until width) {
                kinds[ny * width + nx] = classify(nx, ny)
            }
        }
        dirty = false
    }

    private fun refreshIfDirty() {
        if (dirty) refresh()
    }

    fun kindOf(node: Int): Byte {
        refreshIfDirty()
        return if (node in 0 until nodeCount) kinds[node] else HARD
    }

    fun inBounds(nx: Int, ny: Int): Boolean = nx in 0 until width && ny in 0 until height

    fun nodeOf(nx: Int, ny: Int): Int = ny * width + nx

    fun nodeX(node: Int): Int = node % width

    fun nodeY(node: Int): Int = node / width

    /** 논리 픽셀 좌표(탱크 좌상단)를 가장 가까운 노드로. */
    fun nodeAt(x: Float, y: Float): Int {
        val nx = Math.round(x / Constants.CELL_PX).coerceIn(0, width - 1)
        val ny = Math.round(y / Constants.CELL_PX).coerceIn(0, height - 1)
        return ny * width + nx
    }

    /** 노드의 기준점(탱크 좌상단이 놓일 논리 픽셀). */
    fun nodePxX(node: Int): Float = nodeX(node) * Constants.CELL_PX

    fun nodePxY(node: Int): Float = nodeY(node) * Constants.CELL_PX

    fun nodeCenterX(node: Int): Float = nodePxX(node) + Constants.BLOCK_PX * 0.5f

    fun nodeCenterY(node: Int): Float = nodePxY(node) + Constants.BLOCK_PX * 0.5f

    private fun classify(nx: Int, ny: Int): Byte {
        var soft = false
        for (dy in 0 until Constants.CELLS_PER_BLOCK) {
            for (dx in 0 until Constants.CELLS_PER_BLOCK) {
                val type = map.typeAt(nx + dx, ny + dy)
                if (!type.blocksTank) continue
                // 본진도 부술 수는 있지만 그 자리에 설 수는 없다. 옆에서 쏴야 한다.
                if (type == TileType.BRICK) soft = true else return HARD
            }
        }
        return if (soft) SOFT else FREE
    }

    // -----------------------------------------------------------------------
    // 길찾기
    // -----------------------------------------------------------------------

    /**
     * [start] 에서 [goals] 중 아무 곳에나 닿는 최단 경로.
     *
     * 너비 우선이라 칸 수 기준 최단이다. 대각선은 쓰지 않는다. 탱크가 4방향으로만
     * 움직이기 때문에 대각선 경로는 따라갈 수가 없다.
     *
     * @param out 경로를 담을 배열. [start] 는 넣지 않고 **다음 노드부터** 채운다.
     *            배열 길이가 곧 탐색 깊이 상한이다.
     * @return 채워 넣은 칸 수. 0 이면 경로가 없거나 이미 목적지에 있다.
     */
    fun findPath(start: Int, goals: IntArray, allowSoft: Boolean, out: IntArray): Int {
        refreshIfDirty()
        if (goals.isEmpty() || out.isEmpty()) return 0
        if (start !in 0 until nodeCount) return 0

        goalGeneration++
        var hasGoal = false
        for (goal in goals) {
            if (goal in 0 until nodeCount) {
                goalStamp[goal] = goalGeneration
                hasGoal = true
            }
        }
        if (!hasGoal) return 0
        if (goalStamp[start] == goalGeneration) return 0

        generation++
        stamp[start] = generation
        parent[start] = -1
        depth[start] = 0

        var head = 0
        var tail = 0
        queue[tail++] = start
        var found = -1

        search@ while (head < tail) {
            val current = queue[head++]
            if (depth[current] >= out.size) continue

            val cx = current % width
            val cy = current / width
            for (d in 0 until 4) {
                val nx = cx + STEP_X[d]
                val ny = cy + STEP_Y[d]
                if (nx < 0 || ny < 0 || nx >= width || ny >= height) continue

                val next = ny * width + nx
                if (stamp[next] == generation) continue
                val kind = kinds[next]
                if (kind == HARD) continue
                if (kind == SOFT && !allowSoft) continue

                stamp[next] = generation
                parent[next] = current
                depth[next] = depth[current] + 1
                if (goalStamp[next] == goalGeneration) {
                    found = next
                    break@search
                }
                queue[tail++] = next
            }
        }

        if (found < 0) return 0

        // 목적지에서 거꾸로 훑으며 뒤에서부터 채운다. 그러면 뒤집을 필요가 없다.
        val length = depth[found]
        var node = found
        var index = length - 1
        while (node != start && index >= 0) {
            out[index--] = node
            node = parent[node]
        }
        return length
    }

    /**
     * [center] 주변에서 탱크가 실제로 설 수 있는 노드들.
     *
     * 본진처럼 그 자리에 올라설 수 없는 목표는 이 주변 노드를 목적지로 삼는다.
     */
    fun freeNodesAround(center: Int, radius: Int, limit: Int = 32): IntArray {
        refreshIfDirty()
        if (center !in 0 until nodeCount) return IntArray(0)
        val cx = center % width
        val cy = center / width
        val result = ArrayList<Int>(limit)

        for (ring in 1..radius) {
            for (ny in cy - ring..cy + ring) {
                for (nx in cx - ring..cx + ring) {
                    // 링 위의 칸만. 안쪽은 앞선 반복에서 이미 봤다.
                    if (kotlin.math.max(kotlin.math.abs(nx - cx), kotlin.math.abs(ny - cy)) != ring) continue
                    if (!inBounds(nx, ny)) continue
                    val node = ny * width + nx
                    if (kinds[node] != FREE) continue
                    result += node
                    if (result.size >= limit) return result.toIntArray()
                }
            }
        }
        return result.toIntArray()
    }

    companion object {
        const val FREE: Byte = 0
        const val SOFT: Byte = 1
        const val HARD: Byte = 2

        private val STEP_X = intArrayOf(0, 1, 0, -1)
        private val STEP_Y = intArrayOf(-1, 0, 1, 0)
    }
}
