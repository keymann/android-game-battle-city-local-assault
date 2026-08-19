package com.kophas.battlecity.render

import com.kophas.battlecity.core.Constants
import com.kophas.battlecity.gameplay.MatchState

/**
 * 플레이어 상태 HUD. (계획서 §20 화면 구성)
 *
 * ```
 * P1 ♥♥♥  20
 * P2 ♥♥♡  15
 * P3 ♥♡♡   8
 * P4 💀   12
 * ```
 *
 * 논리 해상도가 4:3 이라 가로가 넓은 화면에서는 좌우에 여백이 남는다.
 * 그 여백을 HUD 자리로 쓰고, 여백이 좁으면 맵 위에 겹쳐 그린다.
 *
 * 조이스틱/버튼 등 조작 UI 는 Phase 7 에서 들어온다.
 */
class HudRenderer(private val catalog: SpriteCatalog) {

    fun render(batch: SpriteBatch, viewport: Viewport, match: MatchState) {
        val unit = viewport.worldToScreenLength(Constants.BLOCK_PX) * UNIT_RATIO
        if (unit <= 0f) return

        val margin = viewport.offsetX
        val usesSideMargin = margin >= unit * MIN_PANEL_UNITS
        val originX = if (usesSideMargin) unit * 0.4f else viewport.offsetX + unit * 0.4f
        var y = viewport.offsetY + unit * 0.4f

        for (slot in match.players) {
            drawPlayerRow(batch, slot, originX, y, unit, match)
            y += unit * ROW_SPACING
        }

        drawEnemyCounter(batch, match, originX, y + unit * 0.4f, unit)
    }

    private fun drawPlayerRow(
        batch: SpriteBatch,
        slot: MatchState.Slot,
        x: Float,
        y: Float,
        unit: Float,
        match: MatchState,
    ) {
        var cursor = x

        // 진영 깃발이 플레이어 색을 그대로 알려 준다.
        batch.draw(
            region = catalog.playerFlag(slot.index),
            x = cursor,
            y = y,
            width = unit,
            height = unit,
            layer = Constants.Layer.HUD,
            alpha = if (slot.eliminated) ELIMINATED_ALPHA else 1f,
        )
        cursor += unit * 1.15f

        // 하트로 남은 Life 를 보여 준다. 잃은 칸은 어둡게 남긴다. (계획서 §12)
        val heart = catalog.heart
        for (i in 0 until match.rules.livesPerPlayer) {
            val filled = i < slot.lives
            batch.draw(
                region = heart,
                x = cursor,
                y = y,
                width = unit,
                height = unit,
                layer = Constants.Layer.HUD,
                red = if (filled) 1f else LOST_LIFE_SHADE,
                green = if (filled) 1f else LOST_LIFE_SHADE,
                blue = if (filled) 1f else LOST_LIFE_SHADE,
                alpha = if (filled) 1f else LOST_LIFE_ALPHA,
            )
            cursor += unit * 0.9f
        }
        cursor += unit * 0.4f

        drawNumber(batch, slot.kills, cursor, y, unit, if (slot.eliminated) ELIMINATED_ALPHA else 1f)

        // 살아 있지 않으면 리스폰 대기 중이라는 표시로 자물쇠를 붙인다.
        if (!slot.alive && !slot.eliminated) {
            batch.draw(
                region = catalog.locked,
                x = x - unit * 0.1f,
                y = y,
                width = unit,
                height = unit,
                layer = Constants.Layer.OVERLAY,
                alpha = 0.8f,
            )
        }
    }

    /** 남은 COM 수. `ENEMY 32/80` 에 해당한다. (계획서 §20) */
    private fun drawEnemyCounter(
        batch: SpriteBatch,
        match: MatchState,
        x: Float,
        y: Float,
        unit: Float,
    ) {
        batch.draw(
            region = catalog.ammo,
            x = x,
            y = y,
            width = unit,
            height = unit,
            layer = Constants.Layer.HUD,
        )
        drawNumber(batch, match.enemiesRemaining, x + unit * 1.15f, y, unit, 1f)
    }

    private fun drawNumber(
        batch: SpriteBatch,
        value: Int,
        x: Float,
        y: Float,
        unit: Float,
        alpha: Float,
    ) {
        val text = value.coerceAtLeast(0).toString()
        var cursor = x
        for (char in text) {
            val digit = char - '0'
            batch.draw(
                region = catalog.digit(digit),
                x = cursor,
                y = y,
                width = unit,
                height = unit,
                layer = Constants.Layer.HUD,
                alpha = alpha,
            )
            cursor += unit * DIGIT_ADVANCE
        }
    }

    private companion object {
        /** HUD 한 칸의 크기. 블록(64px) 대비 비율. */
        const val UNIT_RATIO = 0.55f

        /** 이 폭보다 여백이 좁으면 맵 위에 겹쳐 그린다. */
        const val MIN_PANEL_UNITS = 7f
        const val ROW_SPACING = 1.2f
        const val DIGIT_ADVANCE = 0.72f
        const val ELIMINATED_ALPHA = 0.35f
        const val LOST_LIFE_SHADE = 0.25f
        const val LOST_LIFE_ALPHA = 0.55f
    }
}
