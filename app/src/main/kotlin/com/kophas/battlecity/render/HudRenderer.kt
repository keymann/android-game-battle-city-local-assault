package com.kophas.battlecity.render

import com.kophas.battlecity.core.Constants
import com.kophas.battlecity.gameplay.GameWorld
import com.kophas.battlecity.gameplay.MatchState
import com.kophas.battlecity.gameplay.Tank

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
 * 아이콘은 게임 에셋 팩, 글자는 Kenney Desert Shooter 비트맵 폰트다.
 * 논리 해상도가 16:9 라 가로가 더 넓은 화면에서는 좌우에 여백이 남고, 그 여백을
 * HUD 자리로 쓴다. 여백이 좁으면 맵 위에 겹쳐 그린다.
 *
 * 조이스틱/버튼 등 조작 UI 는 Phase 7 에서 들어온다.
 */
class HudRenderer(private val catalog: SpriteCatalog) {

    fun render(batch: SpriteBatch, viewport: Viewport, match: MatchState, world: GameWorld) {
        val unit = viewport.worldToScreenLength(Constants.BLOCK_PX) * UNIT_RATIO
        if (unit <= 0f) return

        val usesSideMargin = viewport.offsetX >= unit * MIN_PANEL_UNITS
        val originX = if (usesSideMargin) unit * 0.5f else viewport.offsetX + unit * 0.5f
        var y = viewport.offsetY + unit * 0.5f

        for (slot in match.players) {
            val tank = world.tanks.firstOrNull { it.id == slot.tankId && it.alive }
            drawPanel(batch, originX - unit * 0.3f, y - unit * 0.25f, unit * ROW_WIDTH_UNITS, unit * 1.4f)
            drawPlayerRow(batch, slot, originX, y, unit, match, tank)
            y += unit * ROW_SPACING
        }

        y += unit * 0.6f
        drawPanel(batch, originX - unit * 0.3f, y - unit * 0.2f, unit * ENEMY_WIDTH_UNITS, unit * 1.2f)
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
        tank: Tank?,
    ) {
        val dim = if (slot.eliminated) ELIMINATED_ALPHA else 1f
        var cursor = x

        // 고른 탱크 초상 + 슬롯 색 라벨. 탱크 색은 종류가 정하므로 라벨 색이
        // 누가 누구인지 알려 주는 유일한 단서다.
        batch.draw(
            region = catalog.playerPortrait(slot.tankType),
            x = cursor,
            y = y,
            width = unit,
            height = unit,
            layer = Constants.Layer.HUD,
            alpha = dim,
        )
        cursor += unit * 1.1f
        val label = catalog.slotMarker.colorOf(slot.index)
        drawText(batch, "P${slot.index + 1}", cursor, y, unit, dim, label)
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
            // 하트로 남은 Life 를 보여 준다. 빈 칸은 전용 그림이 따로 있다. (계획서 §12)
            for (i in 0 until match.rules.livesPerPlayer) {
                val filled = i < slot.lives
                batch.draw(
                    region = if (filled) catalog.heart else catalog.heartEmpty,
                    x = cursor,
                    y = y,
                    width = unit,
                    height = unit,
                    layer = Constants.Layer.HUD,
                    alpha = if (filled) 1f else LOST_LIFE_ALPHA,
                )
                cursor += unit * 0.85f
            }
            cursor += unit * 0.35f
        }

        drawText(batch, slot.kills.toString(), cursor, y, unit, dim)

        // 특수기 쿨타임. 가득 차면 쓸 수 있다. (계획서 §6, §18.2)
        if (tank != null && tank.special != com.kophas.battlecity.gameplay.BalanceConfig.Special.NONE) {
            drawSpecialCooldown(batch, tank, cursor + unit * 1.4f, y, unit)
        }

        // 리스폰 대기 중이면 표시를 붙인다.
        if (!slot.alive && !slot.eliminated) {
            batch.draw(
                region = catalog.deadIcon,
                x = x + unit * 0.25f,
                y = y,
                width = unit,
                height = unit,
                layer = Constants.Layer.OVERLAY,
                alpha = 0.85f,
            )
        }
    }

    /**
     * 상태창 바탕.
     *
     * 맵 위에 겹쳐 그리면 글자와 하트가 지형에 묻혀 안 읽힌다. 팩의 상태창 판을
     * 뒤에 깐다. 가로로 그냥 늘리면 둥근 모서리가 뭉개지므로 좌·우 끝은 그대로 두고
     * 가운데 조각만 늘린다.
     */
    private fun drawPanel(batch: SpriteBatch, x: Float, y: Float, width: Float, height: Float) {
        val panel = catalog.panel
        val cap = height * 0.5f
        val middle = (width - cap * 2f).coerceAtLeast(0f)

        batch.draw(
            region = panel.sub(0, 0, 3, 1), x = x, y = y,
            width = cap, height = height, layer = Constants.Layer.HUD, alpha = PANEL_ALPHA,
        )
        batch.draw(
            region = panel.sub(1, 0, 3, 1), x = x + cap, y = y,
            width = middle, height = height, layer = Constants.Layer.HUD, alpha = PANEL_ALPHA,
        )
        batch.draw(
            region = panel.sub(2, 0, 3, 1), x = x + cap + middle, y = y,
            width = cap, height = height, layer = Constants.Layer.HUD, alpha = PANEL_ALPHA,
        )
    }

    /**
     * 특수기 쿨타임.
     *
     * 같은 고리를 두 번 그린다. 한 번은 어둡게 전체를, 한 번은 밝게 **아래에서
     * 차오른 만큼만** 잘라서. 원형 마스크를 쓰려면 셰이더가 필요한데 스프라이트
     * 배치에는 그런 것이 없다. 이 방법이면 셰이더 없이도 연속으로 차오른다.
     */
    private fun drawSpecialCooldown(
        batch: SpriteBatch,
        tank: Tank,
        x: Float,
        y: Float,
        unit: Float,
    ) {
        val ratio = tank.specialReadyRatio
        val icon = catalog.cooldownRing

        batch.draw(
            region = icon,
            x = x,
            y = y,
            width = unit,
            height = unit,
            layer = Constants.Layer.HUD,
            red = COOLDOWN_SHADE,
            green = COOLDOWN_SHADE,
            blue = COOLDOWN_SHADE,
            alpha = 0.7f,
        )
        if (ratio <= 0f) return

        val filled = icon.bottomBand(ratio)
        val height = unit * ratio
        batch.draw(
            region = filled,
            x = x,
            y = y + unit - height,
            width = unit,
            height = height,
            layer = Constants.Layer.HUD,
            // 발동 중에는 더 밝게 두어 지금 효과가 걸려 있음을 알린다.
            alpha = if (ratio >= 1f || tank.specialActive) 1f else 0.85f,
        )
    }

    /** 비트맵 폰트로 한 줄 찍는다. 지원하지 않는 문자는 공백으로 넘어간다. */
    private fun drawText(
        batch: SpriteBatch,
        text: String,
        x: Float,
        y: Float,
        size: Float,
        alpha: Float,
        color: Int = 0xFFFFFF,
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
                    red = ((color ushr 16) and 0xFF) / 255f,
                    green = ((color ushr 8) and 0xFF) / 255f,
                    blue = (color and 0xFF) / 255f,
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
        const val ROW_SPACING = 1.55f

        /** 상태창 바탕 폭. HUD 한 칸 기준. */
        const val ROW_WIDTH_UNITS = 8.6f
        const val ENEMY_WIDTH_UNITS = 6.4f
        const val PANEL_ALPHA = 0.88f

        /** 쿨타임 고리의 비어 있는 부분 밝기. */
        const val COOLDOWN_SHADE = 0.35f

        /** 글리프 간격. 폰트 도트가 16px 칸 안에서 여백을 가지고 있어 1보다 작다. */
        const val GLYPH_ADVANCE = 0.7f

        const val ELIMINATED_ALPHA = 0.4f
        const val LOST_LIFE_ALPHA = 0.65f
    }
}
