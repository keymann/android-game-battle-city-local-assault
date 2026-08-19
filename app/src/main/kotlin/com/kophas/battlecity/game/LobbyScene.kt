package com.kophas.battlecity.game

import com.kophas.battlecity.core.Constants
import com.kophas.battlecity.net.Messages
import com.kophas.battlecity.net.Protocol
import com.kophas.battlecity.render.SpriteBatch
import com.kophas.battlecity.render.SpriteCatalog
import com.kophas.battlecity.render.TextureRegion

/**
 * 로비 화면. (계획서 §28, §38)
 *
 * ```
 * ┌─────────────────────────┐
 * │      BATTLE CITY        │
 * │ P1 READY   P2 READY     │
 * │ P3 WAITING P4 ---       │
 * │       START GAME        │
 * └─────────────────────────┘
 * ```
 *
 * Host 만 START 를 누를 수 있고 최소 두 명이 있어야 한다. 자기 자리를 누르면
 * 준비 상태가 바뀐다.
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

    val view = View()

    /** 누른 곳이 무엇이었는지. 세션에 넘길 동작이다. */
    enum class Action { NONE, TOGGLE_READY, START }

    private var width = 0f
    private var height = 0f
    private var startPressed = false

    fun resize(width: Int, height: Int) {
        this.width = width.toFloat()
        this.height = height.toFloat()
    }

    fun onTap(x: Float, y: Float): Action {
        if (hitsStart(x, y)) {
            startPressed = true
            return if (canStart()) Action.START else Action.NONE
        }
        // 자기 자리를 누르면 준비 상태가 바뀐다. 남의 자리는 눌러도 소용없다.
        val slot = slotAt(x, y)
        return if (slot >= 0 && slot == view.localSlot) Action.TOGGLE_READY else Action.NONE
    }

    fun onRelease() {
        startPressed = false
    }

    // -----------------------------------------------------------------------

    fun render(batch: SpriteBatch) {
        if (width <= 0f || height <= 0f) return
        val unit = height * UNIT_RATIO

        drawTitle(batch, unit)
        drawSlots(batch, unit)
        drawStart(batch, unit)
        drawStatus(batch, unit)
    }

    private fun drawTitle(batch: SpriteBatch, unit: Float) {
        val plateWidth = width * TITLE_WIDTH
        val plateHeight = unit * 2.6f
        val plateTop = unit * 0.4f
        batch.draw(
            region = catalog.lobbyTitle,
            x = (width - plateWidth) * 0.5f,
            y = plateTop,
            width = plateWidth,
            height = plateHeight,
            layer = Constants.Layer.HUD,
        )
        drawCentered(
            batch,
            "BATTLE CITY",
            width * 0.5f,
            plateTop + plateHeight * 0.5f - unit * 0.45f,
            unit * 0.9f,
        )
    }

    private fun drawSlots(batch: SpriteBatch, unit: Float) {
        val count = Protocol.MAX_PLAYERS
        val cardWidth = width * CARD_WIDTH
        val gap = cardWidth * 0.18f
        val totalWidth = cardWidth * count + gap * (count - 1)
        val left = (width - totalWidth) * 0.5f
        val top = height * SLOT_TOP
        val cardHeight = unit * 4.2f

        for (index in 0 until count) {
            val slot = view.slots.getOrNull(index)
            val x = left + index * (cardWidth + gap)
            val connected = slot?.connected == true

            batch.draw(
                region = catalog.lobbySlots[index % catalog.lobbySlots.size],
                x = x,
                y = top,
                width = cardWidth,
                height = cardHeight,
                layer = Constants.Layer.HUD,
                alpha = if (connected) 1f else EMPTY_ALPHA,
            )
            drawCentered(
                batch,
                "P${index + 1}",
                x + cardWidth * 0.5f,
                top + unit * 0.25f,
                unit * 0.75f,
            )

            val badge: TextureRegion = when {
                !connected -> catalog.lobbyLocked
                slot.ready -> catalog.lobbyReady
                else -> catalog.lobbyWaiting
            }
            val badgeSize = unit * 1.4f
            batch.draw(
                region = badge,
                x = x + cardWidth * 0.5f - badgeSize * 0.5f,
                y = top + cardHeight - badgeSize * 1.2f,
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
            !view.host -> if (readyOf(view.localSlot)) "READY" else "TAP TO READY"
            enabled -> "START GAME"
            else -> "WAIT PLAYERS"
        }
        drawCentered(batch, label, width * 0.5f, y + buttonHeight * 0.3f, unit * 0.8f)
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

    // -----------------------------------------------------------------------

    private fun canStart(): Boolean =
        view.host &&
            view.countdownTicks == 0 &&
            view.slots.count { it.connected } >= Protocol.MIN_PLAYERS &&
            view.slots.all { !it.connected || it.ready }

    private fun readyOf(slot: Int): Boolean =
        view.slots.getOrNull(slot)?.ready == true

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

    private fun drawCentered(batch: SpriteBatch, text: String, centerX: Float, y: Float, size: Float) {
        val advance = size * GLYPH_ADVANCE
        drawText(batch, text, centerX - text.length * advance * 0.5f, y, size)
    }

    private fun drawText(batch: SpriteBatch, text: String, x: Float, y: Float, size: Float) {
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
                    // 같은 층 안에서는 넣은 순서대로 그려지므로 판 뒤에 넣으면 된다.
                    layer = Constants.Layer.HUD,
                )
            }
            cursor += size * GLYPH_ADVANCE
        }
    }

    private companion object {
        /** 화면 높이 기준 한 칸. 기기 크기가 달라도 비율이 유지된다. */
        const val UNIT_RATIO = 0.075f

        const val TITLE_WIDTH = 0.42f
        const val CARD_WIDTH = 0.15f
        const val SLOT_TOP = 0.32f
        const val START_TOP = 0.75f
        const val START_WIDTH = 0.3f

        const val GLYPH_ADVANCE = 0.7f
        const val EMPTY_ALPHA = 0.35f
        const val DIM = 0.5f
    }
}
