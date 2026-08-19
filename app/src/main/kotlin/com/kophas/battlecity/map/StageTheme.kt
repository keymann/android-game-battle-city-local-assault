package com.kophas.battlecity.map

/**
 * 스테이지마다 굴리는 성격표.
 *
 * 이 값이 달라지면 같은 알고리즘이라도 완전히 다른 인상의 맵이 나온다.
 * 밀도는 절대값이 아니라 **프로필이 정한 범위 안에서 어느 쪽에 붙일지**를 뜻하는
 * 0~1 가중치다. 규칙은 mapgen.json 이 정하고 성격표는 그 안에서만 흔든다.
 */
data class StageTheme(
    val biome: Biome,
    val structureDensity: Float,
    val waterWeight: Float,
    val iceWeight: Float,
    val forestWeight: Float,
    val propPalette: List<String>,
    val mirrorX: Boolean,
) {
    /** 바닥 기조. 이웃 지형이 없는 빈 땅에서 무엇을 깔지 정한다. */
    enum class Biome { GRASS, DIRT, MIXED }

    companion object {
        fun roll(rng: Rng, propGroupIds: List<String>): StageTheme {
            val biome = when (rng.nextInt(100)) {
                in 0..44 -> Biome.GRASS
                in 45..79 -> Biome.DIRT
                else -> Biome.MIXED
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
                structureDensity = rng.nextFloat(0.25f, 0.85f),
                waterWeight = w1 / total,
                iceWeight = w2 / total,
                forestWeight = w3 / total,
                propPalette = palette,
                mirrorX = !rng.chance(0.2f),
            )
        }
    }
}
