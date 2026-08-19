package com.kophas.battlecity.game

import com.kophas.battlecity.render.ScreenUi
import com.kophas.battlecity.render.SpriteBatch
import com.kophas.battlecity.render.SpriteCatalog
import com.kophas.battlecity.render.TextLayout

/**
 * 메인 메뉴. (계획서 §27 게임 상태 머신)
 *
 * ```
 *          BATTLE CITY: LOCAL ASSAULT
 *               [ CREATE GAME ]
 *               [  JOIN GAME  ]
 *  [프로필]                        [설정]
 * ```
 *
 * 방을 열거나 남의 방에 들어간다. 혼자 하는 판은 여기 없다. 최소 인원이 두 명이라
 * (계획서 §28) 혼자서는 시작할 수 없기 때문이다.
 */
class MainMenuScene(private val catalog: SpriteCatalog) {

    sealed interface Action {
        data object None : Action
        data object CreateGame : Action
        data object JoinGame : Action
        data object OpenSettings : Action
    }

    private val ui = ScreenUi(catalog)
    private var pressed: Action = Action.None

    /** 이 기기의 이름. 프로필 배지에 보여 준다. */
    var playerName: String = "P1"

    /** 같은 망에 방이 보이는가. 아직 안 찾아봤으면 null. */
    var roomsFound: Int? = null

    fun resize(width: Int, height: Int) = ui.resize(width, height)

    fun onTap(x: Float, y: Float): Action {
        pressed = when {
            ui.hits(createRect(), x, y) -> Action.CreateGame
            ui.hits(joinRect(), x, y) -> Action.JoinGame
            ui.hits(settingsRect(), x, y) -> Action.OpenSettings
            else -> Action.None
        }
        return pressed
    }

    fun onRelease() {
        pressed = Action.None
    }

    // -----------------------------------------------------------------------

    fun render(batch: SpriteBatch) {
        if (ui.width <= 0f) return
        val unit = ui.unit

        val title = ui.fitByWidth(catalog.menu.title, TITLE_WIDTH, TITLE_TOP)
        ui.bar(batch, catalog.menu.title, title)
        ui.label(batch, "BATTLE CITY", title, title.height * 0.34f, ScreenUi.ACCENT, TITLE_LINE1, 0.62f)
        ui.label(batch, "LOCAL ASSAULT", title, title.height * 0.22f, ScreenUi.WHITE, TITLE_LINE2, 0.5f)

        drawButton(batch, createRect(), Action.CreateGame, "CREATE GAME", ScreenUi.ACCENT)
        drawButton(batch, joinRect(), Action.JoinGame, "JOIN GAME", ScreenUi.CYAN)

        drawProfile(batch, unit)
        drawSettingsButton(batch)
    }

    private fun drawButton(
        batch: SpriteBatch,
        rect: ScreenUi.Rect,
        action: Action,
        label: String,
        color: Int,
    ) {
        val primary = action == Action.CreateGame
        val down = pressed == action
        ui.bar(
            batch,
            when {
                primary && down -> catalog.menu.primaryPressed
                primary -> catalog.menu.primary
                down -> catalog.menu.secondaryPressed
                else -> catalog.menu.secondary
            },
            rect,
        )
        // 아이콘은 글자 왼쪽에 둔다. 무엇을 하는 버튼인지 읽기 전에 알아본다.
        val iconSize = rect.height * 0.62f
        val iconCenter = rect.x + rect.width * ICON_INSET
        ui.icon(
            batch,
            if (primary) catalog.menu.createIcon else catalog.menu.joinIcon,
            iconCenter,
            rect.centerY,
            iconSize,
        )
        // 글자는 버튼 한가운데가 아니라 **아이콘 오른쪽 남은 자리**의 가운데에 놓는다.
        // 버튼 기준으로 가운데를 잡으면 아이콘 쪽으로 붙어 겹쳐 보인다.
        val textLeft = iconCenter + iconSize * 0.7f
        val textRight = rect.x + rect.width * (1f - ICON_INSET * 0.6f)
        val size = TextLayout.fit(label, rect.height * 0.32f, textRight - textLeft)
        ui.centered(
            batch,
            label,
            (textLeft + textRight) * 0.5f,
            rect.y + rect.height * BUTTON_TEXT_CENTER - size * 0.5f,
            size,
            color,
        )
    }

    private fun drawProfile(batch: SpriteBatch, unit: Float) {
        val rect = profileRect()
        ui.bar(batch, catalog.menu.profile, rect)
        ui.icon(
            batch,
            catalog.menu.playerIcon,
            rect.x + rect.width * 0.27f,
            rect.centerY,
            rect.height * 0.5f,
            ScreenUi.CYAN,
        )
        ui.centered(
            batch,
            playerName,
            rect.x + rect.width * 0.66f,
            rect.centerY - rect.height * 0.2f,
            rect.height * 0.4f,
        )
    }

    /** 같은 망에 방이 있는지 알려 준다. 없는데 JOIN 을 눌러 기다리는 일을 줄인다. */
    private fun drawSettingsButton(batch: SpriteBatch) {
        val rect = settingsRect()
        ui.panel(
            batch,
            if (pressed == Action.OpenSettings) catalog.menu.settingsPressed else catalog.menu.settings,
            rect,
        )

        val found = roomsFound
        val signal = when {
            found == null -> catalog.menu.netWeak
            found > 0 -> catalog.menu.netStrong
            else -> catalog.menu.netOff
        }
        val size = ui.unit * 1.3f
        val centerY = ui.height * 0.06f
        val label = when {
            found == null -> "SCANNING"
            found > 0 -> "$found ROOM"
            else -> "NO ROOM"
        }
        // 아이콘과 글자를 한 덩어리로 놓고 오른쪽 끝에 맞춘다. 글자 길이가 바뀌어도
        // 화면 밖으로 밀려나지 않는다.
        val textSize = ui.unit * 0.5f
        val textWidth = TextLayout.width(label, textSize)
        val right = ui.width * 0.98f
        ui.icon(batch, signal, right - textWidth - size, centerY, size)
        ui.text(
            batch,
            label,
            right - textWidth,
            centerY - textSize * 0.5f,
            textSize,
            if (found != null && found > 0) ScreenUi.GREEN else ScreenUi.DIM,
        )
    }

    /** 이름이 들어가도록 정사각 배지를 옆으로 늘린다. 늘어나는 곳은 가운데뿐이다. */
    private fun profileRect(): ScreenUi.Rect {
        val base = ui.fitByHeight(catalog.menu.profile, 1.7f, 0.83f, 0.09f)
        val width = base.height * PROFILE_ASPECT
        return ScreenUi.Rect(ui.width * 0.02f, base.y, width, base.height)
    }

    private fun createRect() = ui.fitByWidth(catalog.menu.primary, BUTTON_WIDTH, 0.40f)

    private fun joinRect() = ui.fitByWidth(catalog.menu.secondary, BUTTON_WIDTH, 0.64f)

    private fun settingsRect() = ui.fitByHeight(catalog.menu.settings, 1.7f, 0.83f, 0.93f)

    private companion object {
        const val TITLE_TOP = 0.05f
        const val TITLE_WIDTH = 0.30f
        const val BUTTON_WIDTH = 0.26f

        // 판 그림에서 잰 자리. 제목판은 두 줄이 들어간다.
        const val TITLE_LINE1 = 0.42f
        const val TITLE_LINE2 = 0.70f
        const val BUTTON_TEXT_CENTER = 0.47f
        const val ICON_INSET = 0.13f
        const val PROFILE_ASPECT = 2.4f
    }
}
