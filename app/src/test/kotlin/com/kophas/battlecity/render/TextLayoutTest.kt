package com.kophas.battlecity.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 글자를 판 한가운데에 놓는 계산.
 *
 * 로비 제목과 START 버튼의 글자가 판과 어긋나 보였다. 원인은 마지막 글자 폭을
 * 간격으로 잡은 것이었다. 눈으로는 "조금 왼쪽인가?" 정도라 놓치기 쉬워 검사로 잡는다.
 */
class TextLayoutTest {

    @Test
    fun `마지막 글자는 간격이 아니라 제 크기만큼 자리를 차지한다`() {
        // 글자 하나면 폭은 정확히 글자 크기다. 간격으로 잡으면 0.7 배가 나온다.
        assertEquals(100f, TextLayout.width("A", 100f), 0.001f)
        // 두 글자면 간격 하나 + 글자 하나.
        assertEquals(170f, TextLayout.width("AB", 100f), 0.001f)
        assertEquals(240f, TextLayout.width("ABC", 100f), 0.001f)
    }

    @Test
    fun `빈 글자는 폭이 없다`() {
        assertEquals(0f, TextLayout.width("", 100f), 0.001f)
    }

    @Test
    fun `가운데에 놓으면 양옆 여백이 같다`() {
        val text = "BATTLE CITY"
        val size = 80f
        val centerX = 1000f
        val left = TextLayout.leftForCenter(text, centerX, size)
        val right = left + TextLayout.width(text, size)

        assertEquals("왼쪽 여백과 오른쪽 여백이 달라졌다", centerX - left, right - centerX, 0.001f)
    }

    @Test
    fun `넘치면 줄여서 넣는다`() {
        val text = "WAIT PLAYERS"
        val size = 80f
        val available = 300f
        val fitted = TextLayout.fit(text, size, available)

        assertTrue("줄어들지 않았다", fitted < size)
        assertEquals("정확히 들어차야 한다", available, TextLayout.width(text, fitted), 0.01f)
    }

    @Test
    fun `넘치지 않으면 크기를 건드리지 않는다`() {
        // 짧은 글자까지 늘리면 이름 세 글자가 버튼만 해진다.
        assertEquals(80f, TextLayout.fit("P1", 80f, 1000f), 0.001f)
    }
}
