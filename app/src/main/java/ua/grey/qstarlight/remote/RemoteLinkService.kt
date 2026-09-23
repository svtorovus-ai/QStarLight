package ua.grey.qstarlight.remote

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import ua.grey.qstarlight.MainActivity
import ua.grey.qstarlight.PresenceMonitor
import ua.grey.qstarlight.R
import ua.grey.qstarlight.ble.BlePrefs
import ua.grey.qstarlight.ble.QStarBleService
import ua.grey.qstarlight.control.ControlActionReceiver
import ua.grey.qstarlight.control.ControlDispatcher
import ua.grey.qstarlight.update.UpdateManager
import ua.grey.qstarlight.widget.QStarWidgetProvider
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import ua.grey.qstarlight.diagnostics.DiagnosticLog
import ua.grey.qstarlight.sync.ConfigVersion
import ua.grey.qstarlight.sync.ConfigSyncStatus
import kotlin.concurrent.thread

class RemoteLinkService : Service() {
    private lateinit var prefs: BlePrefs
    private val running = AtomicBoolean(false)
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var socket: Socket? = null
    @Volatile private var writer: PrintWriter? = null
    @Volatile private var hubProtocol = 2
    @Volatile private var currentHost: String = ""
    private val sendLock = Any()
    private val sender = Executors.newSingleThreadExecutor()
    @Volatile private var updateInProgress = false
    private var lastAutoPushedVersion = -1L
    private var pendingConfigVersion: ConfigVersion? = null
    private var configSentAt = 0L
    private var configAttempts = 0
    private val configRetry = Runnable { sendPendingConfig() }
    @Volatile private var pendingConfigJson: JSONObject? = null
    private val pendingBleEvents = ConcurrentLinkedQueue<JSONObject>()
    @Volatile private var autoLampWake = false
    @Volatile private var probeOnly = false
    @Volatile private var pendingConnectMissing = false
    private var sessionStopRunnable: Runnable? = null

    override fun onCreate() {
        super.onCreate()
        prefs = BlePrefs(this).also { it.ensureDefaults() }
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (prefs.role() == BlePrefs.Role.PHONE && shouldStartPhoneSession(intent?.action)) {
            if (!prefs.anyPhoneLinkConnected() && !prefs.phoneOfflineGraceActive()) {
                prefs.startPhoneOfflineGrace()
            }
            reconcilePhoneLifetime()
        }
        when (intent?.action) {
            ACTION_STOP -> {
                running.set(false)
                closeSocket()
                QStarBleService.start(this, Intent().setAction(QStarBleService.ACTION_RELEASE))
                PresenceMonitor.stop(this)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_AUTO_WAKE -> {
                autoLampWake = intent.getBooleanExtra(EXTRA_FROM_LAMP, false)
                probeOnly = false
                reconcilePhoneLifetime()
                startForegroundSafe()
                ensureLoop()
            }
            ACTION_PROBE_HUB -> {
                probeOnly = true
                startForegroundSafe()
                ensureLoop()
            }
            ACTION_ROUTE_CHANGED -> {
                startForegroundSafe()
                ensureLoop()
                applyRouteMode()
            }
            ACTION_COMMAND -> {
                startForegroundSafe()
                ensureLoop()
                handleCommandIntent(intent)
            }
            ACTION_PUSH_CONFIG -> {
                startForegroundSafe()
                ensureLoop()
                sendConfigNow()
            }
            ACTION_PUSH_UPDATE -> {
                startForegroundSafe()
                ensureLoop()
                pushSelfUpdate(manual = true)
            }
            ACTION_BLE_EVENT -> {
                startForegroundSafe()
                ensureLoop()
                queueBleEvent(intent)
            }
            else -> {
                reconcilePhoneLifetime()
                startForegroundSafe()
                ensureLoop()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running.set(false)
        closeSocket()
        connected = false
        prefs.hubRuntimeState = BlePrefs.RuntimeLinkState.OFFLINE
        prefs.clearRemoteLampRuntimeStates()
        QStarWidgetProvider.refresh(this)
        hubVersionCode = -1L
        pendingConfigVersion = null
        pendingConfigJson = null
        sessionStopRunnable = null
        handler.removeCallbacksAndMessages(null)
        sender.shutdownNow()
        super.onDestroy()
    }

    private fun ensureLoop() {
        if (!running.compareAndSet(false, true)) return
        thread(name = "QStarRemoteLink", isDaemon = true) { connectionLoop() }
    }

    private fun shouldStartPhoneSession(action: String?): Boolean = action in setOf(
        ACTION_START,
        ACTION_AUTO_WAKE,
        ACTION_ROUTE_CHANGED,
        ACTION_COMMAND,
        ACTION_PUSH_CONFIG,
        ACTION_PUSH_UPDATE
    )

    private fun connectionLoop() {
        while (running.get()) {
            if (prefs.role() != BlePrefs.Role.PHONE) {
                finishIdleService()
                break
            }
            if (!prefs.anyPhoneLinkConnected() && !prefs.phoneOfflineGraceActive() && !probeOnly) {
                finishIdleService()
                break
            }
            prefs.hubRuntimeState = BlePrefs.RuntimeLinkState.CONNECTING
            QStarWidgetProvider.refresh(this)
            broadcast(EVENT_CONNECTING, "Пошук магнітоли…")
            val host = chooseHost()
            if (host == null) {
                setConnected(false, "Магнітолу не знайдено")
                if (probeOnly) {
                    finishProbeOnly()
                    break
                }
                if (autoLampWake || prefs.directControlActive() || prefs.directFallback) startDirectBle()
                sleepQuiet(2200)
                continue
            }
            try {
                broadcast(EVENT_CONNECTING, "Підключення до магнітоли…", host)
                val s = Socket()
                s.connect(InetSocketAddress(host, COMMAND_PORT), 1800)
                s.soTimeout = 5000
                socket = s
                val out = PrintWriter(s.getOutputStream(), true)
                writer = out
                val input = BufferedReader(InputStreamReader(s.getInputStream()))
                val hello = JSONObject()
                    .put("type", "hello")
                    .put("protocol", 3)
                    .put("pin", prefs.remotePin)
                    .put("client", android.os.Build.MODEL ?: "Android")
                    .put("versionCode", UpdateManager.versionCode(this))
                    .put("versionName", UpdateManager.versionName(this))
                    .put("configRevision", prefs.configRevision)
                sendLine(hello)

                val helloLine = input.readLine() ?: throw IllegalStateException("hub closed")
                val helloJson = JSONObject(helloLine)
                if (helloJson.optString("type") != "hello" || !helloJson.optBoolean("ok", false)) {
                    val reason = helloJson.optString("reason", "PIN / авторизація")
                    setConnected(false, "Відмова: $reason", host)
                    closeSocket()
                    sleepQuiet(2500)
                    continue
                }

                currentHost = host
                hubProtocol = helloJson.optInt("protocol", 2)
                prefs.lastHubHost = host
                hubVersionCode = helloJson.optLong("versionCode", -1L)
                setConnected(true, "Магнітола online", host)
                autoLampWake = false
                probeOnly = false
                if (pendingConnectMissing) {
                    sendLine(JSONObject().put("type", "command").put("command", ControlDispatcher.CMD_CONNECT))
                    pendingConnectMissing = false
                }

                // Exchange synchronized settings immediately after authorization.
                sendConfigNow()

                // While a HUB is online it is the single GATT owner. Keeping a
                // phone GATT link alive at the same time makes these controllers
                // randomly reject writes while both UIs still look connected.
                QStarBleService.start(this, Intent().setAction(QStarBleService.ACTION_RELEASE))
                sendSimple("resume")
                sendSimple("status_request")
                sendDirectBleSnapshot()
                flushPendingBleEvents()

                val localVersion = UpdateManager.versionCode(this)
                if (prefs.autoPushUpdates && hubVersionCode in 1 until localVersion && lastAutoPushedVersion != localVersion) {
                    lastAutoPushedVersion = localVersion
                    handler.postDelayed({ pushSelfUpdate(manual = false) }, 1400)
                }

                var lastReplyAt = SystemClock.elapsedRealtime()
                while (running.get() && !s.isClosed) {
                    try {
                        val line = input.readLine() ?: break
                        lastReplyAt = SystemClock.elapsedRealtime()
                        handler.post { if (socket === s && running.get()) handleIncoming(line, host) }
                    } catch (_: SocketTimeoutException) {
                        if (SystemClock.elapsedRealtime() - lastReplyAt >= 15_000) throw SocketTimeoutException("HUB did not reply for 15 seconds")
                        sendSimple("ping")
                    }
                }
            } catch (t: Throwable) {
                DiagnosticLog.write("LINK", "Connection failed: ${t.javaClass.simpleName}: ${t.message}", "ERROR")
                setConnected(false, "Зв'язок: ${t.javaClass.simpleName}", host)
            } finally {
                closeSocket()
                setConnected(false, "Магнітола offline", host)
                hubVersionCode = -1L
                if (probeOnly) {
                    finishProbeOnly()
                    break
                }
                if (prefs.forceDirect) {
                    startDirectBle()
                } else if (prefs.directFallback) {
                    handler.postDelayed({
                        if (!connected && prefs.role() == BlePrefs.Role.PHONE && !prefs.forceDirect) {
                            startDirectBle()
                            broadcast(EVENT_ROUTE, "Резервний прямий BLE")
                        }
                    }, 6000)
                }
            }
            sleepQuiet(1400)
        }
    }

    private fun chooseHost(): String? {
        val manual = prefs.manualHubHost.trim()
        if (manual.isNotEmpty() && probeHost(manual)) return manual
        val last = prefs.lastHubHost.trim()
        if (last.isNotEmpty() && probeHost(last)) return last
        return discoverHub()
    }

    private fun probeHost(host: String): Boolean {
        return try {
            Socket().use { s ->
                s.connect(InetSocketAddress(host, COMMAND_PORT), 450)
                true
            }
        } catch (_: Throwable) { false }
    }

    private fun discoverHub(): String? {
        val socket = DatagramSocket().apply {
            broadcast = true
            soTimeout = 800
        }
        return try {
            val payload = DISCOVER_MAGIC.toByteArray(Charsets.UTF_8)
            val addresses = linkedSetOf<InetAddress>()
            addresses += InetAddress.getByName("255.255.255.255")
            try {
                val interfaces = NetworkInterface.getNetworkInterfaces()
                while (interfaces.hasMoreElements()) {
                    val ni = interfaces.nextElement()
                    if (!ni.isUp || ni.isLoopback) continue
                    ni.interfaceAddresses.mapNotNullTo(addresses) { it.broadcast }
                }
            } catch (_: Throwable) { }
            addresses.forEach { address ->
                try { socket.send(DatagramPacket(payload, payload.size, address, DISCOVERY_PORT)) }
                catch (_: Throwable) { }
            }
            val buf = ByteArray(512)
            val packet = DatagramPacket(buf, buf.size)
            socket.receive(packet)
            val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
            if (text.startsWith(RESPONSE_MAGIC)) {
                val host = packet.address.hostAddress ?: return null
                broadcast(EVENT_DISCOVERED, "Знайдено магнітолу", host)
                host
            } else null
        } catch (_: Throwable) {
            null
        } finally {
            socket.close()
        }
    }

    private fun handleIncoming(line: String, host: String) {
        DiagnosticLog.write("LINK RX", "host=$host $line")
        try {
            val json = JSONObject(line)
            when (json.optString("type")) {
                "pong" -> Unit
                "status" -> {
                    val version = ConfigVersion(json.optLong("configRevision", 0L), json.optString("configOrigin", ""))
                    if (pendingConfigJson == null && version >= prefs.configVersion) {
                        prefs.power = json.optBoolean("power", prefs.power)
                        prefs.white = json.optInt("white", prefs.white)
                        prefs.brightness = json.optInt("brightness", prefs.brightness)
                        QStarWidgetProvider.refresh(this)
                    }
                    hubVersionCode = json.optLong("versionCode", hubVersionCode)
                    broadcast(EVENT_HUB_STATUS, json.optString("text", "status"), host, line)
                }
                "ble_snapshot" -> {
                    val lamps = json.optJSONArray("lamps") ?: return
                    for (index in 0 until lamps.length()) {
                        val lamp = lamps.optJSONObject(index) ?: continue
                        val mac = lamp.optString("mac").trim()
                        if (mac.isBlank()) continue
                        val state = runCatching {
                            BlePrefs.RuntimeLinkState.valueOf(lamp.optString("state", BlePrefs.RuntimeLinkState.OFFLINE.name))
                        }.getOrDefault(BlePrefs.RuntimeLinkState.OFFLINE)
                        prefs.setRemoteLampRuntimeState(mac, state)
                        val lampEvent = when (state) {
                            BlePrefs.RuntimeLinkState.CONNECTED -> QStarBleService.EVENT_READY
                            BlePrefs.RuntimeLinkState.CONNECTING -> QStarBleService.EVENT_PHASE
                            BlePrefs.RuntimeLinkState.OFFLINE -> QStarBleService.EVENT_DISCONNECTED
                        }
                        val lampMessage = if (state == BlePrefs.RuntimeLinkState.CONNECTING) "CONNECTING" else "remote=$state"
                        broadcast(
                            EVENT_HUB_BLE,
                            "Стан фари: ${state.name}",
                            host,
                            JSONObject().put("mac", mac).put("event", lampEvent).put("message", lampMessage).toString()
                        )
                    }
                    QStarWidgetProvider.refresh(this)
                }
                "config_ack" -> {
                    val legacyOrigin = if (hubProtocol < 3) pendingConfigVersion?.origin.orEmpty() else ""
                    val ack = ConfigVersion(json.optLong("revision", -1L), json.optString("origin", legacyOrigin))
                    val accepted = json.optBoolean("accepted", hubProtocol < 3)
                    if (ack.acknowledges(pendingConfigVersion, prefs.configVersion, accepted)) {
                        val elapsed = SystemClock.elapsedRealtime() - configSentAt
                        pendingConfigVersion = null
                        pendingConfigJson = null
                        handler.removeCallbacks(configRetry)
                        ConfigSyncStatus.confirmed(ack, "магнітолою за $elapsed мс")
                        broadcast(EVENT_CONFIG_SYNC, "Налаштування підтверджено магнітолою", host)
                    } else {
                        DiagnosticLog.write("SYNC", "Ignored obsolete/rejected ACK rev=${ack.revision} accepted=$accepted; local=${prefs.configVersion.revision}")
                    }
                    json.optJSONObject("config")?.let { receiveHubConfig(it, host) }
                }
                "config_sync" -> json.optJSONObject("config")?.let { receiveHubConfig(it, host) }
                "command" -> {
                    // The HUB is the UI/controller, while this phone owns the
                    // direct BLE links. Execute the relayed command locally;
                    // do not send it back through the HUB or create a loop.
                    val command = json.optString("command").trim()
                    if (command.isBlank()) return
                    val intent = Intent()
                        .putExtra(EXTRA_COMMAND, command)
                    if (json.has("white")) intent.putExtra(EXTRA_WHITE, json.optInt("white", prefs.white))
                    if (json.has("brightness")) intent.putExtra(EXTRA_BRIGHTNESS, json.optInt("brightness", prefs.brightness))
                    if (json.has("power")) intent.putExtra(EXTRA_POWER, json.optBoolean("power", prefs.power))
                    if (json.has("delta")) intent.putExtra(EXTRA_DELTA, json.optInt("delta", 0))
                    if (json.has("enabled")) intent.putExtra(EXTRA_STROBE, json.optBoolean("enabled", false))
                    if (json.has("mac")) intent.putExtra(EXTRA_MAC, json.optString("mac"))
                    dispatchDirect(intent)
                    sendLine(JSONObject().put("type", "ack").put("message", "command_relayed").put("command", command))
                    broadcast(EVENT_ROUTE, "Команда з мафону передана телефону", host, line)
                }
                "ble" -> {
                    val mac = json.optString("mac")
                    val event = json.optString("event")
                    if (mac.isNotBlank()) {
                        val state = when (event) {
                            QStarBleService.EVENT_READY -> BlePrefs.RuntimeLinkState.CONNECTED
                            QStarBleService.EVENT_PHASE -> when (json.optString("message")) {
                                "READY" -> BlePrefs.RuntimeLinkState.CONNECTED
                                "CONNECTING", "DISCOVERING", "SUBSCRIBING", "HANDSHAKE" ->
                                    BlePrefs.RuntimeLinkState.CONNECTING
                                else -> prefs.lampRuntimeState(mac)
                            }
                            QStarBleService.EVENT_DISCONNECTED, QStarBleService.EVENT_ERROR ->
                                BlePrefs.RuntimeLinkState.OFFLINE
                            else -> prefs.lampRuntimeState(mac)
                        }
                        prefs.setRemoteLampRuntimeState(mac, state)
                        if (state == BlePrefs.RuntimeLinkState.CONNECTED) prefs.markLampConnected(mac)
                        QStarWidgetProvider.refresh(this)
                    }
                    broadcast(EVENT_HUB_BLE, json.optString("message", "BLE"), host, line)
                }
                "ack" -> broadcast(EVENT_ACK, json.optString("message", "OK"), host, line)
                "command_ack" -> {
                    val commandId = json.optLong("commandId", -1L)
                    val ok = json.optBoolean("ok", false)
                    broadcast(
                        if (ok) EVENT_ACK else EVENT_ERROR,
                        if (ok) "Команда #$commandId виконана лампами" else "Команда #$commandId не виконана",
                        host,
                        line
                    )
                }
                else -> broadcast(EVENT_MESSAGE, line, host, line)
            }
        } catch (t: Throwable) {
            DiagnosticLog.write("LINK RX", "Invalid message: ${t.javaClass.simpleName}: ${t.message}", "ERROR")
        }
    }

    private fun handleCommandIntent(intent: Intent) {
        val cmd = intent.getStringExtra(EXTRA_COMMAND) ?: return
        if (connected && writer != null) {
            val commandId = nextCommandId()
            val json = JSONObject().put("type", "command").put("command", cmd).put("commandId", commandId)
            if (intent.hasExtra(EXTRA_WHITE)) json.put("white", intent.getIntExtra(EXTRA_WHITE, prefs.white))
            if (intent.hasExtra(EXTRA_BRIGHTNESS)) json.put("brightness", intent.getIntExtra(EXTRA_BRIGHTNESS, prefs.brightness))
            if (intent.hasExtra(EXTRA_POWER)) json.put("power", intent.getBooleanExtra(EXTRA_POWER, prefs.power))
            if (intent.hasExtra(EXTRA_DELTA)) json.put("delta", intent.getIntExtra(EXTRA_DELTA, 0))
            if (intent.hasExtra(EXTRA_STROBE)) json.put("enabled", intent.getBooleanExtra(EXTRA_STROBE, false))
            if (intent.hasExtra(EXTRA_MAC)) json.put("mac", intent.getStringExtra(EXTRA_MAC))
            sendLine(json)
            broadcast(EVENT_ROUTE, "Команда #$commandId через магнітолу", currentHost)
        } else if (prefs.directControlActive()) {
            dispatchDirect(intent)
        } else if (cmd == ControlDispatcher.CMD_CONNECT) {
            pendingConnectMissing = true
            if (prefs.directControlActive() || prefs.directFallback) dispatchDirect(intent)
            broadcast(EVENT_CONNECTING, "Підключаю відсутні пристрої…")
        } else if (cmd == ControlDispatcher.CMD_CONNECT_DEVICE) {
            // A tap on a lamp means "try this lamp now" even if the HUB is currently offline.
            dispatchDirect(intent)
            broadcast(EVENT_CONNECTING, "Підключаю вибрану фару…")
        } else if (prefs.directFallback) {
            dispatchDirect(intent)
            broadcast(EVENT_ROUTE, "Магнітола offline, команда напряму")
        } else {
            broadcast(EVENT_ERROR, "Магнітола offline. Увімкни прямий BLE або резервний канал.")
        }
    }

    private fun dispatchDirect(intent: Intent) {
        when (intent.getStringExtra(EXTRA_COMMAND)) {
            ControlDispatcher.CMD_PRESET -> QStarBleService.start(this,
                Intent().setAction(QStarBleService.ACTION_PRESET)
                    .putExtra(QStarBleService.EXTRA_WHITE, intent.getIntExtra(EXTRA_WHITE, prefs.white)))
            ControlDispatcher.CMD_APPLY -> QStarBleService.start(this,
                Intent().setAction(QStarBleService.ACTION_APPLY)
                    .putExtra(QStarBleService.EXTRA_WHITE, intent.getIntExtra(EXTRA_WHITE, prefs.white))
                    .putExtra(QStarBleService.EXTRA_BRIGHTNESS, intent.getIntExtra(EXTRA_BRIGHTNESS, prefs.brightness)))
            ControlDispatcher.CMD_POWER -> QStarBleService.start(this,
                Intent().setAction(QStarBleService.ACTION_POWER)
                    .putExtra(QStarBleService.EXTRA_POWER, intent.getBooleanExtra(EXTRA_POWER, prefs.power)))
            ControlDispatcher.CMD_BRIGHTNESS_DELTA -> QStarBleService.start(this,
                Intent().setAction(QStarBleService.ACTION_BRIGHTNESS_DELTA)
                    .putExtra(QStarBleService.EXTRA_DELTA, intent.getIntExtra(EXTRA_DELTA, 0)))
            ControlDispatcher.CMD_STROBE -> {
                val i = Intent().setAction(QStarBleService.ACTION_STROBE)
                if (intent.hasExtra(EXTRA_STROBE)) i.putExtra(QStarBleService.EXTRA_STROBE_ENABLED, intent.getBooleanExtra(EXTRA_STROBE, false))
                QStarBleService.start(this, i)
            }
            ControlDispatcher.CMD_CONNECT -> startDirectBle()
            ControlDispatcher.CMD_CONNECT_DEVICE -> QStarBleService.start(
                this,
                Intent().setAction(QStarBleService.ACTION_CONNECT_DEVICE)
                    .putExtra(QStarBleService.EXTRA_MAC, intent.getStringExtra(EXTRA_MAC))
            )
        }
    }

    private fun applyRouteMode() {
        if (prefs.role() != BlePrefs.Role.PHONE) return
        if (connected) {
            QStarBleService.start(this, Intent().setAction(QStarBleService.ACTION_RELEASE))
            sendSimple("resume")
            broadcast(EVENT_ROUTE, "Магнітола online • BLE належить HUB")
        } else if (prefs.forceDirect) {
            handler.postDelayed({ startDirectBle() }, 300)
            broadcast(EVENT_ROUTE, "Прямий BLE")
        } else {
            QStarBleService.start(this, Intent().setAction(QStarBleService.ACTION_RELEASE))
            broadcast(EVENT_ROUTE, "Основний канал: магнітола")
        }
    }

    private fun nextCommandId(): Long = commandIds.incrementAndGet()

    private fun startDirectBle() {
        QStarBleService.start(this, Intent().setAction(QStarBleService.ACTION_CONNECT))
    }

    private fun receiveHubConfig(config: JSONObject, host: String) {
        if (hubProtocol < 3 && config.optLong("revision") == prefs.configRevision) config.put("origin", prefs.configVersion.origin)
        val version = ConfigVersion(config.optLong("revision", 0L), config.optString("origin", ""))
        if (version.revision <= 0L) return
        val changed = prefs.applySyncConfig(config)
        val local = prefs.configVersion
        val accepted = version == local
        sendLine(JSONObject().put("type", "config_ack")
            .put("revision", version.revision).put("origin", version.origin)
            .put("accepted", accepted))
        if (accepted) {
            pendingConfigVersion = null
            pendingConfigJson = null
            handler.removeCallbacks(configRetry)
            // Receiving the exact version from HUB also proves its settings match.
            ConfigSyncStatus.confirmed(local, if (changed) "• отримано з магнітоли" else "магнітолою")
            QStarWidgetProvider.refresh(this)
            broadcast(EVENT_CONFIG_SYNC, "Налаштування узгоджено з магнітолою", host)
        } else if (pendingConfigVersion != local) {
            sendConfigNow()
        }
    }

    private fun sendConfigNow() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post { sendConfigNow() }
            return
        }
        val config = prefs.syncConfigJson()
        val version = ConfigVersion(config.optLong("revision"), config.optString("origin", ""))
        if (version != pendingConfigVersion) {
            configSentAt = SystemClock.elapsedRealtime()
            configAttempts = 0
        }
        pendingConfigVersion = version
        pendingConfigJson = config
        ConfigSyncStatus.waiting(if (connected) "Передаю налаштування • очікую підтвердження HUB" else "HUB недоступний • передам після підключення")
        sendPendingConfig()
    }

    private fun sendPendingConfig() {
        if (!running.get()) return
        handler.removeCallbacks(configRetry)
        val config = pendingConfigJson ?: return
        if (pendingConfigVersion != prefs.configVersion) {
            sendConfigNow()
            return
        }
        if (connected && writer != null) {
            configAttempts++
            DiagnosticLog.write("SYNC TX", "rev=${pendingConfigVersion?.revision} attempt=$configAttempts host=$currentHost")
            sendLine(JSONObject().put("type", "config_sync").put("config", config))
            if (configAttempts > 1) ConfigSyncStatus.waiting("Немає підтвердження HUB • повтор $configAttempts")
        }
        handler.postDelayed(configRetry, 1400)
    }

    private fun pushSelfUpdate(manual: Boolean) {
        if (updateInProgress || prefs.role() != BlePrefs.Role.PHONE) return
        updateInProgress = true
        thread(name = "QStarPushUpdate", isDaemon = true) {
            try {
                val host = currentHost.ifBlank { chooseHost().orEmpty() }
                if (host.isBlank()) {
                    broadcast(EVENT_UPDATE, "Магнітолу не знайдено для оновлення")
                    return@thread
                }
                val apk = File(applicationInfo.sourceDir)
                if (!apk.exists()) {
                    broadcast(EVENT_UPDATE, "Не знайдено APK телефона")
                    return@thread
                }
                val localVersion = UpdateManager.versionCode(this)
                if (!manual && hubVersionCode >= localVersion) return@thread
                broadcast(EVENT_UPDATE, "Передаю v${UpdateManager.versionName(this)} на магнітолу…", host)
                val s = Socket()
                s.connect(InetSocketAddress(host, UPDATE_PORT), 2500)
                s.soTimeout = 90_000
                s.use { sock ->
                    val input = BufferedInputStream(sock.getInputStream())
                    val output = BufferedOutputStream(sock.getOutputStream())
                    val meta = JSONObject()
                        .put("type", "update")
                        .put("pin", prefs.remotePin)
                        .put("package", packageName)
                        .put("versionCode", localVersion)
                        .put("versionName", UpdateManager.versionName(this))
                        .put("length", apk.length())
                        .put("sha256", UpdateManager.sha256(apk))
                    writeJsonLine(output, meta)
                    val readyLine = readAsciiLine(input) ?: throw IllegalStateException("hub no reply")
                    val ready = JSONObject(readyLine)
                    if (!ready.optBoolean("ok", false)) {
                        broadcast(EVENT_UPDATE, "Оновлення не прийнято: ${ready.optString("reason", "unknown")}", host)
                        return@thread
                    }
                    apk.inputStream().use { source ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val n = source.read(buffer)
                            if (n <= 0) break
                            output.write(buffer, 0, n)
                        }
                        output.flush()
                    }
                    val resultLine = readAsciiLine(input) ?: throw IllegalStateException("hub no result")
                    val result = JSONObject(resultLine)
                    if (result.optBoolean("ok", false)) {
                        broadcast(EVENT_UPDATE, "APK передано. Магнітола запускає оновлення.", host)
                    } else {
                        broadcast(EVENT_UPDATE, "Помилка оновлення: ${result.optString("reason", "unknown")}", host)
                    }
                }
            } catch (t: Throwable) {
                broadcast(EVENT_UPDATE, "Оновлення: ${t.javaClass.simpleName}: ${t.message ?: ""}")
            } finally {
                updateInProgress = false
            }
        }
    }

    private fun readAsciiLine(input: InputStream, max: Int = 8192): String? {
        val bytes = ArrayList<Byte>()
        while (bytes.size < max) {
            val b = input.read()
            if (b < 0) return if (bytes.isEmpty()) null else bytes.toByteArray().toString(Charsets.UTF_8)
            if (b == '\n'.code) break
            if (b != '\r'.code) bytes.add(b.toByte())
        }
        return bytes.toByteArray().toString(Charsets.UTF_8)
    }

    private fun writeJsonLine(output: OutputStream, json: JSONObject) {
        output.write((json.toString() + "\n").toByteArray(Charsets.UTF_8))
        output.flush()
    }

    private fun sendSimple(type: String) {
        sendLine(JSONObject().put("type", type))
    }

    private fun queueBleEvent(intent: Intent) {
        val mac = intent.getStringExtra(EXTRA_MAC).orEmpty()
        if (mac.isBlank()) return
        pendingBleEvents.offer(JSONObject()
            .put("type", "ble")
            .put("event", intent.getStringExtra(EXTRA_BLE_EVENT).orEmpty())
            .put("mac", mac)
            .put("name", intent.getStringExtra(EXTRA_NAME).orEmpty())
            .put("message", intent.getStringExtra(EXTRA_MESSAGE).orEmpty()))
        flushPendingBleEvents()
    }

    private fun sendDirectBleSnapshot() {
        val lamps = JSONArray()
        prefs.devices().forEach { device ->
            lamps.put(JSONObject()
                .put("mac", device.mac)
                .put("state", prefs.directLampRuntimeState(device.mac).name))
        }
        sendLine(JSONObject().put("type", "ble_snapshot").put("lamps", lamps))
    }

    private fun flushPendingBleEvents() {
        if (!connected || writer == null) return
        while (true) {
            val event = pendingBleEvents.poll() ?: break
            sendLine(event)
        }
    }

    private fun sendLine(json: JSONObject) {
        val out = writer ?: return
        val payload = json.toString()
        if (sender.isShutdown) return
        sender.execute {
            // Never deliver queued packets to a different connection after reconnecting.
            if (writer !== out) return@execute
            try {
                out.println(payload)
                if (out.checkError()) throw IllegalStateException("socket write failed")
                DiagnosticLog.write("LINK TX", "host=$currentHost $payload")
            } catch (t: Throwable) {
                DiagnosticLog.write("LINK TX", "${t.javaClass.simpleName}: ${t.message}", "ERROR")
                closeSocket()
            }
        }
    }

    private fun closeSocket() {
        synchronized(sendLock) {
            writer = null
            // Closing the socket unblocks both reader and writer without flushing on the UI thread.
            try { socket?.close() } catch (_: Throwable) { }
            socket = null
        }
    }

    private fun reconcilePhoneLifetime() {
        if (prefs.role() != BlePrefs.Role.PHONE) return
        sessionStopRunnable?.let(handler::removeCallbacks)
        sessionStopRunnable = null

        if (prefs.anyPhoneLinkConnected()) {
            prefs.clearPhoneOfflineGrace()
            return
        }

        val until = prefs.startPhoneOfflineGrace()
        val remaining = until - System.currentTimeMillis()
        DiagnosticLog.write("LINK", "PHONE offline grace remaining=${remaining.coerceAtLeast(0L)}ms")
        if (remaining <= 0L) {
            stopAfterOfflineGrace()
            return
        }

        val task = Runnable {
            if (prefs.anyPhoneLinkConnected()) {
                prefs.clearPhoneOfflineGrace()
            } else if (prefs.phoneOfflineGraceActive()) {
                reconcilePhoneLifetime()
            } else {
                stopAfterOfflineGrace()
            }
        }
        sessionStopRunnable = task
        handler.postDelayed(task, remaining.coerceAtLeast(1_000L))
    }

    private fun stopAfterOfflineGrace() {
        if (prefs.anyPhoneLinkConnected()) return
        prefs.clearPhoneOfflineGrace()
        PresenceMonitor.stop(this)
        DiagnosticLog.write("LINK", "PHONE offline grace expired; stopping HUB-link foreground service")
        running.set(false)
        closeSocket()
        QStarBleService.start(this, Intent().setAction(QStarBleService.ACTION_RELEASE))
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** A Wi-Fi probe is a one-shot check, not a persistent foreground service. */
    private fun finishProbeOnly() {
        if (!probeOnly) return
        probeOnly = false
        finishIdleService()
    }

    private fun finishIdleService() {
        running.set(false)
        closeSocket()
        handler.post {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun setConnected(value: Boolean, message: String, host: String? = null) {
        connected = value
        if (!value) ConfigSyncStatus.waiting("HUB недоступний • синхронізацію не підтверджено")
        prefs.hubRuntimeState =
            if (value) BlePrefs.RuntimeLinkState.CONNECTED else BlePrefs.RuntimeLinkState.OFFLINE
        if (value) {
            prefs.markHubConnected()
            prefs.markPhoneLinkAvailable()
        } else if (!probeOnly) {
            // Once HUB transport is gone, its last lamp state is no longer authoritative.
            // Keep any direct-BLE state and clear only the remote HUB snapshot.
            prefs.clearRemoteLampRuntimeStates()
            if (!prefs.anyPhoneLinkConnected()) prefs.startPhoneOfflineGrace()
        }
        if (!probeOnly || value) reconcilePhoneLifetime()
        QStarWidgetProvider.refresh(this)
        broadcast(if (value) EVENT_CONNECTED else EVENT_DISCONNECTED, message, host)
        updateNotification(message)
    }

    private fun broadcast(type: String, message: String, host: String? = null, raw: String? = null) {
        DiagnosticLog.write("LINK", "event=$type host=${host.orEmpty()} $message", if (type == EVENT_ERROR) "ERROR" else "INFO")
        val i = Intent(ACTION_EVENT).setPackage(packageName)
            .putExtra(EXTRA_EVENT, type)
            .putExtra(EXTRA_MESSAGE, message)
            .putExtra(EXTRA_HOST, host)
            .putExtra(EXTRA_CONNECTED, connected)
            .putExtra(EXTRA_RAW, raw)
            .putExtra(EXTRA_HUB_VERSION, hubVersionCode)
        sendBroadcast(i)
    }

    private fun createNotificationChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "QStar Remote", NotificationManager.IMPORTANCE_LOW))
    }

    private fun startForegroundSafe() {
        startForeground(NOTIFICATION_ID, buildNotification(if (connected) "Магнітола online" else "Пошук магнітоли…"))
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 410, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_headlight)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, "🟡", presetPendingIntent(0, 411))
            .addAction(0, "Теплий", presetPendingIntent(50, 412))
            .addAction(0, "⚪", presetPendingIntent(100, 413))
            .addAction(0, "⚡", strobePendingIntent(414))
            .build()
    }

    private fun presetPendingIntent(white: Int, request: Int): PendingIntent {
        val i = Intent(this, ControlActionReceiver::class.java)
            .setAction(ControlActionReceiver.ACTION_PRESET)
            .putExtra(ControlActionReceiver.EXTRA_WHITE, white)
        return PendingIntent.getBroadcast(this, request, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun strobePendingIntent(request: Int): PendingIntent {
        val i = Intent(this, ControlActionReceiver::class.java).setAction(ControlActionReceiver.ACTION_STROBE)
        return PendingIntent.getBroadcast(this, request, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun sleepQuiet(ms: Long) {
        try { Thread.sleep(ms) } catch (_: InterruptedException) { }
    }

    companion object {
        private val commandIds = java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis())
        const val DISCOVERY_PORT = 28760
        const val COMMAND_PORT = 28761
        const val UPDATE_PORT = 28762
        const val DISCOVER_MAGIC = "QSTAR_DISCOVER_V1"
        const val RESPONSE_MAGIC = "QSTAR_HUB_V1"

        const val ACTION_START = "ua.grey.qstarlight.remote.START"
        const val ACTION_AUTO_WAKE = "ua.grey.qstarlight.remote.AUTO_WAKE"
        const val ACTION_PROBE_HUB = "ua.grey.qstarlight.remote.PROBE_HUB"
        const val ACTION_STOP = "ua.grey.qstarlight.remote.STOP"
        const val ACTION_COMMAND = "ua.grey.qstarlight.remote.COMMAND"
        const val ACTION_ROUTE_CHANGED = "ua.grey.qstarlight.remote.ROUTE_CHANGED"
        const val ACTION_PUSH_CONFIG = "ua.grey.qstarlight.remote.PUSH_CONFIG"
        const val ACTION_PUSH_UPDATE = "ua.grey.qstarlight.remote.PUSH_UPDATE"
        const val ACTION_BLE_EVENT = "ua.grey.qstarlight.remote.BLE_EVENT"
        const val ACTION_EVENT = "ua.grey.qstarlight.remote.EVENT"

        const val EXTRA_COMMAND = "command"
        const val EXTRA_WHITE = "white"
        const val EXTRA_BRIGHTNESS = "brightness"
        const val EXTRA_POWER = "power"
        const val EXTRA_DELTA = "delta"
        const val EXTRA_STROBE = "strobe"
        const val EXTRA_EVENT = "event"
        const val EXTRA_MESSAGE = "message"
        const val EXTRA_HOST = "host"
        const val EXTRA_CONNECTED = "connected"
        const val EXTRA_RAW = "raw"
        const val EXTRA_HUB_VERSION = "hub_version"
        const val EXTRA_MAC = "mac"
        const val EXTRA_NAME = "name"
        const val EXTRA_BLE_EVENT = "ble_event"
        const val EXTRA_FROM_LAMP = "from_lamp"

        const val EVENT_CONNECTING = "connecting"
        const val EVENT_CONNECTED = "connected"
        const val EVENT_DISCONNECTED = "disconnected"
        const val EVENT_DISCOVERED = "discovered"
        const val EVENT_ROUTE = "route"
        const val EVENT_HUB_STATUS = "hub_status"
        const val EVENT_HUB_BLE = "hub_ble"
        const val EVENT_ACK = "ack"
        const val EVENT_MESSAGE = "message"
        const val EVENT_ERROR = "error"
        const val EVENT_CONFIG_SYNC = "config_sync"
        const val EVENT_UPDATE = "update"

        @Volatile var connected: Boolean = false
            private set
        @Volatile var hubVersionCode: Long = -1L
            private set

        private const val CHANNEL_ID = "qstar_remote"
        private const val NOTIFICATION_ID = 7002

        private fun launch(context: Context, intent: Intent) {
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (t: Throwable) {
                Log.e("QStarRemote", "Unable to start remote service", t)
            }
        }

        fun start(context: Context) {
            launch(context, Intent(context, RemoteLinkService::class.java).setAction(ACTION_START))
        }

        fun probeHub(context: Context) {
            launch(context, Intent(context, RemoteLinkService::class.java).setAction(ACTION_PROBE_HUB))
        }

        fun startAuto(context: Context, fromLamp: Boolean) {
            launch(
                context,
                Intent(context, RemoteLinkService::class.java)
                    .setAction(ACTION_AUTO_WAKE)
                    .putExtra(EXTRA_FROM_LAMP, fromLamp)
            )
        }

        fun stop(context: Context) {
            try { context.startService(Intent(context, RemoteLinkService::class.java).setAction(ACTION_STOP)) }
            catch (_: Throwable) { }
        }

        fun routeChanged(context: Context) {
            launch(context, Intent(context, RemoteLinkService::class.java).setAction(ACTION_ROUTE_CHANGED))
        }

        fun pushConfig(context: Context) {
            launch(context, Intent(context, RemoteLinkService::class.java).setAction(ACTION_PUSH_CONFIG))
        }

        fun pushUpdate(context: Context) {
            launch(context, Intent(context, RemoteLinkService::class.java).setAction(ACTION_PUSH_UPDATE))
        }

        fun syncDirectBleEvent(context: Context, event: String, mac: String, name: String?, message: String?) {
            launch(
                context,
                Intent(context, RemoteLinkService::class.java)
                    .setAction(ACTION_BLE_EVENT)
                    .putExtra(EXTRA_BLE_EVENT, event)
                    .putExtra(EXTRA_MAC, mac)
                    .putExtra(EXTRA_NAME, name)
                    .putExtra(EXTRA_MESSAGE, message)
            )
        }

        fun sendCommand(
            context: Context,
            command: String,
            white: Int? = null,
            brightness: Int? = null,
            power: Boolean? = null,
            delta: Int? = null,
            strobe: Boolean? = null,
            mac: String? = null
        ) {
            val i = Intent(context, RemoteLinkService::class.java).setAction(ACTION_COMMAND)
                .putExtra(EXTRA_COMMAND, command)
            white?.let { i.putExtra(EXTRA_WHITE, it) }
            brightness?.let { i.putExtra(EXTRA_BRIGHTNESS, it) }
            power?.let { i.putExtra(EXTRA_POWER, it) }
            delta?.let { i.putExtra(EXTRA_DELTA, it) }
            strobe?.let { i.putExtra(EXTRA_STROBE, it) }
            mac?.let { i.putExtra(EXTRA_MAC, it) }
            launch(context, i)
        }
    }
}
