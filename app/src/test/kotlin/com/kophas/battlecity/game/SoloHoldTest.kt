package com.kophas.battlecity.game

import com.kophas.battlecity.core.Constants
import com.kophas.battlecity.net.Messages
import com.kophas.battlecity.net.Protocol
import com.kophas.battlecity.render.AssetManifest
import com.kophas.battlecity.render.AssetSource
import com.kophas.battlecity.render.GameAssets
import com.kophas.battlecity.render.SpriteCatalog
import com.kophas.battlecity.render.StageBox
import com.kophas.battlecity.render.TextureAtlas
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 히든: 내 카드를 오래 누르면 혼자 판이 열린다.
 *
 * 밸런스를 눈으로 확인하려면 사람을 둘 모아야 했다. 그러느라 확인이 미뤄지고, 미뤄진
 * 만큼 어려운 판을 그대로 두게 됐다. 화면에 적지 않는 길이라 **얼마나 눌러야 열리고
 * 언제 취소되는지**를 시험이 못 박아 둔다. 손짓 하나로 열리면 모르고 눌러 방을 깨고,
 * 너무 까다로우면 아는 사람도 못 연다.
 */
class SoloHoldTest {

    private val assetsDir: File = sequenceOf(
        File("src/main/assets"),
        File("app/src/main/assets"),
    ).firstOrNull { it.isDirectory } ?: error("assets 디렉터리를 찾지 못했다")

    private fun catalog(): SpriteCatalog {
        val source = AssetSource.ofDirectory(assetsDir)
        val atlas = TextureAtlas.parse(
            xml = source.readText("atlas/game.xml"),
            textureId = GameAssets.TEXTURE_GAME,
            textureWidth = 1024,
            textureHeight = 2048,
            insetTexels = 0f,
        )
        val constructor = GameAssets::class.java.declaredConstructors.first()
        constructor.isAccessible = true
        val assets = constructor.newInstance(
            AssetManifest.load(source),
            com.kophas.battlecity.map.MapGenProfile.load(source),
            atlas,
        ) as GameAssets
        return SpriteCatalog(assets)
    }

    private fun slot(index: Int, host: Boolean) = Messages.LobbySlot(
        index = index,
        name = if (host) "HST" else "GST",
        tankType = 0,
        colorIndex = index,
        ready = false,
        connected = true,
        host = host,
    )

    /** 방장 자신의 화면. 내 자리는 0번이다. */
    private fun lobby(): LobbyScene {
        val scene = LobbyScene(catalog())
        scene.resize(WIDTH, HEIGHT)
        scene.view.slots = listOf(slot(0, host = true), slot(1, host = false))
        scene.view.localSlot = 0
        scene.view.host = true
        scene.view.connected = true
        return scene
    }

    /**
     * 자리 카드 가운데를 누른다.
     *
     * 카드 자리 계산을 여기 옮겨 적었다. 씬의 상수는 private 이라 볼 수 없다.
     * 카드 배치를 바꾸면 이 시험이 먼저 깨진다 — 그래야 손가락이 어디를 누르는지
     * 아무도 모르는 채로 넘어가지 않는다.
     */
    private fun tapCard(scene: LobbyScene, index: Int): LobbyScene.Action {
        val box = StageBox.fit(WIDTH.toFloat(), HEIGHT.toFloat())
        val cardWidth = box.width * CARD_WIDTH
        val gap = cardWidth * CARD_GAP
        val left = (box.width - (cardWidth * Protocol.MAX_PLAYERS + gap * (Protocol.MAX_PLAYERS - 1))) * 0.5f
        val centerX = left + index * (cardWidth + gap) + cardWidth * 0.5f
        return scene.onTap(box.x + centerX, box.y + box.height * CARD_TOUCH_Y)
    }

    private fun tapOwnCard(scene: LobbyScene): LobbyScene.Action = tapCard(scene, scene.view.localSlot)

    private fun tapOtherCard(scene: LobbyScene): LobbyScene.Action = tapCard(scene, scene.view.localSlot + 1)

    /** 한 틱씩 [seconds] 만큼 굴린다. 기기에서도 이렇게 더해진다. */
    private fun hold(scene: LobbyScene, seconds: Float): LobbyScene.Action {
        var last: LobbyScene.Action = LobbyScene.Action.None
        val ticks = (seconds / Constants.TICK_SECONDS).toInt()
        repeat(ticks) {
            val action = scene.update(Constants.TICK_SECONDS)
            if (action != LobbyScene.Action.None) last = action
        }
        return last
    }

    @Test
    fun `내 카드를 누르는 것은 준비 뒤집기이기도 하다`() {
        // 붙들기를 붙였다고 원래 손짓이 사라지면 안 된다.
        assertEquals(LobbyScene.Action.ToggleReady, tapOwnCard(lobby()))
    }

    @Test
    fun `열 초를 눌러야 열린다`() {
        val scene = lobby()
        tapOwnCard(scene)

        assertEquals(
            "아직 덜 눌렀는데 열렸다",
            LobbyScene.Action.None,
            hold(scene, LobbyScene.SOLO_HOLD_SECONDS - 1f),
        )
        assertEquals(
            "열 초를 눌렀는데 안 열린다",
            LobbyScene.Action.StartSolo,
            hold(scene, 1.2f),
        )
    }

    @Test
    fun `한 번 열리면 거듭 열리지 않는다`() {
        val scene = lobby()
        tapOwnCard(scene)
        assertEquals(LobbyScene.Action.StartSolo, hold(scene, LobbyScene.SOLO_HOLD_SECONDS + 1f))

        // 손을 떼지 않아도 두 번 열리면 안 된다. 판을 여는 일이 두 번 일어난다.
        assertEquals(LobbyScene.Action.None, hold(scene, LobbyScene.SOLO_HOLD_SECONDS + 1f))
    }

    @Test
    fun `손을 떼면 처음부터 다시 센다`() {
        val scene = lobby()
        tapOwnCard(scene)
        hold(scene, LobbyScene.SOLO_HOLD_SECONDS - 1f)
        scene.onRelease()

        assertEquals(
            "손을 뗐는데 눌린 시간이 남아 있다",
            LobbyScene.Action.None,
            hold(scene, 2f),
        )

        tapOwnCard(scene)
        assertEquals(LobbyScene.Action.StartSolo, hold(scene, LobbyScene.SOLO_HOLD_SECONDS + 0.1f))
    }

    @Test
    fun `남의 카드는 오래 눌러도 열리지 않는다`() {
        val scene = lobby()
        tapOtherCard(scene)

        assertEquals(LobbyScene.Action.None, hold(scene, LobbyScene.SOLO_HOLD_SECONDS + 2f))
    }

    @Test
    fun `누르지 않았으면 시간이 흘러도 아무 일도 없다`() {
        assertEquals(LobbyScene.Action.None, hold(lobby(), LobbyScene.SOLO_HOLD_SECONDS + 2f))
    }

    @Test
    fun `세는 중에는 붙들기를 받지 않는다`() {
        // 곧 판이 열리는데 다른 판을 열 이유가 없다.
        val scene = lobby()
        scene.view.countdownTicks = 120
        tapOwnCard(scene)

        assertEquals(LobbyScene.Action.None, hold(scene, LobbyScene.SOLO_HOLD_SECONDS + 2f))
    }

    @Test
    fun `누르고 있는데 세기가 시작되면 붙들기를 버린다`() {
        val scene = lobby()
        tapOwnCard(scene)
        hold(scene, 3f)

        scene.view.countdownTicks = 180
        assertEquals(LobbyScene.Action.None, hold(scene, LobbyScene.SOLO_HOLD_SECONDS + 2f))

        // 카운트다운이 취소돼도 앞서 누른 시간이 되살아나면 안 된다.
        scene.view.countdownTicks = 0
        assertEquals(LobbyScene.Action.None, hold(scene, LobbyScene.SOLO_HOLD_SECONDS - 1f))
    }

    // --- 결과 화면 (계획서 §33) -------------------------------------------

    @Test
    fun `혼자 하는 판은 큰 단추가 RESTART 다`() {
        assertEquals("RESTART", ResultScene.primaryLabel(solo = true))
        assertEquals("PLAY AGAIN", ResultScene.primaryLabel(solo = false))
    }

    @Test
    fun `혼자 하는 판에는 돌아갈 로비가 없다`() {
        assertFalse("혼자 하는 판", ResultScene.lobbyAllowed(hostLost = false, solo = true))
        assertFalse("방을 잃은 판", ResultScene.lobbyAllowed(hostLost = true, solo = false))
        assertTrue("여럿이 하는 판", ResultScene.lobbyAllowed(hostLost = false, solo = false))
    }

    private companion object {
        const val WIDTH = 2400
        const val HEIGHT = 1080

        /** LobbyScene 의 카드 배치. 그쪽을 고치면 여기도 고쳐야 한다. */
        const val CARD_WIDTH = 0.13f
        const val CARD_GAP = 0.18f

        /** 카드 세로 가운데쯤. 카드는 0.26 부터 0.554 까지다. */
        const val CARD_TOUCH_Y = 0.4f
    }
}
