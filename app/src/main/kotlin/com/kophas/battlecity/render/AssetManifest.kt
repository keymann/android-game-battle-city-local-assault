package com.kophas.battlecity.render

import com.kophas.battlecity.util.JsonValue

/**
 * `assets/manifest/assets.json` 리더.
 *
 * 게임 의미 -> 리소스 매핑을 코드에 하드코딩하지 않기 위한 진입점이다.
 * (계획서 §41-6, §41-19 / docs/ASSET_SELECTION.md)
 *
 * Phase 1 에서는 아틀라스 서술과 월드 규격만 타입으로 노출하고, 나머지 섹션은
 * [root] 로 그대로 열어 둔다. 각 Phase 가 필요해질 때 타입을 붙여 나간다.
 */
class AssetManifest(val root: JsonValue) {

    val version: Int = root["version"]?.asInt ?: 0

    val credits: String = root["credits"]?.asString.orEmpty()

    data class AtlasSpec(
        val key: String,
        val image: String,
        val descriptor: String?,
        val type: String,
        /** 원본 타일 한 변의 픽셀 수. 스프라이트를 몇 배로 확대할지 판단할 때 쓴다. */
        val sourceTile: Int,
        /** `nearest`(픽셀아트) 또는 `linear`(회전하는 벡터풍 스프라이트). */
        val filter: String,
    ) {
        companion object {
            const val TYPE_XML = "xml_subtexture"
        }
    }

    val atlases: Map<String, AtlasSpec> =
        (root["atlases"]?.asObject ?: emptyMap()).mapValues { (key, node) ->
            AtlasSpec(
                key = key,
                image = node["image"]?.asString
                    ?: error("atlases.$key.image 가 없다"),
                descriptor = node["descriptor"]?.asString,
                type = node["type"]?.asString ?: AtlasSpec.TYPE_XML,
                sourceTile = node["sourceTile"]?.asInt ?: 16,
                filter = node["filter"]?.asString ?: "nearest",
            )
        }

    data class World(
        val blockPx: Float,
        val cellPx: Float,
        val cellsPerBlock: Int,
        val tankSizePx: Float,
    )

    val world: World = root["world"].let { node ->
        World(
            blockPx = node?.get("blockPx")?.asFloat ?: 64f,
            cellPx = node?.get("cellPx")?.asFloat ?: 32f,
            cellsPerBlock = node?.get("cellsPerBlock")?.asInt ?: 2,
            tankSizePx = node?.get("tankSizePx")?.asFloat ?: 56f,
        )
    }

    /** `tiles.BRICK` 같은 섹션 접근. */
    fun tile(type: String): JsonValue? = root["tiles"]?.get(type)

    /** 특정 TileType 이 후보로 가진 main 아틀라스 스프라이트 이름들. */
    fun tileSprites(type: String): List<String> =
        tile(type)?.get("sprites")?.asStringList ?: emptyList()

    /** 후보가 하나뿐인 TileType 의 스프라이트 이름. (ICE 등) */
    fun tileSprite(type: String): String? = tile(type)?.get("sprite")?.asString

    /** 애니메이션 프레임을 가진 TileType 의 프레임 목록. (WATER) */
    fun tileFrames(type: String): List<String> =
        tile(type)?.get("frames")?.asStringList ?: emptyList()

    /** 지형 섹션. 잔디/흙/자갈 기본 타일과 도로·흙 구역 오토타일 매핑이 들어 있다. */
    fun terrain(group: String): JsonValue? = root["terrain"]?.get(group)

    fun terrainBase(group: String): List<String> =
        terrain(group)?.get("base")?.asStringList ?: emptyList()

    fun propGroup(id: String): JsonValue? = root["props"]?.get("groups")?.get(id)

    val propGroupIds: List<String>
        get() = (root["props"]?.get("groups")?.asObject ?: emptyMap()).keys.toList()

    fun effectFrames(id: String): List<String> =
        root["effects"]?.get(id)?.get("frames")?.asStringList ?: emptyList()

    fun hud(key: String): String? = root["hud"]?.get(key)?.asString

    val hudLifeSprite: String get() = hud("life") ?: "hud_heart_full"

    val hudDigitPrefix: String get() = hud("digitPrefix") ?: "ui_digit_"

    val hudCharPrefix: String get() = hud("charPrefix") ?: "ui_char_"

    companion object {
        const val DEFAULT_PATH = "manifest/assets.json"

        fun load(source: AssetSource, path: String = DEFAULT_PATH): AssetManifest =
            AssetManifest(JsonValue.parse(source.readText(path)))
    }
}
