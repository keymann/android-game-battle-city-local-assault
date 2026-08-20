package com.kophas.battlecity.render

import com.kophas.battlecity.core.Constants
import com.kophas.battlecity.gameplay.BalanceConfig
import com.kophas.battlecity.gameplay.Tank
import com.kophas.battlecity.input.TouchControls

/**
 * 가상 조이스틱과 버튼. (계획서 §18)
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
 * 조이스틱은 짚은 자리에 생긴다. 손을 대지 않았을 때는 왼쪽 아래에 흐리게 두어
 * 어디를 짚으면 되는지 알려 준다.
 *
 * 좌표는 논리 픽셀이 아니라 **화면 픽셀**이다. 조작 UI 는 맵과 함께 늘었다 줄었다
 * 하면 안 된다. 손가락 크기는 화면 해상도가 아니라 기기 크기를 따르기 때문이다.
 */
class ControlsRenderer(private val catalog: SpriteCatalog) {

    fun render(batch: SpriteBatch, controls: TouchControls, tank: Tank?, timeSeconds: Float) {
        drawStick(batch, controls)
        drawButtons(batch, controls, tank, timeSeconds)
    }

    private fun drawStick(batch: SpriteBatch, controls: TouchControls) {
        val state = controls.state
        val radius = controls.stickRadius()
        val size = radius * 2f

        val centerX = if (state.stickActive) state.stickOriginX else controls.restingStickX()
        val centerY = if (state.stickActive) state.stickOriginY else controls.restingStickY()
        val alpha = if (state.stickActive) ACTIVE_ALPHA else IDLE_ALPHA

        batch.draw(
            region = catalog.stickBase,
            x = centerX - radius,
            y = centerY - radius,
            width = size,
            height = size,
            layer = Constants.Layer.HUD,
            alpha = alpha,
        )

        val knobRadius = radius * KNOB_RATIO
        val knobX = if (state.stickActive) state.stickKnobX else centerX
        val knobY = if (state.stickActive) state.stickKnobY else centerY
        batch.draw(
            region = catalog.stickKnob,
            x = knobX - knobRadius,
            y = knobY - knobRadius,
            width = knobRadius * 2f,
            height = knobRadius * 2f,
            layer = Constants.Layer.HUD,
            alpha = alpha,
        )
    }

    private fun drawButtons(
        batch: SpriteBatch,
        controls: TouchControls,
        tank: Tank?,
        timeSeconds: Float,
    ) {
        val state = controls.state
        val radius = controls.buttonRadius()
        val size = radius * 2f

        batch.draw(
            region = if (state.firePressed) catalog.fireButtonPressed else catalog.fireButton,
            x = controls.fireCenterX() - radius,
            y = controls.fireCenterY() - radius,
            width = size,
            height = size,
            layer = Constants.Layer.HUD,
            alpha = if (state.firePressed) 1f else ACTIVE_ALPHA,
        )

        // 특수기가 없는 탱크에는 버튼을 그리지 않는다. 눌러도 아무 일이 없으면
        // 고장 난 것으로 보인다.
        if (tank == null || tank.special == BalanceConfig.Special.NONE) return

        drawSpecial(batch, controls, tank, timeSeconds)
    }

    /**
     * 특수기 버튼이 곧 쿨타임 표시다.
     *
     * 예전에는 고리 그림을 따로 얹었는데, 버튼과 고리가 겹쳐 무엇을 보는지 헷갈렸다.
     * 이제 **버튼 자체**를 쓴다. 어둡게 깐 위에 차오른 만큼만 밝게 덧그린다.
     * 다 차면 잠깐 숨을 쉬듯 커졌다 작아져서 쓸 수 있게 된 것을 알린다.
     */
    private fun drawSpecial(
        batch: SpriteBatch,
        controls: TouchControls,
        tank: Tank,
        timeSeconds: Float,
    ) {
        val ratio = tank.specialReadyRatio
        val ready = ratio >= 1f
        val centerX = controls.specialCenterX()
        val centerY = controls.specialCenterY()
        val region = if (state(controls)) catalog.specialButtonPressed else catalog.specialButton

        // 다 찼을 때만 아주 살짝 맥동한다. 쿨타임 중에 흔들면 조바심만 난다.
        val pulse = if (ready) {
            1f + PULSE_DEPTH * kotlin.math.sin(timeSeconds * PULSE_SPEED)
        } else {
            1f
        }
        val radius = controls.specialRadius() * pulse
        val size = radius * 2f

        batch.draw(
            region = region,
            x = centerX - radius,
            y = centerY - radius,
            width = size,
            height = size,
            layer = Constants.Layer.HUD,
            red = DIM,
            green = DIM,
            blue = DIM,
            alpha = if (ready) 1f else CHARGING_ALPHA,
        )
        if (ratio <= 0f) return

        // 아래에서 위로 차오른다. 남은 시간이 눈에 보인다.
        val filled = region.bottomBand(ratio)
        val height = size * ratio
        batch.draw(
            region = filled,
            x = centerX - radius,
            y = centerY - radius + (size - height),
            width = size,
            height = height,
            layer = Constants.Layer.HUD,
            alpha = 1f,
        )
    }

    private fun state(controls: TouchControls): Boolean = controls.state.specialPressed

    private companion object {
        const val KNOB_RATIO = 0.46f
        const val IDLE_ALPHA = 0.4f
        const val ACTIVE_ALPHA = 0.8f

        /** 아직 안 찬 부분의 밝기. */
        const val DIM = 0.35f
        const val CHARGING_ALPHA = 0.75f

        /** 다 찼을 때의 맥동. 너무 크면 누르기 어려워진다. */
        const val PULSE_DEPTH = 0.05f
        const val PULSE_SPEED = 4.5f
    }
}
