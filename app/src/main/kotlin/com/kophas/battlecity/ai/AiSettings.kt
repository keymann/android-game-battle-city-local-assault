package com.kophas.battlecity.ai

import com.kophas.battlecity.gameplay.BalanceConfig
import com.kophas.battlecity.gameplay.Tank
import com.kophas.battlecity.util.JsonValue

/**
 * `manifest/balance.json` 의 `ai` 구역. (계획서 §10, §11)
 *
 * [BalanceConfig] 가 아니라 여기서 읽는다. 밸런스 값은 한 파일에 모으되,
 * AI 만 아는 개념(시야·반응 시간·스폰 검사)까지 [BalanceConfig] 가 들고 있으면
 * 규칙 계층이 AI 계층을 알게 된다. 파일은 같고 읽는 주인만 다르다.
 */
class AiSettings(root: JsonValue?) {

    /** 상태를 다시 판단하는 간격. 매 틱 판단하면 목표가 떨린다. */
    val decisionIntervalSeconds: Float = root?.get("decisionIntervalSeconds")?.asFloat ?: 0.25f

    /** 경로를 다시 그리는 간격. */
    val repathIntervalSeconds: Float = root?.get("repathIntervalSeconds")?.asFloat ?: 1.2f

    /** 가려다 제자리인 시간이 이만큼 쌓이면 막힌 것으로 본다. */
    val stuckSeconds: Float = root?.get("stuckSeconds")?.asFloat ?: 0.5f

    /**
     * 꺾을 수 있다고 볼 정렬 오차(논리 px).
     *
     * 탱크는 연속 좌표로 움직이는데 경로는 격자다. 노드에 정확히 닿는 순간은
     * 없으므로 이만큼 가까우면 꺾는다. [com.kophas.battlecity.gameplay.GameWorld.steer]
     * 가 격자에 스냅해 주므로 이 값이 곧 한 번에 튀는 최대 거리다.
     */
    val turnTolerancePx: Float = root?.get("turnTolerancePx")?.asFloat ?: 6f

    /** 한 번에 들고 다닐 경로 길이. 그대로 탐색 깊이 상한이 된다. */
    val maxPathNodes: Int = root?.get("maxPathNodes")?.asInt ?: 64

    /** 이만큼 어긋나면 사선이 아니다(논리 px). */
    val fireAlignTolerancePx: Float = root?.get("fireAlignTolerancePx")?.asFloat ?: 20f

    /** 숲에 숨은 적은 이 거리 안에서만 보인다. */
    val concealedRevealBlocks: Float = root?.get("concealedRevealBlocks")?.asFloat ?: 2f

    /**
     * 본진에서 이 거리 안에 적이 들어오면 "본진이 위험하다" 로 본다. (계획서 §10)
     *
     * 본진을 가진 쪽만 쓴다. 성향과 무관한 판 전체의 규칙이라 타입별로 두지 않는다.
     */
    val baseDefendRadiusBlocks: Float = root?.get("baseDefendRadiusBlocks")?.asFloat ?: 6f

    /** COM 스폰 지점 검사 규칙. (계획서 §11) */
    class SpawnRules(node: JsonValue?) {
        /** 플레이어가 이보다 가까우면 그 지점은 쓰지 않는다. */
        val minPlayerDistanceBlocks: Float = node?.get("minPlayerDistanceBlocks")?.asFloat ?: 5f

        /** 본진이 이보다 가까우면 쓰지 않는다. */
        val minBaseDistanceBlocks: Float = node?.get("minBaseDistanceBlocks")?.asFloat ?: 3f

        /** 다른 탱크와 이만큼은 떨어져야 한다. */
        val clearanceBlocks: Float = node?.get("clearanceBlocks")?.asFloat ?: 1.25f
    }

    val spawn: SpawnRules = SpawnRules(root?.get("spawn"))

    private val profiles: Map<Tank.Type, AiProfile> =
        (root?.get("types")?.asObject ?: emptyMap()).mapNotNull { (key, value) ->
            val type = runCatching { Tank.Type.valueOf(key) }.getOrNull() ?: return@mapNotNull null
            val fallback = AiProfile.fallback(type)
            type to AiProfile(
                label = value["label"]?.asString ?: fallback.label,
                sightRangeBlocks = value["sightRangeBlocks"]?.asFloat ?: fallback.sightRangeBlocks,
                fireRangeBlocks = value["fireRangeBlocks"]?.asFloat ?: fallback.fireRangeBlocks,
                baseFocus = value["baseFocus"]?.asFloat ?: fallback.baseFocus,
                holdRadiusBlocks = value["holdRadiusBlocks"]?.asFloat ?: fallback.holdRadiusBlocks,
                retreatHpRatio = value["retreatHpRatio"]?.asFloat ?: fallback.retreatHpRatio,
                breakWalls = value["breakWalls"]?.asBoolean ?: fallback.breakWalls,
                reactionSeconds = value["reactionSeconds"]?.asFloat ?: fallback.reactionSeconds,
                patrolIntervalSeconds = value["patrolIntervalSeconds"]?.asFloat
                    ?: fallback.patrolIntervalSeconds,
            )
        }.toMap()

    fun profileOf(type: Tank.Type): AiProfile = profiles[type] ?: AiProfile.fallback(type)

    companion object {
        fun from(balance: BalanceConfig): AiSettings = AiSettings(balance.root["ai"])
    }
}
