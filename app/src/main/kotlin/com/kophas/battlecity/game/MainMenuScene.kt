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
 *  [프로필]                        [소리]
 * ```
 *
 * 방을 열거나 남의 방에 들어간다. 혼자 하는 판은 여기 없다. 최소 인원이 두 명이라
 * (계획서 §28) 혼자서는 시작할 수 없기 때문이다.
 *
 * 오른쪽 아래 단추는 **사운드 설정**을 연다. 방 규칙은 방장이 로비에서 정하므로
 * (→ [GameSettingsScene]) 여기 남는 설정은 이 기기의 소리 크기뿐이다.
 */
class MainMenuScene(private val catalog: SpriteCatalog) {

    sealed interface Action {
        data object None : Action
        data object CreateGame : Action
        data object JoinGame : Action

        /** 이 기기의 소리 크기를 고친다. (→ [SoundSettingsScene]) */
        data object OpenSound : Action
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
            ui.hits(soundRect(), x, y) -> Action.OpenSound
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
        drawSoundButton(batch)
    }

    private fun drawButton(
        batch: SpriteBatch,
        rect: ScreenUi.Rect,
        action: Action,
        label: String,
        color: Int,
    ) {
        val primary = action == Action.CreateGame
        // 눌렸다는 것은 흐리기로 말한다. 눌린 그림으로 갈아 끼우면 그림마다 다듬긴
        // 여백이 달라 같은 자리에 그려도 판이 한 번 튀었다 돌아온다.
        val alpha = if (pressed == action) PRESSED_ALPHA else 1f
        ui.bar(batch, if (primary) catalog.menu.primary else catalog.menu.secondary, rect, alpha = alpha)
        ui.label(batch, label, rect, rect.height * 0.32f, color, BUTTON_TEXT_CENTER, 0.66f)
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

    /**
     * 사운드 설정 단추와 그 옆의 망 상태.
     *
     * 단추 그림은 설정판의 스피커(`set_speaker_on`)를 그대로 쓴다. 톱니바퀴는
     * "설정 전부" 로 읽히는데 여기서 열리는 것은 소리뿐이다.
     *
     * 망 상태는 같은 망에 방이 있는지 알려 준다. 없는데 JOIN 을 눌러 기다리는 일을
     * 줄인다.
     */
    private fun drawSoundButton(batch: SpriteBatch) {
        val rect = soundRect()
        ui.panel(
            batch,
            catalog.menu.sound,
            rect,
            alpha = if (pressed == Action.OpenSound) PRESSED_ALPHA else 1f,
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

    // 두 단추를 붙여 두면 하나로 보이고 잘못 누른다. 손가락 하나가 들어갈 만큼 띄운다.
    private fun createRect() = ui.fitByWidth(catalog.menu.primary, BUTTON_WIDTH, CREATE_TOP)

    private fun joinRect() = ui.fitByWidth(catalog.menu.secondary, BUTTON_WIDTH, JOIN_TOP)

    private fun soundRect() = ui.fitByHeight(catalog.menu.sound, 1.7f, 0.83f, 0.93f)

    private companion object {
        const val TITLE_TOP = 0.05f
        const val TITLE_WIDTH = 0.30f
        const val BUTTON_WIDTH = 0.26f
        const val CREATE_TOP = 0.36f
        const val JOIN_TOP = 0.67f

        // 판 그림에서 잰 자리. 제목판은 두 줄이 들어간다.
        const val TITLE_LINE1 = 0.42f
        const val TITLE_LINE2 = 0.70f
        const val BUTTON_TEXT_CENTER = 0.47f
        const val PROFILE_ASPECT = 2.4f
        const val PRESSED_ALPHA = 0.65f
    }
}
