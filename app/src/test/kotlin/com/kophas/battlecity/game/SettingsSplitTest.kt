package com.kophas.battlecity.game

import com.kophas.battlecity.render.TextLayout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 설정 화면이 두 장으로 갈렸다. (계획서 §44.2)
 *
 * 게임 설정(방 규칙)은 로비에서 방장만, 사운드 설정(소리 크기)은 메인 메뉴에서
 * 연다. 화면 없이 확인할 수 있는 것만 짚는다 — 누가 열 수 있는지, 그리고 담을 것이
 * 판 안에 들어가는지.
 *
 * 판 배치를 시험이 직접 짚는 까닭은, 줄이 아래 단추 위로 올라가거나 글자가 판
 * 테두리를 물어도 코드는 조용히 돌기 때문이다. 눈으로 볼 수 없는 시험 환경에서는
 * 그것이 드러날 자리가 여기뿐이다.
 */
class SettingsSplitTest {

    /** 흔한 단말 몇 가지. 16:9 조각 크기는 화면비에 따라 달라진다. */
    private val screens = listOf(
        1920f to 1080f,  // 16:9
        2400f to 1080f,  // 20:9 — 좌우에 검은 띠가 생긴다
        1024f to 768f,   // 4:3 — 위아래에 검은 띠가 생긴다
        2560f to 1600f,
    )

    /** 설정판 그림(`set_settings_panel`) 의 비율. 287x197 로 뽑혀 있다. */
    private val panelAspect = 287f / 197f

    private fun layoutsOf(metrics: SettingsLayout.Metrics): List<SettingsLayout> =
        screens.map { (width, height) ->
            val box = com.kophas.battlecity.render.StageBox.fit(width, height)
            SettingsLayout(metrics).apply { resize(box.width, box.height, panelAspect) }
        }

    // --- 누가 여는가 -------------------------------------------------------

    @Test
    fun `게임 설정 단추는 방장에게만 보인다`() {
        // 방 규칙은 방장 것이다. 참가자에게는 눌러서 고칠 것이 없다.
        assertTrue(LobbyScene.settingsAllowed(isHost = true))
        assertFalse(LobbyScene.settingsAllowed(isHost = false))
    }

    // --- 판 안에 들어가는가 ------------------------------------------------

    @Test
    fun `게임 설정은 다섯 줄이 아래 단추와 겹치지 않는다`() {
        for (layout in layoutsOf(GameSettingsScene.METRICS)) {
            assertTrue("줄이 아래 단추 위로 올라간다", layout.rowsFit())
        }
    }

    @Test
    fun `사운드 설정은 두 줄이 아래 단추와 겹치지 않는다`() {
        for (layout in layoutsOf(SoundSettingsScene.METRICS)) {
            assertTrue("줄이 아래 단추 위로 올라간다", layout.rowsFit())
        }
    }

    @Test
    fun `판은 화면 안에 들어간다`() {
        for (metrics in listOf(GameSettingsScene.METRICS, SoundSettingsScene.METRICS)) {
            for ((index, layout) in layoutsOf(metrics).withIndex()) {
                val panel = layout.panelRect()
                val (screenWidth, screenHeight) = screens[index]
                val box = com.kophas.battlecity.render.StageBox.fit(screenWidth, screenHeight)
                assertTrue("판이 조각 위로 넘친다", panel.y >= 0f)
                assertTrue("판이 조각 아래로 넘친다", panel.bottom <= box.height)
                assertTrue("판이 조각 왼쪽으로 넘친다", panel.x >= 0f)
                assertTrue("판이 조각 오른쪽으로 넘친다", panel.x + panel.width <= box.width)
            }
        }
    }

    @Test
    fun `조작기와 눈금 값은 판 속을 벗어나지 않는다`() {
        for (metrics in LONGEST_VALUE.keys) {
            for (layout in layoutsOf(metrics)) {
                val content = layout.contentRect()
                for (row in 0 until metrics.rowCount) {
                    val control = layout.controlRect(row)
                    assertTrue(
                        "조작기가 판 속 오른쪽을 넘는다",
                        control.x + control.width <= content.x + content.width + TOLERANCE,
                    )
                    assertTrue("조작기가 판 속 위로 올라간다", control.y >= content.y - TOLERANCE)

                    // 눈금 오른쪽에 값 글자가 들어갈 자리를 떼어 두었다. 그 화면에서
                    // 가장 긴 값이 들어가도 판 테두리를 물지 않아야 한다.
                    val longest = LONGEST_VALUE.getValue(metrics)
                    assertTrue(
                        "값 글자($longest)가 판 테두리를 문다",
                        layout.valueFits(row, TextLayout.width(longest, layout.valueTextSize())),
                    )
                }
            }
        }
    }

    @Test
    fun `이름표와 조작기가 겹치지 않는다`() {
        // 이름표는 줄 왼쪽에서 시작하고 조작기는 오른쪽 끝에 붙는다. 가장 긴 이름표가
        // 들어가도 사이가 남아야 한다.
        for ((metrics, longest) in LONGEST_LABEL) {
            for (layout in layoutsOf(metrics)) {
                val labelWidth = TextLayout.width(longest.text, layout.unit * longest.size) +
                    layout.unit * longest.indent
                for (row in 0 until metrics.rowCount) {
                    val control = layout.controlRect(row)
                    assertTrue(
                        "이름표(${longest.text})가 조작기와 겹친다",
                        layout.labelX() + labelWidth <= control.x,
                    )
                }
            }
        }
    }

    /**
     * 그 화면에서 가장 긴 이름표. 크기와 들여쓰기는 씬이 쓰는 값보다 넉넉하게 잡는다.
     *
     * 씬의 상수는 private 이라 여기서 볼 수 없다. 위쪽으로 잡아 두면 씬이 글자를
     * 조금 키워도 시험이 먼저 깨지지 않고, 크게 키우면 걸린다.
     */
    private class Label(val text: String, val size: Float, val indent: Float)

    private companion object {
        /** 부동소수 반올림만큼은 눈감아 준다. 한 픽셀 안쪽이다. */
        const val TOLERANCE = 1f

        val LONGEST_LABEL = mapOf(
            GameSettingsScene.METRICS to Label("BASE PROTECTION", size = 0.68f, indent = 0f),
            // 사운드 줄은 왼쪽에 스피커 아이콘이 하나 더 붙는다. 그만큼 밀린다.
            SoundSettingsScene.METRICS to Label("BGM", size = 0.8f, indent = 1.9f),
        )

        /**
         * 화면마다 눈금 오른쪽에 들어갈 수 있는 가장 긴 값.
         *
         * 방 규칙은 시드와 동시 COM 이 AUTO 로 갈 수 있고, 소리는 0 ~ 100 이다.
         */
        val LONGEST_VALUE = mapOf(
            GameSettingsScene.METRICS to "AUTO",
            SoundSettingsScene.METRICS to "100",
        )
    }
}
