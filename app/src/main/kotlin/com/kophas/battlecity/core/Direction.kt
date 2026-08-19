package com.kophas.battlecity.core

import kotlin.math.PI

/**
 * 4방향 이동. 논리적인 방향값으로만 관리한다. (계획서 §41-8)
 *
 * 화면 좌표계가 좌상단 원점이므로 UP 의 dy 가 -1 이다.
 * [radians] 는 스프라이트 회전값이며, 원본 아트가 위쪽을 향하고 있어 UP 이 0 이다.
 */
enum class Direction(val dx: Int, val dy: Int, val radians: Float) {
    UP(0, -1, 0f),
    RIGHT(1, 0, (PI / 2).toFloat()),
    DOWN(0, 1, PI.toFloat()),
    LEFT(-1, 0, (3 * PI / 2).toFloat());

    val isHorizontal: Boolean get() = dx != 0

    val opposite: Direction
        get() = when (this) {
            UP -> DOWN
            DOWN -> UP
            LEFT -> RIGHT
            RIGHT -> LEFT
        }

    fun turnedRight(): Direction = VALUES[(ordinal + 1) % VALUES.size]

    fun turnedLeft(): Direction = VALUES[(ordinal + 3) % VALUES.size]

    companion object {
        val VALUES: Array<Direction> = entries.toTypedArray()

        /** 아날로그 입력 벡터를 가장 가까운 4방향으로 접는다. (계획서 §19) */
        fun fromVector(x: Float, y: Float): Direction? {
            if (x == 0f && y == 0f) return null
            return if (kotlin.math.abs(x) >= kotlin.math.abs(y)) {
                if (x > 0) RIGHT else LEFT
            } else {
                if (y > 0) DOWN else UP
            }
        }
    }
}
