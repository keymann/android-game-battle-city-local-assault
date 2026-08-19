package com.kophas.battlecity.map

import com.kophas.battlecity.render.AssetManifest

/**
 * 도로망 오토타일. (docs/STAGE_GENERATION.md §3-[2])
 *
 * 블록 단위로 그린 도로 그래프의 4방향 이웃 비트마스크를 보고
 * asset1 도로 타일 이름을 고른다. 스프라이트 이름은 매니페스트에서 읽으므로
 * 타일 세트를 바꿔도 이 코드는 그대로다.
 */
class AutoTiler(
    manifest: AssetManifest,
    private val rng: Rng,
    biome: StageTheme.Biome,
) {
    private val roadGroup = when (biome) {
        StageTheme.Biome.SAND -> "sandRoad"
        else -> "grassRoad"
    }

    private val road: Map<String, String> =
        (manifest.root["terrain"]?.get(roadGroup)?.asObject ?: emptyMap())
            .mapNotNull { (key, value) -> value.asString?.let { key to it } }
            .toMap()

    private val grassBase: List<String> =
        manifest.root["terrain"]?.get("grass")?.get("base")?.asStringList ?: listOf("tileGrass1")

    private val sandBase: List<String> =
        manifest.root["terrain"]?.get("sand")?.get("base")?.asStringList ?: listOf("tileSand1")

    /** 도로가 아닌 블록의 바닥. 같은 타일이 반복돼 보이지 않도록 2종을 섞는다. */
    fun groundTile(biome: StageTheme.Biome, blockX: Int, blockY: Int, blocks: Int): String {
        val useSand = when (biome) {
            StageTheme.Biome.GRASS -> false
            StageTheme.Biome.SAND -> true
            // MIXED 는 대각선으로 두 바이옴을 나눈다.
            StageTheme.Biome.MIXED -> blockX + blockY >= blocks
        }
        val palette = if (useSand) sandBase else grassBase
        return palette[rng.nextInt(palette.size)]
    }

    /**
     * @param mask N=1, E=2, S=4, W=8 비트
     */
    fun roadTile(mask: Int): String? {
        val key = when (mask) {
            NORTH or SOUTH, NORTH, SOUTH -> "N"
            EAST or WEST, EAST, WEST -> "E"
            NORTH or EAST -> "NE"
            NORTH or WEST -> "NW"
            SOUTH or EAST -> "SE"
            SOUTH or WEST -> "SW"
            // 3방향 분기: 빠진 방향의 반대편 이름을 쓴다.
            NORTH or EAST or WEST -> "TN"
            NORTH or EAST or SOUTH -> "TE"
            EAST or SOUTH or WEST -> "TS"
            NORTH or SOUTH or WEST -> "TW"
            NORTH or EAST or SOUTH or WEST -> if (rng.chance(0.25f)) "XR" else "X"
            else -> null
        } ?: return null
        return road[key]
    }

    companion object {
        const val NORTH = 1
        const val EAST = 2
        const val SOUTH = 4
        const val WEST = 8
    }
}
