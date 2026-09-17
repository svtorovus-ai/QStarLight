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
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject
import ua.grey.qstarlight.MainActivity
import ua.grey.qstarlight.R
import ua.grey.qstarlight.control.ControlActionReceiver
import ua.grey.qstarlight.control.ControlDispatcher
import ua.grey.qstarlight.remote.HubTransport
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
    private var pairWasReady = false
    private var safetyFallbackActive = false
    private var bootPending = false
    private var latestCct: Pair<Int, Int>? = null
    private var sendingCct = false
    private var rssiLoop = false
    private var hubTransport: HubTransport? = null
    private var remoteTakeover = false

    private var strobeActive = false
    private var strobeGeneration = 0L

    override fun onCreate() {
        super.onCreate()
        prefs = BlePrefs(this).also { it.ensureDefaults() }
        adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        createNotificationChannel()
        if (prefs.role() == BlePrefs.Role.HUB) {
            hubTransport = HubTransport(this, prefs, this)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            interactive = true
            oneShot = false
            startForegroundSafe(if (prefs.role() == BlePrefs.Role.HUB) "Магнітола • QStar hub" else "Прямий BLE • QStar")
            if (prefs.role() == BlePrefs.Role.HUB) ensureHubTransport()
            if (!remoteTakeover) ensureConnections()
            return START_STICKY
        }
        when (intent.action) {
            ACTION_SCAN -> {
                startForegroundSafe("Scanning QStar")
                startScan(8_000) { if (!remoteTakeover) ensureConnections() }
            }
            ACTION_HUB_START -> {
                interactive = true
                oneShot = false
                startForegroundSafe("Магнітола • QStar hub")
                ensureHubTransport()
                if (!remoteTakeover) ensureConnections()
            }
            ACTION_CONNECT -> {
                interactive = true
                oneShot = false
                startForegroundSafe(if (prefs.role() == BlePrefs.Role.HUB) "Магнітола • QStar" else "Прямий BLE • QStar")
                if (prefs.role() == BlePrefs.Role.HUB) ensureHubTransport()
                if (!remoteTakeover) ensureConnections()
            }
            ACTION_RELEASE -> {
                stopStrobeInternal(restore = false)
                if (prefs.role() == BlePrefs.Role.HUB && !remoteTakeover) {
                    interactive = true
                } else {
                    interactive = false
                    disconnectAll()
                    if (!prefs.keepConnected || prefs.role() == BlePrefs.Role.PHONE) shutdownSoon()
                }
            }
            ACTION_APPLY -> {
                stopStrobeInternal(restore = false)
                prefs.white = intent.getIntExtra(EXTRA_WHITE, prefs.white)
                prefs.brightness = intent.getIntExtra(EXTRA_BRIGHTNESS, prefs.brightness)
                oneShot = intent.getBooleanExtra(EXTRA_ONE_SHOT, false)
                startForegroundSafe("Applying light settings")
                latestCct = prefs.white to prefs.brightness
                ensureConnections()
            }
            ACTION_POWER -> {
                stopStrobeInternal(restore = false)
                prefs.power = intent.getBooleanExtra(EXTRA_POWER, prefs.power)
                oneShot = intent.getBooleanExtra(EXTRA_ONE_SHOT, false)
                startForegroundSafe("Changing light power")
                ensureConnections { sendPower(prefs.power) }
            }
            ACTION_PRESET -> {
                stopStrobeInternal(restore = false)
                prefs.white = intent.getIntExtra(EXTRA_WHITE, prefs.white).coerceIn(0, 100)
                oneShot = intent.getBooleanExtra(EXTRA_ONE_SHOT, !interactive)
                startForegroundSafe("Applying preset")
                latestCct = prefs.white to prefs.brightness
                ensureConnections()
            }
            ACTION_BRIGHTNESS_DELTA -> {
                stopStrobeInternal(restore = false)
                prefs.brightness = (prefs.brightness + intent.getIntExtra(EXTRA_DELTA, 0)).coerceIn(5, 100)
                oneShot = intent.getBooleanExtra(EXTRA_ONE_SHOT, !interactive)
                startForegroundSafe("Changing brightness")
                latestCct = prefs.white to prefs.brightness
                ensureConnections()
            }
            ACTION_STROBE -> {
                val requested = if (intent.hasExtra(EXTRA_STROBE_ENABLED)) {
                    intent.getBooleanExtra(EXTRA_STROBE_ENABLED, false)
                } else !strobeActive
                startForegroundSafe(if (requested) "Стробоскоп" else "QStar")
                if (requested) startStrobe() else stopStrobeInternal(restore = true)
            }
            ACTION_CONFIG_CHANGED -> {
                ensureHubTransport()
                hubTransport?.publishConfig()
                hubTransport?.publishStatus("Налаштування оновлено")
            }
            ACTION_BOOT -> {
                if (!prefs.autoBoot) {
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
                bootPending = true
                startForegroundSafe("Відновлення QStar")
                startScan(4_000) { if (!remoteTakeover) ensureConnections() }
            }
        }
        return if (interactive && !oneShot) START_STICKY else START_NOT_STICKY
    }

    private fun ensureHubTransport() {
        if (prefs.role() != BlePrefs.Role.HUB) return
        if (hubTransport == null) hubTransport = HubTransport(this, prefs, this)
        hubTransport?.start()
        hubTransport?.publishStatus(if (remoteTakeover) "Телефон керує напряму" else "Магнітола керує лампами")
    }

    private fun disconnectAll() {
        connections.values.forEach { it.disconnect() }
        connections.clear()
        readyMacs.clear()
        connectPlan.clear()
        connectingMac = null
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
        if (!interactive && !bootPending && connections.values.none { it.isReady() }) shutdownSoon()
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
        if (reconnectScheduled || remoteTakeover) return
        val needed = interactive || bootPending || oneShot || latestCct != null || pendingAfterReady != null
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
        pairWasReady = true
        if (bootPending) {
            bootPending = false
            runBootRoutine()
            return
        }
        if (safetyFallbackActive) {
            sendFrameAll(QStarProtocol.POWER_ON) {
                sendFrameAll(QStarProtocol.cctFrame(100, 100)) {
                    safetyFallbackActive = false
                    handler.postDelayed({ restoreDesiredAfterSafety() }, 100)
                }
            }
            return
        }
        finishReadyCycle()
    }

    private fun finishReadyCycle() {
        val callback = pendingAfterReady
        pendingAfterReady = null
        callback?.invoke()
        pumpLatestCct()
        if (interactive && !rssiLoop) startRssiLoop()
    }

    private fun restoreDesiredAfterSafety() {
        if (!allSelectedReady()) {
            safetyFallbackActive = true
            scheduleReconnect(250)
            return
        }
        if (prefs.power) {
            sendFrameAll(QStarProtocol.POWER_ON) {
                sendFrameAll(QStarProtocol.cctFrame(prefs.white, prefs.brightness)) { finishReadyCycle() }
            }
        } else {
            sendFrameAll(QStarProtocol.POWER_OFF) { finishReadyCycle() }
        }
    }

    private fun activateSafetyFallback(reason: String) {
        if (remoteTakeover) return
        stopStrobeInternal(restore = false)
        safetyFallbackActive = true
        latestCct = null
        event(EVENT_PHASE, message = "failsafe_white_100:$reason")
        sendFrameReady(QStarProtocol.POWER_ON) {
            sendFrameReady(QStarProtocol.cctFrame(100, 100)) { }
        }
        scheduleReconnect(250)
    }

    private fun sendSafetyTo(connection: LampConnection, done: () -> Unit) {
        connection.writeControl(QStarProtocol.POWER_ON) {
            connection.writeControl(QStarProtocol.cctFrame(100, 100)) { done() }
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
        sendFrameAll(if (on) QStarProtocol.POWER_ON else QStarProtocol.POWER_OFF) {
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

    private fun sendFrameReady(frame: ByteArray, done: () -> Unit) {
        val list = prefs.devices().mapNotNull { connections[it.mac] }.filter { it.isReady() }
        fun sendAt(index: Int) {
            if (index >= list.size) { done(); return }
            val c = list[index]
            c.writeControl(frame) { handler.postDelayed({ sendAt(index + 1) }, 35) }
        }
        sendAt(0)
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
                sendFrameAll(QStarProtocol.POWER_OFF) { finishBootRoutine() }
            }
            BlePrefs.StartupMode.RESTORE -> {
                if (!prefs.power) {
                    sendFrameAll(QStarProtocol.POWER_OFF) { finishBootRoutine() }
                } else {
                    sendFrameAll(QStarProtocol.POWER_ON) {
                        sendFrameAll(QStarProtocol.cctFrame(prefs.white, prefs.brightness)) { finishBootRoutine() }
                    }
                }
            }
            BlePrefs.StartupMode.START_ONLY -> {
                prefs.power = true
                prefs.white = prefs.startWhite
                prefs.brightness = prefs.startBrightness
                sendFrameAll(QStarProtocol.POWER_ON) {
                    sendFrameAll(QStarProtocol.cctFrame(prefs.startWhite, prefs.startBrightness)) { finishBootRoutine() }
                }
            }
            BlePrefs.StartupMode.FADE_TO_TARGET -> {
                prefs.power = true
                prefs.brightness = prefs.startBrightness
                sendFrameAll(QStarProtocol.POWER_ON) {
                    sendFrameAll(QStarProtocol.cctFrame(prefs.startWhite, prefs.startBrightness)) {
                        if (prefs.fadeDurationMs <= 0 || prefs.startWhite == prefs.targetWhite) {
                            prefs.white = prefs.targetWhite
                            sendFrameAll(QStarProtocol.cctFrame(prefs.targetWhite, prefs.startBrightness)) { finishBootRoutine() }
                        } else {
                            val steps = prefs.fadeSteps.coerceAtLeast(2)
                            val delay = (prefs.fadeDurationMs / steps).coerceAtLeast(20)
                            handler.postDelayed({ fadeStep(1, steps, prefs.startWhite, prefs.targetWhite, prefs.startBrightness, delay) }, 120)
                        }
                    }
                }
            }
        }
    }

    private fun fadeStep(step: Int, total: Int, startWhite: Int, targetWhite: Int, brightness: Int, delayMs: Int) {
        val white = (startWhite + (targetWhite - startWhite) * step / total).coerceIn(0, 100)
        sendFrameAll(QStarProtocol.cctFrame(white, brightness)) {
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
        if (prefs.role() == BlePrefs.Role.HUB) hubTransport?.publishStatus("QStar готові") else shutdownSoon()
    }

    private data class StrobeStep(
        val states: List<Boolean>,
        val delayMs: Int,
        val whites: List<Int?>? = null
    )

    private fun startStrobe() {
        oneShot = false
        interactive = true
        strobeActive = true
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

    private fun strobeSequence(): List<StrobeStep> {
        val count = prefs.devices().size.coerceAtLeast(1)
        val allOn = List(count) { true }
        val allOff = List(count) { false }
        val on = prefs.strobeOnMs
        val off = prefs.strobeOffMs
        val pause = prefs.strobePauseMs
        return when (prefs.strobeMode) {
            BlePrefs.StrobeMode.CLASSIC -> listOf(
                StrobeStep(allOn, on), StrobeStep(allOff, off)
            )
            BlePrefs.StrobeMode.DOUBLE -> listOf(
                StrobeStep(allOn, on), StrobeStep(allOff, off),
                StrobeStep(allOn, on), StrobeStep(allOff, pause)
            )
            BlePrefs.StrobeMode.TRIPLE -> listOf(
                StrobeStep(allOn, on), StrobeStep(allOff, off),
                StrobeStep(allOn, on), StrobeStep(allOff, off),
                StrobeStep(allOn, on), StrobeStep(allOff, pause)
            )
            BlePrefs.StrobeMode.ALTERNATE -> {
                if (count < 2) listOf(StrobeStep(allOn, on), StrobeStep(allOff, off))
                else listOf(
                    StrobeStep(listOf(true, false), on), StrobeStep(allOff, off),
                    StrobeStep(listOf(false, true), on), StrobeStep(allOff, pause)
                )
            }
            BlePrefs.StrobeMode.DOUBLE_ALTERNATE -> {
                if (count < 2) listOf(
                    StrobeStep(allOn, on), StrobeStep(allOff, off),
                    StrobeStep(allOn, on), StrobeStep(allOff, pause)
                ) else listOf(
                    StrobeStep(listOf(true, false), on), StrobeStep(allOff, off),
                    StrobeStep(listOf(true, false), on), StrobeStep(allOff, pause / 2),
                    StrobeStep(listOf(false, true), on), StrobeStep(allOff, off),
                    StrobeStep(listOf(false, true), on), StrobeStep(allOff, pause)
                )
            }
            BlePrefs.StrobeMode.YELLOW_WHITE_SWAP -> {
                if (count < 2) {
                    listOf(StrobeStep(allOn, on), StrobeStep(allOff, off))
                } else {
                    // Requested pattern:
                    // L yellow -> dark -> R white -> dark -> L white -> dark -> R yellow -> dark.
                    // It intentionally ignores the long series pause and caps timings for a rapid effect.
                    val fastOn = on.coerceIn(40, 80)
                    val fastOff = off.coerceIn(40, 60)
                    listOf(
                        StrobeStep(listOf(true, false), fastOn, listOf(0, null)),
                        StrobeStep(allOff, fastOff),
                        StrobeStep(listOf(false, true), fastOn, listOf(null, 100)),
                        StrobeStep(allOff, fastOff),
                        StrobeStep(listOf(true, false), fastOn, listOf(100, null)),
                        StrobeStep(allOff, fastOff),
                        StrobeStep(listOf(false, true), fastOn, listOf(null, 0)),
                        StrobeStep(allOff, fastOff)
                    )
                }
            }
        }
    }

    private fun applyStrobeStep(step: StrobeStep, done: () -> Unit) {
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

    private fun shutdownSoon() {
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
        event(EVENT_PHASE, mac, connections[mac]?.name, phase.name)
    }

    override fun onReady(mac: String) {
        event(EVENT_READY, mac, connections[mac]?.name, "ready")
        readyMacs.add(mac)
        if (connectingMac == mac) connectingMac = null
        val connection = connections[mac]
        if (safetyFallbackActive && connection != null) {
            sendSafetyTo(connection) { handler.postDelayed({ pumpConnectPlan() }, 180) }
        } else {
            handler.postDelayed({ pumpConnectPlan() }, 850)
        }
    }

    override fun onState(mac: String, state: QStarProtocol.LampState) {
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
        event(EVENT_ERROR, mac, connections[mac]?.name, message)
        val wasReady = readyMacs.remove(mac)
        if (connectingMac == mac) connectingMac = null
        if ((wasReady || pairWasReady) && !remoteTakeover) activateSafetyFallback("error:$message")
        if (!remoteTakeover) scheduleReconnect(120)
    }

    override fun onDisconnected(mac: String, status: Int) {
        event(EVENT_DISCONNECTED, mac, connections[mac]?.name, "status=$status")
        val wasReady = readyMacs.remove(mac)
        if (connectingMac == mac) connectingMac = null
        if ((wasReady || pairWasReady) && !remoteTakeover) activateSafetyFallback("disconnect:$status")
        if (!remoteTakeover) scheduleReconnect(120)
    }

    private fun event(type: String, mac: String? = null, name: String? = null, message: String? = null, rssi: Int? = null) {
        val i = Intent(ACTION_EVENT).setPackage(packageName)
            .putExtra(EXTRA_EVENT, type)
            .putExtra(EXTRA_MAC, mac)
            .putExtra(EXTRA_NAME, name)
            .putExtra(EXTRA_MESSAGE, message)
        if (rssi != null) i.putExtra(EXTRA_RSSI, rssi)
        sendBroadcast(i)
        hubTransport?.publishBle(type, mac, name, message)
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

    override fun onRemoteCommand(command: String, payload: JSONObject) {
        handler.post {
            when (command) {
                ControlDispatcher.CMD_PRESET -> {
                    stopStrobeInternal(restore = false)
                    prefs.white = payload.optInt("white", prefs.white).coerceIn(0, 100)
                    latestCct = prefs.white to prefs.brightness
                    ensureConnections()
                }
                ControlDispatcher.CMD_APPLY -> {
                    stopStrobeInternal(restore = false)
                    prefs.white = payload.optInt("white", prefs.white).coerceIn(0, 100)
                    prefs.brightness = payload.optInt("brightness", prefs.brightness).coerceIn(5, 100)
                    latestCct = prefs.white to prefs.brightness
                    ensureConnections()
                }
                ControlDispatcher.CMD_POWER -> {
                    stopStrobeInternal(restore = false)
                    prefs.power = payload.optBoolean("power", prefs.power)
                    ensureConnections { sendPower(prefs.power) }
                }
                ControlDispatcher.CMD_BRIGHTNESS_DELTA -> {
                    stopStrobeInternal(restore = false)
                    prefs.brightness = (prefs.brightness + payload.optInt("delta", 0)).coerceIn(5, 100)
                    latestCct = prefs.white to prefs.brightness
                    ensureConnections()
                }
                ControlDispatcher.CMD_STROBE -> {
                    val enabled = if (payload.has("enabled")) payload.optBoolean("enabled") else !strobeActive
                    if (enabled) startStrobe() else stopStrobeInternal(restore = true)
                }
                ControlDispatcher.CMD_CONNECT -> ensureConnections()
            }
            hubTransport?.publishStatus("Команда з телефону")
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
                event(EVENT_PHASE, message = "hub_control_resumed")
                ensureConnections()
            }
        }
    }

    override fun onRemoteConfig(config: JSONObject): Boolean {
        val changed = prefs.applySyncConfig(config, force = true)
        if (changed) handler.post {
            latestCct = prefs.white to prefs.brightness
            event(EVENT_CONFIG_SYNC, message = "config_from_phone")
            ensureConnections {
                if (prefs.power) sendFrameAll(QStarProtocol.POWER_ON) { pumpLatestCct() }
                else sendFrameAll(QStarProtocol.POWER_OFF) { }
            }
        }
        return changed
    }

    override fun onUpdateStatus(text: String) {
        handler.post { event(EVENT_UPDATE, message = text) }
    }

    override fun onDestroy() {
        strobeActive = false
        strobeGeneration++
        hubTransport?.stop()
        hubTransport = null
        disconnectAll()
        super.onDestroy()
    }

    companion object {
        const val ACTION_HUB_START = "ua.grey.qstarlight.HUB_START"
        const val ACTION_SCAN = "ua.grey.qstarlight.SCAN"
        const val ACTION_CONNECT = "ua.grey.qstarlight.CONNECT"
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
