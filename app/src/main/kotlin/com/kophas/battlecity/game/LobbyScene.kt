package com.kophas.battlecity.game

import com.kophas.battlecity.core.Constants
import com.kophas.battlecity.gameplay.PlayerProfile
import com.kophas.battlecity.gameplay.Tank
import com.kophas.battlecity.net.Messages
import com.kophas.battlecity.net.Protocol
import com.kophas.battlecity.render.SpriteBatch
import com.kophas.battlecity.render.SpriteCatalog
import com.kophas.battlecity.render.TextLayout
import com.kophas.battlecity.render.TextureRegion

/**
 * 로비 화면. (계획서 §28, §29, §38)
 *
 * ```
 *              BATTLE CITY
 *      [P1]  [P2]  [P3]  [P4]
 *   NAME ABC   TANK ATTACK   COLOR ■
 *              START GAME
 * ```
 *
 * 자기 자리만 고칠 수 있다. 이름은 세 글자, 탱크는 세 종류, 색은 팔레트에서 고른다.
 * 사람끼리 같은 색은 고를 수 없고 COM 색도 고를 수 없다.
 *
 * 좌표는 화면 픽셀이다. 맵이 없으므로 논리 해상도라는 것이 없다.
 */
class LobbyScene(private val catalog: SpriteCatalog) {

    /** 화면에 그릴 값. Host 는 자기 로비에서, Client 는 받은 것에서 채운다. */
    class View {
        var slots: List<Messages.LobbySlot> = emptyList()
        var countdownTicks: Int = 0
        var localSlot: Int = -1
        var host: Boolean = false
        var connected: Boolean = true
        var searching: Boolean = false
    }

    /** 누른 곳이 무엇이었는지. 세션에 넘길 동작이다. */
    sealed interface Action {
        data object None : Action
        data object ToggleReady : Action
        data object Start : Action
        data object CycleType : Action
        data object CycleColor : Action
        data class SetName(val name: String) : Action

        /** 방 설정을 연다. 방장이 아니면 소리 크기만 바꿀 수 있다. (계획서 §44.2) */
        data object OpenSettings : Action

        /** 방을 나가 메인 메뉴로 돌아간다. (계획서 §27) */
        data object Back : Action
    }

    val view = View()

    private var width = 0f
    private var height = 0f
    private var startPressed = false
    private var backPressed = false

    /** 이름을 고치는 중인가. 그동안은 글자판이 화면을 덮는다. */
    private var editingName = false
    private var draft = ""

    fun resize(width: Int, height: Int) {
        this.width = width.toFloat()
        this.height = height.toFloat()
    }

    fun onTap(x: Float, y: Float): Action {
        if (editingName) return tapKeyboard(x, y)

        if (hitsSettings(x, y)) return Action.OpenSettings
        if (hitsBack(x, y)) {
            backPressed = true
            return Action.Back
        }
        if (hitsStart(x, y)) {
            startPressed = true
            return if (canStart()) Action.Start else Action.None
        }
        if (hitsRow(x, y, NAME_COLUMN)) {
            editingName = true
            // 쓰던 이름을 채워 둔다. 빈 칸으로 열면 무엇을 고치는 중인지 보이지 않고,
            // 폰트에 밑줄이 없어 자리 표시도 못 그린다.
            draft = localSlotData()?.name.orEmpty()
            return Action.None
        }
        if (hitsRow(x, y, TYPE_COLUMN)) return Action.CycleType
        if (hitsRow(x, y, COLOR_COLUMN)) return Action.CycleColor

        // 자기 자리를 누르면 준비 상태가 바뀐다. 남의 자리는 눌러도 소용없다.
        val slot = slotAt(x, y)
        return if (slot >= 0 && slot == view.localSlot) Action.ToggleReady else Action.None
    }

    fun onRelease() {
        startPressed = false
        backPressed = false
    }

    // -----------------------------------------------------------------------

    fun render(batch: SpriteBatch) {
        if (width <= 0f || height <= 0f) return
        val unit = height * UNIT_RATIO

        drawTitle(batch, unit)

        // 이름을 고치는 동안에는 글자판만 보여 준다. 카드와 겹쳐 그리면 글자가
        // 카드 위에 흩어져 무엇을 누르는 것인지 알 수 없다.
        if (editingName) {
            drawKeyboard(batch, unit)
            return
        }

        drawSlots(batch, unit)
        drawChoices(batch, unit)
        drawStart(batch, unit)
        drawSettingsButton(batch)
        drawBackButton(batch, unit)
        drawStatus(batch, unit)
    }

    private fun drawTitle(batch: SpriteBatch, unit: Float) {
        val plateWidth = width * TITLE_WIDTH
        val plateHeight = unit * 2.6f
        val plateTop = unit * 0.3f
        batch.draw(
            region = catalog.lobbyTitle,
            x = (width - plateWidth) * 0.5f,
            y = plateTop,
            width = plateWidth,
            height = plateHeight,
            layer = Constants.Layer.HUD,
        )
        drawInPlate(
            batch,
            "BATTLE CITY",
            (width - plateWidth) * 0.5f,
            plateTop,
            plateWidth,
            plateHeight,
            unit * 0.9f,
            TITLE_TEXT_CENTER,
            TITLE_TEXT_WIDTH,
        )
    }

    private fun drawSlots(batch: SpriteBatch, unit: Float) {
        val count = Protocol.MAX_PLAYERS
        val cardWidth = width * CARD_WIDTH
        val gap = cardWidth * 0.18f
        val left = (width - (cardWidth * count + gap * (count - 1))) * 0.5f
        val top = height * SLOT_TOP
        val cardHeight = unit * 4.2f

        for (index in 0 until count) {
            val slot = view.slots.getOrNull(index)
            val x = left + index * (cardWidth + gap)
            val connected = slot?.connected == true
            val color = catalog.palette.colorOf(slot?.colorIndex ?: index)

            batch.draw(
                region = catalog.lobbySlots[index % catalog.lobbySlots.size],
                x = x,
                y = top,
                width = cardWidth,
                height = cardHeight,
                layer = Constants.Layer.HUD,
                alpha = if (connected) 1f else EMPTY_ALPHA,
            )

            // 고른 탱크를 고른 색으로 보여 준다. 게임에 들어가면 이 모습 그대로다.
            if (connected) {
                val portrait = catalog.playerPortrait(typeOf(slot))
                val portraitSize = cardHeight * 0.52f
                batch.draw(
                    region = portrait,
                    x = x + cardWidth * 0.5f - portraitSize * 0.5f,
                    y = top + cardHeight * 0.24f,
                    width = portraitSize,
                    height = portraitSize,
                    layer = Constants.Layer.HUD,
                    red = red(color),
                    green = green(color),
                    blue = blue(color),
                )
            }

            val label = if (connected) PlayerProfile.sanitize(slot.name, index) else "P${index + 1}"
            drawCentered(batch, label, x + cardWidth * 0.5f, top + unit * 0.2f, unit * 0.7f, color)

            val badge: TextureRegion = when {
                !connected -> catalog.lobbyLocked
                slot.ready -> catalog.lobbyReady
                else -> catalog.lobbyWaiting
            }
            val badgeSize = unit * 1.3f
            batch.draw(
                region = badge,
                x = x + cardWidth * 0.5f - badgeSize * 0.5f,
                y = top + cardHeight - badgeSize * 1.1f,
                width = badgeSize,
                height = badgeSize,
                layer = Constants.Layer.HUD,
                alpha = if (connected) 1f else EMPTY_ALPHA,
            )

            // 방을 연 사람에게만 왕관을 붙인다. START 는 이 사람만 누를 수 있다.
            if (slot?.host == true) {
                batch.draw(
                    region = catalog.lobbyHost,
                    x = x + cardWidth - badgeSize * 0.8f,
                    y = top,
                    width = badgeSize * 0.7f,
                    height = badgeSize * 0.7f,
                    layer = Constants.Layer.HUD,
                )
            }
        }
    }

    /** 자기 자리를 고치는 줄. 이름 · 탱크 · 색을 눌러서 바꾼다. (계획서 §29) */
    private fun drawChoices(batch: SpriteBatch, unit: Float) {
        val slot = localSlotData() ?: return
        val y = height * CHOICE_TOP
        val size = unit * 0.72f
        val color = catalog.palette.colorOf(slot.colorIndex)

        drawColumn(batch, NAME_COLUMN, "NAME", PlayerProfile.sanitize(slot.name, slot.index), y, size)
        drawColumn(batch, TYPE_COLUMN, "TANK", typeLabel(typeOf(slot)), y, size)
        drawColumn(batch, COLOR_COLUMN, "COLOR", catalog.palette.nameOf(slot.colorIndex), y, size, color)
    }

    private fun drawColumn(
        batch: SpriteBatch,
        column: Int,
        title: String,
        value: String,
        y: Float,
        size: Float,
        valueColor: Int = 0xFFFFFF,
    ) {
        val centerX = columnCenter(column)
        drawCentered(batch, title, centerX, y, size * 0.72f, LABEL_COLOR)
        drawCentered(batch, value, centerX, y + size * 1.15f, size, valueColor)
    }

    private fun drawBackButton(batch: SpriteBatch, unit: Float) {
        val rect = backRect()
        batch.draw(
            region = catalog.lobbySecondary,
            x = rect[0],
            y = rect[1],
            width = rect[2],
            height = rect[3],
            layer = Constants.Layer.HUD,
            alpha = if (backPressed) 0.65f else 1f,
        )
        drawInPlate(
            batch, "MAIN MENU", rect[0], rect[1], rect[2], rect[3],
            unit * 0.5f, BACK_TEXT_CENTER, 0.72f,
        )
    }

    /** 방 설정 단추. 로비 오른쪽 위에 둔다. 시작 단추와 멀어야 잘못 누르지 않는다. */
    private fun drawSettingsButton(batch: SpriteBatch) {
        val rect = settingsRect()
        batch.draw(
            region = catalog.lobbySettings,
            x = rect[0],
            y = rect[1],
            width = rect[2],
            height = rect[3],
            layer = Constants.Layer.HUD,
        )
    }

    private fun drawStart(batch: SpriteBatch, unit: Float) {
        val buttonWidth = width * START_WIDTH
        val buttonHeight = unit * 2.4f
        val x = (width - buttonWidth) * 0.5f
        val y = height * START_TOP
        val enabled = canStart()

        batch.draw(
            region = if (startPressed) catalog.lobbyStartPressed else catalog.lobbyStart,
            x = x,
            y = y,
            width = buttonWidth,
            height = buttonHeight,
            layer = Constants.Layer.HUD,
            red = if (enabled) 1f else DIM,
            green = if (enabled) 1f else DIM,
            blue = if (enabled) 1f else DIM,
            alpha = if (enabled) 1f else 0.7f,
        )

        val label = when {
            view.countdownTicks > 0 -> countdownLabel()
            !view.host -> if (localSlotData()?.ready == true) "READY" else "TAP TO READY"
            enabled -> "START GAME"
            else -> "WAIT PLAYERS"
        }
        drawInPlate(
            batch,
            label,
            x,
            y,
            buttonWidth,
            buttonHeight,
            unit * 0.8f,
            START_TEXT_CENTER,
            START_TEXT_WIDTH,
        )
    }

    /** 3 2 1 GO. (계획서 §38) */
    private fun countdownLabel(): String {
        val seconds = (view.countdownTicks + Constants.LOGIC_HZ - 1) / Constants.LOGIC_HZ
        return if (seconds <= 0) "GO" else seconds.toString()
    }

    private fun drawStatus(batch: SpriteBatch, unit: Float) {
        val size = unit * 1.2f
        batch.draw(
            region = if (view.connected) catalog.lobbyWifi else catalog.lobbyWifiWeak,
            x = unit * 0.6f,
            y = unit * 0.6f,
            width = size,
            height = size,
            layer = Constants.Layer.HUD,
        )
        val message = when {
            !view.connected -> "DISCONNECTED"
            view.searching -> "SEARCHING"
            else -> "CONNECTED"
        }
        drawText(batch, message, unit * 2.1f, unit * 0.8f, unit * 0.62f)
    }

    // --- 이름 글자판 -------------------------------------------------------
    //
    // 안드로이드 키보드를 띄우지 않는다. 게임 화면은 SurfaceView 하나라 그 위에
    // 입력창을 얹으려면 뷰 계층을 섞어야 하고, 키보드가 화면 절반을 가린다.
    // 세 글자면 글자판을 직접 그리는 편이 간단하고 손도 덜 간다.

    private fun drawKeyboard(batch: SpriteBatch, unit: Float) {
        val size = keySize()
        val left = keyboardLeft()
        val top = keyboardTop()

        drawCentered(batch, "NAME", width * 0.5f, top - unit * 2.4f, unit * 0.7f, LABEL_COLOR)
        drawCentered(batch, draft, width * 0.5f, top - unit * 1.4f, unit * 1.1f, READY_COLOR)

        for ((index, key) in KEYS.withIndex()) {
            val column = index % KEY_COLUMNS
            val row = index / KEY_COLUMNS
            drawCentered(
                batch,
                key.toString(),
                left + column * size + size * 0.5f,
                top + row * size,
                size * 0.62f,
            )
        }
        drawCentered(batch, "DEL", width * 0.5f - size * 2.5f, keyboardBottom(), size * 0.7f, LABEL_COLOR)
        drawCentered(batch, "OK", width * 0.5f + size * 2.5f, keyboardBottom(), size * 0.7f, READY_COLOR)
    }

    private fun tapKeyboard(x: Float, y: Float): Action {
        val size = keySize()
        val bottom = keyboardBottom()

        if (y >= bottom - size * 0.4f) {
            editingName = false
            return if (x > width * 0.5f) {
                Action.SetName(PlayerProfile.sanitize(draft, view.localSlot))
            } else {
                draft = draft.dropLast(1)
                editingName = true
                Action.None
            }
        }

        val column = ((x - keyboardLeft()) / size).toInt()
        val row = ((y - keyboardTop()) / size).toInt()
        if (column !in 0 until KEY_COLUMNS || row < 0) return Action.None
        val index = row * KEY_COLUMNS + column
        if (index !in KEYS.indices) return Action.None

        if (draft.length < PlayerProfile.MAX_NAME) draft += KEYS[index]
        return Action.None
    }

    private fun keySize(): Float = width * KEY_SIZE

    private fun keyboardLeft(): Float = (width - keySize() * KEY_COLUMNS) * 0.5f

    private fun keyboardTop(): Float = height * KEYBOARD_TOP

    private fun keyboardBottom(): Float =
        keyboardTop() + keySize() * ((KEYS.length + KEY_COLUMNS - 1) / KEY_COLUMNS) + keySize() * 0.8f

    // -----------------------------------------------------------------------

    private fun localSlotData(): Messages.LobbySlot? =
        view.slots.getOrNull(view.localSlot)?.takeIf { it.connected }

    private fun typeOf(slot: Messages.LobbySlot?): Tank.Type =
        Tank.Type.entries.getOrElse(slot?.tankType ?: 0) { Tank.Type.ATTACK }

    private fun typeLabel(type: Tank.Type): String = when (type) {
        Tank.Type.ATTACK -> "ATTACK"
        Tank.Type.DEFENSE -> "DEFEND"
        Tank.Type.SPEED -> "SPEED"
    }

    private fun canStart(): Boolean =
        view.host &&
            view.countdownTicks == 0 &&
            view.slots.count { it.connected } >= Protocol.MIN_PLAYERS &&
            view.slots.all { !it.connected || it.ready }

    private fun columnCenter(column: Int): Float = width * (0.3f + column * 0.2f)

    private fun hitsRow(x: Float, y: Float, column: Int): Boolean {
        if (localSlotData() == null) return false
        val unit = height * UNIT_RATIO
        val top = height * CHOICE_TOP - unit * 0.4f
        val bottom = top + unit * 2.2f
        val centerX = columnCenter(column)
        val halfWidth = width * 0.09f
        return y in top..bottom && x in (centerX - halfWidth)..(centerX + halfWidth)
    }

    /**
     * 방을 나가는 단추. 오른쪽 아래에 둔다.
     *
     * 시작 단추와 멀리 떨어뜨린다. 다 모여서 시작하려는 순간에 잘못 눌러 방을
     * 나가 버리면 남은 사람들까지 기다리게 된다.
     */
    private fun backRect(): FloatArray {
        val h = height * UNIT_RATIO * 1.9f
        val w = width * BACK_WIDTH
        return floatArrayOf(width * 0.96f - w, height * BACK_TOP, w, h)
    }

    private fun hitsBack(x: Float, y: Float): Boolean {
        val rect = backRect()
        return x in rect[0]..(rect[0] + rect[2]) && y in rect[1]..(rect[1] + rect[3])
    }

    private fun settingsRect(): FloatArray {
        val size = height * UNIT_RATIO * 2.0f
        return floatArrayOf(width * 0.94f - size, height * 0.04f, size, size)
    }

    private fun hitsSettings(x: Float, y: Float): Boolean {
        val rect = settingsRect()
        return x in rect[0]..(rect[0] + rect[2]) && y in rect[1]..(rect[1] + rect[3])
    }

    private fun hitsStart(x: Float, y: Float): Boolean {
        val buttonWidth = width * START_WIDTH
        val left = (width - buttonWidth) * 0.5f
        val top = height * START_TOP
        val bottom = top + height * UNIT_RATIO * 2.4f
        return x in left..(left + buttonWidth) && y in top..bottom
    }

    private fun slotAt(x: Float, y: Float): Int {
        val count = Protocol.MAX_PLAYERS
        val cardWidth = width * CARD_WIDTH
        val gap = cardWidth * 0.18f
        val left = (width - (cardWidth * count + gap * (count - 1))) * 0.5f
        val top = height * SLOT_TOP
        val bottom = top + height * UNIT_RATIO * 4.2f
        if (y !in top..bottom) return -1

        for (index in 0 until count) {
            val cardLeft = left + index * (cardWidth + gap)
            if (x in cardLeft..(cardLeft + cardWidth)) return index
        }
        return -1
    }

    /**
     * 글자를 [centerX] 에 맞춰 그린다.
     *
     * 글자 폭을 `글자수 x 간격` 으로 잡으면 안 된다. 마지막 글자는 다음 글자를 위한
     * 간격이 아니라 **제 크기만큼** 자리를 차지하기 때문이다. 그 차이만큼 글자가
     * 왼쪽으로 밀려, 판 한가운데에 놓았다고 생각한 글자가 살짝 어긋나 보인다.
     */
    private fun drawCentered(
        batch: SpriteBatch,
        text: String,
        centerX: Float,
        y: Float,
        size: Float,
        color: Int = 0xFFFFFF,
    ) {
        if (text.isEmpty()) return
        drawText(batch, text, TextLayout.leftForCenter(text, centerX, size), y, size, color)
    }

    /**
     * 판 그림의 **속판**에 글자를 맞춰 놓는다.
     *
     * 그린 사각형의 한가운데가 곧 글자 자리가 아니다. 판 그림에는 위아래 테두리와
     * 걸쇠가 들어 있고 그 두께가 서로 달라서, 글자가 들어가야 할 속판은 사각형
     * 한가운데보다 아래(제목판) 또는 위(버튼)에 있다. 그림에서 실제로 재서
     * [centerRatio] 로 넘긴다.
     *
     * 글자가 속판보다 길면 크기를 줄여서 넣는다. 넘쳐서 테두리를 물면 읽기 어렵다.
     */
    private fun drawInPlate(
        batch: SpriteBatch,
        text: String,
        left: Float,
        top: Float,
        boxWidth: Float,
        boxHeight: Float,
        size: Float,
        centerRatio: Float,
        widthRatio: Float,
        color: Int = 0xFFFFFF,
    ) {
        if (text.isEmpty()) return
        val fitted = TextLayout.fit(text, size, boxWidth * widthRatio)

        drawCentered(
            batch,
            text,
            left + boxWidth * 0.5f,
            top + boxHeight * centerRatio - fitted * 0.5f,
            fitted,
            color,
        )
    }

    private fun drawText(
        batch: SpriteBatch,
        text: String,
        x: Float,
        y: Float,
        size: Float,
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
                    // 판과 같은 층에 그린다. 아래층에 두면 판이 글자를 덮는다.
                    layer = Constants.Layer.HUD,
                    red = red(color),
                    green = green(color),
                    blue = blue(color),
                )
            }
            cursor += size * GLYPH_ADVANCE
        }
    }

    private fun red(color: Int): Float = ((color ushr 16) and 0xFF) / 255f

    private fun green(color: Int): Float = ((color ushr 8) and 0xFF) / 255f

    private fun blue(color: Int): Float = (color and 0xFF) / 255f

    private companion object {
        /** 화면 높이 기준 한 칸. 기기 크기가 달라도 비율이 유지된다. */
        const val UNIT_RATIO = 0.07f

        const val TITLE_WIDTH = 0.4f
        const val CARD_WIDTH = 0.13f
        const val SLOT_TOP = 0.26f
        const val CHOICE_TOP = 0.62f
        const val START_TOP = 0.79f
        const val START_WIDTH = 0.28f

        // 판 그림에서 잰 속판 자리. 그림을 바꾸면 이 값도 다시 재야 한다.
        //   제목판  속판이 그림 높이의 0.46 ~ 0.76 -> 가운데 0.61
        //   버튼    주황 면이 0.17 ~ 0.59        -> 가운데 0.38
        const val TITLE_TEXT_CENTER = 0.61f
        const val TITLE_TEXT_WIDTH = 0.72f
        const val BACK_TOP = 0.855f
        const val BACK_WIDTH = 0.17f
        const val BACK_TEXT_CENTER = 0.46f

        const val START_TEXT_CENTER = 0.38f
        const val START_TEXT_WIDTH = 0.68f

        const val NAME_COLUMN = 0
        const val TYPE_COLUMN = 1
        const val COLOR_COLUMN = 2

        /** 글자판. 대문자와 숫자만 있으면 세 글자 이름에는 충분하다. */
        const val KEYS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        const val KEY_COLUMNS = 12
        const val KEY_SIZE = 0.045f
        const val KEYBOARD_TOP = 0.36f

        const val GLYPH_ADVANCE = TextLayout.ADVANCE
        const val EMPTY_ALPHA = 0.35f
        const val DIM = 0.5f
        const val LABEL_COLOR = 0x9AA3AE
        const val READY_COLOR = 0x8BE04B
    }
}
