package com.kophas.battlecity.game

/**
 * 방을 열 때 정하는 규칙. (로비 설정 화면)
 *
 * **방장이 정하고 모두에게 적용된다.** 판정에 영향을 주므로 START 에 실어 보내고
 * 모두가 같은 값으로 판을 연다. 한쪽만 다른 규칙으로 굴리면 같은 seed 라도
 * 결과가 갈린다.
 *
 * 소리 크기는 여기 없다. 그것은 사람마다 다르고 판정과 무관하므로
 * [DeviceSettings] 로 따로 둔다.
 */
data class RoomSettings(
    val mapSize: MapSize = MapSize.STANDARD,
    val randomSeed: Long = 0L,
    /** 아군 포탄에도 맞는가. 끄면 같은 편끼리는 통과한다. */
    val friendlyFire: Boolean = true,
    /**
     * 동시에 나올 수 있는 COM 수. [ACTIVE_ENEMIES_AUTO] 면 balance.json 값을 쓴다.
     *
     * 총 COM 수(플레이어당 20)와는 다른 것이다. 80기를 한꺼번에 내보내지 않고
     * 이 수만큼만 필드에 세워 둔다. (계획서 §9, §44.2)
     */
    val maxActiveEnemies: Int = ACTIVE_ENEMIES_AUTO,
    /** 본진이 첫 포탄을 한 번 막아 내는가. (계획서 §14 protected) */
    val baseProtection: Boolean = true,
) {
    /**
     * 맵 크기. 사람 수에 맞는 격자를 한 단계 좁히거나 넓힌다.
     *
     * 새 격자를 따로 만들지 않는다. mapgen.json 의 격자는 16:9 에 맞춰 고른 것이라
     * 아무 크기나 끼워 넣으면 화면비가 어긋난다.
     */
    enum class MapSize { COMPACT, STANDARD, LARGE }

    /** [MapSize] 를 실제 격자를 고를 때 쓸 사람 수로 바꾼다. */
    fun gridPlayerCount(playerCount: Int): Int = when (mapSize) {
        MapSize.COMPACT -> playerCount - 1
        MapSize.STANDARD -> playerCount
        MapSize.LARGE -> playerCount + 1
    }.coerceIn(MIN_GRID_PLAYERS, MAX_GRID_PLAYERS)

    companion object {
        const val MIN_GRID_PLAYERS = 2
        const val MAX_GRID_PLAYERS = 4

        /**
         * 동시 COM 수를 손으로 정할 때의 범위. 계획서 §44.2 가 정한 8~12 다.
         *
         * balance.json 은 인원별로 2인 8 · 3인 10 · 4인 12 를 준다. 손으로 정하는
         * 값도 그 폭을 넘지 않게 둔다. 더 줄이면 판이 늘어지고 더 늘리면 화면이
         * COM 으로 덮인다.
         */
        const val MIN_ACTIVE_ENEMIES = 8

        const val MAX_ACTIVE_ENEMIES = 12

        /** 정하지 않았다는 뜻. 인원에 맞는 balance.json 값을 그대로 쓴다. */
        const val ACTIVE_ENEMIES_AUTO = 0
    }
}

/**
 * 이 기기에만 적용되는 것. (로비 설정 화면의 볼륨)
 *
 * 남에게 보내지 않는다. 내 소리 크기를 남이 따라야 할 이유가 없다.
 */
data class DeviceSettings(
    /** 0~100. */
    val bgmVolume: Int = 70,
    val sfxVolume: Int = 85,
) {
    val bgmScale: Float get() = bgmVolume / 100f
    val sfxScale: Float get() = sfxVolume / 100f

    companion object {
        const val MAX_VOLUME = 100
        const val VOLUME_STEP = 5
    }
}
