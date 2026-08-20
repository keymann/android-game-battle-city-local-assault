package com.kophas.battlecity.map

import com.kophas.battlecity.core.Constants

/**
 * 런타임 타일 그리드. (계획서 §30, §44.3)
 *
 * [StageData] 를 복사해 와서 파괴를 반영한다. 스테이지 원본은 그대로 두므로
 * 재시작 시 재생성 없이 되돌릴 수 있고, seed 로 만든 원본과 대조도 가능하다.
 *
 * 좌표계는 두 가지다.
 *  - **셀**: 파괴 최소 단위 (32 논리 px). 충돌 판정의 기준.
 *  - **논리 픽셀**: 탱크/포탄이 쓰는 연속 좌표.
 */
class TileMap(val stage: StageData) {

    val cellsX: Int = stage.cellsX
    val cellsY: Int = stage.cellsY
    val widthPx: Float = stage.widthPx
    val heightPx: Float = stage.heightPx

    private val cells: ByteArray = stage.cells.copyOf()
    private val sprites: ShortArray = stage.cellSprite.copyOf()

    var baseDestroyed: Boolean = false
        private set

    fun reset() {
        stage.cells.copyInto(cells)
        stage.cellSprite.copyInto(sprites)
        baseDestroyed = false
    }

    fun inBounds(cellX: Int, cellY: Int): Boolean =
        cellX in 0 until cellsX && cellY in 0 until cellsY

    /** 맵 밖은 부술 수 없는 벽으로 취급한다. 포탄은 경계에서 터진다. */
    fun typeAt(cellX: Int, cellY: Int): TileType =
        if (!inBounds(cellX, cellY)) TileType.STEEL
        else TileType.fromId(cells[cellY * cellsX + cellX])

    fun spriteIndexAt(cellX: Int, cellY: Int): Int =
        if (!inBounds(cellX, cellY)) -1 else sprites[cellY * cellsX + cellX].toInt()

    fun spriteNameAt(cellX: Int, cellY: Int): String? {
        val index = spriteIndexAt(cellX, cellY)
        return if (index < 0) null else stage.spriteNames[index]
    }

    fun setType(cellX: Int, cellY: Int, type: TileType, spriteIndex: Int = -1) {
        if (!inBounds(cellX, cellY)) return
        val i = cellY * cellsX + cellX
        cells[i] = type.id
        sprites[i] = spriteIndex.toShort()
    }

    /** 사분면으로 쪼개지 않고 스프라이트를 통째로 그려야 하는 셀인가. */
    fun isWholeSprite(cellX: Int, cellY: Int): Boolean =
        inBounds(cellX, cellY) && (cellY * cellsX + cellX) in stage.wholeSpriteCells

    fun isExplosive(cellX: Int, cellY: Int): Boolean =
        inBounds(cellX, cellY) &&
            typeAt(cellX, cellY) == TileType.BRICK &&
            (cellY * cellsX + cellX) in stage.explosiveCells

    // -----------------------------------------------------------------------
    // 좌표 변환
    // -----------------------------------------------------------------------

    fun toCellX(px: Float): Int = Math.floorDiv(px.toInt(), Constants.CELL_PX.toInt())

    fun toCellY(px: Float): Int = Math.floorDiv(px.toInt(), Constants.CELL_PX.toInt())

    // -----------------------------------------------------------------------
    // 충돌 질의
    // -----------------------------------------------------------------------

    /** AABB 가 탱크를 막는 타일과 겹치는지. 맵 경계 밖도 막힌 것으로 본다. */
    fun blocksTank(x: Float, y: Float, width: Float, height: Float): Boolean =
        anyCellIn(x, y, width, height) { it.blocksTank }

    fun blocksBullet(x: Float, y: Float, width: Float, height: Float): Boolean =
        anyCellIn(x, y, width, height) { it.blocksBullet }

    /** AABB 중심이 숲에 있으면 은폐된다. */
    fun conceals(x: Float, y: Float, width: Float, height: Float): Boolean {
        val cx = toCellX(x + width * 0.5f)
        val cy = toCellY(y + height * 0.5f)
        return typeAt(cx, cy).conceals
    }

    /** AABB 아래의 미끄러짐 계수. 얼음 위가 아니면 0. */
    fun slipFactor(x: Float, y: Float, width: Float, height: Float): Float {
        val cx = toCellX(x + width * 0.5f)
        val cy = toCellY(y + height * 0.5f)
        return typeAt(cx, cy).slipFactor
    }

    private inline fun anyCellIn(
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        predicate: (TileType) -> Boolean,
    ): Boolean {
        if (x < 0f || y < 0f || x + width > widthPx || y + height > heightPx) return true

        val cell = Constants.CELL_PX
        val minX = Math.floorDiv(x.toInt(), cell.toInt())
        val minY = Math.floorDiv(y.toInt(), cell.toInt())
        // 오른쪽/아래 경계에 딱 붙은 경우를 포함하지 않도록 아주 작은 값을 뺀다.
        val maxX = Math.floorDiv((x + width - EPSILON).toInt(), cell.toInt())
        val maxY = Math.floorDiv((y + height - EPSILON).toInt(), cell.toInt())

        for (cy in minY..maxY) {
            for (cx in minX..maxX) {
                if (predicate(typeAt(cx, cy))) return true
            }
        }
        return false
    }

    // -----------------------------------------------------------------------
    // 파괴
    // -----------------------------------------------------------------------

    /**
     * 한 셀에 피해를 준다.
     *
     * @return 실제로 파괴됐으면 true
     */
    fun damageCell(cellX: Int, cellY: Int, piercing: Boolean): DamageResult {
        if (!inBounds(cellX, cellY)) return DamageResult.NONE
        return when (val type = typeAt(cellX, cellY)) {
            TileType.BRICK -> {
                val chain = isExplosive(cellX, cellY)
                setType(cellX, cellY, TileType.EMPTY)
                if (chain) DamageResult.EXPLODED else DamageResult.DESTROYED
            }

            TileType.STEEL -> {
                // 강철은 관통탄으로만 부술 수 있다. (계획서 §6.1 특수기)
                if (piercing) {
                    setType(cellX, cellY, TileType.EMPTY)
                    DamageResult.DESTROYED
                } else {
                    DamageResult.BLOCKED
                }
            }

            TileType.BASE -> {
                baseDestroyed = true
                DamageResult.BASE_HIT
            }

            else -> if (type.blocksBullet) DamageResult.BLOCKED else DamageResult.NONE
        }
    }

    /**
     * 폭발성 프롭의 연쇄 폭발. 반경 안의 파괴 가능한 셀을 모두 날린다.
     *
     * @return 연쇄로 추가 폭발이 일어난 셀 목록 (재귀는 호출자가 처리한다)
     */
    fun explode(cellX: Int, cellY: Int, radiusCells: Int): List<Int> {
        val chained = ArrayList<Int>()
        for (dy in -radiusCells..radiusCells) {
            for (dx in -radiusCells..radiusCells) {
                if (dx * dx + dy * dy > radiusCells * radiusCells) continue
                val tx = cellX + dx
                val ty = cellY + dy
                if (!inBounds(tx, ty)) continue
                when (typeAt(tx, ty)) {
                    TileType.BRICK -> {
                        if (isExplosive(tx, ty)) chained += ty * cellsX + tx
                        setType(tx, ty, TileType.EMPTY)
                    }
                    // 본진은 폭발에도 파괴된다. 아군 폭발물이라도 마찬가지다. (계획서 §15)
                    TileType.BASE -> baseDestroyed = true
                    else -> Unit
                }
            }
        }
        return chained
    }

    enum class DamageResult {
        /** 아무 일도 없었다. 포탄은 계속 날아간다. */
        NONE,

        /** 막혔지만 부서지지 않았다. 포탄은 여기서 터진다. */
        BLOCKED,

        /** 파괴했다. */
        DESTROYED,

        /** 폭발성 프롭을 터뜨렸다. */
        EXPLODED,

        /** 본진을 맞혔다. 즉시 GAME OVER. */
        BASE_HIT,
        ;

        val stopsBullet: Boolean get() = this != NONE
    }

    private companion object {
        const val EPSILON = 0.001f
    }
}
