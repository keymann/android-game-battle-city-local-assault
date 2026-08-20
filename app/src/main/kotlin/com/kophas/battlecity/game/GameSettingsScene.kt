package com.kophas.battlecity.game

import com.kophas.battlecity.render.ScreenUi
import com.kophas.battlecity.render.SpriteBatch
import com.kophas.battlecity.render.SpriteCatalog
import com.kophas.battlecity.render.TextureRegion
import kotlin.math.roundToInt

/**
 * 게임 설정 화면. 방 규칙만 다룬다. (계획서 §44.2)
 *
 * 맵 크기 · 시드 · 아군 오사 · 본진 보호 · 동시 COM 수. 모두 **그 방의 모두에게**
 * 같이 걸리고 판을 열 때 굳는다. 그래서 **로비에서 방장만** 이 화면을 연다
 * (→ [LobbyScene.settingsAllowed]). 참가자에게 읽기 전용으로 보여 주던 것을
 * 걷어냈다 — 고칠 수 없는 값을 보여 주면 왜 안 되는지부터 설명해야 한다.
 *
 * 소리 크기는 여기 없다. 그것은 기기마다 다르고 방과 무관해서
 * [SoundSettingsScene] 으로 갈라 두었다.
 *
 * 방 규칙만 남으니 판 하나를 다섯 줄이 나눠 쓴다. 예전에는 두 칸으로 쪼개 반쪽에
 * 밀어 넣었는데, 이제는 줄마다 판 너비를 다 쓰므로 글자를 키우고 줄 간격을 벌렸다.
 */
class GameSettingsScene(private val catalog: SpriteCatalog) {

    sealed interface Action {
        data object None : Action
        data object Cancel : Action

        /** 저장하고 닫는다. */
        data class Apply(val room: RoomSettings) : Action
    }

    private val ui = ScreenUi(catalog)
    private val layout = SettingsLayout(METRICS)

    /** 편집 중인 사본. 취소하면 그냥 버린다. */
    private var room = RoomSettings()

    private var draggingEnemies = false
    private var pressedButton = -1

    fun resize(width: Int, height: Int) {
        ui.resize(width, height)
        layout.resize(ui.width, ui.height, catalog.settings.panel.aspect)
    }

    fun open(room: RoomSettings) {
        this.room = room
        draggingEnemies = false
        pressedButton = -1
    }

    // -----------------------------------------------------------------------
    // 입력
    // -----------------------------------------------------------------------

    fun onDown(x: Float, y: Float) {
        if (ui.hits(enemiesSlider(), x, y)) {
            draggingEnemies = true
            onMove(x, y)
            return
        }
        pressedButton = buttonAt(x, y)
    }

    fun onMove(x: Float, y: Float) {
        if (!draggingEnemies) return
        val rect = enemiesSlider()
        val fraction = ((x - rect.x) / rect.width).coerceIn(0f, 1f)
        room = room.copy(maxActiveEnemies = activeEnemiesAt(fraction))
    }

    fun onUp(x: Float, y: Float): Action {
        if (draggingEnemies) {
            draggingEnemies = false
            return Action.None
        }
        val button = pressedButton
        pressedButton = -1
        // 누른 자리에서 손을 떼야 눌린 것으로 본다. 끌어서 벗어나면 취소다.
        if (button < 0 || button != buttonAt(x, y)) return Action.None
        return activate(button)
    }

    fun onCancelTouch() {
        draggingEnemies = false
        pressedButton = -1
    }

    private fun activate(button: Int): Action = when (button) {
        BTN_APPLY -> Action.Apply(room)
        BTN_CANCEL -> Action.Cancel
        BTN_RESET -> {
            // 시드는 남긴다. 방금 고른 판을 다시 열려던 사람이 그것까지 잃으면 안 된다.
            room = RoomSettings(randomSeed = room.randomSeed)
            Action.None
        }
        BTN_DICE -> {
            room = room.copy(randomSeed = nextSeed(room.randomSeed))
            Action.None
        }
        // 값 칸을 누르면 AUTO 로 돌아온다. 주사위만으로는 자동으로 되돌릴 길이 없다.
        BTN_SEED -> {
            room = room.copy(randomSeed = if (room.randomSeed == 0L) nextSeed(1L) else 0L)
            Action.None
        }
        BTN_FRIENDLY -> {
            room = room.copy(friendlyFire = !room.friendlyFire)
            Action.None
        }
        BTN_PROTECT -> {
            room = room.copy(baseProtection = !room.baseProtection)
            Action.None
        }
        in BTN_SIZE_FIRST..(BTN_SIZE_FIRST + 2) -> {
            room = room.copy(mapSize = RoomSettings.MapSize.entries[button - BTN_SIZE_FIRST])
            Action.None
        }
        else -> Action.None
    }

    /**
     * 다음 시드. 표시할 수 있는 자리수 안에서 고른다.
     *
     * 난수원을 쓰지 않고 이전 시드를 섞는다. 같은 화면에서 두 번 누르면 다른 값이
     * 나오고, 값이 어디서 왔는지 추적할 수 있다.
     */
    private fun nextSeed(previous: Long): Long {
        var x = previous * 6364136223846793005L + 1442695040888963407L
        x = x xor (x ushr 33)
        return (x and 0xFFFFFL).coerceAtLeast(1L)
    }

    private fun buttonAt(x: Float, y: Float): Int {
        for (i in 0 until 3) if (ui.hits(segmentRect(i), x, y)) return BTN_SIZE_FIRST + i
        return when {
            ui.hits(seedRect(), x, y) -> BTN_SEED
            ui.hits(diceRect(), x, y) -> BTN_DICE
            ui.hits(toggleRect(ROW_FRIENDLY), x, y) -> BTN_FRIENDLY
            ui.hits(toggleRect(ROW_PROTECT), x, y) -> BTN_PROTECT
            ui.hits(layout.actionRect(0), x, y) -> BTN_RESET
            ui.hits(layout.actionRect(1), x, y) -> BTN_CANCEL
            ui.hits(layout.actionRect(2), x, y) -> BTN_APPLY
            else -> -1
        }
    }

    // -----------------------------------------------------------------------
    // 배치
    // -----------------------------------------------------------------------

    private fun segmentRect(index: Int): ScreenUi.Rect {
        val base = layout.controlRect(ROW_SIZE)
        val gap = base.width * 0.03f
        val width = (base.width - gap * 2f) / 3f
        return ScreenUi.Rect(base.x + (width + gap) * index, base.y, width, base.height)
    }

    private fun diceRect(): ScreenUi.Rect {
        val base = layout.controlRect(ROW_SEED)
        return ScreenUi.Rect(base.x + base.width - base.height, base.y, base.height, base.height)
    }

    /** 시드 값이 들어가는 칸. 눌러서 AUTO 와 고정값을 오간다. */
    private fun seedRect(): ScreenUi.Rect {
        val base = layout.controlRect(ROW_SEED)
        return ScreenUi.Rect(base.x, base.y, base.width - base.height * 1.15f, base.height)
    }

    private fun toggleRect(row: Int): ScreenUi.Rect {
        val base = layout.controlRect(row)
        val width = base.height * 1.9f
        return ScreenUi.Rect(base.x + base.width - width, base.y, width, base.height)
    }

    private fun enemiesSlider(): ScreenUi.Rect = layout.sliderRect(ROW_ENEMIES)

    // -----------------------------------------------------------------------
    // 그리기
    // -----------------------------------------------------------------------

    fun render(batch: SpriteBatch) {
        if (!layout.ready) return
        val art = catalog.settings
        val panel = layout.panelRect()

        ui.bar(batch, art.panel, panel)
        ui.centered(
            batch,
            "GAME SETTINGS",
            panel.centerX,
            layout.titleY(),
            layout.titleSize(),
            ScreenUi.ACCENT,
        )

        drawMapSize(batch)
        drawSeed(batch)

        rowLabel(batch, "FRIENDLY FIRE", ROW_FRIENDLY)
        drawToggle(batch, toggleRect(ROW_FRIENDLY), room.friendlyFire)

        rowLabel(batch, "BASE PROTECTION", ROW_PROTECT)
        drawToggle(batch, toggleRect(ROW_PROTECT), room.baseProtection)

        drawEnemies(batch)
        drawActions(batch)
    }

    private fun drawMapSize(batch: SpriteBatch) {
        val art = catalog.settings
        rowLabel(batch, "MAP SIZE", ROW_SIZE)
        for (index in 0 until 3) {
            val rect = segmentRect(index)
            val selected = room.mapSize.ordinal == index
            ui.panel(batch, if (selected) art.segmentSelected else art.segment, rect)
            ui.label(
                batch,
                SIZE_LABELS[index],
                rect,
                layout.unit * SEGMENT_TEXT_SIZE,
                if (selected) ScreenUi.WHITE else ScreenUi.DIM,
                SEGMENT_TEXT_CENTER,
                0.86f,
            )
        }
    }

    private fun drawSeed(batch: SpriteBatch) {
        val art = catalog.settings
        rowLabel(batch, "RANDOM SEED", ROW_SEED)

        val seed = seedRect()
        ui.bar(batch, art.dropdown, seed, alpha = if (pressedButton == BTN_SEED) PRESSED_ALPHA else 1f)
        ui.label(
            batch,
            if (room.randomSeed == 0L) "AUTO" else room.randomSeed.toString(),
            seed,
            layout.valueTextSize(),
            ScreenUi.WHITE,
            0.5f,
            0.8f,
        )

        val dice = diceRect()
        ui.icon(
            batch,
            art.dice,
            dice.centerX,
            dice.centerY,
            dice.height * 0.95f,
            ScreenUi.WHITE,
            alpha = if (pressedButton == BTN_DICE) PRESSED_ALPHA else 1f,
        )
    }

    private fun drawEnemies(batch: SpriteBatch) {
        rowLabel(batch, "MAX COM", ROW_ENEMIES)
        drawSlider(
            batch,
            enemiesSlider(),
            activeEnemiesFraction(room.maxActiveEnemies),
            // 0 은 "정하지 않았다" 는 뜻이다. 숫자로 보이면 그 값이 쓰이는 줄 안다.
            if (room.maxActiveEnemies == RoomSettings.ACTIVE_ENEMIES_AUTO) {
                "AUTO"
            } else {
                room.maxActiveEnemies.toString()
            },
        )
    }

    private fun drawActions(batch: SpriteBatch) {
        val art = catalog.settings
        drawAction(batch, 0, art.reset, BTN_RESET, "RESET", ScreenUi.DIM)
        drawAction(batch, 1, art.close, BTN_CANCEL, "CANCEL", ScreenUi.WHITE)
        drawAction(batch, 2, art.apply, BTN_APPLY, "APPLY", ScreenUi.ACCENT)
    }

    private fun drawAction(
        batch: SpriteBatch,
        index: Int,
        region: TextureRegion,
        id: Int,
        label: String,
        color: Int,
    ) {
        val rect = layout.actionRect(index)
        ui.bar(batch, region, rect, alpha = if (pressedButton == id) PRESSED_ALPHA else 1f)
        ui.label(batch, label, rect, layout.unit * ACTION_TEXT_SIZE, color, BUTTON_TEXT_CENTER, 0.7f)
    }

    private fun rowLabel(batch: SpriteBatch, text: String, row: Int) {
        val unit = layout.unit
        ui.text(
            batch,
            text,
            layout.labelX(),
            layout.rowY(row) + unit * LABEL_DROP,
            unit * LABEL_SIZE,
            ScreenUi.WHITE,
        )
    }

    private fun drawToggle(batch: SpriteBatch, rect: ScreenUi.Rect, on: Boolean) {
        val art = catalog.settings
        ui.panel(batch, if (on) art.toggleOn else art.toggleOff, rect)
    }

    private fun drawSlider(batch: SpriteBatch, rect: ScreenUi.Rect, fraction: Float, value: String) {
        val art = catalog.settings
        ui.panel(batch, art.sliderTrack, rect)
        val thumb = rect.height * 1.7f
        ui.icon(
            batch,
            art.sliderThumb,
            rect.x + thumb * 0.5f + (rect.width - thumb) * fraction.coerceIn(0f, 1f),
            rect.centerY,
            thumb,
        )
        // 값은 눈금 오른쪽에 왼쪽맞춤으로 쓴다. 가운데맞춤이면 글자 수에 따라
        // 눈금 위로 밀고 들어온다 (AUTO 처럼 긴 값).
        val valueSize = layout.valueTextSize()
        ui.text(
            batch,
            value,
            layout.sliderValueX(ROW_ENEMIES),
            rect.centerY - valueSize * 0.5f,
            valueSize,
            ScreenUi.WHITE,
        )
    }

    companion object {
        /**
         * 동시 COM 슬라이더. 맨 왼쪽 칸이 AUTO 고 그 뒤가 8 ~ 12 다. (계획서 §44.2)
         *
         * AUTO 를 칸 하나로 둔 이유는, 손으로 한 번 정하고 나면 "인원에 맞춰 알아서"
         * 로 되돌릴 길이 없어지기 때문이다. 숫자 사이에 끼워 넣을 수 없으니 끝에 둔다.
         */
        fun activeEnemiesAt(fraction: Float): Int {
            val step = (fraction.coerceIn(0f, 1f) * ACTIVE_ENEMY_STOPS).roundToInt()
            return if (step == 0) {
                RoomSettings.ACTIVE_ENEMIES_AUTO
            } else {
                (RoomSettings.MIN_ACTIVE_ENEMIES + step - 1)
                    .coerceAtMost(RoomSettings.MAX_ACTIVE_ENEMIES)
            }
        }

        /** 값이 놓일 슬라이더 자리. [activeEnemiesAt] 의 역이다. */
        fun activeEnemiesFraction(value: Int): Float {
            if (value < RoomSettings.MIN_ACTIVE_ENEMIES) return 0f
            val step = value.coerceAtMost(RoomSettings.MAX_ACTIVE_ENEMIES) -
                RoomSettings.MIN_ACTIVE_ENEMIES + 1
            return step / ACTIVE_ENEMY_STOPS.toFloat()
        }

        /**
         * 다섯 줄이 판 하나를 나눠 쓴다.
         *
         * 방 규칙만 남아 예전 두 칸 배치의 반쪽이 비었다. 칸을 없애고 줄마다 판
         * 너비를 다 쓰게 하면서 줄 간격(1.5칸)과 글자를 함께 키웠다.
         */
        val METRICS = SettingsLayout.Metrics(
            panelTop = 0.05f,
            panelWidth = 0.80f,
            panelUnits = 12.8f,
            rowTop = 0.55f,
            rowHeight = 1.5f,
            // 이름표 자리는 가장 긴 "BASE PROTECTION" 이 들어갈 만큼 남겨야 한다.
            controlWidth = 0.46f,
            controlHeight = 1.2f,
            // 가장 긴 값은 시드 칸의 "AUTO" 다. 그것이 들어갈 만큼 떼어 둔다.
            valueReserve = 2.6f,
            sliderThickness = 0.5f,
            valueSize = 0.64f,
            rowCount = 5,
        )

        private const val ROW_SIZE = 0
        private const val ROW_SEED = 1
        private const val ROW_FRIENDLY = 2
        private const val ROW_PROTECT = 3
        private const val ROW_ENEMIES = 4

        /** 글자 크기. 모두 한 칸 대비다. 판이 넓어진 만큼 예전보다 키웠다. */
        private const val LABEL_SIZE = 0.66f
        private const val SEGMENT_TEXT_SIZE = 0.55f
        private const val ACTION_TEXT_SIZE = 0.64f

        /** 이름표를 줄 위선에서 내리는 만큼. 조작기와 눈높이를 맞춘다. */
        private const val LABEL_DROP = 0.36f

        /** AUTO 를 뺀 눈금 수. 8 · 9 · 10 · 11 · 12 로 다섯이다. */
        private const val ACTIVE_ENEMY_STOPS =
            RoomSettings.MAX_ACTIVE_ENEMIES - RoomSettings.MIN_ACTIVE_ENEMIES + 1

        private const val BTN_RESET = 100
        private const val BTN_CANCEL = 101
        private const val BTN_APPLY = 102
        private const val BTN_DICE = 103
        private const val BTN_FRIENDLY = 104
        private const val BTN_PROTECT = 105
        private const val BTN_SEED = 106
        private const val BTN_SIZE_FIRST = 0

        private const val PRESSED_ALPHA = 0.65f
        private const val BUTTON_TEXT_CENTER = 0.46f
        private const val SEGMENT_TEXT_CENTER = 0.5f

        private val SIZE_LABELS = arrayOf("SMALL", "NORMAL", "LARGE")
    }
}
