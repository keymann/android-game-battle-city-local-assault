package com.kophas.battlecity.map

import com.kophas.battlecity.render.AssetManifest

/**
 * 지형 오토타일. (docs/STAGE_GENERATION.md §3-[2])
 *
 * 두 종류를 다룬다.
 *  - **도로**: 이웃 4방향 비트마스크 16가지가 전부 대응되는 완전 세트
 *  - **흙 구역**: 3x3 나인슬라이스. 잔디와 맞닿은 방향으로 조각을 고른다
 *
 * 타일 이름은 전부 매니페스트에서 읽으므로 타일셋을 바꿔도 이 코드는 그대로다.
 */
class AutoTiler(
    manifest: AssetManifest,
    private val rng: Rng,
) {
    private val grass: List<String> = manifest.terrainBase("grass").ifEmpty { listOf("town_000") }

    /** 꽃·잡초가 섞인 잔디. 전부 깔면 화면이 시끄러워 낮은 확률로만 쓴다. */
    private val grassAccents: List<String> =
        manifest.terrain("grass")?.get("accents")?.asStringList ?: emptyList()

    private val grassAccentChance: Float =
        manifest.terrain("grass")?.get("accentChance")?.asFloat ?: 0.14f
    private val dirt: List<String> = manifest.terrainBase("dirt").ifEmpty { listOf("town_040") }
    private val gravel: List<String> = manifest.terrainBase("gravel").ifEmpty { listOf("town_043") }

    private val road: Map<Int, String> =
        (manifest.terrain("road")?.asObject ?: emptyMap())
            .mapNotNull { (key, value) ->
                val mask = key.toIntOrNull() ?: return@mapNotNull null
                val name = value.asString ?: return@mapNotNull null
                mask to name
            }
            .toMap()

    private val dirtPatch: Map<String, String> =
        (manifest.terrain("dirtPatch")?.asObject ?: emptyMap())
            .mapNotNull { (key, value) -> value.asString?.let { key to it } }
            .toMap()

    /** 잔디 바닥. 대부분 민무늬이고 가끔 장식 타일이 섞인다. */
    fun grassTile(): String =
        if (grassAccents.isNotEmpty() && rng.chance(grassAccentChance)) {
            grassAccents[rng.nextInt(grassAccents.size)]
        } else {
            grass[rng.nextInt(grass.size)]
        }

    fun gravelTile(): String = gravel[rng.nextInt(gravel.size)]

    /**
     * 도로 타일.
     *
     * @param mask 도로가 이어지는 방향 비트. N=1, E=2, S=4, W=8
     */
    fun roadTile(mask: Int): String? = road[mask and 0xF]

    /**
     * 흙 구역 타일.
     *
     * @param openN 위쪽이 흙이 **아닌가**(= 잔디와 맞닿았는가)
     */
    fun dirtTile(openN: Boolean, openE: Boolean, openS: Boolean, openW: Boolean): String {
        val key = when {
            openN && openW -> "NW"
            openN && openE -> "NE"
            openS && openW -> "SW"
            openS && openE -> "SE"
            openN -> "N"
            openE -> "E"
            openS -> "S"
            openW -> "W"
            else -> "C"
        }
        // 가운데는 밋밋하지 않도록 흙 변형을 섞는다.
        if (key == "C") return dirt[rng.nextInt(dirt.size)]
        return dirtPatch[key] ?: dirt[rng.nextInt(dirt.size)]
    }

    companion object {
        const val NORTH = 1
        const val EAST = 2
        const val SOUTH = 4
        const val WEST = 8
    }
}
