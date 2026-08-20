package com.kophas.battlecity.map

/**
 * 5x5 셀 패턴 스탬프. (docs/STAGE_GENERATION.md §3-[3])
 *
 * 완전 무작위 배치는 원작 느낌이 나지 않는다. 원작 스테이지에서 반복되는
 * 형태를 조각으로 정의하고 격자에 찍은 뒤 회전/반전을 섞는다.
 *
 * `#` = 벽돌, `S` = 강철, `.` = 빈칸
 */
object StagePatterns {

    const val SIZE = 5

    private val SOURCES: List<Array<String>> = listOf(
        // WALL_H - 가로 벽
        arrayOf(
            ".....",
            "#####",
            "#####",
            ".....",
            ".....",
        ),
        // BOX - 사각 방
        arrayOf(
            "#####",
            "#...#",
            "#...#",
            "#...#",
            "#####",
        ),
        // PILLARS - 기둥
        arrayOf(
            "#.#.#",
            ".....",
            "#.#.#",
            ".....",
            "#.#.#",
        ),
        // MAZE - 미로
        arrayOf(
            "####.",
            "....#",
            ".####",
            "#....",
            ".####",
        ),
        // CHECKER - 체크무늬
        arrayOf(
            "#.#.#",
            ".#.#.",
            "#.#.#",
            ".#.#.",
            "#.#.#",
        ),
        // STEEL_CORE - 강철 요새
        arrayOf(
            ".....",
            ".SSS.",
            ".S.S.",
            ".SSS.",
            ".....",
        ),
        // CORRIDOR - 세로 통로
        arrayOf(
            "##.##",
            "##.##",
            "##.##",
            "##.##",
            "##.##",
        ),
        // BUNKER - 강철 코어를 벽돌이 감싼다
        arrayOf(
            "#####",
            "#SSS#",
            "#S.S#",
            "#SSS#",
            "..#..",
        ),
    )

    /** 회전 4가지 x 좌우 반전 2가지 = 배치당 8가지 변형. */
    val VARIANTS: List<Array<CharArray>> = buildList {
        for (source in SOURCES) {
            var grid = source.map { it.toCharArray() }.toTypedArray()
            repeat(4) {
                add(copy(grid))
                add(flipX(grid))
                grid = rotate(grid)
            }
        }
    }

    fun typeOf(symbol: Char): TileType? = when (symbol) {
        '#' -> TileType.BRICK
        'S' -> TileType.STEEL
        else -> null
    }

    private fun copy(grid: Array<CharArray>): Array<CharArray> =
        Array(SIZE) { row -> grid[row].copyOf() }

    private fun rotate(grid: Array<CharArray>): Array<CharArray> =
        Array(SIZE) { row -> CharArray(SIZE) { col -> grid[SIZE - 1 - col][row] } }

    private fun flipX(grid: Array<CharArray>): Array<CharArray> =
        Array(SIZE) { row -> CharArray(SIZE) { col -> grid[row][SIZE - 1 - col] } }
}
