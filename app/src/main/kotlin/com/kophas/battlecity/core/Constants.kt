package com.kophas.battlecity.core

/**
 * 게임 전역 상수.
 *
 * 밸런스 값은 여기에 두지 않는다. 능력치·리소스 매핑은 `assets/manifest/assets.json`
 * 으로 분리한다. (계획서 §41-6, §41-19)
 */
object Constants {

    /** 게임 로직 고정 틱. 렌더 FPS 와 분리한다. (계획서 §25.3) */
    const val LOGIC_HZ: Int = 60

    const val NANOS_PER_TICK: Long = 1_000_000_000L / LOGIC_HZ

    const val TICK_SECONDS: Float = 1f / LOGIC_HZ

    /**
     * 한 프레임에서 따라잡을 수 있는 최대 틱 수.
     * 프레임이 길게 밀렸을 때 죽음의 나선(spiral of death)을 막는다.
     */
    const val MAX_CATCH_UP_TICKS: Int = 5

    /** 지형 타일 1장 = 탱크 1대 크기. (docs/ASSET_SELECTION.md §3) */
    const val BLOCK_PX: Float = 64f

    /** BRICK / STEEL 파괴 최소 단위. 1 block = 2 x 2 cell. */
    const val CELL_PX: Float = 32f

    const val CELLS_PER_BLOCK: Int = 2

    /** 렌더 레이어. 값이 작을수록 먼저 그린다. (docs/STAGE_GENERATION.md §5) */
    object Layer {
        const val GROUND: Int = 0
        const val DECAL: Int = 1
        const val HAZARD: Int = 2
        const val OBJECT: Int = 3
        const val ENTITY: Int = 4
        const val CANOPY: Int = 5
        const val EFFECT: Int = 6
        const val OVERLAY: Int = 7
        const val HUD: Int = 8
        const val COUNT: Int = 9
    }
}
