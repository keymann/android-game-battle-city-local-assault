package com.keymann.battlecity

import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

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
        //   adb shell am start -n com.keymann.battlecity/.MainActivity --ez preferVulkan false
        gameView.host.preferVulkan = intent?.getBooleanExtra(EXTRA_PREFER_VULKAN, true) ?: true
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
    }
}
