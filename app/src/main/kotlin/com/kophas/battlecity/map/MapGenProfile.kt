package com.kophas.battlecity.map

import com.kophas.battlecity.render.AssetSource
import com.kophas.battlecity.util.JsonValue

/**
 * `manifest/mapgen.json` 리더. (assets/RANDOM_MAP_ASSET_GUIDE.md)
 *
 * 맵 생성 규칙을 코드에 하드코딩하지 않는다. 밀도·군집 크기·검증 기준·variation
 * 가중치가 전부 이 파일에서 온다. 규칙을 손보고 싶으면 파일만 고친다.
 */
class MapGenProfile(val root: JsonValue) {

    val version: Int = root["version"]?.asInt ?: 0

    /** 사람 수별 맵 크기. 가이드 권장치를 16:9 에 맞춰 옮긴 값이다. */
    data class Preset(val blocksX: Int, val blocksY: Int)

    private val presets: Map<Int, Preset> =
        (root["mapPresets"]?.asObject ?: emptyMap()).mapNotNull { (key, node) ->
            val players = key.toIntOrNull() ?: return@mapNotNull null
            val x = node["blocksX"]?.asInt ?: return@mapNotNull null
            val y = node["blocksY"]?.asInt ?: return@mapNotNull null
            players to Preset(x, y)
        }.toMap()

    fun presetFor(playerCount: Int): Preset =
        presets[playerCount.coerceIn(MIN_PLAYERS, MAX_PLAYERS)]
            ?: presets.values.firstOrNull()
            ?: Preset(23, 13)

    /** 전체 블록 대비 목표 비율. `[최소, 최대]`. */
    fun density(key: String): ClosedFloatingPointRange<Float> = range("densityTargets", key, 0f, 1f)

    /** 군집 하나가 차지할 블록 수. */
    fun cluster(key: String): IntRange {
        val node = root["clusterSize"]?.get(key)?.asArray ?: return 2..6
        val lo = node.getOrNull(0)?.asInt ?: 2
        val hi = node.getOrNull(1)?.asInt ?: 6
        return lo..maxOf(lo, hi)
    }

    private fun range(section: String, key: String, lo: Float, hi: Float): ClosedFloatingPointRange<Float> {
        val node = root[section]?.get(key)?.asArray ?: return lo..hi
        val a = node.getOrNull(0)?.asFloat ?: lo
        val b = node.getOrNull(1)?.asFloat ?: hi
        return a..maxOf(a, b)
    }

    fun zone(key: String, fallback: Int): Int = root["zones"]?.get(key)?.asInt ?: fallback

    fun zoneRatio(key: String, fallback: Float): Float =
        root["zones"]?.get(key)?.asFloat ?: fallback

    fun rule(key: String, fallback: Float): Float =
        root["validation"]?.get(key)?.asFloat ?: fallback

    fun ruleInt(key: String, fallback: Int): Int = root["validation"]?.get(key)?.asInt ?: fallback

    fun score(key: String, fallback: Int): Int = root["score"]?.get(key)?.asInt ?: fallback

    /**
     * 의미 타입별 그림 후보와 가중치. (가이드 §12)
     *
     * 가중치를 정수 목록으로 펼쳐 두면 고를 때 나눗셈 한 번이면 된다.
     */
    class Weighted(val names: List<String>, val weights: IntArray) {
        val total: Int = weights.sum()

        fun pick(roll: Int): String {
            if (names.isEmpty()) return ""
            var remain = if (total <= 0) 0 else roll % total
            for (i in names.indices) {
                remain -= weights[i]
                if (remain < 0) return names[i]
            }
            return names.last()
        }
    }

    private val variations: Map<String, Weighted> =
        (root["variation"]?.asObject ?: emptyMap()).mapValues { (_, node) ->
            val entries = node.asObject.filterKeys { it != "comment" }
            Weighted(entries.keys.toList(), entries.values.map { it.asInt ?: 1 }.toIntArray())
        }

    fun variation(semanticType: String): Weighted? = variations[semanticType]

    /** 초기 배치에 쓰면 안 되는 그림. (가이드 §13) */
    val runtimeStateOnly: Set<String> =
        (root["runtimeStateOnly"]?.asStringList ?: emptyList()).toSet()

    fun biomeBias(key: String): String? = root["biomeBias"]?.get(key)?.asString

    val biomeBiasRadius: Int = root["biomeBias"]?.get("radius")?.asInt ?: 2

    companion object {
        const val DEFAULT_PATH = "manifest/mapgen.json"
        const val MIN_PLAYERS = 2
        const val MAX_PLAYERS = 4

        fun load(source: AssetSource, path: String = DEFAULT_PATH): MapGenProfile =
            MapGenProfile(JsonValue.parse(source.readText(path)))
    }
}
