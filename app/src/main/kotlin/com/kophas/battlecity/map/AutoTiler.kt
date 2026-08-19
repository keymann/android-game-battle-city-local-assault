package com.kophas.battlecity.map

import com.kophas.battlecity.render.AssetManifest

/**
 * 지형 오토타일. (docs/STAGE_GENERATION.md §3-[2])
 *
 * 두 종류를 다룬다.
 *  - **도로**: 직선 구간에만 중앙선 타일, 나머지는 민무늬 아스팔트
 *  - **흙 구역**: 전이 타일이 없어 경계가 각지게 떨어진다
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

    // 새 타일 시트에는 16-mask 도로 오토타일 세트가 없다. 직선 구간만 중앙선을
    // 넣고 모서리·분기·고립은 민무늬 아스팔트로 둔다.
    private val roadVertical: String =
        manifest.terrain("road")?.get("straightVertical")?.asString ?: "env_road_line"

    private val roadHorizontal: String =
        manifest.terrain("road")?.get("straightHorizontal")?.asString ?: "env_road_line_h"

    private val roadPlain: List<String> =
        manifest.terrain("road")?.get("plain")?.asStringList ?: listOf("env_road_0")

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
    fun roadTile(mask: Int): String = when (mask and 0xF) {
        NORTH or SOUTH, NORTH, SOUTH -> roadVertical
        EAST or WEST, EAST, WEST -> roadHorizontal
        else -> roadPlain[rng.nextInt(roadPlain.size)]
    }

    /**
     * 흙 바닥.
     *
     * 새 시트에는 잔디와 흙 사이의 전이 타일이 없어 경계가 각지게 떨어진다.
     * 원작도 타일 경계가 각졌으므로 그대로 둔다.
     */
    fun dirtTile(): String = dirt[rng.nextInt(dirt.size)]

    companion object {
        const val NORTH = 1
        const val EAST = 2
        const val SOUTH = 4
        const val WEST = 8
    }
}
