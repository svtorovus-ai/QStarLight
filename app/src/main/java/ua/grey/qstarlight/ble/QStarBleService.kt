package ua.grey.qstarlight.ble

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import ua.grey.qstarlight.diagnostics.DiagnosticLog
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject
import ua.grey.qstarlight.MainActivity
import ua.grey.qstarlight.R
import ua.grey.qstarlight.control.ControlActionReceiver
import ua.grey.qstarlight.control.ControlDispatcher
import ua.grey.qstarlight.remote.HubTransport
import ua.grey.qstarlight.remote.RemoteLinkService
import ua.grey.qstarlight.widget.QStarWidgetProvider
import java.util.ArrayDeque
import java.util.LinkedHashMap

@SuppressLint("MissingPermission")
class QStarBleService : Service(), LampConnection.Listener, HubTransport.Listener {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var prefs: BlePrefs
    private lateinit var adapter: BluetoothAdapter

    private val connections = LinkedHashMap<String, LampConnection>()
    private val discovered = LinkedHashMap<String, BlePrefs.DeviceRef>()
    private val connectPlan = ArrayDeque<BlePrefs.DeviceRef>()
    private var connectingMac: String? = null
    private var interactive = false
    private var oneShot = false
    private var scanActive = false
    private var modernScanActive = false
    private var legacyScanActive = false
    private var scanDoneCallback: (() -> Unit)? = null
    private var scanStopRunnable: Runnable? = null
    private var reconnectScheduled = false
    private var reconnectRounds = 0
    private val readyMacs = linkedSetOf<String>()
    private val lampPowerState = LinkedHashMap<String, Boolean>()
    private var pairWasReady = false
    private var safetyArmedAtElapsed = 0L
    private var safetyFallbackActive = false
    private var safetyWriteInFlight = false
    private var bootPending = false
    private var welcomeStateAttempts = 0
    private var welcomeCheckScheduled = false
    private var welcomeInProgress = false
    // If the lamps are already on when this service first sees both of them,
    // keep the one-shot welcome armed until a real both-off state is observed.
    private var welcomeWaitingForOff = false
    private var latestCct: Pair<Int, Int>? = null
    private var sendingCct = false
    private var rssiLoop = false
    private var hubTransport: HubTransport? = null
    private var remoteTakeover = false
    // A control command may start this service.  It must not be mistaken for
    // a new connection and be swallowed by the one-shot welcome gate.
    // Connection/boot actions arm the greeting explicitly.
    private var startupPending = false
    private var phoneStopRunnable: Runnable? = null

    private var strobeActive = false
    private var strobeGeneration = 0L

    override fun onCreate() {
        super.onCreate()
        prefs = BlePrefs(this).also { it.ensureDefaults() }
        adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        createNotificationChannel()
        if (prefs.role() == BlePrefs.Role.HUB) {
            hubTransport = HubTransport(this, prefs, this)
        } else {
            reconcilePhoneLifetime()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        DiagnosticLog.write("BLE SERVICE", "action=${intent?.action} role=${prefs.role()} ready=${readyMacs.size}/${prefs.devices().size}")
        if (intent == null) {
            if (prefs.role() == BlePrefs.Role.PHONE &&
                !prefs.anyPhoneLinkConnected() &&
                !prefs.phoneOfflineGraceActive()) {
                stopSelf()
                return START_NOT_STICKY
            }
            interactive = true
            oneShot = false
            startForegroundSafe(if (prefs.role() == BlePrefs.Role.HUB) "Магнітола • QStar hub" else "Прямий BLE • QStar")
            if (prefs.role() == BlePrefs.Role.HUB) ensureHubTransport() else reconcilePhoneLifetime()
            if (!remoteTakeover) ensureConnections()
            return if (prefs.role() == BlePrefs.Role.HUB) START_STICKY else START_NOT_STICKY
        }

        if (prefs.role() == BlePrefs.Role.PHONE && intent.action != ACTION_RELEASE) {
            if (!prefs.anyPhoneLinkConnected() && !prefs.phoneOfflineGraceActive()) prefs.startPhoneOfflineGrace()
            reconcilePhoneLifetime()
        }
        // Try the relay for every control action.  The in-memory
        // remoteTakeover flag is not durable across service recreation, while
        // HubTransport still knows the connected phone owner.  If no takeover
        // exists, relayHeadUnitCommand returns false and normal HUB BLE
        // handling continues.
        if (prefs.role() == BlePrefs.Role.HUB && relayHeadUnitCommand(intent)) {
            return if (interactive && !oneShot) START_STICKY else START_NOT_STICKY
        }
        when (intent.action) {
            ACTION_SCAN -> {
                startForegroundSafe("Scanning QStar")
                startScan(8_000) { if (!remoteTakeover) ensureConnections() }
            }
            ACTION_HUB_START -> {
                interactive = true
                oneShot = false
                armWelcomeCycle("hub_start")
                startForegroundSafe("Магнітола • QStar hub")
                ensureHubTransport()
                if (!remoteTakeover) ensureConnections()
            }
            ACTION_HUB_WAKE -> {
                if (prefs.role() != BlePrefs.Role.HUB) return START_NOT_STICKY
                interactive = true
                oneShot = false
                startForegroundSafe("Магнітола • відновлення QStar hub")
                ensureHubTransport()
                // Quick-sleep commonly leaves a stale BluetoothGatt object that
                // still looks connected.  Recreate the links deterministically.
                disconnectAll()
                handler.postDelayed({ ensureConnections() }, 450)
            }
            ACTION_CONNECT -> {
                interactive = true
                oneShot = false
                armWelcomeCycle("connect")
                startForegroundSafe(if (prefs.role() == BlePrefs.Role.HUB) "Магнітола • QStar" else "Прямий BLE • QStar")
                if (prefs.role() == BlePrefs.Role.HUB) ensureHubTransport()
                if (!remoteTakeover) ensureConnections()
            }
            ACTION_CONNECT_DEVICE -> {
                interactive = true
                oneShot = false
                armWelcomeCycle("connect_device")
                startForegroundSafe(if (prefs.role() == BlePrefs.Role.HUB) "Магнітола • QStar" else "Прямий BLE • QStar")
                if (prefs.role() == BlePrefs.Role.HUB) ensureHubTransport()
                intent.getStringExtra(EXTRA_MAC)?.let { ensureDevice(it) }
            }
            ACTION_RELEASE -> {
                stopStrobeInternal(restore = false)
                if (prefs.role() == BlePrefs.Role.HUB && !remoteTakeover) {
                    interactive = true
                } else {
                    interactive = false
                    disconnectAll()
                    if (prefs.role() == BlePrefs.Role.PHONE) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                        return START_NOT_STICKY
                    }
                    if (!prefs.keepConnected) shutdownSoon()
                }
            }
            ACTION_APPLY -> {
                stopStrobeInternal(restore = false)
                clearSafetyForCommand("apply")
                prefs.white = intent.getIntExtra(EXTRA_WHITE, prefs.white)
                prefs.brightness = intent.getIntExtra(EXTRA_BRIGHTNESS, prefs.brightness)
                oneShot = intent.getBooleanExtra(EXTRA_ONE_SHOT, false)
                startForegroundSafe("Applying light settings")
                latestCct = prefs.white to prefs.brightness
                ensureConnections()
            }
            ACTION_POWER -> {
                stopStrobeInternal(restore = false)
                clearSafetyForCommand("power")
                prefs.power = intent.getBooleanExtra(EXTRA_POWER, prefs.power)
                oneShot = intent.getBooleanExtra(EXTRA_ONE_SHOT, false)
                startForegroundSafe("Changing light power")
                ensureConnections { sendPower(prefs.power) }
            }
            ACTION_PRESET -> {
                stopStrobeInternal(restore = false)
                clearSafetyForCommand("preset")
                prefs.white = intent.getIntExtra(EXTRA_WHITE, prefs.white).coerceIn(0, 100)
                oneShot = intent.getBooleanExtra(EXTRA_ONE_SHOT, !interactive)
                startForegroundSafe("Applying preset")
                latestCct = prefs.white to prefs.brightness
                ensureConnections()
            }
            ACTION_BRIGHTNESS_DELTA -> {
                stopStrobeInternal(restore = false)
                clearSafetyForCommand("brightness")
                prefs.brightness = (prefs.brightness + intent.getIntExtra(EXTRA_DELTA, 0)).coerceIn(5, 100)
                oneShot = intent.getBooleanExtra(EXTRA_ONE_SHOT, !interactive)
                startForegroundSafe("Changing brightness")
                latestCct = prefs.white to prefs.brightness
                ensureConnections()
            }
            ACTION_STROBE -> {
                clearSafetyForCommand("strobe")
                val requested = if (intent.hasExtra(EXTRA_STROBE_ENABLED)) {
                    intent.getBooleanExtra(EXTRA_STROBE_ENABLED, false)
                } else !strobeActive
                prefs.strobeActive = requested
                startForegroundSafe(if (requested) "Мигалки" else "QStar")
                if (requested) startStrobe() else stopStrobeInternal(restore = true)
            }
            ACTION_CONFIG_CHANGED -> {
                startForegroundSafe("Синхронізація налаштувань")
                ensureHubTransport()
                hubTransport?.publishConfig()
                hubTransport?.publishStatus("Налаштування оновлено")
            }
            ACTION_BOOT -> {
                if (!prefs.autoBoot && prefs.role() != BlePrefs.Role.HUB) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                if (prefs.role() == BlePrefs.Role.HUB) {
                    interactive = true
                    oneShot = false
                    ensureHubTransport()
                } else {
                    oneShot = true
                }
                bootPending = prefs.autoBoot
                if (bootPending) armWelcomeCycle("boot")
                startForegroundSafe("Відновлення QStar")
                startScan(4_000) { if (!remoteTakeover) ensureConnections() }
            }
        }
        return if (prefs.role() == BlePrefs.Role.HUB && interactive && !oneShot) START_STICKY else START_NOT_STICKY
    }

    private fun ensureHubTransport() {
        if (prefs.role() != BlePrefs.Role.HUB) return
        if (hubTransport == null) hubTransport = HubTransport(this, prefs, this)
        hubTransport?.start()
        hubTransport?.publishStatus(if (remoteTakeover) "Телефон керує напряму" else "Магнітола керує лампами")
    }

    private fun armWelcomeCycle(reason: String) {
        if (welcomeInProgress) return
        startupPending = true
        welcomeWaitingForOff = false
        welcomeStateAttempts = 0
        welcomeCheckScheduled = false
        safetyFallbackActive = false
        safetyWriteInFlight = false
        safetyArmedAtElapsed = SystemClock.elapsedRealtime() + 2_500L
        DiagnosticLog.write("WELCOME", "armed reason=$reason")
    }

    private fun relayHeadUnitCommand(intent: Intent): Boolean {
        val action = intent.action ?: return false
        val command = when (action) {
            ACTION_PRESET -> ControlDispatcher.CMD_PRESET
            ACTION_APPLY -> ControlDispatcher.CMD_APPLY
            ACTION_POWER -> ControlDispatcher.CMD_POWER
            ACTION_BRIGHTNESS_DELTA -> ControlDispatcher.CMD_BRIGHTNESS_DELTA
            ACTION_STROBE -> ControlDispatcher.CMD_STROBE
            ACTION_CONNECT -> ControlDispatcher.CMD_CONNECT
            ACTION_CONNECT_DEVICE -> ControlDispatcher.CMD_CONNECT_DEVICE
            else -> return false
        }
        val payload = JSONObject()
        if (intent.hasExtra(EXTRA_WHITE)) payload.put("white", intent.getIntExtra(EXTRA_WHITE, prefs.white))
        if (intent.hasExtra(EXTRA_BRIGHTNESS)) payload.put("brightness", intent.getIntExtra(EXTRA_BRIGHTNESS, prefs.brightness))
        if (intent.hasExtra(EXTRA_POWER)) payload.put("power", intent.getBooleanExtra(EXTRA_POWER, prefs.power))
        if (intent.hasExtra(EXTRA_DELTA)) payload.put("delta", intent.getIntExtra(EXTRA_DELTA, 0))
        if (intent.hasExtra(EXTRA_STROBE_ENABLED)) payload.put("enabled", intent.getBooleanExtra(EXTRA_STROBE_ENABLED, false))
        if (intent.hasExtra(EXTRA_MAC)) payload.put("mac", intent.getStringExtra(EXTRA_MAC).orEmpty())
        val sent = hubTransport?.sendTakeoverCommand(command, payload) == true
        DiagnosticLog.write("BLE SERVICE", "relay_head_unit command=$command sent=$sent")
        if (sent) {
            event(EVENT_PHASE, message="command_relayed:$command")
            return true
        }
        // Never fall back to the HUB's own BLE while takeover is active, even
        // if the socket is in the middle of being re-established.
        if (remoteTakeover) {
            event(EVENT_PHASE, message="command_relay_failed:$command")
            return true
        }
        return false
    }

    private fun disconnectAll() {
        connections.values.forEach { it.disconnect() }
        connections.clear()
        lampPowerState.clear()
        readyMacs.clear()
        connectPlan.clear()
        connectingMac = null
        prefs.devices().forEach { ref ->
            if (prefs.role() == BlePrefs.Role.PHONE) {
                prefs.setDirectLampRuntimeState(ref.mac, BlePrefs.RuntimeLinkState.OFFLINE)
            }
            prefs.setLampRuntimeState(ref.mac, BlePrefs.RuntimeLinkState.OFFLINE)
        }
        QStarWidgetProvider.refresh(this)
    }

    private fun hasScanPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= 31) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun hasConnectPermission(): Boolean {
        return Build.VERSION.SDK_INT < 31 ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    }

    private fun handleDiscoveredDevice(device: BluetoothDevice, name: String?, rssi: Int) {
        if (name?.startsWith("QStar~") != true) return
        val ref = BlePrefs.DeviceRef(device.address, name)
        discovered[device.address] = ref
        prefs.updateMacByName(name, device.address)
        event(EVENT_DEVICE_FOUND, device.address, name, "RSSI $rssi", rssi)
    }

    private val legacyScanCallback = BluetoothAdapter.LeScanCallback { device, rssi, _ ->
        val name = try { device.name } catch (_: Throwable) { null }
        handleDiscoveredDevice(device, name, rssi)
    }

    private fun startLegacyScan(): Boolean {
        if (Build.VERSION.SDK_INT > 30 || legacyScanActive) return false
        return try {
            @Suppress("DEPRECATION")
            val ok = adapter.startLeScan(legacyScanCallback)
            legacyScanActive = ok
            if (ok) event(EVENT_SCAN, message = "legacy_scan_started")
            ok
        } catch (t: Throwable) {
            event(EVENT_ERROR, message = "Legacy BLE scan failed: ${t.javaClass.simpleName}: ${t.message.orEmpty()}")
            false
        }
    }

    private fun startScan(durationMs: Long, onDone: (() -> Unit)? = null) {
        if (scanActive) {
            onDone?.invoke()
            return
        }
        if (!hasScanPermission()) {
            event(EVENT_ERROR, message = "BLE scan: немає дозволу Location / Nearby devices; пробую збережені MAC")
            onDone?.invoke()
            return
        }
        if (!adapter.isEnabled) {
            event(EVENT_ERROR, message = "BLE scan: Bluetooth вимкнено")
            onDone?.invoke()
            return
        }
        scanActive = true
        scanDoneCallback = onDone
        discovered.clear()
        var started = false
        try {
            val scanner = adapter.bluetoothLeScanner
            if (scanner != null) {
                scanner.startScan(scanCallback)
                modernScanActive = true
                started = true
                event(EVENT_SCAN, message = "scan_started")
            }
        } catch (t: Throwable) {
            event(EVENT_ERROR, message = "BLE scan failed: ${t.javaClass.simpleName}: ${t.message.orEmpty()}")
        }
        if (!started) started = startLegacyScan()
        if (!started) {
            scanActive = false
            scanDoneCallback = null
            onDone?.invoke()
            return
        }
        val stop = Runnable { finishScan("scan_finished") }
        scanStopRunnable = stop
        handler.postDelayed(stop, durationMs)
    }

    private fun finishScan(message: String) {
        scanStopRunnable?.let(handler::removeCallbacks)
        scanStopRunnable = null
        if (modernScanActive) {
            try { adapter.bluetoothLeScanner?.stopScan(scanCallback) } catch (_: Throwable) { }
            modernScanActive = false
        }
        if (legacyScanActive) {
            try {
                @Suppress("DEPRECATION")
                adapter.stopLeScan(legacyScanCallback)
            } catch (_: Throwable) { }
            legacyScanActive = false
        }
        val wasActive = scanActive
        scanActive = false
        if (wasActive) event(EVENT_SCAN, message = message)
        val callback = scanDoneCallback
        scanDoneCallback = null
        callback?.invoke()
        if (!interactive && !bootPending && !welcomeWaitingForOff && connections.values.none { it.isReady() }) shutdownSoon()
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val name = result.scanRecord?.deviceName ?: try { device.name } catch (_: Throwable) { null }
            handleDiscoveredDevice(device, name, result.rssi)
        }

        override fun onScanFailed(errorCode: Int) {
            event(EVENT_ERROR, message = "BLE scan error $errorCode")
            modernScanActive = false
            if (Build.VERSION.SDK_INT <= 30 && !legacyScanActive && startLegacyScan()) {
                event(EVENT_SCAN, message = "legacy_scan_fallback")
                return
            }
            finishScan("scan_failed_$errorCode")
        }
    }

    private var pendingAfterReady: (() -> Unit)? = null

    private fun ensureDevice(mac: String) {
        val ref = prefs.devices().firstOrNull { it.mac.equals(mac, true) } ?: return
        val current = connections[ref.mac]
        if (current?.isReady() == true || connectingMac == ref.mac || connectPlan.any { it.mac == ref.mac }) return
        connectPlan.addFirst(ref)
        pumpConnectPlan()
        scheduleReconnect(250)
    }

    private fun allSelectedReady(): Boolean {
        val refs = prefs.devices()
        return refs.isNotEmpty() && refs.all { connections[it.mac]?.isReady() == true }
    }

    private fun ensureConnections(afterReady: (() -> Unit)? = null) {
        if (remoteTakeover) return
        if (afterReady != null) pendingAfterReady = afterReady
        if (!hasConnectPermission() || !adapter.isEnabled) {
            event(EVENT_ERROR, message = "Bluetooth permission or adapter missing")
            scheduleReconnect(2000)
            return
        }
        val refs = prefs.devices()
        if (refs.isEmpty()) {
            event(EVENT_ERROR, message = "No QStar devices selected")
            return
        }
        if (allSelectedReady()) {
            onAllReady()
            return
        }
        refs.forEach { ref ->
            if (connections[ref.mac]?.isReady() != true && connectingMac != ref.mac && connectPlan.none { it.mac == ref.mac }) connectPlan.add(ref)
        }
        pumpConnectPlan()
        scheduleReconnect()
    }

    private fun pumpConnectPlan() {
        if (remoteTakeover || connectingMac != null) return
        while (connectPlan.isNotEmpty()) {
            val ref = connectPlan.removeFirst()
            if (connections[ref.mac]?.isReady() == true) continue
            val device = try { adapter.getRemoteDevice(ref.mac) } catch (_: Throwable) { continue }
            val connection = LampConnection(this, device, prefs::password, this)
            connections[ref.mac]?.disconnect()
            connections[ref.mac] = connection
            readyMacs.remove(ref.mac)
            // A new GATT session must not reuse the power state reported by
            // the previous session while the new handshake is still running.
            lampPowerState.remove(ref.mac)
            connectingMac = ref.mac
            connection.connect()
            handler.postDelayed({
                if (connectingMac == ref.mac && !connection.isReady()) {
                    event(EVENT_ERROR, ref.mac, ref.name, "Connect timeout")
                    connection.disconnect()
                    connectingMac = null
                    scheduleReconnect(350)
                }
            }, 12000)
            return
        }
        if (allSelectedReady()) onAllReady() else scheduleReconnect()
    }

    private fun scheduleReconnect(delayMs: Long = 1200L) {
        if (prefs.role() == BlePrefs.Role.PHONE &&
            !prefs.anyPhoneLinkConnected() &&
            !prefs.phoneOfflineGraceActive()) {
            stopPhoneBleSession()
            return
        }
        if (reconnectScheduled || remoteTakeover) return
        val needed = interactive || bootPending || welcomeWaitingForOff || oneShot || latestCct != null || pendingAfterReady != null
        if (!needed) return
        reconnectScheduled = true
        handler.postDelayed({
            reconnectScheduled = false
            if (remoteTakeover) return@postDelayed
            if (allSelectedReady()) {
                onAllReady()
                return@postDelayed
            }
            reconnectRounds++
            if (reconnectRounds % 5 == 0 && !scanActive && hasScanPermission() && adapter.isEnabled) {
                startScan(2600) { ensureConnections() }
                return@postDelayed
            }
            prefs.devices().forEach { ref ->
                if (connections[ref.mac]?.isReady() != true && connectingMac != ref.mac && connectPlan.none { it.mac == ref.mac }) connectPlan.add(ref)
            }
            pumpConnectPlan()
            if (!allSelectedReady()) scheduleReconnect()
        }, delayMs)
    }

    private fun onAllReady() {
        if (!allSelectedReady()) {
            scheduleReconnect()
            return
        }
        reconnectRounds = 0
        if (!pairWasReady) {
            // Android BLE can report a short-lived disconnect immediately
            // after the first pair-up. Give the new session a small settle
            // window before treating that as a runtime failure.
            safetyArmedAtElapsed = SystemClock.elapsedRealtime() + 2_500L
        }
        pairWasReady = true
        if (prefs.role() == BlePrefs.Role.PHONE) {
            prefs.markPhoneLinkAvailable()
            reconcilePhoneLifetime()
        }
        if (welcomeInProgress) return
        if (safetyFallbackActive) {
            sendSafetyToReadyLamps()
            return
        }
        // An explicit command (especially POWER_OFF) must not be swallowed by
        // the one-shot welcome gate when both GATT links are already READY.
        // The callback itself may update the power map and re-enter welcome.
        pendingAfterReady?.let { callback ->
            pendingAfterReady = null
            callback.invoke()
            return
        }
        val greetingPending = bootPending || startupPending || welcomeWaitingForOff
        if (greetingPending) {
            evaluateWelcomeIfReady()
            return
        }
        finishReadyCycle()
    }

    private fun evaluateWelcomeIfReady() {
        if (welcomeCheckScheduled) return
        welcomeCheckScheduled = true
        handler.post {
            welcomeCheckScheduled = false
            if (!bootPending && !startupPending && !welcomeWaitingForOff) return@post
            if (welcomeInProgress) return@post
            if (!allSelectedReady()) {
                scheduleReconnect()
                return@post
            }
            if (safetyFallbackActive) {
                DiagnosticLog.write("WELCOME", "deferred_by_failsafe states=${prefs.devices().map { lampPowerState[it.mac] }}")
                return@post
            }

            val refs = prefs.devices()
            if (!prefs.welcomeOnConnect || refs.size < 2) {
                bootPending = false
                startupPending = false
                welcomeWaitingForOff = false
                welcomeStateAttempts = 0
                welcomeInProgress = false
                finishReadyCycle()
                return@post
            }

            val states = refs.map { lampPowerState[it.mac] }
            if (states.any { it == null }) {
                welcomeStateAttempts += 1
                refs.filter { lampPowerState[it.mac] == null }.forEach { ref ->
                    connections[ref.mac]?.requestState { ok ->
                        if (!ok) {
                            DiagnosticLog.write("WELCOME", "QUERY_STATE retry failed mac=${ref.mac}")
                        }
                    }
                }
                if (welcomeStateAttempts == 1 || welcomeStateAttempts % 10 == 0) {
                    DiagnosticLog.write(
                        "WELCOME",
                        "waiting_for_power_state attempts=$welcomeStateAttempts states=${states.joinToString(",")}"
                    )
                }
                handler.postDelayed({ evaluateWelcomeIfReady() }, 350L)
                return@post
            }

            val bothLampsWereOff = states.size == refs.size && states.all { it == false }
            bootPending = false
            startupPending = false
            welcomeWaitingForOff = !bothLampsWereOff
            welcomeStateAttempts = 0
            DiagnosticLog.write(
                "WELCOME",
                "phone_or_hub_ready=${prefs.role()} states=${states.joinToString(",")} run=$bothLampsWereOff"
            )
            if (bothLampsWereOff) {
                // The saved color/power is only the previous UI state. It must not
                // suppress the selected welcome profile after a real lamp power-off.
                prefs.power = false
                welcomeInProgress = true
                runBootRoutine()
            } else {
                welcomeInProgress = false
                finishReadyCycle()
            }
        }
    }

    private fun finishReadyCycle() {
        val callback = pendingAfterReady
        pendingAfterReady = null
        callback?.invoke()
        pumpLatestCct()
        if (interactive && !rssiLoop) startRssiLoop()
    }

    private fun clearSafetyForCommand(source: String) {
        if (!safetyFallbackActive) return
        safetyFallbackActive = false
        DiagnosticLog.write("BLE", "failsafe cleared by $source")
    }

    private fun activateSafetyFallback(reason: String) {
        if (remoteTakeover) return
        // During the initial pair-up the lamps can briefly disconnect while
        // Android is still discovering/subscribing.  Treating that as a real
        // runtime failure turns a normal yellow welcome into white failsafe.
        if (bootPending || startupPending || welcomeInProgress || welcomeWaitingForOff) {
            DiagnosticLog.write("BLE", "skip failsafe while welcome is pending: $reason")
            scheduleReconnect(250)
            return
        }
        if (SystemClock.elapsedRealtime() < safetyArmedAtElapsed) {
            DiagnosticLog.write("BLE", "skip failsafe during startup settle: $reason")
            scheduleReconnect(250)
            return
        }
        stopStrobeInternal(restore = false)
        safetyFallbackActive = true
        latestCct = null
        DiagnosticLog.write("BLE", "failsafe armed ready=${readyMacs.size}/${prefs.devices().size} reason=$reason")
        event(EVENT_PHASE, message = "failsafe_white_100:$reason")
        sendSafetyToReadyLamps()
        scheduleReconnect(250)
    }

    private fun sendSafetyToReadyLamps() {
        if (!safetyFallbackActive || welcomeInProgress || safetyWriteInFlight) return
        val list = prefs.devices().mapNotNull { connections[it.mac] }.filter { it.isReady() }
        if (list.isEmpty()) {
            DiagnosticLog.write("BLE", "failsafe waiting_for_ready ready=0/${prefs.devices().size}")
            scheduleReconnect(250)
            return
        }

        safetyWriteInFlight = true
        DiagnosticLog.write("BLE", "failsafe write ready=${list.size}/${prefs.devices().size}")
        sendFrameReadySimultaneous(list, QStarProtocol.POWER_ON) {
            if (!safetyFallbackActive) {
                safetyWriteInFlight = false
                return@sendFrameReadySimultaneous
            }
            sendFrameReadySimultaneous(list, QStarProtocol.cctFrame(100, 100)) {
                safetyWriteInFlight = false
                DiagnosticLog.write("BLE", "failsafe white sent ready=${list.count { it.isReady() }}/${prefs.devices().size}")
                // A lamp can have dropped while the two writes were queued.
                // Re-arm once, after the current GATT queues have drained.
                if (safetyFallbackActive && list.any { !it.isReady() }) {
                    handler.postDelayed({ sendSafetyToReadyLamps() }, 180)
                }
            }
        }
    }

    private fun sendFrameReadySimultaneous(
        list: List<LampConnection>,
        frame: ByteArray,
        done: () -> Unit
    ) {
        if (list.isEmpty()) {
            done()
            return
        }
        val remaining = java.util.concurrent.atomic.AtomicInteger(list.size)
        list.forEach { connection ->
            connection.writeControl(frame) { ok ->
                event(if (ok) EVENT_WRITE else EVENT_ERROR, connection.mac, connection.name, QStarProtocol.hex(frame))
                if (remaining.decrementAndGet() == 0) handler.post(done)
            }
        }
    }

    private fun pumpLatestCct() {
        if (sendingCct || strobeActive) return
        if (!allSelectedReady()) { scheduleReconnect(); return }
        val desired = latestCct ?: return
        latestCct = null
        sendingCct = true
        sendFrameAll(QStarProtocol.cctFrame(desired.first, desired.second)) {
            sendingCct = false
            if (latestCct != null) {
                handler.postDelayed({ pumpLatestCct() }, 60)
            } else if (oneShot) {
                shutdownSoon()
            }
        }
    }

    private fun sendPower(on: Boolean, done: (() -> Unit)? = null) {
        sendFrameAllSimultaneous(if (on) QStarProtocol.POWER_ON else QStarProtocol.POWER_OFF) {
            if (!on) {
                // Some controller firmwares do not emit a state notification
                // for every power write.  Record the explicit OFF command so
                // the armed one-shot welcome cannot wait forever for a packet
                // that never arrives.
                prefs.devices().forEach { lampPowerState[it.mac] = false }
                if (bootPending || startupPending || welcomeWaitingForOff) evaluateWelcomeIfReady()
            }
            done?.invoke()
            if (oneShot) shutdownSoon()
        }
    }

    private fun sendFrameAll(frame: ByteArray, done: () -> Unit) {
        val refs = prefs.devices()
        if (refs.isEmpty() || refs.any { connections[it.mac]?.isReady() != true }) {
            event(EVENT_ERROR, message = "Waiting for both QStar lamps")
            scheduleReconnect()
            done()
            return
        }
        val list = refs.map { connections[it.mac]!! }
        fun sendAt(index: Int) {
            if (index >= list.size) { done(); return }
            val c = list[index]
            c.writeControl(frame) { ok ->
                event(if (ok) EVENT_WRITE else EVENT_ERROR, c.mac, c.name, QStarProtocol.hex(frame))
                handler.postDelayed({ sendAt(index + 1) }, 45)
            }
        }
        sendAt(0)
    }

    private fun sendFrameAllSimultaneous(frame: ByteArray, done: () -> Unit) {
        val refs = prefs.devices()
        if (refs.isEmpty() || refs.any { connections[it.mac]?.isReady() != true }) {
            event(EVENT_ERROR, message = "Waiting for both QStar lamps")
            scheduleReconnect()
            done()
            return
        }
        val list = refs.map { connections[it.mac]!! }
        val remaining = java.util.concurrent.atomic.AtomicInteger(list.size)
        list.forEach { connection ->
            connection.writeControl(frame) { ok ->
                event(if (ok) EVENT_WRITE else EVENT_ERROR, connection.mac, connection.name, QStarProtocol.hex(frame))
                if (remaining.decrementAndGet() == 0) handler.post(done)
            }
        }
    }

    private fun applyPowerStates(states: List<Boolean>, done: () -> Unit) {
        val refs = prefs.devices()
        fun sendAt(index: Int) {
            if (index >= refs.size) { done(); return }
            val connection = connections[refs[index].mac]
            if (connection == null || !connection.isReady()) {
                sendAt(index + 1)
                return
            }
            val state = states.getOrElse(index) { false }
            val frame = if (state) QStarProtocol.POWER_ON else QStarProtocol.POWER_OFF
            connection.writeControl(frame) {
                handler.postDelayed({ sendAt(index + 1) }, 25)
            }
        }
        sendAt(0)
    }

    private fun runBootRoutine() {
        stopStrobeInternal(restore = false)
        when (prefs.startupMode) {
            BlePrefs.StartupMode.OFF -> {
                prefs.power = false
                sendFrameAllSimultaneous(QStarProtocol.POWER_OFF) { finishBootRoutine() }
            }
            BlePrefs.StartupMode.RESTORE -> {
                if (!prefs.power) {
                    sendFrameAllSimultaneous(QStarProtocol.POWER_OFF) { finishBootRoutine() }
                } else {
                    sendFrameAllSimultaneous(QStarProtocol.POWER_ON) {
                        sendFrameAllSimultaneous(QStarProtocol.cctFrame(prefs.white, prefs.brightness)) { finishBootRoutine() }
                    }
                }
            }
            BlePrefs.StartupMode.START_ONLY -> {
                prefs.power = true
                prefs.white = prefs.startWhite
                prefs.brightness = prefs.startBrightness
                sendFrameAllSimultaneous(QStarProtocol.POWER_ON) {
                    sendFrameAllSimultaneous(QStarProtocol.cctFrame(prefs.startWhite, prefs.startBrightness)) { finishBootRoutine() }
                }
            }
            BlePrefs.StartupMode.FADE_TO_TARGET -> {
                runFadeBootRoutine(prefs.startWhite, prefs.targetWhite, prefs.startBrightness)
            }
            BlePrefs.StartupMode.SMOOTH_YELLOW_WHITE -> {
                runFadeBootRoutine(0, 100, prefs.startBrightness)
            }
        }
    }

    private fun runFadeBootRoutine(startWhite: Int, targetWhite: Int, brightness: Int) {
        prefs.power = true
        prefs.white = startWhite
        prefs.brightness = brightness
        sendFrameAllSimultaneous(QStarProtocol.POWER_ON) {
            sendFrameAllSimultaneous(QStarProtocol.cctFrame(startWhite, brightness)) {
                if (prefs.fadeDurationMs <= 0 || startWhite == targetWhite) {
                    prefs.white = targetWhite
                    sendFrameAllSimultaneous(QStarProtocol.cctFrame(targetWhite, brightness)) { finishBootRoutine() }
                } else {
                    val steps = prefs.fadeSteps.coerceAtLeast(2)
                    val delay = (prefs.fadeDurationMs / steps).coerceAtLeast(20)
                    handler.postDelayed({ fadeStep(1, steps, startWhite, targetWhite, brightness, delay) }, 120)
                }
            }
        }
    }

    private fun fadeStep(step: Int, total: Int, startWhite: Int, targetWhite: Int, brightness: Int, delayMs: Int) {
        val white = (startWhite + (targetWhite - startWhite) * step / total).coerceIn(0, 100)
        sendFrameAllSimultaneous(QStarProtocol.cctFrame(white, brightness)) {
            if (step >= total) {
                prefs.white = targetWhite
                prefs.brightness = brightness
                finishBootRoutine()
            } else {
                handler.postDelayed({ fadeStep(step + 1, total, startWhite, targetWhite, brightness, delayMs) }, delayMs.toLong())
            }
        }
    }

    private fun finishBootRoutine() {
        welcomeInProgress = false
        bootPending = false
        startupPending = false
        welcomeWaitingForOff = false
        if (prefs.role() == BlePrefs.Role.HUB) hubTransport?.publishStatus("QStar готові") else shutdownSoon()
    }

    private fun startStrobe() {
        oneShot = false
        interactive = true
        strobeActive = true
        prefs.strobeActive = true
        strobeGeneration++
        val generation = strobeGeneration
        ensureConnections {
            if (prefs.strobeMode == BlePrefs.StrobeMode.YELLOW_WHITE_SWAP) {
                // Start from a known dark state. The selected normal color/power remain untouched
                // in prefs so STOP can restore exactly what the user had before the strobe.
                sendFrameAll(QStarProtocol.POWER_OFF) {
                    if (strobeActive && generation == strobeGeneration) {
                        event(EVENT_STROBE, message = "strobe_on:${prefs.strobeMode.name}")
                        runStrobeSequence(generation, 0)
                    }
                }
            } else {
                sendFrameAll(QStarProtocol.cctFrame(prefs.strobeWhite, prefs.strobeBrightness)) {
                    if (strobeActive && generation == strobeGeneration) {
                        event(EVENT_STROBE, message = "strobe_on:${prefs.strobeMode.name}")
                        runStrobeSequence(generation, 0)
                    }
                }
            }
        }
    }

    private fun stopStrobeInternal(restore: Boolean) {
        if (!strobeActive && !restore) return
        strobeActive = false
        prefs.strobeActive = false
        strobeGeneration++
        event(EVENT_STROBE, message = "strobe_off")
        if (restore) {
            ensureConnections {
                sendFrameAll(QStarProtocol.cctFrame(prefs.white, prefs.brightness)) {
                    sendFrameAll(if (prefs.power) QStarProtocol.POWER_ON else QStarProtocol.POWER_OFF) { }
                }
            }
        }
    }

    private fun strobeSequence(): List<StrobeTimeline.Step> = StrobeTimeline.sequence(
        prefs.strobeMode,
        prefs.devices().size,
        prefs.strobeOnMs,
        prefs.strobeOffMs,
        prefs.strobePauseMs
    )

    private fun applyStrobeStep(step: StrobeTimeline.Step, done: () -> Unit) {
        val refs = prefs.devices()
        fun sendAt(index: Int) {
            if (index >= refs.size) { done(); return }
            val connection = connections[refs[index].mac]
            if (connection == null || !connection.isReady()) {
                sendAt(index + 1)
                return
            }
            val desiredOn = step.states.getOrElse(index) { false }
            val desiredWhite = step.whites?.getOrNull(index)

            fun writePower() {
                connection.writeControl(if (desiredOn) QStarProtocol.POWER_ON else QStarProtocol.POWER_OFF) {
                    handler.postDelayed({ sendAt(index + 1) }, 8)
                }
            }

            if (desiredWhite != null) {
                connection.writeControl(QStarProtocol.cctFrame(desiredWhite, prefs.strobeBrightness)) {
                    writePower()
                }
            } else {
                writePower()
            }
        }
        sendAt(0)
    }

    private fun runStrobeSequence(generation: Long, index: Int) {
        if (!strobeActive || generation != strobeGeneration) return
        val seq = strobeSequence()
        if (seq.isEmpty()) return
        val stepIndex = index % seq.size
        val step = seq[stepIndex]
        applyStrobeStep(step) {
            if (!strobeActive || generation != strobeGeneration) return@applyStrobeStep
            handler.postDelayed({ runStrobeSequence(generation, (stepIndex + 1) % seq.size) }, step.delayMs.toLong())
        }
    }

    private fun startRssiLoop() {
        rssiLoop = true
        var index = 0
        val task = object : Runnable {
            override fun run() {
                if (!interactive) {
                    rssiLoop = false
                    return
                }
                val ready = prefs.devices().mapNotNull { connections[it.mac] }.filter { it.isReady() }
                if (ready.isNotEmpty()) {
                    ready[index % ready.size].readRssi()
                    index++
                }
                handler.postDelayed(this, 4_500)
            }
        }
        handler.post(task)
    }

    private fun reconcilePhoneLifetime() {
        if (prefs.role() != BlePrefs.Role.PHONE) return
        phoneStopRunnable?.let(handler::removeCallbacks)
        phoneStopRunnable = null

        if (prefs.anyPhoneLinkConnected()) {
            prefs.clearPhoneOfflineGrace()
            return
        }

        val until = prefs.startPhoneOfflineGrace()
        val remaining = until - System.currentTimeMillis()
        DiagnosticLog.write("BLE SERVICE", "PHONE offline grace remaining=${remaining.coerceAtLeast(0L)}ms")
        if (remaining <= 0L) {
            stopPhoneBleSession()
            return
        }

        val task = Runnable {
            if (prefs.anyPhoneLinkConnected()) {
                prefs.clearPhoneOfflineGrace()
                return@Runnable
            }
            if (prefs.phoneOfflineGraceActive()) {
                reconcilePhoneLifetime()
            } else {
                stopPhoneBleSession()
            }
        }
        phoneStopRunnable = task
        handler.postDelayed(task, remaining.coerceAtLeast(1_000L))
    }

    private fun stopPhoneBleSession() {
        if (prefs.role() != BlePrefs.Role.PHONE) return
        if (prefs.anyPhoneLinkConnected()) return
        prefs.clearPhoneOfflineGrace()
        DiagnosticLog.write("BLE SERVICE", "PHONE offline grace expired; stopping direct BLE foreground service")
        interactive = false
        oneShot = false
        reconnectScheduled = false
        stopStrobeInternal(restore = false)
        disconnectAll()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun shutdownSoon() {
        if (prefs.role() == BlePrefs.Role.PHONE) {
            reconcilePhoneLifetime()
            return
        }
        if (prefs.role() == BlePrefs.Role.HUB && interactive && !remoteTakeover) return
        handler.postDelayed({
            if (!interactive || oneShot) {
                connections.values.forEach { it.disconnect() }
                connections.clear()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }, 1_200)
    }

    override fun onPhase(mac: String, phase: LampConnection.Phase) {
        val state = when (phase) {
            LampConnection.Phase.READY -> BlePrefs.RuntimeLinkState.CONNECTED
            LampConnection.Phase.CONNECTING,
            LampConnection.Phase.DISCOVERING,
            LampConnection.Phase.SUBSCRIBING,
            LampConnection.Phase.HANDSHAKE -> BlePrefs.RuntimeLinkState.CONNECTING
            LampConnection.Phase.DISCONNECTED,
            LampConnection.Phase.ERROR -> BlePrefs.RuntimeLinkState.OFFLINE
        }
        if (prefs.role() == BlePrefs.Role.PHONE) {
            prefs.setDirectLampRuntimeState(mac, state)
            if (prefs.forceDirect || prefs.hubRuntimeState != BlePrefs.RuntimeLinkState.CONNECTED) {
                prefs.setLampRuntimeState(mac, state)
            }
            if (state == BlePrefs.RuntimeLinkState.CONNECTED) prefs.markPhoneLinkAvailable()
            reconcilePhoneLifetime()
        } else {
            prefs.setLampRuntimeState(mac, state)
        }
        QStarWidgetProvider.refresh(this)
        event(EVENT_PHASE, mac, connections[mac]?.name, phase.name)
    }

    override fun onReady(mac: String) {
        prefs.markLampConnected(mac)
        event(EVENT_READY, mac, connections[mac]?.name, "ready")
        readyMacs.add(mac)
        if (prefs.role() == BlePrefs.Role.PHONE) {
            prefs.setDirectLampRuntimeState(mac, BlePrefs.RuntimeLinkState.CONNECTED)
            if (prefs.forceDirect || prefs.hubRuntimeState != BlePrefs.RuntimeLinkState.CONNECTED) {
                prefs.setLampRuntimeState(mac, BlePrefs.RuntimeLinkState.CONNECTED)
            }
            prefs.markPhoneLinkAvailable()
            reconcilePhoneLifetime()
        } else {
            prefs.setLampRuntimeState(mac, BlePrefs.RuntimeLinkState.CONNECTED)
        }
        QStarWidgetProvider.refresh(this)
        if (connectingMac == mac) connectingMac = null
        if (safetyFallbackActive) {
            sendSafetyToReadyLamps()
            handler.postDelayed({ pumpConnectPlan() }, 180)
        } else {
            if (bootPending || startupPending || welcomeWaitingForOff) evaluateWelcomeIfReady()
            handler.postDelayed({ pumpConnectPlan() }, 850)
        }
    }

    override fun onState(mac: String, state: QStarProtocol.LampState) {
        lampPowerState[mac] = state.power
        if (!safetyFallbackActive && (bootPending || startupPending || welcomeWaitingForOff)) {
            evaluateWelcomeIfReady()
        }
        event(
            EVENT_STATE,
            mac,
            connections[mac]?.name,
            "${if (state.power) "ON" else "OFF"} W=${state.white} Y=${state.yellow} B=${state.brightness} M=${state.mode}"
        )
    }

    override fun onPasswordRequired(mac: String, required: Boolean) {
        event(EVENT_PASSWORD, mac, connections[mac]?.name, if (required) "password_required" else "password_off")
    }

    override fun onRssi(mac: String, rssi: Int) {
        event(EVENT_RSSI, mac, connections[mac]?.name, "RSSI $rssi", rssi)
    }

    override fun onError(mac: String, message: String) {
        if (prefs.role() == BlePrefs.Role.PHONE) {
            prefs.setDirectLampRuntimeState(mac, BlePrefs.RuntimeLinkState.OFFLINE)
            if (prefs.forceDirect || prefs.hubRuntimeState != BlePrefs.RuntimeLinkState.CONNECTED) {
                prefs.setLampRuntimeState(mac, BlePrefs.RuntimeLinkState.OFFLINE)
            }
            reconcilePhoneLifetime()
        } else {
            prefs.setLampRuntimeState(mac, BlePrefs.RuntimeLinkState.OFFLINE)
        }
        QStarWidgetProvider.refresh(this)
        event(EVENT_ERROR, mac, connections[mac]?.name, message)
        readyMacs.remove(mac)
        lampPowerState.remove(mac)
        if (connectingMac == mac) connectingMac = null
        // A single lamp becoming READY during the initial pair-up is not a
        // runtime failure.  Failsafe is valid only after both lamps were
        // READY together; otherwise a normal GATT race turns yellow startup
        // into an unwanted white emergency state.
        if (pairWasReady && !remoteTakeover) activateSafetyFallback("error:$message")
        if (!remoteTakeover) scheduleReconnect(120)
    }

    override fun onDisconnected(mac: String, status: Int) {
        if (prefs.role() == BlePrefs.Role.PHONE) {
            prefs.setDirectLampRuntimeState(mac, BlePrefs.RuntimeLinkState.OFFLINE)
            if (prefs.forceDirect || prefs.hubRuntimeState != BlePrefs.RuntimeLinkState.CONNECTED) {
                prefs.setLampRuntimeState(mac, BlePrefs.RuntimeLinkState.OFFLINE)
            }
            reconcilePhoneLifetime()
        } else {
            prefs.setLampRuntimeState(mac, BlePrefs.RuntimeLinkState.OFFLINE)
        }
        QStarWidgetProvider.refresh(this)
        event(EVENT_DISCONNECTED, mac, connections[mac]?.name, "status=$status")
        readyMacs.remove(mac)
        lampPowerState.remove(mac)
        if (connectingMac == mac) connectingMac = null
        if (pairWasReady && !remoteTakeover) activateSafetyFallback("disconnect:$status")
        if (prefs.role() == BlePrefs.Role.HUB && readyMacs.isEmpty() && !remoteTakeover) pairWasReady = false
        if (!remoteTakeover) scheduleReconnect(120)
    }

    private fun event(type: String, mac: String? = null, name: String? = null, message: String? = null, rssi: Int? = null) {
        DiagnosticLog.write("BLE", "event=$type mac=${mac.orEmpty()} name=${name.orEmpty()} ${message.orEmpty()}", if (type == EVENT_ERROR) "ERROR" else "INFO")
        val i = Intent(ACTION_EVENT).setPackage(packageName)
            .putExtra(EXTRA_EVENT, type)
            .putExtra(EXTRA_MAC, mac)
            .putExtra(EXTRA_NAME, name)
            .putExtra(EXTRA_MESSAGE, message)
        if (rssi != null) i.putExtra(EXTRA_RSSI, rssi)
        sendBroadcast(i)
        hubTransport?.publishBle(type, mac, name, message)
        if (prefs.role() == BlePrefs.Role.PHONE && mac != null && type in setOf(EVENT_PHASE, EVENT_READY, EVENT_DISCONNECTED, EVENT_ERROR)) {
            RemoteLinkService.syncDirectBleEvent(this, type, mac, name, message)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "QStar BLE", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun startForegroundSafe(text: String) {
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_headlight)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .addAction(0, "🟡", presetPendingIntent(0, 7011))
            .addAction(0, "Теплий", presetPendingIntent(50, 7012))
            .addAction(0, "⚪", presetPendingIntent(100, 7013))
            .addAction(0, "⚡", strobePendingIntent(7014))
            .build()
        startForeground(NOTIFICATION_ID, notification)
    }

    private fun presetPendingIntent(white: Int, requestCode: Int): PendingIntent {
        val i = Intent(this, ControlActionReceiver::class.java)
            .setAction(ControlActionReceiver.ACTION_PRESET)
            .putExtra(ControlActionReceiver.EXTRA_WHITE, white)
        return PendingIntent.getBroadcast(
            this, requestCode, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun strobePendingIntent(requestCode: Int): PendingIntent {
        val i = Intent(this, ControlActionReceiver::class.java).setAction(ControlActionReceiver.ACTION_STROBE)
        return PendingIntent.getBroadcast(this, requestCode, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    override fun onRemoteCommand(command: String, payload: JSONObject, onComplete: (Boolean) -> Unit) {
        handler.post {
            when (command) {
                ControlDispatcher.CMD_PRESET -> {
                    stopStrobeInternal(restore = false)
                    clearSafetyForCommand("remote_preset")
                    prefs.white = payload.optInt("white", prefs.white).coerceIn(0, 100)
                    ensureConnections {
                        sendFrameAllResult(QStarProtocol.cctFrame(prefs.white, prefs.brightness), onComplete)
                    }
                }
                ControlDispatcher.CMD_APPLY -> {
                    stopStrobeInternal(restore = false)
                    clearSafetyForCommand("remote_apply")
                    prefs.white = payload.optInt("white", prefs.white).coerceIn(0, 100)
                    prefs.brightness = payload.optInt("brightness", prefs.brightness).coerceIn(5, 100)
                    ensureConnections {
                        sendFrameAllResult(QStarProtocol.cctFrame(prefs.white, prefs.brightness), onComplete)
                    }
                }
                ControlDispatcher.CMD_POWER -> {
                    stopStrobeInternal(restore = false)
                    clearSafetyForCommand("remote_power")
                    prefs.power = payload.optBoolean("power", prefs.power)
                    ensureConnections { sendPowerResult(prefs.power, onComplete) }
                }
                ControlDispatcher.CMD_BRIGHTNESS_DELTA -> {
                    stopStrobeInternal(restore = false)
                    clearSafetyForCommand("remote_brightness")
                    prefs.brightness = (prefs.brightness + payload.optInt("delta", 0)).coerceIn(5, 100)
                    ensureConnections {
                        sendFrameAllResult(QStarProtocol.cctFrame(prefs.white, prefs.brightness), onComplete)
                    }
                }
                ControlDispatcher.CMD_STROBE -> {
                    clearSafetyForCommand("remote_strobe")
                    val enabled = if (payload.has("enabled")) payload.optBoolean("enabled") else !strobeActive
                    if (enabled) startStrobe() else stopStrobeInternal(restore = true)
                    onComplete(allSelectedReady())
                }
                ControlDispatcher.CMD_CONNECT -> ensureConnections { onComplete(allSelectedReady()) }
                ControlDispatcher.CMD_CONNECT_DEVICE -> {
                    val mac = payload.optString("mac")
                    if (mac.isBlank()) onComplete(false) else {
                        ensureDevice(mac)
                        handler.postDelayed({ onComplete(connections[mac]?.isReady() == true) }, 1500)
                    }
                }
                else -> onComplete(false)
            }
        }
    }

    private fun sendFrameAllResult(frame: ByteArray, done: (Boolean) -> Unit) {
        val refs = prefs.devices()
        if (refs.isEmpty() || refs.any { connections[it.mac]?.isReady() != true }) {
            event(EVENT_ERROR, message = "Waiting for both QStar lamps")
            scheduleReconnect()
            done(false)
            return
        }
        val list = refs.map { connections[it.mac]!! }
        var allOk = true
        fun sendAt(index: Int) {
            if (index >= list.size) {
                done(allOk)
                return
            }
            val connection = list[index]
            connection.writeControl(frame) { ok ->
                allOk = allOk && ok
                event(if (ok) EVENT_WRITE else EVENT_ERROR, connection.mac, connection.name, QStarProtocol.hex(frame))
                handler.postDelayed({ sendAt(index + 1) }, 45)
            }
        }
        sendAt(0)
    }

    private fun sendPowerResult(on: Boolean, done: (Boolean) -> Unit) {
        val frame = if (on) QStarProtocol.POWER_ON else QStarProtocol.POWER_OFF
        sendFrameAllResult(frame) { ok ->
            if (ok && !on) prefs.devices().forEach { lampPowerState[it.mac] = false }
            done(ok)
        }
    }

    override fun onTakeoverChanged(active: Boolean) {
        handler.post {
            remoteTakeover = active
            stopStrobeInternal(restore = false)
            if (active) {
                disconnectAll()
                event(EVENT_PHASE, message = "remote_direct_takeover")
            } else {
                interactive = true
                armWelcomeCycle("hub_resume")
                event(EVENT_PHASE, message = "hub_control_resumed")
                ensureConnections()
            }
        }
    }

    override fun onRemoteConfig(config: JSONObject, onApplied: (Boolean) -> Unit) {
        // Share the command handler so a config cannot overtake a brightness/power command.
        handler.post {
            val changed = prefs.applySyncConfig(config)
            if (changed) {
                clearSafetyForCommand("config_sync")
                event(EVENT_CONFIG_SYNC, message = "config_from_phone rev=${config.optLong("revision")}")
                QStarWidgetProvider.refresh(this)
                val welcomeOwnsStartup = welcomeInProgress || bootPending || startupPending || welcomeWaitingForOff
                if (welcomeOwnsStartup) {
                    // Do not let a synchronized previous UI state turn the
                    // lamps on before the one-shot welcome has inspected them.
                    latestCct = null
                    ensureConnections { evaluateWelcomeIfReady() }
                } else {
                    latestCct = prefs.white to prefs.brightness
                    ensureConnections {
                        if (prefs.power) sendPower(true) { pumpLatestCct() }
                        else sendPower(false)
                    }
                }
            }
            onApplied(changed)
        }
    }

    override fun onUpdateStatus(text: String) {
        handler.post { event(EVENT_UPDATE, message = text) }
    }

    override fun onDestroy() {
        strobeActive = false
        prefs.strobeActive = false
        strobeGeneration++
        hubTransport?.stop()
        hubTransport = null
        disconnectAll()
        super.onDestroy()
    }

    companion object {
        const val ACTION_HUB_START = "ua.grey.qstarlight.HUB_START"
        const val ACTION_HUB_WAKE = "ua.grey.qstarlight.HUB_WAKE"
        const val ACTION_SCAN = "ua.grey.qstarlight.SCAN"
        const val ACTION_CONNECT = "ua.grey.qstarlight.CONNECT"
        const val ACTION_CONNECT_DEVICE = "ua.grey.qstarlight.CONNECT_DEVICE"
        const val ACTION_RELEASE = "ua.grey.qstarlight.RELEASE"
        const val ACTION_APPLY = "ua.grey.qstarlight.APPLY"
        const val ACTION_POWER = "ua.grey.qstarlight.POWER"
        const val ACTION_PRESET = "ua.grey.qstarlight.PRESET"
        const val ACTION_BRIGHTNESS_DELTA = "ua.grey.qstarlight.BRIGHTNESS_DELTA"
        const val ACTION_STROBE = "ua.grey.qstarlight.STROBE"
        const val ACTION_CONFIG_CHANGED = "ua.grey.qstarlight.CONFIG_CHANGED"
        const val ACTION_BOOT = "ua.grey.qstarlight.BOOT"
        const val ACTION_EVENT = "ua.grey.qstarlight.EVENT"

        const val EXTRA_WHITE = "white"
        const val EXTRA_BRIGHTNESS = "brightness"
        const val EXTRA_POWER = "power"
        const val EXTRA_ONE_SHOT = "one_shot"
        const val EXTRA_DELTA = "delta"
        const val EXTRA_STROBE_ENABLED = "strobe_enabled"
        const val EXTRA_EVENT = "event"
        const val EXTRA_MAC = "mac"
        const val EXTRA_NAME = "name"
        const val EXTRA_MESSAGE = "message"
        const val EXTRA_RSSI = "rssi"

        const val EVENT_DEVICE_FOUND = "device_found"
        const val EVENT_SCAN = "scan"
        const val EVENT_PHASE = "phase"
        const val EVENT_READY = "ready"
        const val EVENT_STATE = "state"
        const val EVENT_PASSWORD = "password"
        const val EVENT_RSSI = "rssi"
        const val EVENT_WRITE = "write"
        const val EVENT_ERROR = "error"
        const val EVENT_DISCONNECTED = "disconnected"
        const val EVENT_STROBE = "strobe"
        const val EVENT_PHONE_LINK = "phone_link"
        const val EVENT_UI_STATE = "ui_state"
        const val EVENT_CONFIG_SYNC = "config_sync"
        const val EVENT_UPDATE = "update"

        private const val CHANNEL_ID = "qstar_ble"
        private const val NOTIFICATION_ID = 7001

        fun start(context: Context, intent: Intent) {
            try {
                ContextCompat.startForegroundService(context, intent.setClass(context, QStarBleService::class.java))
            } catch (t: Throwable) {
                Log.e("QStarBle", "Unable to start BLE service", t)
            }
        }
    }
}
