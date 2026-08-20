package com.kophas.battlecity.audio

import com.kophas.battlecity.render.AssetSource
import com.kophas.battlecity.util.JsonValue

/**
 * `manifest/audio.json` 리더. (계획서 §34)
 *
 * 파일 이름과 볼륨을 코드에 두지 않는다. 소리를 갈아 끼우거나 크기를 조정할 때
 * 이 파일만 고친다. 진동 세기도 같은 파일에 둔다. "쏘는 순간" 이라는 한 사건에
 * 소리와 진동이 함께 붙으므로 따로 두면 한쪽만 고치게 된다.
 */
class AudioSettings(val root: JsonValue) {

    val version: Int = root["version"]?.asInt ?: 0

    class Clip(val name: String, val volume: Float, val minIntervalMs: Long)

    class Haptic(val milliseconds: Long, val amplitude: Int)

    private fun section(vararg path: String): JsonValue? =
        path.fold(root as JsonValue?) { node, key -> node?.get(key) }

    // --- 효과음 -----------------------------------------------------------

    val sfxPath: String = section("sfx", "path")?.asString ?: "audio/sfx"

    val maxStreams: Int = section("sfx", "maxStreams")?.asInt ?: 12

    private fun clips(vararg path: String): Map<String, Clip> =
        (section(*path)?.asObject ?: emptyMap())
            .filterKeys { it != "comment" }
            .mapValues { (_, node) ->
                Clip(
                    name = node["clip"]?.asString.orEmpty(),
                    volume = node["volume"]?.asFloat ?: 1f,
                    minIntervalMs = (node["minIntervalMs"]?.asInt ?: 0).toLong(),
                )
            }
            .filterValues { it.name.isNotEmpty() }

    /** 한 번 나고 마는 소리. */
    val events: Map<String, Clip> = clips("sfx", "events")

    /** 조건이 이어지는 동안 계속 도는 소리. */
    val loops: Map<String, Clip> = clips("sfx", "loops")

    fun event(id: String): Clip? = events[id]

    fun loop(id: String): Clip? = loops[id]

    /** 모아 둔 모든 클립 이름. 미리 읽어 둘 때 쓴다. */
    val allClipNames: List<String> =
        (events.values + loops.values).map { it.name }.distinct()

    // --- 배경음 -----------------------------------------------------------

    val bgmPath: String = section("bgm", "path")?.asString ?: "audio/bgm"

    val bgmVolume: Float = section("bgm", "volume")?.asFloat ?: 0.6f

    /** 남은 COM 이 이 비율 아래로 떨어지면 곡을 바꾼다. */
    val finalWaveRatio: Float = section("bgm", "finalWaveRatio")?.asFloat ?: 0.25f

    private val tracks: Map<String, String> =
        (section("bgm", "tracks")?.asObject ?: emptyMap())
            .filterKeys { it != "comment" }
            .mapValues { (_, node) -> node.asString.orEmpty() }

    fun track(id: String): String? = tracks[id]?.takeIf { it.isNotEmpty() }

    // --- 진동 -------------------------------------------------------------

    val hapticsEnabled: Boolean = section("haptics", "enabled")?.asBoolean ?: true

    private val haptics: Map<String, Haptic> =
        (section("haptics", "events")?.asObject ?: emptyMap())
            .filterKeys { it != "comment" }
            .mapValues { (_, node) ->
                Haptic(
                    milliseconds = (node["ms"]?.asInt ?: 0).toLong(),
                    amplitude = node["amplitude"]?.asInt ?: 128,
                )
            }

    fun haptic(id: String): Haptic? = if (hapticsEnabled) haptics[id] else null

    // --- 저사양 (계획서 §24) ----------------------------------------------

    class LowRam(node: JsonValue?) {
        val maxStreams: Int = node?.get("maxStreams")?.asInt ?: 6
        val bgmEnabled: Boolean = node?.get("bgmEnabled")?.asBoolean ?: false
        val dashTrail: Boolean = node?.get("dashTrail")?.asBoolean ?: false
        val maxExplosions: Int = node?.get("maxExplosions")?.asInt ?: 16
    }

    val lowRam: LowRam = LowRam(section("quality", "lowRam"))

    companion object {
        const val DEFAULT_PATH = "manifest/audio.json"

        fun load(source: AssetSource, path: String = DEFAULT_PATH): AudioSettings =
            AudioSettings(JsonValue.parse(source.readText(path)))
    }
}
