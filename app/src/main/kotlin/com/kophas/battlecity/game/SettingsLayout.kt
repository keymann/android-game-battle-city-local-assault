package com.kophas.battlecity.game

import com.kophas.battlecity.render.ScreenUi

/**
 * 설정판의 자리 계산. (게임 설정 · 사운드 설정)
 *
 * 그림도 손가락도 다루지 않고 좌표만 낸다. 씬에서 떼어 놓은 까닭은 두 가지다.
 *  - 설정판이 두 장으로 갈리면서 같은 계산을 두 벌 두게 되었다.
 *  - [ScreenUi] 를 쓰는 씬은 실제 텍스처를 물고 있어 단위 시험에서 만들 수 없다.
 *    자리 계산만 떼어 두면 "줄이 판을 넘지 않는가" 를 시험이 직접 짚을 수 있다.
 *
 * 좌표는 [com.kophas.battlecity.render.StageBox] 안쪽 값이다. 화면 어디에 놓일지는
 * [ScreenUi] 가 정한다.
 */
class SettingsLayout(private val metrics: Metrics) {

    /**
     * 판 하나의 치수. 담을 줄 수가 다르면 판 크기도 줄 간격도 달라진다.
     *
     * 값은 모두 비율이다. [panelTop] · [panelWidth] 는 화면 대비, 나머지는
     * 한 칸([unit]) 대비다.
     */
    class Metrics(
        /** 판 위 여백. */
        val panelTop: Float,
        /** 판 너비. 화면 너비 대비. */
        val panelWidth: Float,
        /** 판 높이. 한 칸 단위. */
        val panelUnits: Float,
        /** 첫 줄이 앉는 자리. 판 속 위쪽부터 잰다. */
        val rowTop: Float,
        /** 줄 간격. */
        val rowHeight: Float,
        /** 줄에서 조작기가 차지하는 몫. 나머지가 이름표 자리다. */
        val controlWidth: Float,
        /** 조작기 높이. */
        val controlHeight: Float,
        /**
         * 눈금 오른쪽에 값 글자를 놓을 자리.
         *
         * 값을 눈금 오른쪽에 쓰는데, 눈금을 조작기 끝까지 붙이면 값이 판 테두리 위로
         * 올라간다. 그 화면에서 가장 긴 값이 [valueSize] 로 들어갈 만큼 떼어 둔다.
         */
        val valueReserve: Float,
        /** 눈금 두께. */
        val sliderThickness: Float,
        /** 눈금 오른쪽 값 글자 크기. [valueReserve] 와 짝이다. */
        val valueSize: Float,
        /** 담는 줄 수. 아래 단추와 겹치지 않는지 시험이 이 값으로 따진다. */
        val rowCount: Int,
    )

    /** 16:9 조각 크기와 판 그림 비율. [resize] 로 들어온다. */
    private var width = 0f
    private var height = 0f
    private var panelAspect = 1f

    /** 한 칸. 조각 높이 기준이라 기기가 달라도 비율이 유지된다. */
    val unit: Float get() = height * UNIT_RATIO

    val ready: Boolean get() = width > 0f && height > 0f

    fun resize(width: Float, height: Float, panelAspect: Float) {
        this.width = width
        this.height = height
        this.panelAspect = panelAspect
    }

    /**
     * 설정판. 세 조각으로 나눠 가운데만 늘린다.
     *
     * 그림 비율대로만 놓으면 칸이 좁아 이름표와 조작기가 겹치고, 통째로 늘리면
     * 모서리 장식이 부풀어 오른다. 끝을 그대로 두고 가운데만 늘리면 둘 다 피한다.
     */
    fun panelRect(): ScreenUi.Rect = ScreenUi.Rect(
        x = width * (1f - metrics.panelWidth) * 0.5f,
        y = height * metrics.panelTop,
        width = width * metrics.panelWidth,
        height = unit * metrics.panelUnits,
    )

    /**
     * 판 안쪽에서 글자와 조작기가 들어가도 되는 자리.
     *
     * 판 그림의 네 귀퉁이에는 굵은 걸쇠 장식이 있고, 그것은 판을 아무리 넓게 늘려도
     * 크기가 그대로다 — [ScreenUi.bar] 가 좌우 끝 조각을 원래 비율로 그리기 때문이다.
     * 그래서 여백을 판 **너비**의 비율로 잡으면 넓은 화면에서 걸쇠 위로 글자가 올라간다.
     * 끝 조각의 크기(= 높이에서 나온다)를 기준으로 잡아야 어느 화면에서나 비껴간다.
     */
    fun contentRect(): ScreenUi.Rect {
        val panel = panelRect()
        // bar() 가 쓰는 끝 조각 너비와 같은 식이다. 그 안쪽 절반쯤에 걸쇠가 있다.
        val cap = panel.height * panelAspect / BAR_SLICES
        val inset = cap * CAP_CLEARANCE
        return ScreenUi.Rect(
            x = panel.x + inset,
            y = panel.y + panel.height * CONTENT_TOP,
            width = panel.width - inset * 2f,
            height = panel.height * (CONTENT_BOTTOM - CONTENT_TOP),
        )
    }

    /**
     * 판 이름표가 앉는 자리와 크기.
     *
     * 판 그림 위쪽 테두리 띠 안에 놓는다. 띠 두께는 판 높이에 비례하므로(그림을
     * 세로로 늘려 그린다) 글자도 한 칸이 아니라 **판 높이**에 비례해야 한다. 한 칸
     * 기준으로 잡으면 작은 판(사운드)에서 이름표가 띠를 넘어 내려온다.
     */
    fun titleY(): Float = panelRect().let { it.y + it.height * TITLE_TOP }

    fun titleSize(): Float = panelRect().height * TITLE_SIZE

    fun rowY(row: Int): Float =
        contentRect().y + unit * (metrics.rowTop + row * metrics.rowHeight)

    /** 이름표 자리. 줄 왼쪽에서 시작한다. */
    fun labelX(indent: Float = 0f): Float = contentRect().x + indent

    /** 조작기는 줄의 오른쪽 끝에 붙인다. 왼쪽은 이름표 자리다. */
    fun controlRect(row: Int, widthScale: Float = 1f): ScreenUi.Rect {
        val content = contentRect()
        val full = content.width * metrics.controlWidth
        val w = full * widthScale
        return ScreenUi.Rect(
            x = content.x + content.width - w,
            y = rowY(row) + unit * CONTROL_DROP,
            width = w,
            height = unit * metrics.controlHeight,
        )
    }

    /** 눈금 자리. 오른쪽에 값 글자가 들어갈 만큼([Metrics.valueReserve])을 비워 둔다. */
    fun sliderRect(row: Int): ScreenUi.Rect {
        val base = controlRect(row)
        return ScreenUi.Rect(
            x = base.x,
            y = base.centerY - unit * metrics.sliderThickness * 0.5f,
            width = base.width - unit * metrics.valueReserve,
            height = unit * metrics.sliderThickness,
        )
    }

    /** 값 글자가 들어갈 자리의 왼쪽 끝. 눈금 오른쪽에 왼쪽맞춤으로 쓴다. */
    fun sliderValueX(row: Int): Float {
        val slider = sliderRect(row)
        return slider.x + slider.width + unit * VALUE_GAP
    }

    /** 값 글자 크기. 씬이 그릴 때와 시험이 폭을 잴 때 같은 값을 본다. */
    fun valueTextSize(): Float = unit * metrics.valueSize

    /**
     * 폭이 [width] 인 값 글자를 놓아도 판 속을 넘지 않는가.
     *
     * 떼어 둔 자리([Metrics.valueReserve])가 그 화면에서 가장 긴 값에 못 미치면
     * 글자가 판 테두리를 문다. 눈으로 보기 전에 시험이 짚는다.
     */
    fun valueFits(row: Int, width: Float): Boolean {
        val content = contentRect()
        return sliderValueX(row) + width <= content.x + content.width + ROUNDING
    }

    /**
     * 아래 단추. RESET · CANCEL · APPLY 를 나란히 둔다.
     *
     * 판 너비가 아니라 **판 속** 너비를 나눠 쓴다. 판 너비로 잡으면 세 단추가
     * 걸쇠 장식 위로 올라간다 — 귀퉁이 걸쇠는 판을 늘려도 크기가 그대로여서,
     * 판이 넓을수록 단추가 그 위로 밀려 들어간다.
     */
    fun actionRect(index: Int, count: Int = ACTION_COUNT): ScreenUi.Rect {
        val content = contentRect()
        val width = content.width * ACTION_WIDTH
        val gap = content.width * ACTION_GAP
        val total = width * count + gap * (count - 1)
        return ScreenUi.Rect(
            x = content.x + content.width * 0.5f - total * 0.5f + (width + gap) * index,
            y = actionTop(),
            width = width,
            height = unit * ACTION_HEIGHT,
        )
    }

    private fun actionTop(): Float {
        val content = contentRect()
        return content.bottom - unit * ACTION_HEIGHT
    }

    /** 마지막 줄이 끝나는 자리. 아래 단추와 겹치는지 보려면 이 값을 본다. */
    fun rowsBottom(): Float =
        rowY(metrics.rowCount - 1) + unit * (CONTROL_DROP + metrics.controlHeight)

    /** 줄이 모두 판 속에 들어가고 아래 단추와 겹치지 않는가. */
    fun rowsFit(): Boolean = ready && rowsBottom() <= actionTop()

    companion object {
        /** [ScreenUi.unit] 과 같은 값이어야 한다. 두 계산이 같은 격자를 써야 한다. */
        private const val UNIT_RATIO = 0.07f

        /** [ScreenUi.bar] 가 판을 나누는 조각 수. 끝 조각 크기를 되짚는 데 쓴다. */
        private const val BAR_SLICES = 3f

        /** 끝 조각 안에서 걸쇠 장식을 비껴가는 데 필요한 만큼. */
        private const val CAP_CLEARANCE = 0.5f

        /** 판 높이에서 위아래 테두리가 먹는 몫. */
        private const val CONTENT_TOP = 0.12f
        private const val CONTENT_BOTTOM = 0.88f

        /** 이름표 자리. 판 높이 대비다. 예전 설정판에서 잰 비율을 그대로 옮겼다. */
        private const val TITLE_TOP = 0.04f
        private const val TITLE_SIZE = 0.065f

        /** 조작기는 이름표보다 조금 내려 앉는다. 글자 윗선과 눈높이를 맞춘다. */
        private const val CONTROL_DROP = 0.1f

        /** 눈금과 값 글자 사이. */
        private const val VALUE_GAP = 0.4f

        /** 부동소수 반올림만큼은 눈감아 준다. 한 픽셀 안쪽이다. */
        private const val ROUNDING = 1f

        private const val ACTION_COUNT = 3

        /** 단추 하나가 먹는 판 속 너비와 사이 간격. 셋이 판 속을 거의 다 쓴다. */
        private const val ACTION_WIDTH = 0.28f
        private const val ACTION_GAP = 0.05f
        private const val ACTION_HEIGHT = 1.45f
    }
}
