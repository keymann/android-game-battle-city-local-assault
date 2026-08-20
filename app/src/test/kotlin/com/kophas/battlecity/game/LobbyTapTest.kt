package com.kophas.battlecity.game

import com.kophas.battlecity.net.Messages
import com.kophas.battlecity.render.AssetManifest
import com.kophas.battlecity.render.AssetSource
import com.kophas.battlecity.render.GameAssets
import com.kophas.battlecity.render.SpriteCatalog
import com.kophas.battlecity.render.TextureAtlas
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 로비에서 참가자가 누른 자리가 무엇으로 읽히는가. (계획서 §28, §29)
 *
 * "참가자가 탱크·색·준비를 눌러도 아무 일도 없다" 를 쫓다가 만든 시험이다. 원인은
 * 씬이 아니라 세션을 만지는 스레드였지만(→ [LoopQueue]), 그 사이를 가르는 데 이
 * 시험이 필요했다. 손가락 판정은 화면 크기에서 나오는 계산이라 조용히 어긋날 수 있어
 * 그대로 남긴다.
 *
 * 씬은 [SpriteCatalog] 를 물고 있고 그것은 텍스처를 올려야 만들어진다. 그런데 손가락
 * 판정은 그림을 한 장도 쓰지 않으므로, 아틀라스만 파싱해 넣은 [GameAssets] 를 만들어
 * 끼우면 시험할 수 있다. 텍스처 업로드만 건너뛴 것이고 자리 계산은 기기와 같다.
 */
class LobbyTapTest {

    private val assetsDir: File = sequenceOf(
        File("src/main/assets"),
        File("app/src/main/assets"),
    ).firstOrNull { it.isDirectory } ?: error("assets 디렉터리를 찾지 못했다")

    private fun catalog(): SpriteCatalog {
        val source = AssetSource.ofDirectory(assetsDir)
        val manifest = AssetManifest.load(source)
        val atlas = TextureAtlas.parse(
            xml = source.readText("atlas/game.xml"),
            textureId = GameAssets.TEXTURE_GAME,
            textureWidth = 1024,
            textureHeight = 2048,
            insetTexels = 0f,
        )
        val mapGen = com.kophas.battlecity.map.MapGenProfile.load(source)
        val constructor = GameAssets::class.java.declaredConstructors.first()
        constructor.isAccessible = true
        val assets = constructor.newInstance(manifest, mapGen, atlas) as GameAssets
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

    /** 방장 하나와 참가자 하나가 들어와 있는 로비. 참가자 화면이다. */
    private fun clientLobby(width: Int = 2400, height: Int = 1080): LobbyScene {
        val scene = LobbyScene(catalog())
        scene.resize(width, height)
        scene.view.slots = listOf(
            slot(0, host = true),
            slot(1, host = false),
            Messages.LobbySlot(2, "", 0, 2, false, false, false),
            Messages.LobbySlot(3, "", 0, 3, false, false, false),
        )
        scene.view.localSlot = 1
        scene.view.host = false
        scene.view.connected = true
        return scene
    }

    /** 16:9 조각 안쪽 비율을 화면 좌표로. 씬이 좌표를 어떻게 옮기는지와 짝이다. */
    private fun tapAt(scene: LobbyScene, xRatio: Float, yRatio: Float, width: Int = 2400, height: Int = 1080): LobbyScene.Action {
        val box = com.kophas.battlecity.render.StageBox.fit(width.toFloat(), height.toFloat())
        return scene.onTap(box.x + box.width * xRatio, box.y + box.height * yRatio)
    }

    @Test
    fun `참가자가 탱크 칸을 누르면 종류를 넘긴다`() {
        val scene = clientLobby()
        // TANK 칸은 조각 가로 0.5 자리, 고르는 줄은 세로 0.62 부터다.
        assertEquals(LobbyScene.Action.CycleType, tapAt(scene, 0.5f, 0.65f))
    }

    @Test
    fun `참가자가 색 칸을 누르면 색을 넘긴다`() {
        val scene = clientLobby()
        assertEquals(LobbyScene.Action.CycleColor, tapAt(scene, 0.7f, 0.65f))
    }

    @Test
    fun `참가자가 아래 큰 단추를 누르면 준비를 뒤집는다`() {
        val scene = clientLobby()
        assertEquals(LobbyScene.Action.ToggleReady, tapAt(scene, 0.5f, 0.83f))
    }

    @Test
    fun `참가자가 제 자리 카드를 누르면 준비를 뒤집는다`() {
        val scene = clientLobby()
        // 카드는 넷이 가운데에 나란히 있다. 2번 카드가 참가자 자리다.
        assertEquals(LobbyScene.Action.ToggleReady, tapAt(scene, 0.44f, 0.35f))
    }

    @Test
    fun `참가자에게는 게임 설정 단추가 없다`() {
        // 방 규칙은 방장 것이다. 그 자리를 눌러도 아무 일도 없어야 한다.
        val scene = clientLobby()
        assertEquals(LobbyScene.Action.None, tapAt(scene, 0.9f, 0.07f))
    }

    @Test
    fun `방장이 오른쪽 위를 누르면 게임 설정이 열린다`() {
        val scene = clientLobby()
        scene.view.host = true
        scene.view.localSlot = 0
        assertEquals(LobbyScene.Action.OpenGameSettings, tapAt(scene, 0.9f, 0.07f))
    }
}
