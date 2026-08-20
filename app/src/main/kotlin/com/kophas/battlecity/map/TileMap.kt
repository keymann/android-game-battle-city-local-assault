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

    /**
     * 본진 보호막이 아직 남아 있는가. (계획서 §14 protected)
     *
     * 방 설정에서 켜면 본진이 첫 포탄을 한 번 막아 낸다. 한 발에 판이 끝나 버리면
     * 마지막 순간에 손 쓸 도리가 없다. 막아 낸 뒤에는 사라진다.
     */
    var baseShielded: Boolean = false
        private set

    /** 판을 열 때 보호막을 켠다. 방 설정이 정한다. */
    fun enableBaseShield(enabled: Boolean) {
        baseShielded = enabled
    }

    /**
     * 판이 열린 뒤 바뀐 셀. 셀 번호 -> 마지막으로 바뀐 차례.
     *
     * Host 가 이것을 스냅샷에 실어 Client 에게 보낸다. Client 는 규칙을 굴리지 않으므로
     * 알려 주지 않으면 부서진 벽이 화면에 그대로 남는다. 순서를 지키려고 값이 아니라
     * **지금 종류**를 보내므로, 늦게 온 변경이 최신 상태를 되돌리지 않는다.
     */
    private val changedAt = LinkedHashMap<Int, Long>()

    /** 지금 몇 번째 스냅샷인가. Host 가 스냅샷을 뜰 때마다 올린다. */
    private var changeSerial: Long = 0

    /**
     * 최근 [window] 차례 안에 바뀐 셀을 [limit] 개까지 돌려준다.
     *
     * 같은 변경을 여러 차례 거듭 보내는 이유는 스냅샷이 최신 값 채널이라 재전송이
     * 없기 때문이다. 세 번 보내면 연속 두 번 유실까지 견딘다.
     */
    fun collectChanges(window: Int, limit: Int): List<Int> {
        changeSerial++
        val oldest = changeSerial - window
        changedAt.entries.removeAll { it.value < oldest }
        // 오래된 것부터 보낸다. 한 번에 다 못 실으면 다음 차례로 넘어간다.
        return changedAt.keys.take(limit)
    }

    /** 지금 이 셀의 종류. 스냅샷에 실을 값이다. */
    fun typeAtIndex(index: Int): TileType =
        if (index !in cells.indices) TileType.STEEL else TileType.fromId(cells[index])

    /** Host 가 알려 준 셀을 그대로 놓는다. Client 만 쓴다. */
    fun applyRemoteCell(index: Int, type: TileType) {
        if (index !in cells.indices) return
        if (cells[index] == type.id) return
        cells[index] = type.id
        // 부서진 자리에는 그림이 없다. 남겨 두면 사라진 벽이 계속 그려진다.
        if (type == TileType.EMPTY) sprites[index] = -1
    }

    /** Host 가 알려 준 본진 상태를 그대로 놓는다. Client 만 쓴다. */
    fun applyRemoteBase(destroyed: Boolean, shielded: Boolean) {
        baseDestroyed = destroyed
        baseShielded = shielded
    }

    fun reset() {
        stage.cells.copyInto(cells)
        stage.cellSprite.copyInto(sprites)
        baseDestroyed = false
        changedAt.clear()
        changeSerial = 0
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
        if (cells[i] == type.id) return
        cells[i] = type.id
        sprites[i] = spriteIndex.toShort()
        // 바뀐 자리를 적어 둔다. 다시 바뀌면 뒤로 보내 최근 것부터 살아남게 한다.
        changedAt.remove(i)
        changedAt[i] = changeSerial
        // 혼자 하는 판에서는 아무도 걷어 가지 않는다. 한 판 내내 쌓이지 않게 막는다.
        while (changedAt.size > MAX_TRACKED_CHANGES) {
            changedAt.remove(changedAt.keys.first())
        }
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
                if (baseShielded) {
                    // 보호막이 한 발을 먹는다. 다음 발부터는 그대로 들어간다.
                    baseShielded = false
                    DamageResult.BASE_SHIELDED
                } else {
                    baseDestroyed = true
                    DamageResult.BASE_HIT
                }
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
                    TileType.BASE -> if (baseShielded) baseShielded = false else baseDestroyed = true
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

        /** 본진 보호막이 막아 냈다. 이번 한 발은 견딘다. */
        BASE_SHIELDED,
        ;

        val stopsBullet: Boolean get() = this != NONE
    }

    private companion object {
        const val EPSILON = 0.001f

        /** 들고 있을 변경 셀의 최대 수. 걷어 가는 쪽이 없어도 여기서 멈춘다. */
        const val MAX_TRACKED_CHANGES = 512
    }
}
