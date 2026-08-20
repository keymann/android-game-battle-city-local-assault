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
 * Desert Shooter 팩의 비트맵 폰트가 들어와 숫자뿐 아니라 문자도 찍을 수 있다.
 * 논리 해상도가 4:3 이라 가로가 넓은 화면에서는 좌우에 여백이 남고, 그 여백을
 * HUD 자리로 쓴다. 여백이 좁으면 맵 위에 겹쳐 그린다.
 *
 * 조이스틱/버튼 등 조작 UI 는 Phase 7 에서 들어온다.
 */
class HudRenderer(private val catalog: SpriteCatalog) {

    fun render(batch: SpriteBatch, viewport: Viewport, match: MatchState) {
        val unit = viewport.worldToScreenLength(Constants.BLOCK_PX) * UNIT_RATIO
        if (unit <= 0f) return

        val usesSideMargin = viewport.offsetX >= unit * MIN_PANEL_UNITS
        val originX = if (usesSideMargin) unit * 0.5f else viewport.offsetX + unit * 0.5f
        var y = viewport.offsetY + unit * 0.5f

        for (slot in match.players) {
            drawPlayerRow(batch, slot, originX, y, unit, match)
            y += unit * ROW_SPACING
        }

        y += unit * 0.6f
        drawText(batch, "ENEMY", originX, y, unit * 0.85f, 1f)
        drawText(
            batch,
            match.enemiesRemaining.toString(),
            originX + unit * 0.85f * GLYPH_ADVANCE * 6f,
            y,
            unit * 0.85f,
            1f,
        )
    }

    private fun drawPlayerRow(
        batch: SpriteBatch,
        slot: MatchState.Slot,
        x: Float,
        y: Float,
        unit: Float,
        match: MatchState,
    ) {
        val dim = if (slot.eliminated) ELIMINATED_ALPHA else 1f
        var cursor = x

        // P1..P4 라벨. 진영기가 색을, 숫자가 번호를 알려 준다.
        batch.draw(
            region = catalog.playerFlag(slot.index),
            x = cursor,
            y = y,
            width = unit,
            height = unit,
            layer = Constants.Layer.HUD,
            alpha = dim,
        )
        cursor += unit * 1.1f
        drawText(batch, "P${slot.index + 1}", cursor, y, unit, dim)
        cursor += unit * GLYPH_ADVANCE * 2f + unit * 0.3f

        if (slot.eliminated) {
            // 탈락하면 하트 대신 해골 하나로 정리한다.
            batch.draw(
                region = catalog.skull,
                x = cursor,
                y = y,
                width = unit,
                height = unit,
                layer = Constants.Layer.HUD,
                alpha = dim,
            )
            cursor += unit * 1.2f
        } else {
            // 하트로 남은 Life 를 보여 준다. 잃은 칸은 어둡게 남긴다. (계획서 §12)
            for (i in 0 until match.rules.livesPerPlayer) {
                val filled = i < slot.lives
                batch.draw(
                    region = catalog.heart,
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
                cursor += unit * 0.85f
            }
            cursor += unit * 0.35f
        }

        drawText(batch, slot.kills.toString(), cursor, y, unit, dim)

        // 리스폰 대기 중이면 표시를 붙인다.
        if (!slot.alive && !slot.eliminated) {
            batch.draw(
                region = catalog.locked,
                x = x + unit * 0.25f,
                y = y,
                width = unit,
                height = unit,
                layer = Constants.Layer.OVERLAY,
                alpha = 0.85f,
            )
        }
    }

    /** 비트맵 폰트로 한 줄 찍는다. 지원하지 않는 문자는 공백으로 넘어간다. */
    private fun drawText(
        batch: SpriteBatch,
        text: String,
        x: Float,
        y: Float,
        size: Float,
        alpha: Float,
    ) {
        var cursor = x
        for (char in text) {
            catalog.glyph(char)?.let { glyph ->
                batch.draw(
                    region = glyph,
                    x = cursor,
                    y = y,
                    width = size,
                    height = size,
                    layer = Constants.Layer.HUD,
                    alpha = alpha,
                )
            }
            cursor += size * GLYPH_ADVANCE
        }
    }

    private companion object {
        /** HUD 한 칸의 크기. 블록(64px) 대비 비율. */
        const val UNIT_RATIO = 0.55f

        /** 이 폭보다 여백이 좁으면 맵 위에 겹쳐 그린다. */
        const val MIN_PANEL_UNITS = 8f
        const val ROW_SPACING = 1.25f

        /** 글리프 간격. 폰트 도트가 16px 칸 안에서 여백을 가지고 있어 1보다 작다. */
        const val GLYPH_ADVANCE = 0.7f

        const val ELIMINATED_ALPHA = 0.4f
        const val LOST_LIFE_SHADE = 0.25f
        const val LOST_LIFE_ALPHA = 0.55f
    }
}
