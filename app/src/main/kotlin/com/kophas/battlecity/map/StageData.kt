package com.kophas.battlecity.map

import com.kophas.battlecity.core.Constants

/**
 * 생성이 끝난 스테이지. (docs/STAGE_GENERATION.md §4)
 *
 * [cells] 만이 게임 로직의 진실이고 [ground] / [decor] 는 렌더 전용이다.
 * (계획서 §41-1 로직과 렌더링 분리)
 *
 * 맵은 **가로:세로 16:9** 다. 게임이 항상 landscape 로 돌고 요즘 기기가 대부분
 * 16:9 보다 넓기 때문에, 이보다 좁으면 좌우를 크게 낭비한다. (계획서 §21 논리 해상도)
 */
class StageData(
    val blocksX: Int,
    val blocksY: Int,
    val cells: ByteArray,
    /** 셀별 오브젝트 스프라이트. [spriteNames] 인덱스, -1 이면 없음. */
    val cellSprite: ShortArray,
    val spriteNames: Array<String>,
    /** 블록별 바닥 스프라이트. [groundNames] 인덱스. */
    val ground: ShortArray,
    val groundNames: Array<String>,
    val decor: List<Decor>,
    /** 폭발성 프롭이 놓인 셀 인덱스. 파괴되면 연쇄 폭발한다. */
    val explosiveCells: Set<Int>,
    /**
     * 스프라이트를 사분면으로 쪼개지 않고 통째로 그릴 셀.
     *
     * 벽은 블록에 한 장을 걸쳐 놓고 셀마다 자기 사분면을 그리지만, 드럼통처럼
     * 한 칸짜리 물건은 그러면 4분의 1만 보인다. 그런 셀을 여기 표시한다.
     */
    val wholeSpriteCells: Set<Int> = emptySet(),
    /** 본진 블록 인덱스. */
    val baseBlock: Int,
    val comSpawnBlocks: IntArray,
    val playerSpawnBlocks: IntArray,
    val theme: StageTheme,
    val seed: Long,
    /** 생성기가 매긴 검증 결과. 로그와 테스트가 읽는다. (가이드 §14) */
    val report: StageGenerator.Report? = null,
) {
    val cellsX: Int = blocksX * Constants.CELLS_PER_BLOCK
    val cellsY: Int = blocksY * Constants.CELLS_PER_BLOCK

    val widthPx: Float = blocksX * Constants.BLOCK_PX
    val heightPx: Float = blocksY * Constants.BLOCK_PX

    /** 논리 해상도의 가로:세로. 16:9 여야 한다. */
    val aspect: Float = widthPx / heightPx

    /**
     * Host / Client 대조용 FNV-1a 해시.
     * 같은 seed 로 같은 그리드가 나왔는지 한 번에 확인한다.
     */
    val gridHash: Long = run {
        var hash = FNV_OFFSET
        for (byte in cells) {
            hash = hash xor (byte.toLong() and 0xFF)
            hash *= FNV_PRIME
        }
        hash
    }

    fun typeAt(cellX: Int, cellY: Int): TileType =
        if (cellX < 0 || cellY < 0 || cellX >= cellsX || cellY >= cellsY) {
            TileType.STEEL // 맵 경계는 부술 수 없는 벽으로 취급한다.
        } else {
            TileType.fromId(cells[cellY * cellsX + cellX])
        }

    fun blockToCell(blockIndex: Int): Pair<Int, Int> {
        val bx = blockIndex % blocksX
        val by = blockIndex / blocksX
        return bx * Constants.CELLS_PER_BLOCK to by * Constants.CELLS_PER_BLOCK
    }

    /** 블록 인덱스를 논리 픽셀 좌표(블록 좌상단)로. */
    fun blockToPx(blockIndex: Int): Pair<Float, Float> {
        val bx = blockIndex % blocksX
        val by = blockIndex / blocksX
        return bx * Constants.BLOCK_PX to by * Constants.BLOCK_PX
    }

    data class Decor(
        val spriteIndex: Short,
        val x: Float,
        val y: Float,
        val width: Float,
        val height: Float,
        val rotation: Float,
        val layer: Int,
        val alpha: Float = 1f,
    )

    private companion object {
        const val FNV_OFFSET = -0x340d631b7bdddcdbL
        const val FNV_PRIME = 0x100000001b3L
    }
}
