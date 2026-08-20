package com.kophas.battlecity.render

import android.content.res.AssetManager
import java.io.File
import java.io.InputStream

/**
 * 에셋 읽기 경로 추상화.
 *
 * 런타임은 APK 의 `assets/`, 단위 테스트는 파일 시스템에서 같은 파일을 읽는다.
 * 덕분에 매니페스트/아틀라스 파싱을 실제 에셋으로 검증할 수 있다. (계획서 §41-13)
 */
fun interface AssetSource {
    fun open(path: String): InputStream

    fun readText(path: String): String = open(path).use { it.readBytes().toString(Charsets.UTF_8) }

    companion object {
        fun of(assets: AssetManager): AssetSource = AssetSource { assets.open(it) }

        fun ofDirectory(root: File): AssetSource = AssetSource { File(root, it).inputStream() }
    }
}
