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

    /**
     * 이 기기가 겪는 왕복 지연. 못 쟀거나 혼자 하는 판이면 음수. (계획서 §44.2)
     *
     * 평균 지연이 아니라 **내 기기의** 지연이다. 화면이 튀는 것이 내 탓인지 남의
     * 탓인지 알 수 없으면 아무것도 할 수 없다. 숫자로 보여 주면 공유기 곁으로
     * 옮겨 앉는 것만으로 나아지는지 스스로 판단할 수 있다.
     */
    var latencyMs: Int = -1

    /** 접속이 살아 있는가. 끊기면 숫자 대신 끊긴 표시가 나간다. */
    var online: Boolean = true

    /** 네트워크 표시를 그릴지. 혼자 하는 판에서는 뜻이 없다. */
    var showNetwork: Boolean = false

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

        // 남은 적은 총량과 함께 보여 준다. 몇 기 남았는지만으로는 판의 길이를
        // 가늠할 수 없다. (계획서 §20 ENEMY 32/80)
        y += unit * 0.6f
        drawPanel(batch, originX - unit * 0.3f, y - unit * 0.2f, unit * ENEMY_WIDTH_UNITS, unit * 1.2f)
        val label = "ENEMY ${match.enemiesRemaining}-${match.totalEnemies}"
        drawText(batch, label, originX, y, unit * 0.8f, 1f)

        if (showNetwork) {
            y += unit * 1.4f
            drawNetwork(batch, originX, y, unit)
        }
    }

    /**
     * 접속 상태와 왕복 지연.
     *
     * ```
     * 📶 42MS
     * ```
     *
     * 색으로 한 번, 숫자로 한 번 말한다. 색만으로는 얼마나 나쁜지 모르고, 숫자만
     * 두면 좋은 값인지 나쁜 값인지 판단할 기준이 없다.
     */
    private fun drawNetwork(batch: SpriteBatch, x: Float, y: Float, unit: Float) {
        val ms = latencyMs
        val color = when {
            !online -> BAD_COLOR
            ms < 0 -> DIM_COLOR
            ms <= LATENCY_GOOD_MS -> GOOD_COLOR
            ms <= LATENCY_POOR_MS -> WARN_COLOR
            else -> BAD_COLOR
        }
        drawPanel(batch, x - unit * 0.3f, y - unit * 0.2f, unit * NETWORK_WIDTH_UNITS, unit * 1.2f)
        batch.draw(
            region = if (online) catalog.netOnline else catalog.netOffline,
            x = x,
            y = y,
            width = unit * 0.9f,
            height = unit * 0.9f,
            layer = Constants.Layer.HUD,
            red = red(color),
            green = green(color),
            blue = blue(color),
        )
        val text = when {
            !online -> "LOST"
            ms < 0 -> "--MS"
            else -> "${ms.coerceAtMost(MAX_SHOWN_MS)}MS"
        }
        drawText(batch, text, x + unit * 1.1f, y, unit * 0.7f, 1f, color)
    }

    private fun drawPlayerRow(
        batch: SpriteBatch,
        slot: MatchState.Slot,
        x: Float,
        y: Float,
        unit: Float,
        match: MatchState,
        @Suppress("UNUSED_PARAMETER") tank: Tank?,
    ) {
        val dim = if (slot.eliminated) ELIMINATED_ALPHA else 1f
        var cursor = x

        // 로비에서 고른 탱크와 색으로 초상을 그리고, 그 옆에 고른 이름을 쓴다.
        // 화면의 탱크와 같은 그림 같은 색이라 누구 것인지 바로 이어진다.
        val color = catalog.palette.colorOf(slot.colorIndex)
        batch.draw(
            region = catalog.playerPortrait(slot.tankType),
            x = cursor,
            y = y,
            width = unit,
            height = unit,
            layer = Constants.Layer.HUD,
            red = red(color),
            green = green(color),
            blue = blue(color),
            alpha = dim,
        )
        cursor += unit * 1.1f
        drawText(batch, slot.name, cursor, y, unit, dim, color)
        cursor += unit * GLYPH_ADVANCE * NAME_COLUMNS + unit * 0.3f

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

    /** 비트맵 폰트로 한 줄 찍는다. 지원하지 않는 문자(공백, 빗금)는 자리만 비운다. */
    private fun red(color: Int): Float = ((color ushr 16) and 0xFF) / 255f

    private fun green(color: Int): Float = ((color ushr 8) and 0xFF) / 255f

    private fun blue(color: Int): Float = (color and 0xFF) / 255f

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
        /** 이 값까지는 쾌적하다. Protocol 과 같은 기준을 쓴다. */
        const val LATENCY_GOOD_MS = 60
        const val LATENCY_POOR_MS = 180
        const val MAX_SHOWN_MS = 999
        const val NETWORK_WIDTH_UNITS = 5.2f

        const val GOOD_COLOR = 0x8BE04B
        const val WARN_COLOR = 0xF5A623
        const val BAD_COLOR = 0xE2543C
        const val DIM_COLOR = 0x8A93A0

        /** HUD 한 칸의 크기. 블록(64px) 대비 비율. */
        const val UNIT_RATIO = 0.55f

        /** 이 폭보다 여백이 좁으면 맵 위에 겹쳐 그린다. */
        const val MIN_PANEL_UNITS = 8f
        const val ROW_SPACING = 1.55f

        /** 상태창 바탕 폭. HUD 한 칸 기준. */
        const val ROW_WIDTH_UNITS = 8.6f
        const val ENEMY_WIDTH_UNITS = 8.2f
        const val PANEL_ALPHA = 0.88f

        /** 이름 칸. 세 글자까지라 자리를 고정해 둔다. */
        const val NAME_COLUMNS = 3f

        /** 글리프 간격. 폰트 도트가 16px 칸 안에서 여백을 가지고 있어 1보다 작다. */
        const val GLYPH_ADVANCE = 0.7f

        const val ELIMINATED_ALPHA = 0.4f
        const val LOST_LIFE_ALPHA = 0.65f
    }
}
