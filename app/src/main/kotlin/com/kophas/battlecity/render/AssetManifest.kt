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
        val tileSize: Int,
        val columns: Int,
        val rows: Int,
        val spacing: Int,
        val sourceScale: Float,
    ) {
        val isGrid: Boolean get() = type == TYPE_GRID

        companion object {
            const val TYPE_XML = "xml_subtexture"
            const val TYPE_GRID = "index_grid"
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
                tileSize = node["tileSize"]?.asInt ?: 0,
                columns = node["columns"]?.asInt ?: 0,
                rows = node["rows"]?.asInt ?: 0,
                spacing = node["spacing"]?.asInt ?: 0,
                sourceScale = node["sourceScale"]?.asFloat ?: 1f,
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

    /** 특정 TileType 이 쓰는 tiny 아틀라스 인덱스들. */
    fun tileIndices(type: String): List<Int> =
        tile(type)?.get("indices")?.asIntList ?: emptyList()

    fun propGroup(id: String): JsonValue? = root["props"]?.get("groups")?.get(id)

    val propGroupIds: List<String>
        get() = (root["props"]?.get("groups")?.asObject ?: emptyMap()).keys.toList()

    fun effectFrames(id: String): List<String> =
        root["effects"]?.get(id)?.get("frames")?.asStringList ?: emptyList()

    val hudLifeIndex: Int get() = root["hud"]?.get("life")?.asInt ?: 0

    val hudDigitIndices: List<Int> get() = root["hud"]?.get("digits")?.asIntList ?: emptyList()

    companion object {
        const val DEFAULT_PATH = "manifest/assets.json"

        fun load(source: AssetSource, path: String = DEFAULT_PATH): AssetManifest =
            AssetManifest(JsonValue.parse(source.readText(path)))
    }
}
