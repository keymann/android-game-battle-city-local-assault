package com.kophas.battlecity.game

import com.kophas.battlecity.gameplay.MatchState
import com.kophas.battlecity.render.ScreenUi
import com.kophas.battlecity.render.SpriteBatch
import com.kophas.battlecity.render.SpriteCatalog

/**
 * 게임 결과 화면. (계획서 §33)
 *
 * 순위 · 승자 · 처치 수를 보여 주고 다음 판으로 넘어가는 문을 연다.
 *
 * PLAY AGAIN 은 방장만 누른다. 방을 다시 여는 일이라 참가자가 각자 시작하면
 * 서로 다른 판이 열린다. 방장 말고 다른 사람이 모두 결과 화면을 떠났다면 다시
 * 시작해 봐야 혼자 남으므로 (최소 두 명, 계획서 §28) 방장에게도 잠근다.
 */
class ResultScene(private val catalog: SpriteCatalog) {

    sealed interface Action {
        data object None : Action
        data object PlayAgain : Action
        data object ReturnToLobby : Action
        data object MainMenu : Action
    }

    private val ui = ScreenUi(catalog)

    private var ranking: List<MatchState.Slot> = emptyList()
    private var victory = false
    private var winners: List<Int> = emptyList()
    private var enemiesDestroyed = 0
    private var reason: MatchState.EndReason = MatchState.EndReason.NONE
    private var totalEnemies = 0
    private var stageNumber = 1
    private var localSlot = -1

    var isHost: Boolean = false
        private set

    /** 아직 결과 화면에 남아 있는 다른 플레이어 수. 방장이 세어 준다. */
    var peersPresent: Int = 0

    /**
     * 그 자리 사람이 아직 결과 화면에 있는가. 방장 화면에서만 등으로 보여 준다.
     *
     * PLAY AGAIN 이 잠겼을 때 왜 잠겼는지 한눈에 알 수 있다. 숫자만 있으면
     * 누가 나갔는지 모른 채 기다리게 된다.
     */
    var presenceOf: (Int) -> Boolean = { true }

    /** 재석 등을 그릴지. 혼자 하는 판이나 참가자 화면에서는 알 길이 없다. */
    var showPresence: Boolean = false

    private var pressed = -1

    val playAgainEnabled: Boolean get() = playAgainAllowed(isHost, peersPresent)

    fun resize(width: Int, height: Int) = ui.resize(width, height)

    fun show(match: MatchState, stageIndex: Int, localSlot: Int, isHost: Boolean, peersPresent: Int) {
        // 등은 방장 화면에서만 뜻이 있다. 남은 사람을 세는 것이 방장뿐이기 때문이다.
        ranking = match.ranking()
        victory = match.phase == MatchState.Phase.VICTORY
        winners = if (victory) match.winners else emptyList()
        enemiesDestroyed = match.enemiesDestroyed
        reason = match.endReason
        totalEnemies = match.totalEnemies
        stageNumber = stageIndex + 1
        this.localSlot = localSlot
        this.isHost = isHost
        this.peersPresent = peersPresent
        pressed = -1
    }

    // -----------------------------------------------------------------------

    fun onDown(x: Float, y: Float) {
        pressed = buttonAt(x, y)
    }

    fun onUp(x: Float, y: Float): Action {
        val button = pressed
        pressed = -1
        if (button < 0 || button != buttonAt(x, y)) return Action.None
        return when (button) {
            BTN_AGAIN -> if (playAgainEnabled) Action.PlayAgain else Action.None
            BTN_LOBBY -> Action.ReturnToLobby
            BTN_HOME -> Action.MainMenu
            else -> Action.None
        }
    }

    fun onCancelTouch() {
        pressed = -1
    }

    private fun buttonAt(x: Float, y: Float): Int = when {
        ui.hits(buttonRect(0), x, y) -> BTN_AGAIN
        ui.hits(buttonRect(1), x, y) -> BTN_LOBBY
        ui.hits(buttonRect(2), x, y) -> BTN_HOME
        else -> -1
    }

    // -----------------------------------------------------------------------
    // 배치
    // -----------------------------------------------------------------------

    // 판마다 그려진 비율이 다르다. 늘려 쓰면 걸쇠와 모서리가 뭉개져 다른 물건처럼
    // 보이므로, 띠는 세 조각으로 나눠 가운데만 늘리고 나머지는 비율을 지킨다.

    private fun bannerRect() = ui.fitByWidth(bannerArt(), BANNER_WIDTH, 0.04f)

    private fun bannerArt() = if (victory) catalog.result.victory else catalog.result.gameOver

    private fun winnerRect() = ui.fitByHeight(catalog.result.winnerCard, 5.2f, 0.29f, LEFT_COLUMN)

    private fun stageRect() = ui.fitByHeight(catalog.result.stageClear, 3.8f, 0.66f, LEFT_COLUMN)

    private fun rowRect(index: Int): ScreenUi.Rect = ScreenUi.Rect(
        x = ui.width * 0.31f,
        y = ui.height * 0.29f + ui.unit * ROW_SPACING * index,
        width = ui.width * 0.61f,
        height = ui.unit * ROW_HEIGHT,
    )

    private fun buttonRect(index: Int): ScreenUi.Rect {
        val width = ui.width * 0.17f
        val gap = ui.width * 0.025f
        val total = width * 3f + gap * 2f
        return ScreenUi.Rect(
            x = (ui.width - total) * 0.5f + (width + gap) * index,
            y = ui.height * 0.855f,
            width = width,
            height = ui.unit * 1.5f,
        )
    }

    // -----------------------------------------------------------------------
    // 그리기
    // -----------------------------------------------------------------------

    fun render(batch: SpriteBatch) {
        if (ui.width <= 0f) return
        val art = catalog.result
        val unit = ui.unit

        val banner = bannerRect()
        ui.bar(batch, bannerArt(), banner)
        ui.label(
            batch,
            if (victory) "VICTORY" else "GAME OVER",
            banner,
            banner.height * 0.34f,
            if (victory) ScreenUi.ACCENT else ScreenUi.RED,
            BANNER_TEXT_CENTER,
            0.58f,
        )

        // 왜 끝났는지 한 줄 더 쓴다. 배너만 두면 본진이 깨진 것인지 다 죽은 것인지
        // 알 수 없다. (계획서 §33) 배너 안에 넣는다 — 아래는 순위 줄이 바로 온다.
        reasonText()?.let {
            ui.label(batch, it, banner, banner.height * 0.18f, ScreenUi.DIM, REASON_TEXT_CENTER, 0.6f)
        }

        drawWinner(batch)
        drawStageCard(batch)
        ranking.forEachIndexed { index, slot -> drawRow(batch, index, slot) }
        drawButtons(batch)
    }

    private fun reasonText(): String? = when (reason) {
        MatchState.EndReason.BASE_DESTROYED -> "BASE DESTROYED"
        MatchState.EndReason.ALL_PLAYERS_ELIMINATED -> "ALL PLAYERS DOWN"
        MatchState.EndReason.ALL_ENEMIES_DESTROYED -> "ALL COM DESTROYED"
        MatchState.EndReason.NONE -> null
    }

    private fun drawWinner(batch: SpriteBatch) {
        val art = catalog.result
        val rect = winnerRect()
        val unit = ui.unit
        ui.panel(batch, art.winnerCard, rect)

        val top = ranking.firstOrNull()
        val champion = if (victory) top else null
        ui.centered(batch, "WINNER", rect.centerX, rect.y + rect.height * 0.11f, unit * 0.45f, ScreenUi.DIM)
        if (champion == null) {
            ui.centered(batch, "NONE", rect.centerX, rect.centerY - unit * 0.4f, unit * 0.8f, ScreenUi.DIM)
            return
        }
        // 훈장은 모서리에, 탱크는 한가운데에. 누가 이겼는지가 먼저 보여야 한다.
        ui.icon(
            batch,
            art.medal,
            rect.x + rect.width * 0.82f,
            rect.y + rect.height * 0.18f,
            rect.width * 0.28f,
        )
        ui.icon(
            batch,
            catalog.playerPortrait(champion.tankType),
            rect.centerX,
            rect.centerY,
            rect.width * 0.48f,
            catalog.palette.colorOf(champion.colorIndex),
        )
        ui.centered(
            batch,
            champion.name,
            rect.centerX,
            rect.bottom - rect.height * 0.22f,
            unit * 0.7f,
            catalog.palette.colorOf(champion.colorIndex),
        )
        if (winners.size > 1) {
            ui.centered(batch, "TIE", rect.centerX, rect.bottom - rect.height * 0.1f, unit * 0.4f, ScreenUi.DIM)
        }
    }

    private fun drawStageCard(batch: SpriteBatch) {
        val rect = stageRect()
        val unit = ui.unit
        ui.panel(batch, catalog.result.stageClear, rect)
        ui.label(
            batch,
            if (victory) "STAGE $stageNumber CLEAR" else "STAGE $stageNumber",
            rect,
            unit * 0.42f,
            if (victory) ScreenUi.GREEN else ScreenUi.DIM,
            0.3f,
            0.78f,
        )
        ui.label(
            batch,
            "COM $enemiesDestroyed - $totalEnemies",
            rect,
            unit * 0.55f,
            ScreenUi.WHITE,
            0.62f,
            0.78f,
        )
    }

    private fun drawRow(batch: SpriteBatch, index: Int, slot: MatchState.Slot) {
        val art = catalog.result
        val rect = rowRect(index)
        val unit = ui.unit
        val color = catalog.palette.colorOf(slot.colorIndex)

        ui.bar(batch, art.rows[slot.colorIndex % art.rows.size], rect)
        // 등수 딱지는 줄 밖 왼쪽에 세운다. 줄 안에 넣으면 이름 자리를 먹는다.
        ui.icon(
            batch,
            art.rankBadges[index.coerceAtMost(art.rankBadges.size - 1)],
            rect.x - rect.height * 0.55f,
            rect.centerY,
            rect.height * 1.05f,
        )
        ui.icon(
            batch,
            catalog.playerPortrait(slot.tankType),
            rect.x + rect.height * 0.55f,
            rect.centerY,
            rect.height * 0.72f,
            color,
        )

        val textY = rect.centerY - unit * 0.28f
        ui.text(batch, slot.name, rect.x + rect.height * 1.05f, textY, unit * 0.56f, color)

        // 내 줄은 밝게. 여러 줄 중에서 자기 것을 먼저 찾게 한다.
        val statColor = if (slot.index == localSlot) ScreenUi.WHITE else ScreenUi.DIM
        stat(batch, "KILL", slot.kills, rect, 0.55f, textY, statColor)
        stat(batch, "LIFE", slot.lives, rect, 0.72f, textY, statColor)
        stat(batch, "HP", slot.lastHp, rect, 0.88f, textY, statColor)

        if (slot.eliminated) {
            ui.centered(batch, "OUT", rect.x + rect.width * 0.42f, textY, unit * 0.45f, ScreenUi.RED)
        }

        if (showPresence) {
            val present = presenceOf(slot.index)
            ui.icon(
                batch,
                if (present) catalog.menu.statusOn else catalog.menu.statusOff,
                rect.x + rect.width + rect.height * 0.5f,
                rect.centerY,
                rect.height * 0.6f,
                if (present) ScreenUi.GREEN else ScreenUi.DIM,
            )
        }
    }

    private fun stat(
        batch: SpriteBatch,
        label: String,
        value: Int,
        rect: ScreenUi.Rect,
        xRatio: Float,
        y: Float,
        color: Int,
    ) {
        val unit = ui.unit
        val centerX = rect.x + rect.width * xRatio
        ui.centered(batch, label, centerX, rect.y + unit * 0.18f, unit * 0.34f, ScreenUi.DIM)
        ui.centered(batch, value.toString(), centerX, y + unit * 0.12f, unit * 0.6f, color)
    }

    private fun drawButtons(batch: SpriteBatch) {
        val art = catalog.result
        drawButton(batch, 0, art.primary, BTN_AGAIN, "PLAY AGAIN", playAgainEnabled)
        drawButton(batch, 1, art.secondary, BTN_LOBBY, "LOBBY", true)
        drawButton(batch, 2, art.home, BTN_HOME, "MAIN MENU", true)
    }

    private fun drawButton(
        batch: SpriteBatch,
        index: Int,
        region: com.kophas.battlecity.render.TextureRegion,
        id: Int,
        label: String,
        enabled: Boolean,
    ) {
        val rect = buttonRect(index)
        val down = enabled && pressed == id
        ui.bar(batch, region, rect, alpha = if (!enabled) DISABLED_ALPHA else if (down) PRESSED_ALPHA else 1f)
        ui.label(
            batch,
            label,
            rect,
            ui.unit * 0.6f,
            if (enabled) ScreenUi.WHITE else ScreenUi.DIM,
            BUTTON_TEXT_CENTER,
            0.76f,
            alpha = if (enabled) 1f else DISABLED_ALPHA,
        )
        // 왜 못 누르는지 알려 준다. 잠긴 버튼만 두면 고장으로 읽힌다.
        if (id == BTN_AGAIN && !enabled) {
            ui.centered(
                batch,
                if (isHost) "NO PLAYERS LEFT" else "HOST ONLY",
                rect.centerX,
                rect.bottom + ui.unit * 0.1f,
                ui.unit * 0.36f,
                ScreenUi.DIM,
            )
        }
    }

    companion object {
        /**
         * PLAY AGAIN 을 누를 수 있는가. (계획서 §33)
         *
         * 방장이 아니면 못 누른다. 방을 다시 여는 일이라 각자 누르면 서로 다른 판이
         * 열린다. 방장이라도 결과 화면에 남은 사람이 없으면 못 누른다. 다시 열어야
         * 최소 인원(두 명)을 못 채운다.
         */
        fun playAgainAllowed(isHost: Boolean, peersPresent: Int): Boolean =
            isHost && peersPresent > 0

        private const val BTN_AGAIN = 0
        private const val BTN_LOBBY = 1
        private const val BTN_HOME = 2

        private const val BANNER_WIDTH = 0.24f
        private const val LEFT_COLUMN = 0.15f
        private const val ROW_HEIGHT = 1.3f
        private const val ROW_SPACING = 1.55f

        private const val BANNER_TEXT_CENTER = 0.43f
        private const val REASON_TEXT_CENTER = 0.72f
        private const val BUTTON_TEXT_CENTER = 0.46f
        private const val PRESSED_ALPHA = 0.65f
        private const val DISABLED_ALPHA = 0.35f
    }
}
