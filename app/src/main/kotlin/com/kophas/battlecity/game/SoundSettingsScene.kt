package com.kophas.battlecity.game

import com.kophas.battlecity.render.ScreenUi
import com.kophas.battlecity.render.SpriteBatch
import com.kophas.battlecity.render.SpriteCatalog
import com.kophas.battlecity.render.TextureRegion
import kotlin.math.roundToInt

/**
 * 사운드 설정 화면. 이 기기의 소리 크기만 다룬다. (계획서 §44.2)
 *
 * 방과 아무 상관이 없다. 남의 귀까지 정해 줄 이유가 없으므로 네트워크에 싣지 않고
 * `SharedPreferences` 에만 남긴다. 방에 들어가지 않아도 고칠 일이라 **메인 메뉴
 * 오른쪽 아래**에서 연다. 방 규칙은 [GameSettingsScene] 이 따로 맡는다.
 *
 * 슬라이더를 끄는 동안 값이 바로 반영된다([onDeviceChanged]). 고른 크기가 맞는지는
 * 들어 봐야 알 수 있고, APPLY 를 눌러야 들린다면 몇 번을 오가게 된다.
 *
 * 두 줄뿐이라 판을 작게 잡고 줄 간격(2칸)과 글자를 크게 두었다. 예전 설정판의
 * 오른쪽 반 칸에 밀어 넣었던 것과 같은 값이지만 자리는 훨씬 넉넉하다.
 */
class SoundSettingsScene(private val catalog: SpriteCatalog) {

    sealed interface Action {
        data object None : Action
        data object Cancel : Action

        /** 저장하고 닫는다. */
        data class Apply(val device: DeviceSettings) : Action
    }

    private enum class Handle { NONE, BGM, SFX }

    private val ui = ScreenUi(catalog)
    private val layout = SettingsLayout(METRICS)

    /** 편집 중인 사본과 열었을 때의 값. 취소하면 열었을 때로 되돌린다. */
    private var device = DeviceSettings()
    private var openedDevice = DeviceSettings()

    private var dragging = Handle.NONE
    private var pressedButton = -1

    /** 소리 크기가 바뀔 때마다 부른다. 슬라이더를 끄는 동안 바로 들려야 한다. */
    var onDeviceChanged: (DeviceSettings) -> Unit = {}

    /** 끄기 직전의 크기. 다시 켤 때 여기로 돌아온다. */
    private var mutedBgm = DeviceSettings().bgmVolume
    private var mutedSfx = DeviceSettings().sfxVolume

    fun resize(width: Int, height: Int) {
        ui.resize(width, height)
        layout.resize(ui.width, ui.height, catalog.settings.panel.aspect)
    }

    fun open(device: DeviceSettings) {
        this.device = device
        this.openedDevice = device
        dragging = Handle.NONE
        pressedButton = -1
    }

    // -----------------------------------------------------------------------
    // 입력
    // -----------------------------------------------------------------------

    fun onDown(x: Float, y: Float) {
        dragging = when {
            ui.hits(layout.sliderRect(ROW_BGM), x, y) -> Handle.BGM
            ui.hits(layout.sliderRect(ROW_SFX), x, y) -> Handle.SFX
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
            Handle.BGM -> device = device.copy(bgmVolume = volumeAt(layout.sliderRect(ROW_BGM), x))
            Handle.SFX -> device = device.copy(sfxVolume = volumeAt(layout.sliderRect(ROW_SFX), x))
        }
        onDeviceChanged(device)
    }

    fun onUp(x: Float, y: Float): Action {
        if (dragging != Handle.NONE) {
            dragging = Handle.NONE
            return Action.None
        }
        val button = pressedButton
        pressedButton = -1
        // 누른 자리에서 손을 떼야 눌린 것으로 본다. 끌어서 벗어나면 취소다.
        if (button < 0 || button != buttonAt(x, y)) return Action.None
        return activate(button)
    }

    fun onCancelTouch() {
        dragging = Handle.NONE
        pressedButton = -1
    }

    private fun volumeAt(rect: ScreenUi.Rect, x: Float): Int =
        quantizeVolume(((x - rect.x) / rect.width).coerceIn(0f, 1f))

    private fun activate(button: Int): Action = when (button) {
        BTN_APPLY -> Action.Apply(device)
        BTN_CANCEL -> {
            // 취소하면 소리도 열었을 때로 되돌린다. 미리 들려 준 값이 남으면 안 된다.
            if (device != openedDevice) onDeviceChanged(openedDevice)
            Action.Cancel
        }
        BTN_RESET -> {
            device = DeviceSettings()
            onDeviceChanged(device)
            Action.None
        }
        // 껐다 켜면 끄기 직전 크기로 돌아온다. 슬라이더를 다시 맞추게 하지 않는다.
        BTN_MUTE_BGM -> {
            device = if (device.bgmVolume == 0) {
                device.copy(bgmVolume = mutedBgm)
            } else {
                mutedBgm = device.bgmVolume
                device.copy(bgmVolume = 0)
            }
            onDeviceChanged(device)
            Action.None
        }
        BTN_MUTE_SFX -> {
            device = if (device.sfxVolume == 0) {
                device.copy(sfxVolume = mutedSfx)
            } else {
                mutedSfx = device.sfxVolume
                device.copy(sfxVolume = 0)
            }
            onDeviceChanged(device)
            Action.None
        }
        else -> Action.None
    }

    private fun buttonAt(x: Float, y: Float): Int = when {
        ui.hits(muteRect(ROW_BGM), x, y) -> BTN_MUTE_BGM
        ui.hits(muteRect(ROW_SFX), x, y) -> BTN_MUTE_SFX
        ui.hits(layout.actionRect(0), x, y) -> BTN_RESET
        ui.hits(layout.actionRect(1), x, y) -> BTN_CANCEL
        ui.hits(layout.actionRect(2), x, y) -> BTN_APPLY
        else -> -1
    }

    // -----------------------------------------------------------------------
    // 배치
    // -----------------------------------------------------------------------

    /** 소리 아이콘 자리. 줄 왼쪽에 두고, 눌러서 그 갈래만 껐다 켠다. */
    private fun muteRect(row: Int): ScreenUi.Rect {
        val size = layout.unit * ICON_SIZE
        val slider = layout.sliderRect(row)
        return ScreenUi.Rect(layout.labelX(), slider.centerY - size * 0.5f, size, size)
    }

    // -----------------------------------------------------------------------
    // 그리기
    // -----------------------------------------------------------------------

    fun render(batch: SpriteBatch) {
        if (!layout.ready) return
        val art = catalog.settings
        val panel = layout.panelRect()

        ui.bar(batch, art.panel, panel)
        ui.centered(batch, "SOUND", panel.centerX, layout.titleY(), layout.titleSize(), ScreenUi.ACCENT)

        drawRow(batch, ROW_BGM, "BGM", art.bgmIcon, BTN_MUTE_BGM, device.bgmVolume)
        drawRow(batch, ROW_SFX, "SFX", art.sfxIcon, BTN_MUTE_SFX, device.sfxVolume)

        drawAction(batch, 0, art.reset, BTN_RESET, "RESET", ScreenUi.DIM)
        drawAction(batch, 1, art.close, BTN_CANCEL, "CANCEL", ScreenUi.WHITE)
        drawAction(batch, 2, art.apply, BTN_APPLY, "APPLY", ScreenUi.ACCENT)
    }

    private fun drawRow(
        batch: SpriteBatch,
        row: Int,
        label: String,
        icon: TextureRegion,
        muteButton: Int,
        volume: Int,
    ) {
        val unit = layout.unit
        val muted = volume == 0
        val color = if (muted) ScreenUi.DIM else ScreenUi.WHITE
        val mute = muteRect(row)

        // 꺼진 갈래는 스피커에 금이 간 그림으로 바꾼다. 크기가 0 이라고만 쓰면
        // 슬라이더를 끝까지 끈 것과 눌러서 끈 것을 구별할 수 없다.
        ui.icon(
            batch,
            if (muted) catalog.settings.speakerMuted else icon,
            mute.centerX,
            mute.centerY,
            mute.height,
            color,
            alpha = if (pressedButton == muteButton) PRESSED_ALPHA else 1f,
        )
        ui.text(
            batch,
            label,
            mute.x + mute.width + unit * LABEL_GAP,
            mute.centerY - unit * LABEL_SIZE * 0.5f,
            unit * LABEL_SIZE,
            color,
        )

        val slider = layout.sliderRect(row)
        val art = catalog.settings
        ui.panel(batch, art.sliderTrack, slider, color)
        val thumb = slider.height * 1.7f
        val fraction = volume / DeviceSettings.MAX_VOLUME.toFloat()
        ui.icon(
            batch,
            art.sliderThumb,
            slider.x + thumb * 0.5f + (slider.width - thumb) * fraction.coerceIn(0f, 1f),
            slider.centerY,
            thumb,
            color,
        )
        // 값은 눈금 오른쪽에 왼쪽맞춤으로 쓴다. 가운데맞춤이면 자리수가 바뀔 때마다
        // 글자가 좌우로 흔들린다 (100 -> 95).
        val valueSize = layout.valueTextSize()
        ui.text(
            batch,
            volume.toString(),
            layout.sliderValueX(row),
            slider.centerY - valueSize * 0.5f,
            valueSize,
            color,
        )
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

        /**
         * 두 줄이 판 하나를 나눠 쓴다.
         *
         * 방 규칙과 갈라서면서 담을 것이 둘로 줄었다. 판을 그만큼 작게 잡고 남은
         * 자리를 줄 간격(2칸)과 글자 크기에 돌렸다.
         */
        val METRICS = SettingsLayout.Metrics(
            panelTop = 0.18f,
            panelWidth = 0.62f,
            panelUnits = 8.2f,
            rowTop = 0.8f,
            rowHeight = 2.0f,
            // 담을 것이 아이콘 · 이름표 · 눈금뿐이라 눈금을 길게 뽑는다. 짧은 눈금은
            // 손가락으로 5 단위를 짚기 어렵다.
            controlWidth = 0.64f,
            controlHeight = 1.2f,
            // 가장 긴 값은 "100" 이다. 방 규칙 쪽보다 짧아 그만큼 덜 떼어 둔다.
            valueReserve = 2.2f,
            sliderThickness = 0.5f,
            valueSize = 0.68f,
            rowCount = 2,
        )

        private const val ROW_BGM = 0
        private const val ROW_SFX = 1

        /** 글자와 아이콘 크기. 모두 한 칸 대비다. 두 줄뿐이라 넉넉하게 잡았다. */
        private const val LABEL_SIZE = 0.78f
        private const val ACTION_TEXT_SIZE = 0.64f
        private const val ICON_SIZE = 1.4f

        /** 아이콘과 이름표 사이. */
        private const val LABEL_GAP = 0.45f

        private const val BTN_RESET = 100
        private const val BTN_CANCEL = 101
        private const val BTN_APPLY = 102
        private const val BTN_MUTE_BGM = 103
        private const val BTN_MUTE_SFX = 104

        private const val PRESSED_ALPHA = 0.65f
        private const val BUTTON_TEXT_CENTER = 0.46f
    }
}
