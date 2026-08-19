package com.kophas.battlecity.map

/**
 * 스테이지마다 굴리는 성격표. (docs/STAGE_GENERATION.md §3-[1])
 *
 * 이 값이 달라지면 같은 알고리즘이라도 완전히 다른 인상의 맵이 나온다.
 */
data class StageTheme(
    val biome: Biome,
    val roadStyle: RoadStyle,
    val structureDensity: Float,
    val waterWeight: Float,
    val iceWeight: Float,
    val forestWeight: Float,
    val propPalette: List<String>,
    val mirrorX: Boolean,
) {
    /** 바닥 구성. DIRT 는 흙이 넓게 깔린 개활지, MIXED 는 대각선으로 갈린다. */
    enum class Biome { GRASS, DIRT, MIXED }

    enum class RoadStyle { NONE, CROSS, RING, GRID }

    companion object {
        fun roll(rng: Rng, propGroupIds: List<String>): StageTheme {
            val biome = when (rng.nextInt(100)) {
                in 0..44 -> Biome.GRASS
                in 45..79 -> Biome.DIRT
                else -> Biome.MIXED
            }
            val roadStyle = when (rng.nextInt(100)) {
                in 0..19 -> RoadStyle.NONE
                in 20..49 -> RoadStyle.CROSS
                in 50..74 -> RoadStyle.RING
                else -> RoadStyle.GRID
            }

            // 해저드 비중을 셋으로 쪼갠다. 합이 1이 되도록 정규화한다.
            val w1 = rng.nextFloat(0.2f, 1f)
            val w2 = rng.nextFloat(0.2f, 1f)
            val w3 = rng.nextFloat(0.2f, 1f)
            val total = w1 + w2 + w3

            val paletteSize = rng.nextInt(3, 6).coerceAtMost(propGroupIds.size)
            val palette = rng.shuffled(propGroupIds).take(paletteSize)

            return StageTheme(
                biome = biome,
                roadStyle = roadStyle,
                structureDensity = rng.nextFloat(0.18f, 0.34f),
                waterWeight = w1 / total,
                iceWeight = w2 / total,
                forestWeight = w3 / total,
                propPalette = palette,
                mirrorX = !rng.chance(0.2f),
            )
        }
    }
}
