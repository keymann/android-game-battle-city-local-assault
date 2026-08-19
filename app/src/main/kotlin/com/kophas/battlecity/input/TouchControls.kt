package com.kophas.battlecity.input

import com.kophas.battlecity.core.Direction
import kotlin.math.abs
import kotlin.math.hypot

/**
 * 화면을 만지는 손가락을 조종 입력으로 바꾼다. (계획서 §18, §19)
 *
 * ```
 *  왼쪽                              오른쪽
 *  ┌─────────┐                    ┌────────┐
 *  │    ↑    │                    │ SPECIAL│
 *  │  ← ● →  │                    │  FIRE  │
 *  │    ↓    │                    └────────┘
 *  └─────────┘
 * ```
 *
 * 안드로이드를 모른다. `MotionEvent` 대신 손가락 번호와 좌표만 받는다. 그래야
 * 화면도 기기도 없이 "가운데에서 오른쪽 위로 그으면 어디로 가는가" 를 시험할 수 있다.
 *
 * ### 조이스틱은 붙잡은 자리에서 생긴다
 *
 * 고정된 자리에 두면 손가락을 정확히 그 위에 놓아야 한다. 화면을 안 보고 조작하는
 * 게임에서는 그게 어렵다. 왼쪽 절반 아무 데나 짚으면 그 자리가 중심이 된다.
 */
class TouchControls(private val layout: Layout = Layout()) {

    /**
     * 버튼과 조이스틱의 자리. 화면 크기에 대한 비율로 둔다. 기기마다 해상도가 달라
     * 픽셀로 두면 태블릿에서 손가락만 해진다.
     */
    data class Layout(
        /** 조이스틱을 받는 영역. 화면 왼쪽 이 비율까지. */
        val stickZoneRatio: Float = 0.42f,
        /** 조이스틱 반지름. 화면 짧은 변 기준. */
        val stickRadiusRatio: Float = 0.16f,
        /** 이만큼 밀어야 방향으로 인정한다. 반지름 대비. */
        val deadZoneRatio: Float = 0.28f,
        /** FIRE 버튼 반지름. 화면 짧은 변 기준. */
        val buttonRadiusRatio: Float = 0.115f,
        /** SPECIAL 버튼은 조금 작다. 자주 쓰지 않고, FIRE 를 가리면 안 된다. */
        val specialRadiusRatio: Float = 0.085f,
        /** FIRE 버튼 중심. 오른쪽 아래 구석. */
        val fireCenterX: Float = 0.90f,
        val fireCenterY: Float = 0.80f,
        /** SPECIAL 버튼 중심. FIRE 바로 위. */
        val specialCenterX: Float = 0.90f,
        val specialCenterY: Float = 0.50f,
    )

    /** 이번 틱에 게임에 넘길 것. */
    class State {
        var direction: Direction? = null
            internal set

        var moving: Boolean = false
            internal set

        /** 이번 틱에 눌렸다. 누르고 있는 동안 계속 true 가 아니다. */
        var fire: Boolean = false
            internal set

        var special: Boolean = false
            internal set

        /** 조이스틱을 잡고 있는 자리와 기울기. 그리는 쪽이 쓴다. */
        var stickActive: Boolean = false
            internal set

        var stickOriginX: Float = 0f
            internal set

        var stickOriginY: Float = 0f
            internal set

        var stickKnobX: Float = 0f
            internal set

        var stickKnobY: Float = 0f
            internal set

        var firePressed: Boolean = false
            internal set

        var specialPressed: Boolean = false
            internal set
    }

    val state = State()

    private var width = 0f
    private var height = 0f

    private var stickPointer = NO_POINTER
    private var firePointer = NO_POINTER
    private var specialPointer = NO_POINTER

    /** 누르는 순간에만 한 번 넘긴다. 누르고 있는 내내 쏘면 연사 제한이 무의미해진다. */
    private var firePending = false
    private var specialPending = false

    fun resize(width: Int, height: Int) {
        this.width = width.toFloat()
        this.height = height.toFloat()
    }

    fun onDown(pointerId: Int, x: Float, y: Float) {
        when {
            hits(x, y, fireCenterX(), fireCenterY(), buttonRadius()) -> {
                firePointer = pointerId
                firePending = true
                state.firePressed = true
            }

            hits(x, y, specialCenterX(), specialCenterY(), specialRadius()) -> {
                specialPointer = pointerId
                specialPending = true
                state.specialPressed = true
            }

            x <= width * layout.stickZoneRatio && stickPointer == NO_POINTER -> {
                stickPointer = pointerId
                state.stickActive = true
                state.stickOriginX = x
                state.stickOriginY = y
                state.stickKnobX = x
                state.stickKnobY = y
            }
        }
    }

    fun onMove(pointerId: Int, x: Float, y: Float) {
        if (pointerId != stickPointer) return
        val radius = stickRadius()
        val dx = x - state.stickOriginX
        val dy = y - state.stickOriginY
        val distance = hypot(dx, dy)

        // 손잡이는 테두리 밖으로 나가지 않는다. 나가면 기울기를 가늠할 수 없다.
        val clamp = if (distance > radius) radius / distance else 1f
        state.stickKnobX = state.stickOriginX + dx * clamp
        state.stickKnobY = state.stickOriginY + dy * clamp

        if (distance < radius * layout.deadZoneRatio) {
            state.direction = null
            state.moving = false
            return
        }
        // 아날로그로 받되 가장 가까운 네 방향으로 접는다. 원작의 조작감이다. (계획서 §19)
        state.direction = Direction.fromVector(dx, dy)
        state.moving = true
    }

    fun onUp(pointerId: Int) {
        when (pointerId) {
            stickPointer -> {
                stickPointer = NO_POINTER
                state.stickActive = false
                state.moving = false
                state.direction = null
            }

            firePointer -> {
                firePointer = NO_POINTER
                state.firePressed = false
            }

            specialPointer -> {
                specialPointer = NO_POINTER
                state.specialPressed = false
            }
        }
    }

    fun onCancel() {
        stickPointer = NO_POINTER
        firePointer = NO_POINTER
        specialPointer = NO_POINTER
        state.stickActive = false
        state.moving = false
        state.direction = null
        state.firePressed = false
        state.specialPressed = false
    }

    /**
     * 이번 틱 분의 입력을 꺼낸다. 누름은 한 번만 나간다.
     *
     * 게임 루프가 부른다. 두 번 부르면 두 번째에는 발사가 없다.
     */
    fun consume(): State {
        state.fire = firePending
        state.special = specialPending
        firePending = false
        specialPending = false
        return state
    }

    // --- 그리는 쪽이 쓰는 자리 --------------------------------------------

    fun stickRadius(): Float = shortSide() * layout.stickRadiusRatio

    fun buttonRadius(): Float = shortSide() * layout.buttonRadiusRatio

    fun specialRadius(): Float = shortSide() * layout.specialRadiusRatio

    fun fireCenterX(): Float = width * layout.fireCenterX

    fun fireCenterY(): Float = height * layout.fireCenterY

    fun specialCenterX(): Float = width * layout.specialCenterX

    fun specialCenterY(): Float = height * layout.specialCenterY

    /** 손을 안 댔을 때 조이스틱을 그려 둘 자리. 어디를 짚으면 되는지 알려 준다. */
    fun restingStickX(): Float = width * RESTING_X

    fun restingStickY(): Float = height * RESTING_Y

    private fun shortSide(): Float = minOf(width, height)

    private fun hits(x: Float, y: Float, centerX: Float, centerY: Float, radius: Float): Boolean {
        // 넉넉함을 네모 검사에도 똑같이 준다. 한쪽만 주면 모서리에서 판정이 어긋난다.
        val reach = radius * TOUCH_SLACK
        if (abs(x - centerX) > reach || abs(y - centerY) > reach) return false
        return hypot(x - centerX, y - centerY) <= reach
    }

    private companion object {
        const val NO_POINTER = -1

        /** 버튼 판정을 그림보다 조금 넉넉하게. 손가락 끝이 정확하지 않다. */
        const val TOUCH_SLACK = 1.25f

        const val RESTING_X = 0.12f
        const val RESTING_Y = 0.74f
    }
}
