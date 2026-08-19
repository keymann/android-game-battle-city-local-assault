package com.kophas.battlecity.ai

import com.kophas.battlecity.gameplay.Tank

/**
 * COM 한 타입의 행동 성향. (계획서 §8, §10)
 *
 * 값은 전부 `manifest/balance.json` 의 `ai` 에서 온다. 코드에는 숫자를 두지 않는다.
 * (계획서 §7 중요 구현 원칙, §41-19)
 *
 * 거리는 **블록** 단위다. 탱크 한 대가 정확히 한 블록이라, 화면을 보면서
 * "탱크 몇 대 거리" 로 감을 잡을 수 있는 단위가 조정하기 가장 쉽다.
 */
data class AiProfile(
    val label: String,
    /** 이 거리 안의 적만 인식한다. */
    val sightRangeBlocks: Float,
    /** 사선이 열려도 이 거리를 넘으면 쏘지 않는다. */
    val fireRangeBlocks: Float,
    /** 본진을 목표로 삼을 확률. 스폰 때 한 번만 굴린다. */
    val baseFocus: Float,
    /** 본진에 붙었다고 볼 거리. 이 안에 들면 자리를 잡고 두들긴다. */
    val holdRadiusBlocks: Float,
    /** 체력이 이 비율 아래로 떨어지면 물러난다. 0 이면 절대 물러나지 않는다. */
    val retreatHpRatio: Float,
    /** 돌아갈 길이 없을 때 벽돌을 부수고 지나간다. */
    val breakWalls: Boolean,
    /** 사선을 잡고 나서 실제로 쏘기까지의 반응 시간(초). 클수록 어수룩하다. */
    val reactionSeconds: Float,
    /** 배회 중 방향을 다시 고르는 간격(초). */
    val patrolIntervalSeconds: Float,
) {
    companion object {
        /** balance.json 에 항목이 없을 때 쓰는 무난한 값. */
        fun fallback(type: Tank.Type): AiProfile = when (type) {
            Tank.Type.ATTACK -> AiProfile(
                "공격형", 9f, 8f, 0.25f, 2.5f, 0f, true, 0.15f, 2.0f,
            )

            Tank.Type.DEFENSE -> AiProfile(
                "방어형", 6f, 7f, 0.70f, 3.0f, 0.25f, true, 0.30f, 2.5f,
            )

            Tank.Type.SPEED -> AiProfile(
                "스피드형", 12f, 6f, 0.10f, 2.0f, 0.35f, false, 0.45f, 1.5f,
            )
        }
    }
}
