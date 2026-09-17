from pathlib import Path


def read(path):
    return Path(path).read_text()


def write(path, text):
    Path(path).write_text(text)


def rep(path, old, new, count=1):
    text = read(path)
    found = text.count(old)
    if found < count:
        raise SystemExit(f"{path}: expected {count}, found {found}: {old[:140]!r}")
    write(path, text.replace(old, new, count))


# v0.3.2
rep('app/build.gradle.kts', 'versionCode = 4', 'versionCode = 5')
rep('app/build.gradle.kts', 'versionName = "0.3.1"', 'versionName = "0.3.2"')

# Every live light change must advance the shared revision and be pushed to HUB.
control = 'app/src/main/java/ua/grey/qstarlight/control/ControlDispatcher.kt'
rep(control,
'''        prefs.white = white
        prefs.brightness = brightness
        dispatch(context, CMD_APPLY, white = white, brightness = brightness)
        QStarWidgetProvider.refresh(context)
''',
'''        prefs.white = white
        prefs.brightness = brightness
        prefs.touchConfig()
        dispatch(context, CMD_APPLY, white = white, brightness = brightness)
        configChanged(context)
        QStarWidgetProvider.refresh(context)
''')
rep(control,
'''        prefs.power = on
        dispatch(context, CMD_POWER, power = on)
        QStarWidgetProvider.refresh(context)
''',
'''        prefs.power = on
        prefs.touchConfig()
        dispatch(context, CMD_POWER, power = on)
        configChanged(context)
        QStarWidgetProvider.refresh(context)
''')
rep(control,
'''        prefs.brightness = value.coerceIn(5, 100)
        dispatch(context, CMD_APPLY, white = prefs.white, brightness = prefs.brightness)
        QStarWidgetProvider.refresh(context)
''',
'''        prefs.brightness = value.coerceIn(5, 100)
        prefs.touchConfig()
        dispatch(context, CMD_APPLY, white = prefs.white, brightness = prefs.brightness)
        configChanged(context)
        QStarWidgetProvider.refresh(context)
''')
rep(control,
'''        prefs.brightness = (prefs.brightness + delta).coerceIn(5, 100)
        dispatch(context, CMD_BRIGHTNESS_DELTA, delta = delta)
        QStarWidgetProvider.refresh(context)
''',
'''        prefs.brightness = (prefs.brightness + delta).coerceIn(5, 100)
        prefs.touchConfig()
        dispatch(context, CMD_BRIGHTNESS_DELTA, delta = delta)
        configChanged(context)
        QStarWidgetProvider.refresh(context)
''')

# Do not lie in UI that sync is complete before HUB ACKs it.
main = 'app/src/main/java/ua/grey/qstarlight/MainActivity.kt'
rep(main,
'''        val task = Runnable {
            ControlDispatcher.configChanged(this)
            updateSyncStatus("Синхронізовано")
        }
''',
'''        val task = Runnable {
            ControlDispatcher.configChanged(this)
            updateSyncStatus("Відправлено • очікую HUB")
        }
''')

# Remote config sync: keep the exact config pending and retransmit until an explicit ACK.
remote = 'app/src/main/java/ua/grey/qstarlight/remote/RemoteLinkService.kt'
rep(remote,
'''    @Volatile private var pendingConfigRevision: Long? = null
''',
'''    @Volatile private var pendingConfigRevision: Long? = null
    @Volatile private var pendingConfigJson: JSONObject? = null
    @Volatile private var configRetryScheduled = false
''')
rep(remote,
'''                    if (pendingConfigRevision == revision) {
                        pendingConfigRevision = null
                        broadcast(EVENT_CONFIG_SYNC, "Налаштування підтверджено магнітолою", host, line)
                    }
''',
'''                    if (pendingConfigRevision == revision) {
                        pendingConfigRevision = null
                        pendingConfigJson = null
                        configRetryScheduled = false
                        broadcast(EVENT_CONFIG_SYNC, "Налаштування підтверджено магнітолою", host, line)
                    }
''')
rep(remote,
'''    private fun sendConfigNow() {
        if (connected && writer != null) {
            val config = prefs.syncConfigJson()
            val revision = config.optLong("revision", prefs.configRevision)
            pendingConfigRevision = revision
            if (!sendLine(JSONObject().put("type", "config_sync").put("config", config))) {
                pendingConfigRevision = null
            }
        }
    }
''',
'''    private fun sendConfigNow() {
        val config = prefs.syncConfigJson()
        pendingConfigRevision = config.optLong("revision", prefs.configRevision)
        pendingConfigJson = config
        sendPendingConfig()
    }

    private fun sendPendingConfig() {
        val config = pendingConfigJson ?: return
        if (connected && writer != null) {
            sendLine(JSONObject().put("type", "config_sync").put("config", config))
        }
        if (configRetryScheduled) return
        configRetryScheduled = true
        handler.postDelayed({
            configRetryScheduled = false
            if (pendingConfigJson != null) sendPendingConfig()
        }, 1400)
    }
''')
rep(remote,
'''        hubVersionCode = -1L
        super.onDestroy()
''',
'''        hubVersionCode = -1L
        pendingConfigRevision = null
        pendingConfigJson = null
        configRetryScheduled = false
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
''')

# HUB: every valid phone config is authoritative; always ACK it so retries converge.
hub = 'app/src/main/java/ua/grey/qstarlight/remote/HubTransport.kt'
rep(hub,
'''                    "config_sync" -> {
                        val config = json.optJSONObject("config")
                        if (config != null && listener.onRemoteConfig(config)) {
                            writer.println(JSONObject().put("type", "config_ack").put("revision", config.optLong("revision", 0L)))
                            publishConfig()
                            publishStatus("Налаштування синхронізовано")
                        } else {
                            sendConfig(client)
                        }
                    }
''',
'''                    "config_sync" -> {
                        val config = json.optJSONObject("config")
                        if (config != null && config.optLong("revision", 0L) > 0L) {
                            listener.onRemoteConfig(config)
                            writer.println(JSONObject().put("type", "config_ack").put("revision", config.optLong("revision", 0L)))
                            publishConfig()
                            publishStatus("Налаштування синхронізовано")
                        } else {
                            sendConfig(client)
                        }
                    }
''')

# Lamp connection: relax weak-link operation timeout and lower connection pressure after READY.
lamp = 'app/src/main/java/ua/grey/qstarlight/ble/LampConnection.kt'
rep(lamp, '            handler.postDelayed(timeout, 2500)\n', '            handler.postDelayed(timeout, 6000)\n')
rep(lamp,
'''    private fun markReady() {
        if (closed || phase == Phase.READY) return
        setPhase(Phase.READY)
        listener.onReady(mac)
    }
''',
'''    private fun markReady() {
        if (closed || phase == Phase.READY) return
        try { gatt?.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_BALANCED) } catch (_: Throwable) { }
        setPhase(Phase.READY)
        listener.onReady(mac)
    }
''')

# Main BLE service: Android 10 scan fallback, sticky restart, pair failsafe and gentler RSSI polling.
svc = 'app/src/main/java/ua/grey/qstarlight/ble/QStarBleService.kt'
rep(svc, 'import android.bluetooth.BluetoothManager\n', 'import android.bluetooth.BluetoothManager\nimport android.bluetooth.BluetoothDevice\n')
rep(svc,
'''    private var scanActive = false
    private var reconnectScheduled = false
''',
'''    private var scanActive = false
    private var modernScanActive = false
    private var legacyScanActive = false
    private var scanDoneCallback: (() -> Unit)? = null
    private var scanStopRunnable: Runnable? = null
    private var reconnectScheduled = false
''')
rep(svc,
'''    private val readyMacs = linkedSetOf<String>()
    private var safetyFallbackActive = false
''',
'''    private val readyMacs = linkedSetOf<String>()
    private var pairWasReady = false
    private var safetyFallbackActive = false
''')
rep(svc,
'''    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) return START_NOT_STICKY
        when (intent.action) {
''',
'''    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            interactive = true
            oneShot = false
            startForegroundSafe(if (prefs.role() == BlePrefs.Role.HUB) "Магнітола • QStar hub" else "Прямий BLE • QStar")
            if (prefs.role() == BlePrefs.Role.HUB) ensureHubTransport()
            if (!remoteTakeover) ensureConnections()
            return START_STICKY
        }
        when (intent.action) {
''')
rep(svc,
'''            ACTION_SCAN -> {
                startForegroundSafe("Scanning QStar")
                startScan(8_000)
            }
''',
'''            ACTION_SCAN -> {
                startForegroundSafe("Scanning QStar")
                startScan(8_000) { if (!remoteTakeover) ensureConnections() }
            }
''')

# Replace scan implementation and callback with modern + legacy fallback.
text = read(svc)
start = text.index('    private fun startScan(durationMs: Long, onDone: (() -> Unit)? = null) {')
end = text.index('    private var pendingAfterReady: (() -> Unit)? = null', start)
scan_block = '''    private fun handleDiscoveredDevice(device: BluetoothDevice, name: String?, rssi: Int) {
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

'''
write(svc, text[:start] + scan_block + text[end:])

# Periodically discover by name after repeated direct-MAC failures. This lets the hub recover
# even if the controller changes address, while direct connection remains the fast path.
rep(svc,
'''            reconnectRounds++
            prefs.devices().forEach { ref ->
''',
'''            reconnectRounds++
            if (reconnectRounds % 5 == 0 && !scanActive && hasScanPermission() && adapter.isEnabled) {
                startScan(2600) { ensureConnections() }
                return@postDelayed
            }
            prefs.devices().forEach { ref ->
''')

# Pair has reached the safe synchronized state at least once.
rep(svc,
'''        reconnectRounds = 0
        if (bootPending) {
''',
'''        reconnectRounds = 0
        pairWasReady = true
        if (bootPending) {
''')

# Faster fail-safe recovery and trigger it for any degradation after the pair was established.
rep(svc,
'''        if (wasReady && !remoteTakeover) activateSafetyFallback("error:$message")
        if (!remoteTakeover) scheduleReconnect(350)
''',
'''        if ((wasReady || pairWasReady) && !remoteTakeover) activateSafetyFallback("error:$message")
        if (!remoteTakeover) scheduleReconnect(120)
''')
rep(svc,
'''        if (wasReady && !remoteTakeover) activateSafetyFallback("disconnect:$status")
        if (!remoteTakeover) scheduleReconnect(300)
''',
'''        if ((wasReady || pairWasReady) && !remoteTakeover) activateSafetyFallback("disconnect:$status")
        if (!remoteTakeover) scheduleReconnect(120)
''')

# Do not hammer two weak GATT links with simultaneous RSSI reads every three seconds.
old_rssi = '''    private fun startRssiLoop() {
        rssiLoop = true
        val task = object : Runnable {
            override fun run() {
                if (!interactive) {
                    rssiLoop = false
                    return
                }
                connections.values.forEach { if (it.isReady()) it.readRssi() }
                handler.postDelayed(this, 3_000)
            }
        }
        handler.post(task)
    }
'''
new_rssi = '''    private fun startRssiLoop() {
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
'''
rep(svc, old_rssi, new_rssi)

# Give the first GATT session time to settle before starting the second one.
rep(svc, '            handler.postDelayed({ pumpConnectPlan() }, 300)\n', '            handler.postDelayed({ pumpConnectPlan() }, 850)\n')

# Remote config already waits for both lamps through ensureConnections; keep desired state queued.
rep(svc,
'''        val changed = prefs.applySyncConfig(config, force = true)
        if (changed) handler.post {
            latestCct = prefs.white to prefs.brightness
''',
'''        val changed = prefs.applySyncConfig(config, force = true)
        if (changed) handler.post {
            latestCct = prefs.white to prefs.brightness
''')

# Marker only after all replacements succeeded.
Path('.hotfix-v032-applied').write_text('QStarLight 0.3.2 sync + BLE reliability hotfix applied\n')
