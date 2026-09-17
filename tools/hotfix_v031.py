from pathlib import Path


def read(path):
    return Path(path).read_text()


def write(path, text):
    Path(path).write_text(text)


def rep(path, old, new, count=1):
    text = read(path)
    actual = text.count(old)
    if actual < count:
        raise SystemExit(f"{path}: expected {count}, found {actual}: {old[:100]!r}")
    write(path, text.replace(old, new, count))


def between(path, start, end, replacement):
    text = read(path)
    a = text.find(start)
    if a < 0:
        raise SystemExit(f"{path}: start marker not found: {start}")
    b = text.find(end, a)
    if b < 0:
        raise SystemExit(f"{path}: end marker not found: {end}")
    write(path, text[:a] + replacement + text[b:])


# Version.
rep('app/build.gradle.kts', 'versionCode = 3', 'versionCode = 4')
rep('app/build.gradle.kts', 'versionName = "0.3.0"', 'versionName = "0.3.1"')

# Phone foreground service can use the connectedDevice type on Android 14+ without crashing.
rep(
    'app/src/main/AndroidManifest.xml',
    '    <uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />\n',
    '    <uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />\n'
    '    <uses-permission android:name="android.permission.CHANGE_NETWORK_STATE" />\n'
    '    <uses-permission android:name="android.permission.CHANGE_WIFI_STATE" />\n'
)

# Field test: physical CCT command bytes are yellow first, white second.
rep(
    'app/src/main/java/ua/grey/qstarlight/ble/QStarProtocol.kt',
    '        return bytes(0x56, w, y, b, mode.coerceIn(0, 255), 0x00, 0xAA)',
    '        return bytes(0x56, y, w, b, mode.coerceIn(0, 255), 0x00, 0xAA)'
)
rep(
    'app/src/test/java/ua/grey/qstarlight/ble/QStarProtocolTest.kt',
    '            byteArrayOf(0x56, 0x64, 0x00, 0x64, 0x00, 0x00, 0xAA.toByte()),\n            QStarProtocol.cctFrame(100, 100)',
    '            byteArrayOf(0x56, 0x00, 0x64, 0x64, 0x00, 0x00, 0xAA.toByte()),\n            QStarProtocol.cctFrame(100, 100)'
)
rep(
    'app/src/test/java/ua/grey/qstarlight/ble/QStarProtocolTest.kt',
    '            byteArrayOf(0x56, 0x00, 0x64, 0x64, 0x00, 0x00, 0xAA.toByte()),\n            QStarProtocol.cctFrame(0, 100)',
    '            byteArrayOf(0x56, 0x64, 0x00, 0x64, 0x00, 0x00, 0xAA.toByte()),\n            QStarProtocol.cctFrame(0, 100)'
)

# Head unit role detection. CYCLONE is Android 10; phone is modern Android.
rep(
    'app/src/main/java/ua/grey/qstarlight/ble/BlePrefs.kt',
    '''    private fun inferRole(): Role {
        val dm: DisplayMetrics = context.resources.displayMetrics
        val smallestDp = minOf(dm.widthPixels / dm.density, dm.heightPixels / dm.density)
        return if (Build.VERSION.SDK_INT <= 29 && smallestDp >= 540f) Role.HUB else Role.PHONE
    }
''',
    '''    private fun inferRole(): Role {
        return if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.R) Role.HUB else Role.PHONE
    }
'''
)

# Include live state in phone<->hub sync and permit authoritative phone pushes.
rep(
    'app/src/main/java/ua/grey/qstarlight/ble/BlePrefs.kt',
    '            .put("revision", configRevision)\n            .put("startupMode", startupMode.name)',
    '            .put("revision", configRevision)\n'
    '            .put("power", power)\n'
    '            .put("white", white)\n'
    '            .put("brightness", brightness)\n'
    '            .put("startupMode", startupMode.name)'
)
rep(
    'app/src/main/java/ua/grey/qstarlight/ble/BlePrefs.kt',
    '    fun applySyncConfig(json: JSONObject): Boolean {\n        val incomingRevision = json.optLong("revision", 0L)\n        if (incomingRevision <= configRevision) return false\n\n        val e = prefs.edit()\n            .putString(KEY_STARTUP_MODE, json.optString("startupMode", startupMode.name))',
    '    fun applySyncConfig(json: JSONObject, force: Boolean = false): Boolean {\n'
    '        val incomingRevision = json.optLong("revision", 0L)\n'
    '        if (incomingRevision <= 0L) return false\n'
    '        if (!force && incomingRevision <= configRevision) return false\n\n'
    '        val e = prefs.edit()\n'
    '            .putBoolean("power", json.optBoolean("power", power))\n'
    '            .putInt("white", json.optInt("white", white).coerceIn(0, 100))\n'
    '            .putInt("brightness", json.optInt("brightness", brightness).coerceIn(5, 100))\n'
    '            .putString(KEY_STARTUP_MODE, json.optString("startupMode", startupMode.name))'
)

# Hub acknowledges config explicitly.
rep(
    'app/src/main/java/ua/grey/qstarlight/remote/HubTransport.kt',
    '''                    "config_sync" -> {
                        val config = json.optJSONObject("config")
                        if (config != null && listener.onRemoteConfig(config)) {
                            publishConfig()
                            publishStatus("Налаштування синхронізовано")
                        } else {
                            sendConfig(client)
                        }
                    }
''',
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
'''
)

remote = 'app/src/main/java/ua/grey/qstarlight/remote/RemoteLinkService.kt'
rep(remote, 'import android.os.Looper\n', 'import android.os.Looper\nimport android.util.Log\n')
rep(remote, '    private var lastAutoPushedVersion = -1L\n', '    private var lastAutoPushedVersion = -1L\n    @Volatile private var pendingConfigRevision: Long? = null\n')
rep(
    remote,
    '''                "config_sync" -> {
                    val config = json.optJSONObject("config")
                    if (config != null && prefs.applySyncConfig(config)) {
                        broadcast(EVENT_CONFIG_SYNC, "Налаштування отримано з магнітоли", host, line)
                    } else {
                        broadcast(EVENT_CONFIG_SYNC, "Налаштування синхронні", host, line)
                    }
                }
''',
    '''                "config_ack" -> {
                    val revision = json.optLong("revision", -1L)
                    if (pendingConfigRevision == revision) {
                        pendingConfigRevision = null
                        broadcast(EVENT_CONFIG_SYNC, "Налаштування підтверджено магнітолою", host, line)
                    }
                }
                "config_sync" -> {
                    val config = json.optJSONObject("config")
                    if (pendingConfigRevision != null) {
                        broadcast(EVENT_CONFIG_SYNC, "Очікую підтвердження налаштувань телефона", host, line)
                    } else if (config != null && prefs.applySyncConfig(config)) {
                        broadcast(EVENT_CONFIG_SYNC, "Налаштування отримано з магнітоли", host, line)
                    } else {
                        broadcast(EVENT_CONFIG_SYNC, "Налаштування синхронні", host, line)
                    }
                }
'''
)
rep(
    remote,
    '''    private fun sendConfigNow() {
        if (connected && writer != null) {
            sendLine(JSONObject().put("type", "config_sync").put("config", prefs.syncConfigJson()))
        }
    }
''',
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
'''
)
rep(
    remote,
    '''        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, RemoteLinkService::class.java).setAction(ACTION_START))
        }
''',
    '''        private fun launch(context: Context, intent: Intent) {
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (t: Throwable) {
                Log.e("QStarRemote", "Unable to start remote service", t)
            }
        }

        fun start(context: Context) {
            launch(context, Intent(context, RemoteLinkService::class.java).setAction(ACTION_START))
        }
'''
)
rep(remote, '            ContextCompat.startForegroundService(context, Intent(context, RemoteLinkService::class.java).setAction(ACTION_ROUTE_CHANGED))', '            launch(context, Intent(context, RemoteLinkService::class.java).setAction(ACTION_ROUTE_CHANGED))')
rep(remote, '            ContextCompat.startForegroundService(context, Intent(context, RemoteLinkService::class.java).setAction(ACTION_PUSH_CONFIG))', '            launch(context, Intent(context, RemoteLinkService::class.java).setAction(ACTION_PUSH_CONFIG))')
rep(remote, '            ContextCompat.startForegroundService(context, Intent(context, RemoteLinkService::class.java).setAction(ACTION_PUSH_UPDATE))', '            launch(context, Intent(context, RemoteLinkService::class.java).setAction(ACTION_PUSH_UPDATE))')
rep(remote, '            ContextCompat.startForegroundService(context, i)\n        }\n    }\n}', '            launch(context, i)\n        }\n    }\n}', 1)

# GATT queue hardening and stock-like password timing.
lamp = 'app/src/main/java/ua/grey/qstarlight/ble/LampConnection.kt'
rep(lamp, '    private var activeOp: Op? = null\n', '    private var activeOp: Op? = null\n    private var activeOpTimeout: Runnable? = null\n')
rep(
    lamp,
    '''        enqueue(Op.DescriptorWrite(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) { ok ->
            if (ok) {
                setPhase(Phase.HANDSHAKE)
                sendHandshake()
            } else {
                fail("CCCD write failed")
            }
        })
''',
    '''        enqueue(Op.DescriptorWrite(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) { _ ->
            setPhase(Phase.HANDSHAKE)
            sendHandshake()
        })
'''
)
rep(lamp, '                }, 120)\n', '                }, 500)\n')
rep(
    lamp,
    '''        if (!started) finishActive(false)
    }

    private fun finishActive(ok: Boolean) {
        val op = activeOp
        activeOp = null
''',
    '''        if (!started) {
            finishActive(false)
        } else {
            val timeout = Runnable {
                if (activeOp === op && !closed) {
                    finishActive(false)
                    fail("GATT operation timeout")
                    disconnect()
                }
            }
            activeOpTimeout = timeout
            handler.postDelayed(timeout, 2500)
        }
    }

    private fun finishActive(ok: Boolean) {
        activeOpTimeout?.let(handler::removeCallbacks)
        activeOpTimeout = null
        val op = activeOp
        activeOp = null
'''
)

# UI stability: preserve scroll position and don't let sliders drag the containing page.
main = 'app/src/main/java/ua/grey/qstarlight/MainActivity.kt'
rep(main, 'import android.view.View\n', 'import android.view.View\nimport android.view.MotionEvent\n')
rep(main, 'import android.widget.SeekBar\n', 'import android.widget.SeekBar\nimport android.widget.ScrollView\n')
rep(main, '    private lateinit var controlPage: View\n    private lateinit var settingsPage: View\n', '    private lateinit var controlPage: ScrollView\n    private lateinit var settingsPage: ScrollView\n')
rep(
    main,
    '        val old = updatingUi\n        updatingUi = true\n\n        seekTemp.progress = prefs.white',
    '        val old = updatingUi\n        val controlScrollY = controlPage.scrollY\n        val settingsScrollY = settingsPage.scrollY\n        updatingUi = true\n\n        seekTemp.progress = prefs.white'
)
rep(
    main,
    '        editRemotePin.setText(prefs.remotePin)\n        editHubIp.setText(prefs.manualHubHost)\n',
    '        if (!editRemotePin.hasFocus() && editRemotePin.text.toString() != prefs.remotePin) editRemotePin.setText(prefs.remotePin)\n'
    '        if (!editHubIp.hasFocus() && editHubIp.text.toString() != prefs.manualHubHost) editHubIp.setText(prefs.manualHubHost)\n'
)
rep(
    main,
    '        updateSyncStatus("Синхронізовано")\n        updatingUi = old\n    }',
    '        updateSyncStatus("Синхронізовано")\n'
    '        updatingUi = old\n'
    '        handler.post { controlPage.scrollTo(0, controlScrollY); settingsPage.scrollTo(0, settingsScrollY) }\n'
    '    }',
    1
)
rep(
    main,
    '        seekTemp.setOnSeekBarChangeListener(mainSeekListener)\n        seekBrightness.setOnSeekBarChangeListener(mainSeekListener)\n',
    '        seekTemp.setOnSeekBarChangeListener(mainSeekListener)\n'
    '        seekBrightness.setOnSeekBarChangeListener(mainSeekListener)\n'
    '        listOf(seekTemp, seekBrightness).forEach(::lockScrollWhileSeeking)\n'
)
rep(
    main,
    '    private fun bindSyncSeek(seek: SeekBar, setter: (Int) -> Unit) {\n        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {',
    '    private fun bindSyncSeek(seek: SeekBar, setter: (Int) -> Unit) {\n        lockScrollWhileSeeking(seek)\n        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {'
)
rep(
    main,
    '    override fun onStart() {\n        super.onStart()\n',
    '    private fun lockScrollWhileSeeking(seek: SeekBar) {\n'
    '        seek.setOnTouchListener { view, event ->\n'
    '            when (event.actionMasked) {\n'
    '                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> view.parent?.requestDisallowInterceptTouchEvent(true)\n'
    '                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> view.parent?.requestDisallowInterceptTouchEvent(false)\n'
    '            }\n'
    '            false\n'
    '        }\n'
    '    }\n\n'
    '    override fun onStart() {\n        super.onStart()\n'
)
rep(
    main,
    '        if (ensurePermissions(false) || prefs.role() == BlePrefs.Role.PHONE) ControlDispatcher.connect(this)\n',
    '        runCatching { if (ensurePermissions(false) || prefs.role() == BlePrefs.Role.PHONE) ControlDispatcher.connect(this) }\n'
    '            .onFailure { appendLog("START ERROR ${it.javaClass.simpleName}: ${it.message.orEmpty()}") }\n'
)

# BLE service: reconnect forever, handle Android 10 scanning gracefully, require both lamps before applying desired state,
# and use white 100% as the recovery safety state.
svc = 'app/src/main/java/ua/grey/qstarlight/ble/QStarBleService.kt'
rep(svc, 'import android.os.Looper\n', 'import android.os.Looper\nimport android.os.SystemClock\nimport android.util.Log\n')
rep(
    svc,
    '    private var scanActive = false\n    private var bootPending = false\n',
    '    private var scanActive = false\n'
    '    private var reconnectScheduled = false\n'
    '    private var reconnectRounds = 0\n'
    '    private val readyMacs = linkedSetOf<String>()\n'
    '    private var safetyFallbackActive = false\n'
    '    private var bootPending = false\n'
)
rep(
    svc,
    '        return START_NOT_STICKY\n    }\n\n    private fun ensureHubTransport()',
    '        return if (interactive && !oneShot) START_STICKY else START_NOT_STICKY\n    }\n\n    private fun ensureHubTransport()',
    1
)
rep(svc, '        connections.clear()\n        connectPlan.clear()', '        connections.clear()\n        readyMacs.clear()\n        connectPlan.clear()', 1)

between(
    svc,
    '    private fun ensureConnections(afterReady: (() -> Unit)? = null) {',
    '    private fun onAllReady() {',
    '''    private var pendingAfterReady: (() -> Unit)? = null

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
            prefs.devices().forEach { ref ->
                if (connections[ref.mac]?.isReady() != true && connectingMac != ref.mac && connectPlan.none { it.mac == ref.mac }) connectPlan.add(ref)
            }
            pumpConnectPlan()
            if (!allSelectedReady()) scheduleReconnect()
        }, delayMs)
    }

'''
)

between(
    svc,
    '    private fun onAllReady() {',
    '    private fun pumpLatestCct() {',
    '''    private fun onAllReady() {
        if (!allSelectedReady()) {
            scheduleReconnect()
            return
        }
        reconnectRounds = 0
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

'''
)
rep(
    svc,
    '    private fun pumpLatestCct() {\n        if (sendingCct || strobeActive) return\n        val desired = latestCct ?: return',
    '    private fun pumpLatestCct() {\n        if (sendingCct || strobeActive) return\n        if (!allSelectedReady()) { scheduleReconnect(); return }\n        val desired = latestCct ?: return'
)
between(
    svc,
    '    private fun sendFrameAll(frame: ByteArray, done: () -> Unit) {',
    '    private fun applyPowerStates(states: List<Boolean>, done: () -> Unit) {',
    '''    private fun sendFrameAll(frame: ByteArray, done: () -> Unit) {
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

'''
)
rep(
    svc,
    '''    override fun onReady(mac: String) {
        event(EVENT_READY, mac, connections[mac]?.name, "ready")
        if (connectingMac == mac) connectingMac = null
        handler.postDelayed({ pumpConnectPlan() }, 550)
    }
''',
    '''    override fun onReady(mac: String) {
        event(EVENT_READY, mac, connections[mac]?.name, "ready")
        readyMacs.add(mac)
        if (connectingMac == mac) connectingMac = null
        val connection = connections[mac]
        if (safetyFallbackActive && connection != null) {
            sendSafetyTo(connection) { handler.postDelayed({ pumpConnectPlan() }, 180) }
        } else {
            handler.postDelayed({ pumpConnectPlan() }, 300)
        }
    }
'''
)
rep(
    svc,
    '''    override fun onError(mac: String, message: String) {
        event(EVENT_ERROR, mac, connections[mac]?.name, message)
        if (connectingMac == mac) {
            connectingMac = null
            handler.postDelayed({ pumpConnectPlan() }, 300)
        }
    }

    override fun onDisconnected(mac: String, status: Int) {
        event(EVENT_DISCONNECTED, mac, connections[mac]?.name, "status=$status")
        if (interactive && !remoteTakeover) {
            val ref = prefs.devices().firstOrNull { it.mac == mac } ?: return
            handler.postDelayed({
                if (connections[mac]?.isReady() != true && !connectPlan.any { it.mac == mac }) {
                    connectPlan.add(ref)
                    pumpConnectPlan()
                }
            }, 1_500)
        }
    }
''',
    '''    override fun onError(mac: String, message: String) {
        event(EVENT_ERROR, mac, connections[mac]?.name, message)
        val wasReady = readyMacs.remove(mac)
        if (connectingMac == mac) connectingMac = null
        if (wasReady && !remoteTakeover) activateSafetyFallback("error:$message")
        if (!remoteTakeover) scheduleReconnect(350)
    }

    override fun onDisconnected(mac: String, status: Int) {
        event(EVENT_DISCONNECTED, mac, connections[mac]?.name, "status=$status")
        val wasReady = readyMacs.remove(mac)
        if (connectingMac == mac) connectingMac = null
        if (wasReady && !remoteTakeover) activateSafetyFallback("disconnect:$status")
        if (!remoteTakeover) scheduleReconnect(300)
    }
'''
)
rep(
    svc,
    '''    override fun onRemoteConfig(config: JSONObject): Boolean {
        val changed = prefs.applySyncConfig(config)
        if (changed) handler.post { event(EVENT_CONFIG_SYNC, message = "config_from_phone") }
        return changed
    }
''',
    '''    override fun onRemoteConfig(config: JSONObject): Boolean {
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
'''
)
rep(
    svc,
    '''        fun start(context: Context, intent: Intent) {
            ContextCompat.startForegroundService(context, intent.setClass(context, QStarBleService::class.java))
        }
''',
    '''        fun start(context: Context, intent: Intent) {
            try {
                ContextCompat.startForegroundService(context, intent.setClass(context, QStarBleService::class.java))
            } catch (t: Throwable) {
                Log.e("QStarBle", "Unable to start BLE service", t)
            }
        }
'''
)

Path('.hotfix-v031-applied').write_text('QStarLight 0.3.1 stability hotfix applied\n')
print('hotfix applied')
