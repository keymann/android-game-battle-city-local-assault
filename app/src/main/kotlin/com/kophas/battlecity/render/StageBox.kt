package com.kophas.battlecity.render

/**
 * 화면 안에서 16:9 를 유지하는 자리. (계획서 §21, §22)
 *
 * ```
 *  21:9 단말                       4:3 단말
 * ┌──┬──────────────┬──┐          ┌────────────────┐
 * │  │              │  │          ├────────────────┤
 * │  │    16:9      │  │          │      16:9      │
 * │  │              │  │          ├────────────────┤
 * └──┴──────────────┴──┘          └────────────────┘
 * ```
 *
 * 화면을 꽉 채우려고 늘리지 않는다. 단말마다 가로세로비가 다른데 그에 맞춰 늘리면
 * 어떤 기기에서는 단추가 엄지에서 멀어지고 어떤 기기에서는 글자가 납작해진다.
 * 한 비율로 고정하고 남는 곳은 검은 띠로 둔다. 그러면 어느 기기에서나 같은 화면이다.
 *
 * 게임 월드는 [Viewport] 가 같은 방식으로 이미 맞춘다. 이 값은 그 바깥의 UI —
 * 메뉴 · 방 목록 · 설정 · 로비 · 결과 화면과 조작 UI — 가 쓴다.
 */
data class StageBox(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
) {
    val centerX: Float get() = x + width * 0.5f
    val centerY: Float get() = y + height * 0.5f

    /** 짧은 쪽. 손가락 크기를 정할 때 쓴다. 16:9 에서는 언제나 높이다. */
    val shortSide: Float get() = minOf(width, height)

    companion object {
        const val ASPECT: Float = 16f / 9f

        val NONE = StageBox(0f, 0f, 0f, 0f)

        /** 주어진 화면 안에 들어가는 가장 큰 16:9 사각형. 가운데에 놓는다. */
        fun fit(screenWidth: Float, screenHeight: Float): StageBox {
            if (screenWidth <= 0f || screenHeight <= 0f) return NONE
            val width = minOf(screenWidth, screenHeight * ASPECT)
            val height = width / ASPECT
            return StageBox(
                x = (screenWidth - width) * 0.5f,
                y = (screenHeight - height) * 0.5f,
                width = width,
                height = height,
            )
        }
    }
}
