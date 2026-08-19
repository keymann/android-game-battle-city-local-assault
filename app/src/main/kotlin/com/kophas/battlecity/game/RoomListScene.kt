package com.kophas.battlecity.game

import com.kophas.battlecity.net.RoomScanner
import com.kophas.battlecity.render.ScreenUi
import com.kophas.battlecity.render.SpriteBatch
import com.kophas.battlecity.render.SpriteCatalog
import java.time.Instant
import java.time.ZoneId

/**
 * 같은 망에 열려 있는 방 목록. (계획서 §27, §28)
 *
 * ```
 *              ROOM LIST
 *   ┌──────────────────────────────┐
 *   │ 거실   0820 08:15        2-4 │
 *   │ 방구석 0820 08:17   FULL 4-4 │
 *   └──────────────────────────────┘
 *        [ REFRESH ]  [ MAIN MENU ]
 * ```
 *
 * 로비에서 기다리는 방만 나온다. 이미 시작한 방은 들어갈 수 없으므로 목록에 두면
 * 눌러 보고 나서야 알게 된다. 정원이 찬 방은 지우지 않고 **잠가서** 보여 준다 —
 * 자리가 날 수도 있고, 방이 있다는 것 자체가 알 만한 정보다.
 */
class RoomListScene(private val catalog: SpriteCatalog) {

    sealed interface Action {
        data object None : Action
        data object Refresh : Action
        data object Back : Action

        /** 그 방에 들어가겠다. */
        data class Join(val room: RoomScanner.Room) : Action
    }

    private val ui = ScreenUi(catalog)

    /** 화면에 그릴 방들. 매 프레임 [RoomScanner] 에서 받아 온다. */
    var rooms: List<RoomScanner.Room> = emptyList()

    /** 아직 한 번도 못 찾았는가. 찾는 중과 없는 것을 가른다. */
    var scanning: Boolean = true

    /** 들어가려고 기다리는 중인 방. 답을 기다리는 동안 다른 줄을 못 누르게 한다. */
    var joining: RoomScanner.Room? = null

    /** 마지막으로 거절당한 까닭. 왜 못 들어갔는지 알려 준다. */
    var lastDenial: String? = null

    private var pressed = -1

    fun resize(width: Int, height: Int) = ui.resize(width, height)

    fun onDown(x: Float, y: Float) {
        pressed = hitAt(x, y)
    }

    fun onUp(x: Float, y: Float): Action {
        val hit = pressed
        pressed = -1
        if (hit < 0 || hit != hitAt(x, y)) return Action.None
        return when (hit) {
            BTN_REFRESH -> Action.Refresh
            BTN_BACK -> Action.Back
            else -> {
                val room = rooms.getOrNull(hit) ?: return Action.None
                // 잠긴 줄은 눌러도 아무 일이 없어야 한다. 눌리는 것처럼 보이면 고장이다.
                if (room.full || joining != null) Action.None else Action.Join(room)
            }
        }
    }

    fun onCancelTouch() {
        pressed = -1
    }

    private fun hitAt(x: Float, y: Float): Int {
        if (ui.hits(buttonRect(0), x, y)) return BTN_REFRESH
        if (ui.hits(buttonRect(1), x, y)) return BTN_BACK
        for (index in rooms.indices) {
            if (index >= MAX_ROWS) break
            if (ui.hits(rowRect(index), x, y)) return index
        }
        return -1
    }

    // -----------------------------------------------------------------------

    private fun titleRect() = ui.fitByWidth(catalog.menu.title, 0.26f, 0.04f)

    private fun rowRect(index: Int): ScreenUi.Rect = ScreenUi.Rect(
        x = ui.width * 0.18f,
        y = ui.height * 0.28f + ui.unit * ROW_SPACING * index,
        width = ui.width * 0.64f,
        height = ui.unit * ROW_HEIGHT,
    )

    private fun buttonRect(index: Int): ScreenUi.Rect {
        val width = ui.width * 0.2f
        val gap = ui.width * 0.04f
        val total = width * 2f + gap
        return ScreenUi.Rect(
            x = (ui.width - total) * 0.5f + (width + gap) * index,
            y = ui.height * 0.855f,
            width = width,
            height = ui.unit * 1.5f,
        )
    }

    // -----------------------------------------------------------------------

    fun render(batch: SpriteBatch) {
        if (ui.width <= 0f) return
        val unit = ui.unit

        val title = titleRect()
        ui.bar(batch, catalog.menu.title, title)
        ui.label(batch, "ROOM LIST", title, title.height * 0.32f, ScreenUi.ACCENT, 0.52f, 0.66f)

        if (rooms.isEmpty()) {
            ui.centered(
                batch,
                if (scanning) "SEARCHING" else "NO ROOMS",
                ui.width * 0.5f,
                ui.height * 0.44f,
                unit * 0.8f,
                ScreenUi.DIM,
            )
        } else {
            rooms.take(MAX_ROWS).forEachIndexed { index, room -> drawRow(batch, index, room) }
            if (rooms.size > MAX_ROWS) {
                ui.centered(
                    batch,
                    "AND ${rooms.size - MAX_ROWS} MORE",
                    ui.width * 0.5f,
                    rowRect(MAX_ROWS).y,
                    unit * 0.45f,
                    ScreenUi.DIM,
                )
            }
        }

        lastDenial?.let {
            ui.centered(batch, it, ui.width * 0.5f, ui.height * 0.80f, unit * 0.5f, ScreenUi.RED)
        }

        drawButton(batch, 0, catalog.menu.primary, BTN_REFRESH, "REFRESH", ScreenUi.ACCENT)
        drawButton(batch, 1, catalog.menu.secondary, BTN_BACK, "MAIN MENU", ScreenUi.WHITE)
    }

    private fun drawRow(batch: SpriteBatch, index: Int, room: RoomScanner.Room) {
        val rect = rowRect(index)
        val unit = ui.unit
        val open = !room.full && joining == null
        val alpha = if (open) 1f else LOCKED_ALPHA
        val down = pressed == index && open

        ui.bar(
            batch,
            if (open) catalog.menu.secondary else catalog.menu.secondaryPressed,
            rect,
            alpha = if (down) PRESSED_ALPHA else alpha,
        )

        val color = if (open) ScreenUi.WHITE else ScreenUi.DIM
        val textY = rect.centerY - unit * 0.3f
        ui.text(batch, room.hostName, rect.x + rect.height * 0.6f, textY, unit * 0.6f, color, alpha)

        // 언제 열린 방인지. 오래 켜 둔 방과 방금 연 방을 가른다.
        ui.centered(
            batch,
            formatOpenedAt(room.createdAt),
            rect.centerX,
            textY,
            unit * 0.55f,
            ScreenUi.DIM,
            alpha,
        )

        // 인원은 방장까지 센 값이다. 빗금이 폰트에 없어 붙임표로 쓴다.
        ui.centered(
            batch,
            "${room.players}-${room.maxPlayers}",
            rect.x + rect.width * 0.86f,
            textY,
            unit * 0.6f,
            if (room.full) ScreenUi.RED else ScreenUi.GREEN,
            alpha,
        )

        val badge = when {
            joining === room -> "JOINING"
            room.full -> "FULL"
            else -> null
        }
        badge?.let {
            ui.centered(batch, it, rect.x + rect.width * 0.68f, textY, unit * 0.45f, ScreenUi.DIM)
        }
    }

    private fun drawButton(
        batch: SpriteBatch,
        index: Int,
        region: com.kophas.battlecity.render.TextureRegion,
        id: Int,
        label: String,
        color: Int,
    ) {
        val rect = buttonRect(index)
        ui.bar(batch, region, rect, alpha = if (pressed == id) PRESSED_ALPHA else 1f)
        ui.label(batch, label, rect, ui.unit * 0.6f, color, BUTTON_TEXT_CENTER, 0.74f)
    }

    companion object {
        /** 한 화면에 보여 줄 줄 수. 같은 공유기 아래에 이보다 많을 일은 드물다. */
        const val MAX_ROWS = 5

        private const val BTN_REFRESH = 100
        private const val BTN_BACK = 101

        private const val ROW_HEIGHT = 1.5f
        private const val ROW_SPACING = 1.8f
        private const val LOCKED_ALPHA = 0.45f
        private const val PRESSED_ALPHA = 0.65f
        private const val BUTTON_TEXT_CENTER = 0.46f

        /**
         * 방이 열린 시각을 `MMDD HH:mm` 으로. 방장 기기의 시계를 이 기기의 시간대로 읽는다.
         *
         * 연도는 빼도 된다. 같은 공유기 아래에서 지금 열려 있는 방이라 오늘 아니면
         * 어제다. 대신 날짜를 남긴 이유는 자정을 넘겨 켜 둔 방을 가르기 위해서다.
         */
        fun formatOpenedAt(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String {
            if (epochMillis <= 0L) return "----"
            val time = Instant.ofEpochMilli(epochMillis).atZone(zone)
            return "%02d%02d %02d:%02d".format(
                time.monthValue,
                time.dayOfMonth,
                time.hour,
                time.minute,
            )
        }
    }
}
