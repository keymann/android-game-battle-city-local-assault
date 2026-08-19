package com.kophas.battlecity.map

/**
 * 원작의 타일 종류. (계획서 §30)
 *
 * 판정 규칙은 원작과 동일하게 유지하고, 어떤 스프라이트로 그릴지는
 * `assets/manifest/assets.json` 이 정한다. (docs/ASSET_SELECTION.md §4)
 */
enum class TileType(
    val blocksTank: Boolean,
    val blocksBullet: Boolean,
    val destructible: Boolean,
    /** 탱크를 가려 숨겨 준다(숲). */
    val conceals: Boolean,
    /** 미끄러진다(얼음). 0이면 일반 지면. */
    val slipFactor: Float = 0f,
) {
    EMPTY(blocksTank = false, blocksBullet = false, destructible = false, conceals = false),
    BRICK(blocksTank = true, blocksBullet = true, destructible = true, conceals = false),
    STEEL(blocksTank = true, blocksBullet = true, destructible = false, conceals = false),
    WATER(blocksTank = true, blocksBullet = false, destructible = false, conceals = false),
    FOREST(blocksTank = false, blocksBullet = false, destructible = false, conceals = true),
    ICE(blocksTank = false, blocksBullet = false, destructible = false, conceals = false, slipFactor = 0.12f),
    BASE(blocksTank = true, blocksBullet = true, destructible = true, conceals = false),
    SPAWN(blocksTank = false, blocksBullet = false, destructible = false, conceals = false);

    val isWalkable: Boolean get() = !blocksTank

    val id: Byte get() = ordinal.toByte()

    companion object {
        val VALUES: Array<TileType> = entries.toTypedArray()

        fun fromId(id: Byte): TileType = VALUES[id.toInt()]
    }
}
