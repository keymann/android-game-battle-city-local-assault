package com.kophas.battlecity.gameplay

/**
 * 로비에서 고른 것. (계획서 §28, §29)
 *
 * 이름 · 탱크 종류 · 색. 이 셋이 로비에서 게임 화면까지 그대로 따라간다.
 * 색은 팔레트의 자리 번호다. 색값 자체가 아니라 번호를 들고 다니는 이유는,
 * 팔레트를 매니페스트에서 갈아 끼워도 저장된 선택이 살아남게 하기 위해서다.
 */
data class PlayerProfile(
    val name: String,
    val type: Tank.Type,
    val colorIndex: Int,
) {
    companion object {
        /** 이름은 세 글자까지. HUD 한 칸에 들어가야 한다. */
        const val MAX_NAME: Int = 3

        /** 아무것도 고르지 않았을 때. P1 ~ P4 와 자리 순서대로의 색. */
        fun default(slot: Int): PlayerProfile = PlayerProfile(
            name = "P${slot + 1}",
            type = Tank.Type.entries[slot % Tank.Type.entries.size],
            colorIndex = slot,
        )

        /** 세 글자로 자르고 대문자로 맞춘다. 비트맵 폰트에 소문자가 없다. */
        fun sanitize(name: String, slot: Int): String {
            val trimmed = name.filter { it.isLetterOrDigit() }.take(MAX_NAME).uppercase()
            return trimmed.ifEmpty { "P${slot + 1}" }
        }
    }
}
