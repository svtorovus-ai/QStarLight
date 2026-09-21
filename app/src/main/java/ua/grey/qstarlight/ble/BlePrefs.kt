package ua.grey.qstarlight.ble

import android.content.Context
import android.os.Build
import android.util.DisplayMetrics
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import java.util.UUID
import ua.grey.qstarlight.sync.ConfigVersion

class BlePrefs(private val context: Context) {
    private val prefs = context.getSharedPreferences("qstar_prefs", Context.MODE_PRIVATE)

    data class DeviceRef(val mac: String, val name: String)

    enum class Role { HUB, PHONE }
    enum class StartupMode { RESTORE, START_ONLY, FADE_TO_TARGET, SMOOTH_YELLOW_WHITE, OFF }
    enum class StrobeMode { CLASSIC, DOUBLE, TRIPLE, ALTERNATE, DOUBLE_ALTERNATE, YELLOW_WHITE_SWAP }

    fun ensureDefaults(): Unit = synchronized(CONFIG_LOCK) {
        if (!prefs.contains(KEY_DEVICE_ID)) prefs.edit().putString(KEY_DEVICE_ID, UUID.randomUUID().toString()).apply()
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

    fun devices(): List<DeviceRef> = canonicalOrder(
        listOfNotNull(
            device(KEY_MAC_1, KEY_NAME_1),
            device(KEY_MAC_2, KEY_NAME_2)
        ).distinctBy { it.mac }
    )

    fun lampSide(ref: DeviceRef): String = when {
        ref.name.equals(RIGHT_NAME, true) || ref.mac.equals(RIGHT_DEFAULT_MAC, true) -> "Права фара"
        ref.name.equals(LEFT_NAME, true) || ref.mac.equals(LEFT_DEFAULT_MAC, true) -> "Ліва фара"
        else -> "Фара"
    }

    fun lampDisplayName(ref: DeviceRef): String = "${lampSide(ref)} • ${ref.name}"

    private fun canonicalOrder(input: List<DeviceRef>): List<DeviceRef> =
        input.sortedWith(compareBy<DeviceRef> {
            when {
                it.name.equals(LEFT_NAME, true) || it.mac.equals(LEFT_DEFAULT_MAC, true) -> 0
                it.name.equals(RIGHT_NAME, true) || it.mac.equals(RIGHT_DEFAULT_MAC, true) -> 1
                else -> 2
            }
        }.thenBy { it.name })

    private fun device(macKey: String, nameKey: String): DeviceRef? {
        val mac = prefs.getString(macKey, null)?.trim().orEmpty()
        if (mac.isEmpty()) return null
        return DeviceRef(mac, prefs.getString(nameKey, "QStar") ?: "QStar")
    }

    fun setDevices(devices: List<DeviceRef>, touch: Boolean = true): Unit = synchronized(CONFIG_LOCK) {
        val ordered = canonicalOrder(devices)
        val first = ordered.getOrNull(0)
        val second = ordered.getOrNull(1)
        val e = prefs.edit()
            .putString(KEY_MAC_1, first?.mac ?: "")
            .putString(KEY_NAME_1, first?.name)
            .putString(KEY_MAC_2, second?.mac ?: "")
            .putString(KEY_NAME_2, second?.name)
        if (touch) e.putLong(KEY_CONFIG_REVISION, nextRevision()).putString(KEY_CONFIG_ORIGIN, deviceId)
        e.apply()
    }

    fun updateMacByName(name: String, newMac: String): Unit = synchronized(CONFIG_LOCK) {
        val macKey = when (name) {
            prefs.getString(KEY_NAME_1, null) -> KEY_MAC_1
            prefs.getString(KEY_NAME_2, null) -> KEY_MAC_2
            else -> return@synchronized
        }
        if (prefs.getString(macKey, null).equals(newMac, ignoreCase = true)) return@synchronized
        prefs.edit()
            .putString(macKey, newMac)
            .putLong(KEY_CONFIG_REVISION, nextRevision())
            .putString(KEY_CONFIG_ORIGIN, deviceId)
            .apply()
    }

    fun password(mac: String): String = prefs.getString("pwd_$mac", "1234") ?: "1234"
    fun setPassword(mac: String, value: String, touch: Boolean = true): Unit = synchronized(CONFIG_LOCK) {
        val e = prefs.edit().putString("pwd_$mac", value)
        if (touch) e.putLong(KEY_CONFIG_REVISION, nextRevision()).putString(KEY_CONFIG_ORIGIN, deviceId)
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

    /** Runtime-only state used by the activity and widget to show an active strobe. */
    var strobeActive: Boolean
        get() = prefs.getBoolean("strobe_active", false)
        set(value) {
            val edit = prefs.edit().putBoolean("strobe_active", value)
            if (value) {
                if (prefs.getLong(KEY_STROBE_STARTED_AT, 0L) <= 0L) {
                    edit.putLong(KEY_STROBE_STARTED_AT, System.currentTimeMillis())
                }
            } else {
                edit.putLong(KEY_STROBE_STARTED_AT, 0L)
            }
            edit.apply()
        }

    val strobeStartedAt: Long
        get() = prefs.getLong(KEY_STROBE_STARTED_AT, 0L)

    fun updateLight(white: Int? = null, brightness: Int? = null, power: Boolean? = null): Unit = synchronized(CONFIG_LOCK) {
        val editor = prefs.edit()
        white?.let { editor.putInt("white", it.coerceIn(0, 100)) }
        brightness?.let { editor.putInt("brightness", it.coerceIn(5, 100)) }
        power?.let { editor.putBoolean("power", it) }
        editor.putLong(KEY_CONFIG_REVISION, nextRevision()).putString(KEY_CONFIG_ORIGIN, deviceId).apply()
    }

    // Device-local behavior.
    var autoBoot: Boolean
        get() = prefs.getBoolean("auto_boot", true)
        set(value) { prefs.edit().putBoolean("auto_boot", value).apply() }

    /** Shared greeting profile gate. It is consumed once per BLE service restart. */
    var welcomeOnConnect: Boolean
        get() = prefs.getBoolean(KEY_WELCOME_ON_CONNECT, true)
        set(value) = putSyncBoolean(KEY_WELCOME_ON_CONNECT, value)

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

    /** Last successful phone ↔ HUB session, kept after disconnect for diagnostics. */
    var lastHubConnectionAt: Long
        get() = prefs.getLong(KEY_LAST_HUB_CONNECTION_AT, 0L)
        set(value) { prefs.edit().putLong(KEY_LAST_HUB_CONNECTION_AT, value).apply() }

    fun lastLampConnectionAt(mac: String): Long =
        prefs.getLong(KEY_LAST_LAMP_CONNECTION_PREFIX + mac.uppercase(), 0L)

    fun markHubConnected() {
        lastHubConnectionAt = System.currentTimeMillis()
    }

    fun markLampConnected(mac: String) {
        prefs.edit().putLong(KEY_LAST_LAMP_CONNECTION_PREFIX + mac.uppercase(), System.currentTimeMillis()).apply()
    }

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

    enum class RuntimeLinkState { OFFLINE, CONNECTING, CONNECTED }

    var hubRuntimeState: RuntimeLinkState
        get() = runCatching {
            RuntimeLinkState.valueOf(prefs.getString(KEY_RUNTIME_HUB_STATE, RuntimeLinkState.OFFLINE.name)!!)
        }.getOrDefault(RuntimeLinkState.OFFLINE)
        set(value) { prefs.edit().putString(KEY_RUNTIME_HUB_STATE, value.name).apply() }

    fun lampRuntimeState(mac: String): RuntimeLinkState {
        val local = readRuntimeState(KEY_RUNTIME_LAMP_PREFIX + mac.uppercase())
        val direct = directLampRuntimeState(mac)
        val remote = remoteLampRuntimeState(mac)

        // On a phone there are two different owners of the same lamps.  Do
        // not merge their states with OR: a stale direct CONNECTED flag must
        // not make a HUB-owned lamp look alive (and vice versa).
        if (role() == Role.PHONE) {
            return if (hubRuntimeState == RuntimeLinkState.CONNECTED) remote else direct
        }
        // In HUB mode only this device's GATT callbacks are authoritative.
        // Phone-side fallback telemetry must never turn a dead local link green.
        return local
    }

    fun setLampRuntimeState(mac: String, state: RuntimeLinkState) {
        prefs.edit().putString(KEY_RUNTIME_LAMP_PREFIX + mac.uppercase(), state.name).apply()
    }

    fun remoteLampRuntimeState(mac: String): RuntimeLinkState =
        readRuntimeState(KEY_RUNTIME_REMOTE_LAMP_PREFIX + mac.uppercase())

    fun setRemoteLampRuntimeState(mac: String, state: RuntimeLinkState) {
        prefs.edit().putString(KEY_RUNTIME_REMOTE_LAMP_PREFIX + mac.uppercase(), state.name).apply()
    }

    fun clearRemoteLampRuntimeStates() {
        val e = prefs.edit()
        devices().forEach { e.putString(KEY_RUNTIME_REMOTE_LAMP_PREFIX + it.mac.uppercase(), RuntimeLinkState.OFFLINE.name) }
        e.apply()
    }

    private fun readRuntimeState(key: String): RuntimeLinkState = runCatching {
        RuntimeLinkState.valueOf(prefs.getString(key, RuntimeLinkState.OFFLINE.name)!!)
    }.getOrDefault(RuntimeLinkState.OFFLINE)

    fun directLampRuntimeState(mac: String): RuntimeLinkState = runCatching {
        RuntimeLinkState.valueOf(
            prefs.getString(KEY_RUNTIME_DIRECT_LAMP_PREFIX + mac.uppercase(), RuntimeLinkState.OFFLINE.name)!!
        )
    }.getOrDefault(RuntimeLinkState.OFFLINE)

    fun setDirectLampRuntimeState(mac: String, state: RuntimeLinkState) {
        prefs.edit().putString(KEY_RUNTIME_DIRECT_LAMP_PREFIX + mac.uppercase(), state.name).apply()
    }

    fun resetRuntimeLinkStates() {
        val e = prefs.edit().putString(KEY_RUNTIME_HUB_STATE, RuntimeLinkState.OFFLINE.name)
        devices().forEach {
            e.putString(KEY_RUNTIME_LAMP_PREFIX + it.mac.uppercase(), RuntimeLinkState.OFFLINE.name)
            e.putString(KEY_RUNTIME_DIRECT_LAMP_PREFIX + it.mac.uppercase(), RuntimeLinkState.OFFLINE.name)
            e.putString(KEY_RUNTIME_REMOTE_LAMP_PREFIX + it.mac.uppercase(), RuntimeLinkState.OFFLINE.name)
        }
        e.apply()
    }

    fun anyDirectLampConnected(): Boolean =
        devices().any { directLampRuntimeState(it.mac) == RuntimeLinkState.CONNECTED }

    fun anyDirectLampActive(): Boolean =
        devices().any { directLampRuntimeState(it.mac) != RuntimeLinkState.OFFLINE }

    /** True when commands from this phone must go to its own GATT links. */
    fun directControlActive(): Boolean =
        role() == Role.PHONE && hubRuntimeState != RuntimeLinkState.CONNECTED && forceDirect

    fun anyPhoneLinkConnected(): Boolean =
        hubRuntimeState == RuntimeLinkState.CONNECTED || anyDirectLampConnected()

    val phoneOfflineGraceUntil: Long
        get() = prefs.getLong(KEY_PHONE_OFFLINE_GRACE_UNTIL, 0L)

    fun startPhoneOfflineGrace(now: Long = System.currentTimeMillis()): Long {
        if (anyPhoneLinkConnected()) {
            clearPhoneOfflineGrace()
            return 0L
        }
        val current = phoneOfflineGraceUntil
        if (current > now) return current
        val until = now + PHONE_OFFLINE_GRACE_MS
        prefs.edit().putLong(KEY_PHONE_OFFLINE_GRACE_UNTIL, until).apply()
        return until
    }

    fun phoneOfflineGraceActive(now: Long = System.currentTimeMillis()): Boolean =
        !anyPhoneLinkConnected() && phoneOfflineGraceUntil > now

    fun clearPhoneOfflineGrace() {
        prefs.edit().remove(KEY_PHONE_OFFLINE_GRACE_UNTIL).apply()
    }

    fun markPhoneLinkAvailable() {
        clearPhoneOfflineGrace()
    }

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

    private val deviceId: String
        get() = prefs.getString(KEY_DEVICE_ID, "") ?: ""

    val configVersion: ConfigVersion
        get() = synchronized(CONFIG_LOCK) { ConfigVersion(configRevision, prefs.getString(KEY_CONFIG_ORIGIN, "") ?: "") }

    fun touchConfig(): Long = synchronized(CONFIG_LOCK) {
        val rev = nextRevision()
        prefs.edit().putLong(KEY_CONFIG_REVISION, rev).putString(KEY_CONFIG_ORIGIN, deviceId).apply()
        return@synchronized rev
    }

    fun syncConfigJson(): JSONObject = synchronized(CONFIG_LOCK) {
        val devicesJson = JSONArray()
        devices().forEach { d ->
            devicesJson.put(
                JSONObject()
                    .put("mac", d.mac)
                    .put("name", d.name)
                    .put("password", password(d.mac))
            )
        }
        return@synchronized JSONObject()
            .put("revision", configRevision)
            .put("origin", configVersion.origin)
            .put("power", power)
            .put("white", white)
            .put("brightness", brightness)
            .put("welcomeOnConnect", welcomeOnConnect)
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
    fun applySyncConfig(json: JSONObject, force: Boolean = false): Boolean = synchronized(CONFIG_LOCK) {
        val incomingRevision = json.optLong("revision", 0L)
        if (incomingRevision <= 0L) return@synchronized false
        val incomingVersion = ConfigVersion(incomingRevision, json.optString("origin", ""))
        if (!force && incomingVersion <= configVersion) return@synchronized false

        val e = prefs.edit()
            .putBoolean("power", json.optBoolean("power", power))
            .putInt("white", json.optInt("white", white).coerceIn(0, 100))
            .putInt("brightness", json.optInt("brightness", brightness).coerceIn(5, 100))
            .putBoolean(KEY_WELCOME_ON_CONNECT, json.optBoolean("welcomeOnConnect", welcomeOnConnect))
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
            .putString(KEY_CONFIG_ORIGIN, incomingVersion.origin)

        val devices = json.optJSONArray("devices")
        if (devices != null) {
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
            e.putString(KEY_MAC_1, first?.mac ?: "")
                .putString(KEY_NAME_1, first?.name)
                .putString(KEY_MAC_2, second?.mac ?: "")
                .putString(KEY_NAME_2, second?.name)
            passwords.forEach { (mac, password) -> e.putString("pwd_$mac", password) }
        }
        e.apply()
        return@synchronized true
    }

    private fun putSyncInt(key: String, value: Int): Unit = synchronized(CONFIG_LOCK) {
        prefs.edit().putInt(key, value).putLong(KEY_CONFIG_REVISION, nextRevision()).putString(KEY_CONFIG_ORIGIN, deviceId).apply()
    }

    private fun putSyncString(key: String, value: String): Unit = synchronized(CONFIG_LOCK) {
        prefs.edit().putString(key, value).putLong(KEY_CONFIG_REVISION, nextRevision()).putString(KEY_CONFIG_ORIGIN, deviceId).apply()
    }

    private fun putSyncBoolean(key: String, value: Boolean): Unit = synchronized(CONFIG_LOCK) {
        prefs.edit().putBoolean(key, value).putLong(KEY_CONFIG_REVISION, nextRevision()).putString(KEY_CONFIG_ORIGIN, deviceId).apply()
    }

    private fun nextRevision(): Long = maxOf(System.currentTimeMillis(), configRevision + 1)

    private fun generatePin(): String {
        val random = SecureRandom().nextInt(900_000) + 100_000
        return random.toString()
    }

    companion object {
        private val CONFIG_LOCK = Any()
        private const val KEY_DEVICE_ID = "sync_device_id"
        private const val KEY_CONFIG_ORIGIN = "sync_config_origin"
        private const val KEY_MAC_1 = "mac1"
        private const val KEY_NAME_1 = "name1"
        private const val KEY_MAC_2 = "mac2"
        private const val KEY_NAME_2 = "name2"
        private const val KEY_ROLE = "role"
        private const val KEY_HUB_PIN = "hub_pin"
        private const val KEY_REMOTE_PIN = "remote_pin"
        private const val KEY_WELCOME_ON_CONNECT = "welcome_on_connect"
        private const val KEY_MANUAL_HOST = "manual_hub_host"
        private const val KEY_LAST_HOST = "last_hub_host"
        private const val KEY_LAST_HUB_CONNECTION_AT = "last_hub_connection_at"
        private const val KEY_LAST_LAMP_CONNECTION_PREFIX = "last_lamp_connection_"
        private const val KEY_DIRECT_FALLBACK = "direct_fallback"
        private const val KEY_FORCE_DIRECT = "force_direct"
        private const val KEY_REMOTE_KEEPALIVE = "remote_keepalive"
        private const val KEY_AUTO_PUSH_UPDATES = "auto_push_updates"
        private const val KEY_SILENT_ROOT_INSTALL = "silent_root_install"
        private const val KEY_PHONE_OFFLINE_GRACE_UNTIL = "phone_offline_grace_until"
        private const val KEY_RUNTIME_HUB_STATE = "runtime_hub_state"
        private const val KEY_RUNTIME_LAMP_PREFIX = "runtime_lamp_"
        private const val KEY_RUNTIME_DIRECT_LAMP_PREFIX = "runtime_direct_lamp_"
        private const val KEY_RUNTIME_REMOTE_LAMP_PREFIX = "runtime_remote_lamp_"

        const val RIGHT_NAME = "QStar~D35D"
        const val LEFT_NAME = "QStar~F072"
        const val RIGHT_DEFAULT_MAC = "C2:15:11:00:D3:5D"
        const val LEFT_DEFAULT_MAC = "F2:16:11:00:F0:72"
        const val PHONE_OFFLINE_GRACE_MS = 30L * 60L * 1000L

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
        private const val KEY_STROBE_STARTED_AT = "strobe_started_at"
    }
}
