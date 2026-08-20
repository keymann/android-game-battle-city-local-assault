package com.kophas.battlecity.render

import com.kophas.battlecity.core.Direction
import com.kophas.battlecity.gameplay.Explosion
import com.kophas.battlecity.gameplay.Tank

/**
 * 매니페스트의 스프라이트 이름을 실제 [TextureRegion] 으로 한 번만 풀어 둔다.
 *
 * 렌더 루프에서 문자열 조회가 일어나지 않도록 시작할 때 전부 해석한다.
 * 리소스를 바꿔도 여기만 다시 읽으면 되고 그리는 코드는 그대로다.
 *
 * 탱크와 포탄은 **방향별 그림이 따로** 있다. 그래서 회전값이 없다.
 * 픽셀아트를 돌리면 가장자리가 뭉개지는데 그 문제가 통째로 사라진다.
 */
class SpriteCatalog(private val assets: GameAssets) {

    private val manifest = assets.manifest

    private fun node(vararg path: String) = path.fold(manifest.root as com.kophas.battlecity.util.JsonValue?) { acc, key ->
        acc?.get(key)
    }

    private fun regions(vararg path: String): List<TextureRegion> =
        (node(*path)?.asStringList ?: emptyList()).map { assets[it] }

    // --- 타일 -------------------------------------------------------------

    /** 물결 프레임. 전용 그림이 4장이라 프레임 교대만으로 넘실거린다. */
    val waterFrames: List<TextureRegion> =
        manifest.tileFrames("WATER").map { assets[it] }.ifEmpty { listOf(assets["water_frame_1"]) }

    val waterAnimFps: Float = manifest.tile("WATER")?.get("animFps")?.asFloat ?: 4f

    /** 얼음. 전용 타일이 있어 물을 물들여 쓰지 않는다. */
    val iceTiles: List<TextureRegion> =
        manifest.tileSprites("ICE").map { assets[it] }.ifEmpty { listOf(assets["ice_clean_a"]) }

    // --- 본진 -------------------------------------------------------------

    private val base = node("tiles", "BASE")

    val baseIntact: TextureRegion = assets[base?.get("intact")?.asString ?: "base_intact"]

    /** 파괴 애니메이션. 손상 -> 심한 손상 -> 파괴 순으로 재생한다. */
    val baseDestroyFrames: List<TextureRegion> =
        regions("tiles", "BASE", "destroyFrames").ifEmpty { listOf(baseIntact) }

    val baseDestroyFps: Float = base?.get("destroyFps")?.asFloat ?: 6f

    /** 보호막이 남아 있는 동안의 본진. (계획서 §14 protected) */
    val baseShielded: TextureRegion = assets[base?.get("shielded")?.asString ?: "base_shielded"]

    /** 본진이 위험할 때 번갈아 그린다. */
    val baseWarnFrames: List<TextureRegion> =
        regions("tiles", "BASE", "warnFrames").ifEmpty { listOf(baseIntact) }

    val baseWarnFps: Float = base?.get("warnFps")?.asFloat ?: 4f

    /** 폭발이 끝난 뒤 남는 잔해. */
    val baseWreck: TextureRegion = assets[base?.get("wreck")?.asString ?: "base_rubble"]

    // --- 탱크 -------------------------------------------------------------

    /** 스프라이트 캔버스가 차체보다 넓다. 포신이 밖으로 뻗기 때문이다. */
    val tankSpritePx: Float = node("world", "tankSpritePx")?.asFloat ?: 96f

    private val directionSuffix: List<String> =
        node("tanks", "directions")?.asStringList ?: listOf("up", "right", "down", "left")

    /** [faction]/[type] 별 4방향 그림. Direction.ordinal 로 바로 찾는다. */
    private val tankArt: Map<Pair<Tank.Faction, Tank.Type>, Array<TextureRegion>> =
        Tank.Faction.entries.flatMap { faction ->
            val section = if (faction == Tank.Faction.PLAYER) "player" else "enemy"
            Tank.Type.entries.map { type ->
                val prefix = node("tanks", section, type.name, "prefix")?.asString
                    ?: "${section}_${type.name.lowercase()}"
                (faction to type) to Array(Direction.VALUES.size) { i ->
                    assets["${prefix}_${directionSuffix.getOrElse(i) { "up" }}"]
                }
            }
        }.toMap()

    fun tankSprite(tank: Tank): TextureRegion =
        tankArt.getValue(tank.faction to tank.type)[tank.direction.ordinal]

    /** HUD 에 쓰는 정면 초상. 어떤 탱크를 골랐는지 한눈에 보인다. */
    fun playerPortrait(type: Tank.Type): TextureRegion =
        tankArt.getValue(Tank.Faction.PLAYER to type)[Direction.UP.ordinal]

    /**
     * 탱크 색 팔레트. (계획서 §29)
     *
     * 탱크 그림은 무채색이다. 색이 박힌 그림에 다른 색을 곱하면 탁해지기 때문이다.
     * 여기 있는 색을 그릴 때 곱한다.
     */
    class Palette(val playerColors: IntArray, val playerNames: List<String>, val comColor: Int) {

        val size: Int get() = playerColors.size

        /** [index] 가 음수면 COM 색이다. */
        fun colorOf(index: Int): Int =
            if (index < 0 || playerColors.isEmpty()) comColor
            else playerColors[index % playerColors.size]

        fun nameOf(index: Int): String =
            if (index < 0 || playerNames.isEmpty()) "COM"
            else playerNames[index % playerNames.size]
    }

    val palette: Palette = run {
        val entries = node("tanks", "palette", "players")?.asArray ?: emptyList()
        Palette(
            playerColors = entries.map { parseColor(it["color"]?.asString, 0xFFFFFF) }.toIntArray(),
            playerNames = entries.map { it["name"]?.asString ?: "" },
            comColor = parseColor(node("tanks", "palette", "com", "color")?.asString, 0x9AA3AE),
        )
    }

    /** 플레이어 머리 위 표식. 그 사람이 고른 색으로 물들여 그린다. */
    class SlotMarker(val region: TextureRegion, val sizeRatio: Float, val offsetRatio: Float)

    val slotMarker = SlotMarker(
        region = assets[node("tanks", "slotMarker", "sprite")?.asString ?: "hud_heart_full"],
        sizeRatio = node("tanks", "slotMarker", "sizeRatio")?.asFloat ?: 0.32f,
        offsetRatio = node("tanks", "slotMarker", "offsetRatio")?.asFloat ?: -0.62f,
    )

    // --- 포탄 -------------------------------------------------------------

    private fun directional(prefix: String): Array<TextureRegion> =
        Array(Direction.VALUES.size) { i ->
            assets["${prefix}_${directionSuffix.getOrElse(i) { "up" }}"]
        }

    private val normalShell = directional(
        node("projectiles", "normal", "prefix")?.asString ?: "shell_normal",
    )

    private val piercingShell = directional(
        node("projectiles", "piercing", "prefix")?.asString ?: "shell_piercing",
    )

    private val muzzleFlash = directional(
        node("projectiles", "muzzleFlash", "prefix")?.asString ?: "muzzle_flash",
    )

    fun shellSprite(direction: Direction, piercing: Boolean): TextureRegion =
        (if (piercing) piercingShell else normalShell)[direction.ordinal]

    fun muzzleFlash(direction: Direction): TextureRegion = muzzleFlash[direction.ordinal]

    // --- 특수기 연출 (계획서 §6) ------------------------------------------

    class FrameArt(val frames: List<TextureRegion>, val fps: Float) {
        fun at(timeSeconds: Float): TextureRegion =
            frames[((timeSeconds * fps).toInt().coerceAtLeast(0)) % frames.size]

        /** 0~1 진행도에 맞춰 한 번만 재생한다. */
        fun atProgress(progress: Float): TextureRegion =
            frames[(progress.coerceIn(0f, 1f) * (frames.size - 1)).toInt()]
    }

    private fun frameArt(id: String, fallback: String): FrameArt {
        val names = (node("effects", id, "frames")?.asStringList ?: emptyList())
            .ifEmpty { listOf(fallback) }
        return FrameArt(names.map { assets[it] }, node("effects", id, "fps")?.asFloat ?: 12f)
    }

    /** 방어막. 지속되는 동안 프레임을 돌린다. */
    val shieldFx: FrameArt = frameArt("shield", "shield_2")

    /** 대시. 진행 반대쪽에 잔상을 남긴다. */
    val dashFx: FrameArt = frameArt("dash", "dash_trail_2")

    val baseWarningFx: FrameArt = frameArt("baseWarning", "base_warning_pulse")

    // --- HUD --------------------------------------------------------------

    val heart: TextureRegion = assets[manifest.hud("life") ?: "hud_heart_full"]

    val heartEmpty: TextureRegion = assets[manifest.hud("lifeEmpty") ?: "hud_heart_empty"]

    val skull: TextureRegion = assets[manifest.hud("skull") ?: "hud_enemy_counter"]

    val killIcon: TextureRegion = assets[manifest.hud("kill") ?: "hud_kill_explosion"]

    val panel: TextureRegion = assets[manifest.hud("panel") ?: "hud_status_panel"]

    val deadIcon: TextureRegion = assets[manifest.hud("dead") ?: "hud_player_dead"]

    /** 전투 중 표시하는 접속 상태. (계획서 §44.2) */
    val netOnline: TextureRegion = screen("network", "online", "hud_network_strong")

    val netOffline: TextureRegion = screen("network", "offline", "hud_network_disconnected")

    // --- 조작 UI (계획서 §18) ---------------------------------------------

    private fun control(key: String, fallback: String): TextureRegion =
        assets[node("hud", "controls", key)?.asString ?: fallback]

    val stickBase: TextureRegion = control("joystickBase", "hud_joystick_base")

    val stickKnob: TextureRegion = control("joystickKnob", "hud_joystick_knob")

    val fireButton: TextureRegion = control("fire", "hud_fire_button_normal")

    val fireButtonPressed: TextureRegion = control("firePressed", "hud_fire_button_pressed")

    val specialButton: TextureRegion = control("special", "hud_special_button_normal")

    val specialButtonPressed: TextureRegion =
        control("specialPressed", "hud_special_button_pressed")

    // --- 로비 (계획서 §28) -------------------------------------------------

    private fun lobby(key: String, fallback: String): TextureRegion =
        assets[node("hud", "lobby", key)?.asString ?: fallback]

    val lobbyTitle: TextureRegion = lobby("title", "lobby_title_plate")

    /** 자리 색은 슬롯 번호를 따른다. P1 cyan / P2 orange / P3 lime / P4 violet. */
    val lobbySlots: List<TextureRegion> =
        (node("hud", "lobby", "slotByColor")?.asStringList ?: emptyList())
            .map { assets[it] }
            .ifEmpty { listOf(assets["lobby_player_slot_cyan"]) }

    val lobbyReady: TextureRegion = lobby("ready", "lobby_ready_badge")

    val lobbyWaiting: TextureRegion = lobby("waiting", "lobby_waiting_badge")

    val lobbyLocked: TextureRegion = lobby("locked", "lobby_locked_badge")

    val lobbyHost: TextureRegion = lobby("host", "lobby_host_badge")

    val lobbyDisconnected: TextureRegion = lobby("disconnected", "lobby_disconnected")

    val lobbyStart: TextureRegion = lobby("start", "lobby_primary_button_normal")

    val lobbyWifi: TextureRegion = lobby("wifiStrong", "lobby_wifi_strong")

    val lobbyWifiWeak: TextureRegion = lobby("wifiWeak", "lobby_wifi_weak")

    /** 로비에서 메인 메뉴로 나가는 단추. (계획서 §27) */
    val lobbySecondary: TextureRegion = lobby("secondary", "lobby_secondary_button")

    /** 로비에서 방 설정을 여는 단추. 방장만 쓸모가 있다. (계획서 §44.2) */
    val lobbySettings: TextureRegion = lobby("settings", "lobby_settings_button")

    // --- 메뉴 · 설정 · 결과 화면 ------------------------------------------

    private fun screen(group: String, key: String, fallback: String): TextureRegion =
        assets[node("hud", group, key)?.asString ?: fallback]

    private fun screenList(group: String, key: String, fallback: String): List<TextureRegion> =
        (node("hud", group, key)?.asStringList ?: emptyList())
            .map { assets[it] }
            .ifEmpty { listOf(assets[fallback]) }

    class MenuArt(
        val title: TextureRegion,
        val primary: TextureRegion,
        val secondary: TextureRegion,
        val secondaryPressed: TextureRegion,
        /** 사운드 설정을 여는 단추. 설정판의 스피커 그림을 그대로 쓴다. */
        val sound: TextureRegion,
        val profile: TextureRegion,
        val playerIcon: TextureRegion,
        val netStrong: TextureRegion,
        val netWeak: TextureRegion,
        val netOff: TextureRegion,
        /** 켜짐 · 꺼짐을 알리는 작은 등. 지금은 결과 화면의 재석 표시에 쓴다. */
        val statusOn: TextureRegion,
        val statusOff: TextureRegion,
    )

    val menu = MenuArt(
        title = screen("menu", "title", "menu_title_plate"),
        primary = screen("menu", "primary", "menu_primary_button_normal"),
        secondary = screen("menu", "secondary", "menu_secondary_button_normal"),
        secondaryPressed = screen("menu", "secondaryPressed", "menu_secondary_button_pressed"),
        sound = screen("menu", "sound", "set_speaker_on"),
        profile = screen("menu", "profile", "menu_profile_badge"),
        playerIcon = screen("menu", "playerIcon", "menu_player_icon"),
        netStrong = screen("menu", "netStrong", "menu_network_strong"),
        netWeak = screen("menu", "netWeak", "menu_network_weak"),
        netOff = screen("menu", "netOff", "menu_network_disconnected"),
        statusOn = screen("menu", "statusOn", "menu_status_indicator_active"),
        statusOff = screen("menu", "statusOff", "menu_status_indicator_inactive"),
    )

    class SettingsArt(
        val panel: TextureRegion,
        val segment: TextureRegion,
        val segmentSelected: TextureRegion,
        val toggleOn: TextureRegion,
        val toggleOff: TextureRegion,
        val sliderTrack: TextureRegion,
        val sliderThumb: TextureRegion,
        val dropdown: TextureRegion,
        val dice: TextureRegion,
        val apply: TextureRegion,
        val close: TextureRegion,
        val reset: TextureRegion,
        val bgmIcon: TextureRegion,
        val sfxIcon: TextureRegion,
        val speakerMuted: TextureRegion,
    )

    val settings = SettingsArt(
        panel = screen("settings", "panel", "set_settings_panel"),
        segment = screen("settings", "segment", "set_segment_normal"),
        segmentSelected = screen("settings", "segmentSelected", "set_segment_selected"),
        toggleOn = screen("settings", "toggleOn", "set_toggle_on"),
        toggleOff = screen("settings", "toggleOff", "set_toggle_off"),
        sliderTrack = screen("settings", "sliderTrack", "set_slider_track"),
        sliderThumb = screen("settings", "sliderThumb", "set_slider_thumb"),
        dropdown = screen("settings", "dropdown", "set_dropdown_button"),
        dice = screen("settings", "dice", "set_randomize_dice"),
        apply = screen("settings", "apply", "set_apply_button"),
        close = screen("settings", "close", "set_close_button"),
        reset = screen("settings", "reset", "set_reset_button"),
        bgmIcon = screen("settings", "bgmIcon", "set_bgm_icon"),
        sfxIcon = screen("settings", "sfxIcon", "set_sfx_icon"),
        speakerMuted = screen("settings", "speakerMuted", "set_speaker_muted"),
    )

    class ResultArt(
        val victory: TextureRegion,
        val gameOver: TextureRegion,
        val winnerCard: TextureRegion,
        val medal: TextureRegion,
        val stageClear: TextureRegion,
        val primary: TextureRegion,
        val secondary: TextureRegion,
        val home: TextureRegion,
        val rows: List<TextureRegion>,
        val rankBadges: List<TextureRegion>,
    )

    val result = ResultArt(
        victory = screen("result", "victory", "result_victory_banner"),
        gameOver = screen("result", "gameOver", "result_game_over_banner"),
        winnerCard = screen("result", "winnerCard", "result_winner_card"),
        medal = screen("result", "medal", "result_winner_medal"),
        stageClear = screen("result", "stageClear", "result_stage_clear_card"),
        primary = screen("result", "primary", "result_primary_action_button"),
        secondary = screen("result", "secondary", "result_secondary_action_button"),
        home = screen("result", "home", "result_home_button"),
        rows = screenList("result", "rowByColor", "result_result_row_cyan"),
        rankBadges = screenList("result", "rankBadges", "result_rank_1_badge"),
    )

    private val digits: List<TextureRegion> =
        (0..9).map { assets["${manifest.hudDigitPrefix}$it"] }

    private val chars: Map<Char, TextureRegion> =
        ('A'..'Z').associateWith { assets["${manifest.hudCharPrefix}$it"] }

    /**
     * 글자와 숫자 말고 쓰는 기호. 빗금은 없어서 붙임표로 대신한다.
     *
     * 콜론은 시트에 없어서 같은 화풍으로 직접 그려 얹었다. (tools/build_atlas.py)
     */
    private val symbols: Map<Char, TextureRegion> = buildMap {
        assets.find("ui_minus")?.let { put('-', it) }
        assets.find("ui_plus")?.let { put('+', it) }
        assets.find("ui_percent")?.let { put('%', it) }
        assets.find("ui_colon")?.let { put(':', it) }
    }

    fun digit(value: Int): TextureRegion = digits[value.coerceIn(0, 9)]

    /** 비트맵 폰트 글리프. 지원하지 않는 문자는 null 이라 공백으로 넘어간다. */
    fun glyph(char: Char): TextureRegion? = when (char) {
        in '0'..'9' -> digits[char - '0']
        in 'A'..'Z' -> chars[char]
        '-', '+', '%', ':' -> symbols[char]
        in 'a'..'z' -> chars[char.uppercaseChar()]
        else -> null
    }

    // --- 이펙트 -----------------------------------------------------------

    private val effects: Map<Explosion.Kind, FrameArt> = mapOf(
        Explosion.Kind.TANK to frameArt("tankExplosion", "explosion_4"),
        Explosion.Kind.BULLET_HIT to frameArt("bulletHit", "impact_small"),
        Explosion.Kind.BRICK_BREAK to frameArt("brickBreak", "debris_brick"),
        Explosion.Kind.STEEL_HIT to frameArt("steelHit", "ricochet_steel"),
        Explosion.Kind.SPAWN to frameArt("spawn", "spawn_glow"),
        // 총구 화염은 방향이 있어 프레임 대신 방향으로 고른다. 자리만 채워 둔다.
        Explosion.Kind.MUZZLE to frameArt("bulletHit", "impact_small"),
    )

    fun effectOf(kind: Explosion.Kind): FrameArt = effects.getValue(kind)

    /** 총구 화염. 유일하게 방향이 있는 이펙트다. */
    fun muzzleOf(direction: Direction): TextureRegion = muzzleFlash[direction.ordinal]

    // --- 스테이지 스프라이트 ----------------------------------------------

    /** [names] 를 그대로 해석한다. 없는 이름은 예외로 바로 드러난다. */
    fun resolveAll(names: Array<String>): Array<TextureRegion> =
        Array(names.size) { assets[names[it]] }

    private fun parseColor(text: String?, fallback: Int): Int {
        if (text == null || !text.startsWith("#")) return fallback
        return runCatching { text.substring(1).toLong(16).toInt() }.getOrDefault(fallback)
    }
}
