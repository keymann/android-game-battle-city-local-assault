package com.kophas.battlecity

import android.content.pm.ActivityInfo
import android.os.Bundle
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

        // 로컬 대전 역할. 조작 UI 가 붙기 전까지는 실행 인자로 정한다. (계획서 §4.2)
        //   adb shell am start -n .../.MainActivity --es netRole host
        //   adb shell am start -n .../.MainActivity --es netRole client --es hostAddress 192.168.0.5
        gameView.host.netRole = when (intent?.getStringExtra(EXTRA_NET_ROLE)) {
            "host" -> NetRole.HOST
            "client" -> NetRole.CLIENT
            else -> NetRole.LOCAL
        }
        gameView.host.hostAddress = intent?.getStringExtra(EXTRA_HOST_ADDRESS)
        // 이름을 주지 않으면 자리 번호로 P1 ~ P4 가 들어간다. 로비에서 고칠 수 있다.
        intent?.getStringExtra(EXTRA_PLAYER_NAME)?.let { gameView.host.playerName = it }

        if (intent?.getBooleanExtra(EXTRA_NET_SELF_TEST, false) == true) {
            UdpSelfTest.runAsync()
        }
        setContentView(gameView)
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
        const val EXTRA_PREFER_VULKAN = "preferVulkan"
        const val EXTRA_NET_ROLE = "netRole"
        const val EXTRA_HOST_ADDRESS = "hostAddress"
        const val EXTRA_PLAYER_NAME = "playerName"
        const val EXTRA_NET_SELF_TEST = "netSelfTest"
    }
}
