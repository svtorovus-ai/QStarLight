package ua.grey.qstarlight.ble

import android.content.Context
import android.os.Build
import android.util.DisplayMetrics
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom

class BlePrefs(private val context: Context) {
    private val prefs = context.getSharedPreferences("qstar_prefs", Context.MODE_PRIVATE)

    data class DeviceRef(val mac: String, val name: String)

    enum class Role { HUB, PHONE }
    enum class StartupMode { RESTORE, START_ONLY, FADE_TO_TARGET, OFF }
    enum class StrobeMode { CLASSIC, DOUBLE, TRIPLE, ALTERNATE, DOUBLE_ALTERNATE, YELLOW_WHITE_SWAP }

    fun ensureDefaults() {
        if (!prefs.contains(KEY_MAC_1)) {
            prefs.edit()
                .putString(KEY_MAC_1, "C2:15:11:00:D3:5D")
                .putString(KEY_NAME_1, "QStar~D35D")
                .putString(KEY_MAC_2, "F2:16:11:00:F0:72")
                .putString(KEY_NAME_2, "QStar~F072")
                .apply()
        }
        if (!prefs.contains(KEY_HUB_PIN)) {
            prefs.edit().putString(KEY_HUB_PIN, generatePin()).apply()
        }
        if (!prefs.contains(KEY_CONFIG_REVISION)) {
            prefs.edit().putLong(KEY_CONFIG_REVISION, 1L).apply()
        }
    }

    fun devices(): List<DeviceRef> = listOfNotNull(
        device(KEY_MAC_1, KEY_NAME_1),
        device(KEY_MAC_2, KEY_NAME_2)
    ).distinctBy { it.mac }

    private fun device(macKey: String, nameKey: String): DeviceRef? {
        val mac = prefs.getString(macKey, null)?.trim().orEmpty()
        if (mac.isEmpty()) return null
        return DeviceRef(mac, prefs.getString(nameKey, "QStar") ?: "QStar")
    }

    fun setDevices(devices: List<DeviceRef>, touch: Boolean = true) {
        val first = devices.getOrNull(0)
        val second = devices.getOrNull(1)
        val e = prefs.edit()
            .putString(KEY_MAC_1, first?.mac)
            .putString(KEY_NAME_1, first?.name)
            .putString(KEY_MAC_2, second?.mac)
            .putString(KEY_NAME_2, second?.name)
        if (touch) e.putLong(KEY_CONFIG_REVISION, nextRevision())
        e.apply()
    }

    fun updateMacByName(name: String, newMac: String) {
        val macKey = when (name) {
            prefs.getString(KEY_NAME_1, null) -> KEY_MAC_1
            prefs.getString(KEY_NAME_2, null) -> KEY_MAC_2
            else -> return
        }
        if (prefs.getString(macKey, null).equals(newMac, ignoreCase = true)) return
        prefs.edit()
            .putString(macKey, newMac)
            .putLong(KEY_CONFIG_REVISION, nextRevision())
            .apply()
    }

    fun password(mac: String): String = prefs.getString("pwd_$mac", "1234") ?: "1234"
    fun setPassword(mac: String, value: String, touch: Boolean = true) {
        val e = prefs.edit().putString("pwd_$mac", value)
        if (touch) e.putLong(KEY_CONFIG_REVISION, nextRevision())
        e.apply()
    }

    // Current live lamp state. These are intentionally not part of config revision tracking.
    var white: Int
        get() = prefs.getInt("white", 100)
        set(value) { prefs.edit().putInt("white", value.coerceIn(0, 100)).apply() }

    var brightness: Int
        get() = prefs.getInt("brightness", 100)
        set(value) { prefs.edit().putInt("brightness", value.coerceIn(5, 100)).apply() }

    var power: Boolean
        get() = prefs.getBoolean("power", true)
        set(value) { prefs.edit().putBoolean("power", value).apply() }

    // Device-local behavior.
    var autoBoot: Boolean
        get() = prefs.getBoolean("auto_boot", true)
        set(value) { prefs.edit().putBoolean("auto_boot", value).apply() }

    var keepConnected: Boolean
        get() = prefs.getBoolean("keep_connected", true)
        set(value) { prefs.edit().putBoolean("keep_connected", value).apply() }

    var roleOverride: String
        get() = prefs.getString(KEY_ROLE, "auto") ?: "auto"
        set(value) { prefs.edit().putString(KEY_ROLE, value).apply() }

    fun role(): Role {
        return when (roleOverride) {
            "hub" -> Role.HUB
            "phone" -> Role.PHONE
            else -> inferRole()
        }
    }

    private fun inferRole(): Role {
        return if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.R) Role.HUB else Role.PHONE
    }

    val hubPin: String
        get() {
            ensureDefaults()
            return prefs.getString(KEY_HUB_PIN, "000000") ?: "000000"
        }

    fun regenerateHubPin(): String {
        val pin = generatePin()
        prefs.edit().putString(KEY_HUB_PIN, pin).apply()
        return pin
    }

    var remotePin: String
        get() = prefs.getString(KEY_REMOTE_PIN, "") ?: ""
        set(value) { prefs.edit().putString(KEY_REMOTE_PIN, value.filter(Char::isDigit).take(6)).apply() }

    var manualHubHost: String
        get() = prefs.getString(KEY_MANUAL_HOST, "") ?: ""
        set(value) { prefs.edit().putString(KEY_MANUAL_HOST, value.trim()).apply() }

    var lastHubHost: String
        get() = prefs.getString(KEY_LAST_HOST, "") ?: ""
        set(value) { prefs.edit().putString(KEY_LAST_HOST, value.trim()).apply() }

    var directFallback: Boolean
        get() = prefs.getBoolean(KEY_DIRECT_FALLBACK, false)
        set(value) { prefs.edit().putBoolean(KEY_DIRECT_FALLBACK, value).apply() }

    var forceDirect: Boolean
        get() = prefs.getBoolean(KEY_FORCE_DIRECT, false)
        set(value) { prefs.edit().putBoolean(KEY_FORCE_DIRECT, value).apply() }

    var remoteKeepAlive: Boolean
        get() = prefs.getBoolean(KEY_REMOTE_KEEPALIVE, true)
        set(value) { prefs.edit().putBoolean(KEY_REMOTE_KEEPALIVE, value).apply() }

    var autoPushUpdates: Boolean
        get() = prefs.getBoolean(KEY_AUTO_PUSH_UPDATES, true)
        set(value) { prefs.edit().putBoolean(KEY_AUTO_PUSH_UPDATES, value).apply() }

    var silentRootInstall: Boolean
        get() = prefs.getBoolean(KEY_SILENT_ROOT_INSTALL, false)
        set(value) { prefs.edit().putBoolean(KEY_SILENT_ROOT_INSTALL, value).apply() }

    // Cross-device synchronized startup configuration.
    var startupMode: StartupMode
        get() = runCatching { StartupMode.valueOf(prefs.getString(KEY_STARTUP_MODE, StartupMode.FADE_TO_TARGET.name)!!) }
            .getOrDefault(StartupMode.FADE_TO_TARGET)
        set(value) = putSyncString(KEY_STARTUP_MODE, value.name)

    var startWhite: Int
        get() = prefs.getInt(KEY_START_WHITE, 0)
        set(value) = putSyncInt(KEY_START_WHITE, value.coerceIn(0, 100))

    var targetWhite: Int
        get() = prefs.getInt(KEY_TARGET_WHITE, 100)
        set(value) = putSyncInt(KEY_TARGET_WHITE, value.coerceIn(0, 100))

    var startBrightness: Int
        get() = prefs.getInt(KEY_START_BRIGHTNESS, 100)
        set(value) = putSyncInt(KEY_START_BRIGHTNESS, value.coerceIn(5, 100))

    var fadeDurationMs: Int
        get() = prefs.getInt(KEY_FADE_DURATION, 1800)
        set(value) = putSyncInt(KEY_FADE_DURATION, value.coerceIn(0, 10_000))

    var fadeSteps: Int
        get() = prefs.getInt(KEY_FADE_STEPS, 24)
        set(value) = putSyncInt(KEY_FADE_STEPS, value.coerceIn(2, 80))

    // Cross-device synchronized strobe profile.
    var strobeMode: StrobeMode
        get() = runCatching { StrobeMode.valueOf(prefs.getString(KEY_STROBE_MODE, StrobeMode.CLASSIC.name)!!) }
            .getOrDefault(StrobeMode.CLASSIC)
        set(value) = putSyncString(KEY_STROBE_MODE, value.name)

    var strobeWhite: Int
        get() = prefs.getInt(KEY_STROBE_WHITE, 100)
        set(value) = putSyncInt(KEY_STROBE_WHITE, value.coerceIn(0, 100))

    var strobeBrightness: Int
        get() = prefs.getInt(KEY_STROBE_BRIGHTNESS, 100)
        set(value) = putSyncInt(KEY_STROBE_BRIGHTNESS, value.coerceIn(5, 100))

    var strobeOnMs: Int
        get() = prefs.getInt(KEY_STROBE_ON, 120)
        set(value) = putSyncInt(KEY_STROBE_ON, value.coerceIn(40, 1000))

    var strobeOffMs: Int
        get() = prefs.getInt(KEY_STROBE_OFF, 110)
        set(value) = putSyncInt(KEY_STROBE_OFF, value.coerceIn(40, 1000))

    var strobePauseMs: Int
        get() = prefs.getInt(KEY_STROBE_PAUSE, 420)
        set(value) = putSyncInt(KEY_STROBE_PAUSE, value.coerceIn(100, 2000))

    val configRevision: Long
        get() = prefs.getLong(KEY_CONFIG_REVISION, 1L)

    fun touchConfig(): Long {
        val rev = nextRevision()
        prefs.edit().putLong(KEY_CONFIG_REVISION, rev).apply()
        return rev
    }

    fun syncConfigJson(): JSONObject {
        val devicesJson = JSONArray()
        devices().forEach { d ->
            devicesJson.put(
                JSONObject()
                    .put("mac", d.mac)
                    .put("name", d.name)
                    .put("password", password(d.mac))
            )
        }
        return JSONObject()
            .put("revision", configRevision)
            .put("power", power)
            .put("white", white)
            .put("brightness", brightness)
            .put("startupMode", startupMode.name)
            .put("startWhite", startWhite)
            .put("targetWhite", targetWhite)
            .put("startBrightness", startBrightness)
            .put("fadeDurationMs", fadeDurationMs)
            .put("fadeSteps", fadeSteps)
            .put("strobeMode", strobeMode.name)
            .put("strobeWhite", strobeWhite)
            .put("strobeBrightness", strobeBrightness)
            .put("strobeOnMs", strobeOnMs)
            .put("strobeOffMs", strobeOffMs)
            .put("strobePauseMs", strobePauseMs)
            .put("devices", devicesJson)
    }

    /** Applies only if the incoming synchronized configuration is newer. */
    fun applySyncConfig(json: JSONObject, force: Boolean = false): Boolean {
        val incomingRevision = json.optLong("revision", 0L)
        if (incomingRevision <= 0L) return false
        if (!force && incomingRevision <= configRevision) return false

        val e = prefs.edit()
            .putBoolean("power", json.optBoolean("power", power))
            .putInt("white", json.optInt("white", white).coerceIn(0, 100))
            .putInt("brightness", json.optInt("brightness", brightness).coerceIn(5, 100))
            .putString(KEY_STARTUP_MODE, json.optString("startupMode", startupMode.name))
            .putInt(KEY_START_WHITE, json.optInt("startWhite", startWhite).coerceIn(0, 100))
            .putInt(KEY_TARGET_WHITE, json.optInt("targetWhite", targetWhite).coerceIn(0, 100))
            .putInt(KEY_START_BRIGHTNESS, json.optInt("startBrightness", startBrightness).coerceIn(5, 100))
            .putInt(KEY_FADE_DURATION, json.optInt("fadeDurationMs", fadeDurationMs).coerceIn(0, 10_000))
            .putInt(KEY_FADE_STEPS, json.optInt("fadeSteps", fadeSteps).coerceIn(2, 80))
            .putString(KEY_STROBE_MODE, json.optString("strobeMode", strobeMode.name))
            .putInt(KEY_STROBE_WHITE, json.optInt("strobeWhite", strobeWhite).coerceIn(0, 100))
            .putInt(KEY_STROBE_BRIGHTNESS, json.optInt("strobeBrightness", strobeBrightness).coerceIn(5, 100))
            .putInt(KEY_STROBE_ON, json.optInt("strobeOnMs", strobeOnMs).coerceIn(40, 1000))
            .putInt(KEY_STROBE_OFF, json.optInt("strobeOffMs", strobeOffMs).coerceIn(40, 1000))
            .putInt(KEY_STROBE_PAUSE, json.optInt("strobePauseMs", strobePauseMs).coerceIn(100, 2000))
            .putLong(KEY_CONFIG_REVISION, incomingRevision)

        val devices = json.optJSONArray("devices")
        if (devices != null && devices.length() > 0) {
            val refs = mutableListOf<DeviceRef>()
            val passwords = mutableMapOf<String, String>()
            for (i in 0 until minOf(2, devices.length())) {
                val obj = devices.optJSONObject(i) ?: continue
                val mac = obj.optString("mac").trim()
                if (mac.isEmpty()) continue
                val name = obj.optString("name", "QStar")
                refs += DeviceRef(mac, name)
                val password = obj.optString("password", "1234")
                if (password.length == 4 && password.all(Char::isDigit)) passwords[mac] = password
            }
            val first = refs.getOrNull(0)
            val second = refs.getOrNull(1)
            e.putString(KEY_MAC_1, first?.mac)
                .putString(KEY_NAME_1, first?.name)
                .putString(KEY_MAC_2, second?.mac)
                .putString(KEY_NAME_2, second?.name)
            passwords.forEach { (mac, password) -> e.putString("pwd_$mac", password) }
        }
        e.apply()
        return true
    }

    private fun putSyncInt(key: String, value: Int) {
        prefs.edit().putInt(key, value).putLong(KEY_CONFIG_REVISION, nextRevision()).apply()
    }

    private fun putSyncString(key: String, value: String) {
        prefs.edit().putString(key, value).putLong(KEY_CONFIG_REVISION, nextRevision()).apply()
    }

    private fun nextRevision(): Long = maxOf(System.currentTimeMillis(), configRevision + 1)

    private fun generatePin(): String {
        val random = SecureRandom().nextInt(900_000) + 100_000
        return random.toString()
    }

    companion object {
        private const val KEY_MAC_1 = "mac1"
        private const val KEY_NAME_1 = "name1"
        private const val KEY_MAC_2 = "mac2"
        private const val KEY_NAME_2 = "name2"
        private const val KEY_ROLE = "role"
        private const val KEY_HUB_PIN = "hub_pin"
        private const val KEY_REMOTE_PIN = "remote_pin"
        private const val KEY_MANUAL_HOST = "manual_hub_host"
        private const val KEY_LAST_HOST = "last_hub_host"
        private const val KEY_DIRECT_FALLBACK = "direct_fallback"
        private const val KEY_FORCE_DIRECT = "force_direct"
        private const val KEY_REMOTE_KEEPALIVE = "remote_keepalive"
        private const val KEY_AUTO_PUSH_UPDATES = "auto_push_updates"
        private const val KEY_SILENT_ROOT_INSTALL = "silent_root_install"

        private const val KEY_CONFIG_REVISION = "sync_config_revision"
        private const val KEY_STARTUP_MODE = "startup_mode"
        private const val KEY_START_WHITE = "start_white"
        private const val KEY_TARGET_WHITE = "target_white"
        private const val KEY_START_BRIGHTNESS = "start_brightness"
        private const val KEY_FADE_DURATION = "fade_duration_ms"
        private const val KEY_FADE_STEPS = "fade_steps"
        private const val KEY_STROBE_MODE = "strobe_mode"
        private const val KEY_STROBE_WHITE = "strobe_white"
        private const val KEY_STROBE_BRIGHTNESS = "strobe_brightness"
        private const val KEY_STROBE_ON = "strobe_on_ms"
        private const val KEY_STROBE_OFF = "strobe_off_ms"
        private const val KEY_STROBE_PAUSE = "strobe_pause_ms"
    }
}
