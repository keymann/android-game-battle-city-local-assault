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

    fun render(batch: SpriteBatch, controls: TouchControls, tank: Tank?) {
        drawStick(batch, controls)
        drawButtons(batch, controls, tank)
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

    private fun drawButtons(batch: SpriteBatch, controls: TouchControls, tank: Tank?) {
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

        val ready = tank.specialReadyRatio >= 1f
        val centerX = controls.specialCenterX()
        val centerY = controls.specialCenterY()
        val specialRadius = controls.specialRadius()
        val specialSize = specialRadius * 2f

        batch.draw(
            region = if (state.specialPressed) {
                catalog.specialButtonPressed
            } else {
                catalog.specialButton
            },
            x = centerX - specialRadius,
            y = centerY - specialRadius,
            width = specialSize,
            height = specialSize,
            layer = Constants.Layer.HUD,
            // 아직 못 쓰면 어둡게. 쓸 수 있게 되면 밝아진다.
            red = if (ready) 1f else DIM,
            green = if (ready) 1f else DIM,
            blue = if (ready) 1f else DIM,
            alpha = if (ready) 1f else ACTIVE_ALPHA,
        )

        // 차오르는 고리를 버튼 위에 겹쳐 남은 시간을 보여 준다.
        if (ready) return
        val ring = catalog.cooldownRing
        val filled = ring.bottomBand(tank.specialReadyRatio)
        val height = specialSize * tank.specialReadyRatio
        batch.draw(
            region = filled,
            x = centerX - specialRadius,
            y = centerY - specialRadius + (specialSize - height),
            width = specialSize,
            height = height,
            layer = Constants.Layer.HUD,
            alpha = 0.9f,
        )
    }

    private companion object {
        const val KNOB_RATIO = 0.46f
        const val IDLE_ALPHA = 0.4f
        const val ACTIVE_ALPHA = 0.8f
        const val DIM = 0.45f
    }
}
