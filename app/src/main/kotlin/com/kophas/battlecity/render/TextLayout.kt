package com.kophas.battlecity.render

/**
 * 비트맵 글자를 놓을 자리를 잰다.
 *
 * 글자 폭을 `글자수 x 간격` 으로 잡으면 안 된다. 마지막 글자는 다음 글자를 위한
 * 간격이 아니라 **제 크기만큼** 자리를 차지한다. 그 차이만큼 글자가 왼쪽으로 밀려,
 * 판 한가운데에 놓았다고 생각한 글자가 어긋나 보인다. 로비 제목과 버튼이 그랬다.
 */
object TextLayout {

    /** 글리프 사이 간격. 폰트 도트가 칸 안에서 여백을 가지고 있어 1보다 작다. */
    const val ADVANCE: Float = 0.7f

    fun width(text: String, size: Float, advance: Float = ADVANCE): Float =
        if (text.isEmpty()) 0f else (text.length - 1) * size * advance + size

    /** [centerX] 에 맞춰 그리기 시작할 왼쪽 좌표. */
    fun leftForCenter(text: String, centerX: Float, size: Float, advance: Float = ADVANCE): Float =
        centerX - width(text, size, advance) * 0.5f

    /**
     * [available] 안에 들어가도록 줄인 글자 크기. 넘치지 않으면 [size] 그대로.
     *
     * 넘쳐서 판 테두리를 물면 읽기 어렵다. 글자 수는 이름처럼 사람이 정하는 것이라
     * 미리 맞춰 둘 수 없다.
     */
    fun fit(text: String, size: Float, available: Float, advance: Float = ADVANCE): Float {
        val natural = width(text, size, advance)
        if (natural <= available || natural <= 0f) return size
        return size * (available / natural)
    }
}
