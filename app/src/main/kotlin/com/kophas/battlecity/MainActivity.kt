package com.kophas.battlecity

import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.kophas.battlecity.game.NetRole
import com.kophas.battlecity.net.UdpSelfTest

/**
 * Phase 1 진입점.
 *
 * 게임은 항상 가로(landscape)로만 진행한다. 매니페스트의 `sensorLandscape` 에 더해
 * 런타임에서도 한 번 더 고정한다. 대화면/폴더블에서 시스템이 매니페스트 선언을
 * 무시하는 경우가 있기 때문이다.
 *
 * 화면 회전 / 폴더블 접힘·펼침은 액티비티를 재생성하지 않고 서피스 변경으로만
 * 처리한다. 논리 좌표계는 그대로 두고 viewport 만 갱신한다. (계획서 §21, §22)
 */
class MainActivity : ComponentActivity() {

    private lateinit var gameView: GameSurfaceView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE

        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        gameView = GameSurfaceView(this)
        // 저사양 폴백 경로를 실기기에서 강제로 검증하기 위한 디버그 스위치. (계획서 §24)
        //   adb shell am start -n com.kophas.battlecity/.MainActivity --ez preferVulkan false
        gameView.host.preferVulkan = intent?.getBooleanExtra(EXTRA_PREFER_VULKAN, true) ?: true

        // 역할은 메인 메뉴에서 고른다. 실행 인자를 주면 메뉴를 건너뛴다. (계획서 §4.2)
        //   adb shell am start -n .../.MainActivity --es netRole host
        //   adb shell am start -n .../.MainActivity --es netRole client --es hostAddress 192.168.0.5
        //   adb shell am start -n .../.MainActivity --es netRole solo     (혼자 판만 확인)
        val role = intent?.getStringExtra(EXTRA_NET_ROLE)
        gameView.host.netRole = when (role) {
            "host" -> NetRole.HOST
            "client" -> NetRole.CLIENT
            else -> NetRole.LOCAL
        }
        gameView.host.soloDebug = role == "solo"
        gameView.host.hostAddress = intent?.getStringExtra(EXTRA_HOST_ADDRESS)
        // 이름을 주지 않으면 자리 번호로 P1 ~ P4 가 들어간다. 로비에서 고칠 수 있다.
        intent?.getStringExtra(EXTRA_PLAYER_NAME)?.let { gameView.host.playerName = it }

        if (intent?.getBooleanExtra(EXTRA_NET_SELF_TEST, false) == true) {
            UdpSelfTest.runAsync()
        }
        setContentView(withSplash(gameView))
    }

    /**
     * 시작 화면을 잠깐 붙들어 둔다. (계획서 §27 BOOT)
     *
     * 창 배경(`splash_window`)은 서피스가 준비되는 순간 사라진다. 요즘 기기에서는
     * 그게 눈 깜빡할 사이라 무엇이 지나갔는지 알 수 없다. 그림 한 장을 게임 위에
     * 덮어 두었다가 [SPLASH_MS] 뒤에 걷는다.
     *
     * **앱을 처음 띄울 때만이다.** 프로세스가 살아 있는 동안 다시 들어오는 것은
     * 시작이 아니라 돌아오는 것이다. 그때마다 2초를 기다리게 하면 성가시다.
     */
    private fun withSplash(game: View): View {
        val root = FrameLayout(this)
        root.addView(
            game,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        if (splashShown) return root

        splashShown = true
        val splash = ImageView(this).apply {
            setBackgroundResource(R.color.splash_background)
            setImageResource(R.drawable.splash_landscape)
            // 늘리면 픽셀아트가 뭉개진다. 가운데를 채우고 남는 곳은 바탕색으로 둔다.
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        root.addView(
            splash,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER,
            ),
        )
        // 게임은 뒤에서 이미 돌아간다. 걷어 내면 곧바로 메인 메뉴가 보인다.
        splash.postDelayed({ root.removeView(splash) }, SPLASH_MS)
        return root
    }

    override fun onResume() {
        super.onResume()
        gameView.host.onResume()
    }

    override fun onPause() {
        gameView.host.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        gameView.host.release()
        super.onDestroy()
    }

    private companion object {
        /** 시작 화면을 붙들어 두는 시간. */
        const val SPLASH_MS = 2000L

        /**
         * 이 프로세스에서 시작 화면을 이미 보여 줬는가.
         *
         * 액티비티가 아니라 프로세스에 매다는 이유는, 화면을 돌리거나 뒤로 갔다
         * 돌아올 때는 "처음 실행" 이 아니기 때문이다. 앱이 메모리에서 내려가면
         * 이 값도 함께 사라져 다음 실행에서 다시 보인다.
         */
        @JvmStatic
        private var splashShown = false

        const val EXTRA_PREFER_VULKAN = "preferVulkan"
        const val EXTRA_NET_ROLE = "netRole"
        const val EXTRA_HOST_ADDRESS = "hostAddress"
        const val EXTRA_PLAYER_NAME = "playerName"
        const val EXTRA_NET_SELF_TEST = "netSelfTest"
    }
}
