package com.kophas.battlecity.game

import com.kophas.battlecity.render.ScreenUi
import com.kophas.battlecity.render.SpriteBatch
import com.kophas.battlecity.render.SpriteCatalog
import kotlin.math.roundToInt

/**
 * 로비 설정 화면. (계획서 §44.2 방 설정)
 *
 * 두 종류가 한 화면에 있다.
 *  - **방 규칙** (맵 크기 · 시드 · 아군 오사 · 본진 보호 · COM 수): 방을 열 때 굳는다.
 *    방장만 만질 수 있고, 참가자에게는 읽기 전용으로 보인다.
 *  - **소리 크기**: 이 기기에만 걸린다. 남의 귀까지 정해 줄 이유가 없으므로
 *    방장이 아니어도 언제나 바꿀 수 있고, 손을 떼는 즉시 반영된다.
 */
class SettingsScene(private val catalog: SpriteCatalog) {

    sealed interface Action {
        data object None : Action
        data object Cancel : Action

        /** 저장하고 닫는다. 방 규칙은 [editableRoom] 일 때만 의미가 있다. */
        data class Apply(val room: RoomSettings, val device: DeviceSettings) : Action
    }

    private enum class Handle { NONE, ENEMIES, BGM, SFX }

    private val ui = ScreenUi(catalog)

    /** 편집 중인 사본. 취소하면 그냥 버린다. */
    private var room = RoomSettings()
    private var device = DeviceSettings()
    private var openedRoom = RoomSettings()
    private var openedDevice = DeviceSettings()

    /** 방장인가. 아니면 방 규칙은 보기만 한다. */
    var editableRoom: Boolean = true
        private set

    private var dragging = Handle.NONE
    private var pressedButton = -1

    /** 소리 크기가 바뀔 때마다 부른다. 슬라이더를 끄는 동안 바로 들려야 한다. */
    var onDeviceChanged: (DeviceSettings) -> Unit = {}

    fun resize(width: Int, height: Int) = ui.resize(width, height)

    fun open(room: RoomSettings, device: DeviceSettings, editableRoom: Boolean) {
        this.room = room
        this.device = device
        this.openedRoom = room
        this.openedDevice = device
        this.editableRoom = editableRoom
        dragging = Handle.NONE
        pressedButton = -1
    }

    // -----------------------------------------------------------------------
    // 입력
    // -----------------------------------------------------------------------

    fun onDown(x: Float, y: Float) {
        dragging = when {
            editableRoom && ui.hits(sliderRect(ROW_ENEMIES, LEFT), x, y) -> Handle.ENEMIES
            ui.hits(sliderRect(ROW_BGM, RIGHT), x, y) -> Handle.BGM
            ui.hits(sliderRect(ROW_SFX, RIGHT), x, y) -> Handle.SFX
            else -> Handle.NONE
        }
        if (dragging != Handle.NONE) {
            onMove(x, y)
            return
        }
        pressedButton = buttonAt(x, y)
    }

    fun onMove(x: Float, y: Float) {
        when (dragging) {
            Handle.NONE -> return
            Handle.ENEMIES -> {
                val range = RoomSettings.MIN_ACTIVE_ENEMIES..RoomSettings.MAX_ACTIVE_ENEMIES
                val value = range.first + (fraction(sliderRect(ROW_ENEMIES, LEFT), x) *
                    (range.last - range.first)).roundToInt()
                room = room.copy(maxActiveEnemies = value.coerceIn(range))
            }
            Handle.BGM -> {
                device = device.copy(bgmVolume = volumeAt(sliderRect(ROW_BGM, RIGHT), x))
                onDeviceChanged(device)
            }
            Handle.SFX -> {
                device = device.copy(sfxVolume = volumeAt(sliderRect(ROW_SFX, RIGHT), x))
                onDeviceChanged(device)
            }
        }
    }

    fun onUp(x: Float, y: Float): Action {
        if (dragging != Handle.NONE) {
            dragging = Handle.NONE
            return Action.None
        }
        val button = pressedButton
        pressedButton = -1
        if (button < 0 || button != buttonAt(x, y)) return Action.None
        return activate(button)
    }

    fun onCancelTouch() {
        dragging = Handle.NONE
        pressedButton = -1
    }

    private fun volumeAt(rect: ScreenUi.Rect, x: Float): Int = quantizeVolume(fraction(rect, x))

    private fun fraction(rect: ScreenUi.Rect, x: Float): Float =
        ((x - rect.x) / rect.width).coerceIn(0f, 1f)

    private fun activate(button: Int): Action = when (button) {
        BTN_APPLY -> Action.Apply(room, device)
        BTN_CANCEL -> {
            // 취소하면 소리도 열었을 때로 되돌린다. 미리 들려 준 값이 남으면 안 된다.
            if (device != openedDevice) onDeviceChanged(openedDevice)
            Action.Cancel
        }
        BTN_RESET -> {
            room = if (editableRoom) RoomSettings(randomSeed = room.randomSeed) else openedRoom
            device = DeviceSettings()
            onDeviceChanged(device)
            Action.None
        }
        BTN_DICE -> {
            if (editableRoom) room = room.copy(randomSeed = nextSeed(room.randomSeed))
            Action.None
        }
        // 값 칸을 누르면 AUTO 로 돌아온다. 주사위만으로는 자동으로 되돌릴 길이 없다.
        BTN_SEED -> {
            if (editableRoom) {
                room = room.copy(randomSeed = if (room.randomSeed == 0L) nextSeed(1L) else 0L)
            }
            Action.None
        }
        BTN_FRIENDLY -> {
            if (editableRoom) room = room.copy(friendlyFire = !room.friendlyFire)
            Action.None
        }
        BTN_PROTECT -> {
            if (editableRoom) room = room.copy(baseProtection = !room.baseProtection)
            Action.None
        }
        in BTN_SIZE_FIRST..(BTN_SIZE_FIRST + 2) -> {
            if (editableRoom) {
                room = room.copy(mapSize = RoomSettings.MapSize.entries[button - BTN_SIZE_FIRST])
            }
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
            ui.hits(toggleRect(ROW_FRIENDLY, LEFT), x, y) -> BTN_FRIENDLY
            ui.hits(toggleRect(ROW_PROTECT, LEFT), x, y) -> BTN_PROTECT
            ui.hits(actionRect(0), x, y) -> BTN_RESET
            ui.hits(actionRect(1), x, y) -> BTN_CANCEL
            ui.hits(actionRect(2), x, y) -> BTN_APPLY
            else -> -1
        }
    }

    // -----------------------------------------------------------------------
    // 배치
    // -----------------------------------------------------------------------

    /**
     * 설정판. 세 조각으로 나눠 가운데만 늘린다.
     *
     * 그림 비율대로만 놓으면 두 칸이 좁아 이름표와 조작기가 겹치고, 통째로 늘리면
     * 모서리 장식이 부풀어 오른다. 끝을 그대로 두고 가운데만 늘리면 둘 다 피한다.
     */
    private fun panelRect() = ui.centeredRect(0.03f, PANEL_WIDTH, PANEL_UNITS)

    private fun column(side: Int): Float {
        val panel = panelRect()
        return panel.x + panel.width * (if (side == LEFT) 0.05f else 0.53f)
    }

    private fun columnWidth(): Float = panelRect().width * 0.42f

    private fun rowY(row: Int): Float = panelRect().y + ui.unit * (ROW_TOP + row * ROW_HEIGHT)

    /** 조작기는 칸의 오른쪽 절반에 놓는다. 왼쪽 절반은 이름표 자리다. */
    private fun controlRect(row: Int, side: Int, widthScale: Float = CONTROL_WIDTH): ScreenUi.Rect {
        val width = columnWidth() * widthScale
        return ScreenUi.Rect(
            x = column(side) + columnWidth() - width,
            y = rowY(row) + ui.unit * 0.15f,
            width = width,
            height = ui.unit * 0.95f,
        )
    }

    private fun segmentRect(index: Int): ScreenUi.Rect {
        val base = controlRect(ROW_SIZE, LEFT)
        val gap = base.width * 0.03f
        val width = (base.width - gap * 2f) / 3f
        return ScreenUi.Rect(base.x + (width + gap) * index, base.y, width, base.height)
    }

    private fun diceRect(): ScreenUi.Rect {
        val base = controlRect(ROW_SEED, LEFT)
        return ScreenUi.Rect(base.x + base.width - base.height, base.y, base.height, base.height)
    }

    /** 시드 값이 들어가는 칸. 눌러서 AUTO 와 고정값을 오간다. */
    private fun seedRect(): ScreenUi.Rect {
        val base = controlRect(ROW_SEED, LEFT)
        return ScreenUi.Rect(base.x, base.y, base.width - base.height * 1.15f, base.height)
    }

    private fun toggleRect(row: Int, side: Int): ScreenUi.Rect {
        val base = controlRect(row, side)
        val width = base.height * 1.9f
        return ScreenUi.Rect(base.x + base.width - width, base.y, width, base.height)
    }

    private fun sliderRect(row: Int, side: Int): ScreenUi.Rect {
        val base = controlRect(row, side, CONTROL_WIDTH * 0.82f)
        return ScreenUi.Rect(base.x, base.centerY - ui.unit * 0.22f, base.width, ui.unit * 0.44f)
    }

    private fun actionRect(index: Int): ScreenUi.Rect {
        val panel = panelRect()
        val width = panel.width * 0.18f
        val gap = panel.width * 0.04f
        val total = width * 3f + gap * 2f
        return ScreenUi.Rect(
            x = panel.centerX - total * 0.5f + (width + gap) * index,
            y = panel.bottom - ui.unit * 1.75f,
            width = width,
            height = ui.unit * 1.25f,
        )
    }

    // -----------------------------------------------------------------------
    // 그리기
    // -----------------------------------------------------------------------

    fun render(batch: SpriteBatch) {
        if (ui.width <= 0f) return
        val art = catalog.settings
        val panel = panelRect()
        ui.bar(batch, art.panel, panel)
        ui.centered(batch, "SETTINGS", panel.centerX, panel.y + ui.unit * 0.55f, ui.unit * 0.8f, ScreenUi.ACCENT)

        val roomColor = if (editableRoom) ScreenUi.WHITE else ScreenUi.DIM
        ui.centered(
            batch,
            if (editableRoom) "ROOM RULES" else "ROOM RULES - HOST ONLY",
            column(LEFT) + columnWidth() * 0.5f,
            rowY(ROW_HEADER),
            ui.unit * 0.55f,
            if (editableRoom) ScreenUi.CYAN else ScreenUi.DIM,
        )
        ui.centered(
            batch,
            "THIS DEVICE",
            column(RIGHT) + columnWidth() * 0.5f,
            rowY(ROW_HEADER),
            ui.unit * 0.55f,
            ScreenUi.CYAN,
        )

        drawRoomRules(batch, roomColor)
        drawDeviceRules(batch)
        drawActions(batch)
    }

    private fun drawRoomRules(batch: SpriteBatch, color: Int) {
        val art = catalog.settings

        rowLabel(batch, "MAP SIZE", ROW_SIZE, LEFT, color)
        for (index in 0 until 3) {
            val rect = segmentRect(index)
            val selected = room.mapSize.ordinal == index
            ui.panel(batch, if (selected) art.segmentSelected else art.segment, rect)
            ui.label(
                batch,
                SIZE_LABELS[index],
                rect,
                ui.unit * 0.45f,
                if (selected) ScreenUi.WHITE else ScreenUi.DIM,
                SEGMENT_TEXT_CENTER,
                0.86f,
            )
        }

        rowLabel(batch, "RANDOM SEED", ROW_SEED, LEFT, color)
        val seed = seedRect()
        ui.bar(batch, art.dropdown, seed, alpha = if (pressedButton == BTN_SEED) PRESSED_ALPHA else 1f)
        ui.label(
            batch,
            if (room.randomSeed == 0L) "AUTO" else room.randomSeed.toString(),
            seed,
            ui.unit * 0.52f,
            color,
            0.5f,
            0.8f,
        )
        ui.icon(batch, art.dice, diceRect().centerX, diceRect().centerY, diceRect().height * 0.9f, color)

        rowLabel(batch, "FRIENDLY FIRE", ROW_FRIENDLY, LEFT, color)
        drawToggle(batch, toggleRect(ROW_FRIENDLY, LEFT), room.friendlyFire, color)

        rowLabel(batch, "BASE PROTECTION", ROW_PROTECT, LEFT, color)
        drawToggle(batch, toggleRect(ROW_PROTECT, LEFT), room.baseProtection, color)

        rowLabel(batch, "MAX COM", ROW_ENEMIES, LEFT, color)
        val range = RoomSettings.MIN_ACTIVE_ENEMIES..RoomSettings.MAX_ACTIVE_ENEMIES
        val active = if (room.maxActiveEnemies > 0) room.maxActiveEnemies else range.first
        drawSlider(
            batch,
            sliderRect(ROW_ENEMIES, LEFT),
            (active - range.first).toFloat() / (range.last - range.first),
            active.toString(),
            color,
        )
    }

    private fun drawDeviceRules(batch: SpriteBatch) {
        val art = catalog.settings
        val unit = ui.unit

        rowLabel(batch, "BGM VOLUME", ROW_BGM, RIGHT, ScreenUi.WHITE, indent = unit * ICON_INDENT)
        ui.icon(
            batch,
            if (device.bgmVolume == 0) art.speakerMuted else art.bgmIcon,
            column(RIGHT) + unit * 0.4f,
            rowY(ROW_BGM) + unit * 0.55f,
            unit * 0.8f,
        )
        drawSlider(
            batch,
            sliderRect(ROW_BGM, RIGHT),
            device.bgmVolume / DeviceSettings.MAX_VOLUME.toFloat(),
            device.bgmVolume.toString(),
            ScreenUi.WHITE,
        )

        rowLabel(batch, "SFX VOLUME", ROW_SFX, RIGHT, ScreenUi.WHITE, indent = unit * ICON_INDENT)
        ui.icon(
            batch,
            if (device.sfxVolume == 0) art.speakerMuted else art.sfxIcon,
            column(RIGHT) + unit * 0.4f,
            rowY(ROW_SFX) + unit * 0.55f,
            unit * 0.8f,
        )
        drawSlider(
            batch,
            sliderRect(ROW_SFX, RIGHT),
            device.sfxVolume / DeviceSettings.MAX_VOLUME.toFloat(),
            device.sfxVolume.toString(),
            ScreenUi.WHITE,
        )

        ui.icon(
            batch,
            if (device.bgmVolume == 0 && device.sfxVolume == 0) art.speakerMuted else art.speakerOn,
            column(RIGHT) + columnWidth() * 0.5f,
            rowY(ROW_SPEAKER) + unit * 0.9f,
            unit * 1.6f,
            if (device.bgmVolume == 0 && device.sfxVolume == 0) ScreenUi.DIM else ScreenUi.GREEN,
        )
    }

    private fun drawActions(batch: SpriteBatch) {
        val art = catalog.settings
        drawAction(batch, actionRect(0), art.reset, BTN_RESET, "RESET", ScreenUi.DIM)
        drawAction(batch, actionRect(1), art.close, BTN_CANCEL, "CANCEL", ScreenUi.WHITE)
        drawAction(batch, actionRect(2), art.apply, BTN_APPLY, "APPLY", ScreenUi.ACCENT)
    }

    private fun drawAction(
        batch: SpriteBatch,
        rect: ScreenUi.Rect,
        region: com.kophas.battlecity.render.TextureRegion,
        id: Int,
        label: String,
        color: Int,
    ) {
        val down = pressedButton == id
        ui.bar(batch, region, rect, alpha = if (down) PRESSED_ALPHA else 1f)
        ui.label(batch, label, rect, ui.unit * 0.55f, color, BUTTON_TEXT_CENTER, 0.62f)
    }

    private fun rowLabel(
        batch: SpriteBatch,
        text: String,
        row: Int,
        side: Int,
        color: Int,
        indent: Float = 0f,
    ) {
        ui.text(batch, text, column(side) + indent, rowY(row) + ui.unit * 0.3f, ui.unit * 0.5f, color)
    }

    private fun drawToggle(batch: SpriteBatch, rect: ScreenUi.Rect, on: Boolean, color: Int) {
        val art = catalog.settings
        ui.panel(batch, if (on) art.toggleOn else art.toggleOff, rect, color)
    }

    private fun drawSlider(
        batch: SpriteBatch,
        rect: ScreenUi.Rect,
        fraction: Float,
        value: String,
        color: Int,
    ) {
        val art = catalog.settings
        ui.panel(batch, art.sliderTrack, rect, color)
        val thumb = rect.height * 1.7f
        ui.icon(
            batch,
            art.sliderThumb,
            rect.x + thumb * 0.5f + (rect.width - thumb) * fraction.coerceIn(0f, 1f),
            rect.centerY,
            thumb,
            color,
        )
        ui.centered(
            batch,
            value,
            rect.x + rect.width + ui.unit * 0.7f,
            rect.centerY - ui.unit * 0.25f,
            ui.unit * 0.5f,
            color,
        )
    }

    companion object {
        /**
         * 슬라이더 위치를 소리 크기로 바꾼다. 5 단위로 끊는다.
         *
         * 손가락으로 1 단위를 맞출 수는 없다. 끊어 두면 같은 자리를 다시 짚었을 때
         * 같은 값이 나온다.
         */
        fun quantizeVolume(fraction: Float): Int {
            val step = DeviceSettings.VOLUME_STEP
            val raw = fraction.coerceIn(0f, 1f) * DeviceSettings.MAX_VOLUME
            return ((raw / step).roundToInt() * step).coerceIn(0, DeviceSettings.MAX_VOLUME)
        }

        private const val LEFT = 0
        private const val RIGHT = 1

        private const val ROW_HEADER = 0
        private const val ROW_SIZE = 1
        private const val ROW_SEED = 2
        private const val ROW_FRIENDLY = 3
        private const val ROW_PROTECT = 4
        private const val ROW_ENEMIES = 5

        // 오른쪽 칸은 항목이 적어 아래를 비운다. 스피커 그림으로 채운다.
        private const val ROW_BGM = 1
        private const val ROW_SFX = 2
        private const val ROW_SPEAKER = 4

        private const val PANEL_UNITS = 12.6f
        private const val PANEL_WIDTH = 0.88f

        /** 조작기가 차지하는 칸의 비율. 나머지가 이름표 자리다. */
        private const val CONTROL_WIDTH = 0.5f
        private const val ROW_TOP = 2.4f
        private const val ROW_HEIGHT = 1.35f

        /** 소리 칸은 아이콘을 왼쪽에 두므로 글자를 그만큼 민다. */
        private const val ICON_INDENT = 1.0f

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
