package ua.grey.qstarlight.remote

import android.content.Context
import android.os.Build
import org.json.JSONObject
import ua.grey.qstarlight.ble.BlePrefs
import ua.grey.qstarlight.update.UpdateManager
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.PrintWriter
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import ua.grey.qstarlight.diagnostics.DiagnosticLog
import ua.grey.qstarlight.sync.ConfigVersion
import ua.grey.qstarlight.sync.ConfigSyncStatus
import kotlin.concurrent.thread

class HubTransport(
    private val context: Context,
    private val prefs: BlePrefs,
    private val listener: Listener
) {
    interface Listener {
        fun onRemoteCommand(command: String, payload: JSONObject)
        fun onTakeoverChanged(active: Boolean)
        fun onRemoteConfig(config: JSONObject, onApplied: (Boolean) -> Unit)
        fun onUpdateStatus(text: String)
    }

    private data class Client(val id: String, val socket: Socket, val writer: PrintWriter, val supportsConfigAck: Boolean) {
        var pendingVersion: ConfigVersion? = null
        var confirmedVersion: ConfigVersion? = null
    }
    private val sender = Executors.newSingleThreadScheduledExecutor()

    private val running = AtomicBoolean(false)
    private val clients = CopyOnWriteArrayList<Client>()
    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var discoverySocket: DatagramSocket? = null
    @Volatile private var updateServerSocket: ServerSocket? = null
    @Volatile private var takeoverOwner: String? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        sender.scheduleWithFixedDelay({
            clients.filter { it.pendingVersion != null }.forEach { sendConfig(it) }
        }, 1400, 1400, TimeUnit.MILLISECONDS)
        thread(name = "QStarHubTcp", isDaemon = true) { tcpLoop() }
        thread(name = "QStarHubDiscovery", isDaemon = true) { discoveryLoop() }
        thread(name = "QStarHubUpdate", isDaemon = true) { updateLoop() }
    }

    fun stop() {
        running.set(false)
        try { serverSocket?.close() } catch (_: Throwable) { }
        try { discoverySocket?.close() } catch (_: Throwable) { }
        try { updateServerSocket?.close() } catch (_: Throwable) { }
        clients.forEach { try { it.socket.close() } catch (_: Throwable) { } }
        clients.clear()
        takeoverOwner = null
        sender.shutdownNow()
    }

    fun publishBle(event: String, mac: String?, name: String?, message: String?) {
        val json = JSONObject()
            .put("type", "ble")
            .put("event", event)
            .put("mac", mac ?: "")
            .put("name", name ?: "")
            .put("message", message ?: "")
        broadcast(json)
    }

    fun publishStatus(text: String) {
        val json = JSONObject()
            .put("type", "status")
            .put("text", text)
            .put("power", prefs.power)
            .put("white", prefs.white)
            .put("brightness", prefs.brightness)
            .put("takeover", takeoverOwner != null)
            .put("configRevision", prefs.configRevision).put("configOrigin", prefs.configVersion.origin)
            .put("versionCode", UpdateManager.versionCode(context))
            .put("versionName", UpdateManager.versionName(context))
        broadcast(json)
    }

    fun publishConfig() {
        ConfigSyncStatus.waiting(if (clients.isEmpty()) "Телефон не підключений • передам після підключення" else "Передаю налаштування • очікую підтвердження телефона")
        clients.forEach { sendConfig(it) }
    }

    private fun tcpLoop() {
        try {
            ServerSocket().use { server ->
                server.reuseAddress = true
                server.bind(InetSocketAddress(RemoteLinkService.COMMAND_PORT))
                server.soTimeout = 1500
                serverSocket = server
                while (running.get()) {
                    try {
                        val socket = server.accept()
                        thread(name = "QStarHubClient", isDaemon = true) { handleClient(socket) }
                    } catch (_: SocketTimeoutException) {
                        // Periodically re-check running.
                    }
                }
            }
        } catch (t: Throwable) {
            if (running.get()) DiagnosticLog.write("HUB", "TCP server: ${t.javaClass.simpleName}: ${t.message}", "ERROR")
        } finally {
            serverSocket = null
        }
    }

    private fun handleClient(socket: Socket) {
        var client: Client? = null
        try {
            socket.soTimeout = 12000
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val writer = PrintWriter(socket.getOutputStream(), true)
            val helloLine = reader.readLine() ?: return
            val hello = JSONObject(helloLine)
            if (hello.optString("type") != "hello") {
                writer.println(JSONObject().put("type", "hello").put("ok", false).put("reason", "bad_hello"))
                return
            }
            if (hello.optString("pin") != prefs.hubPin) {
                writer.println(JSONObject().put("type", "hello").put("ok", false).put("reason", "wrong_pin"))
                return
            }
            val id = "${socket.inetAddress.hostAddress}:${socket.port}:${System.nanoTime()}"
            val peer = Client(id, socket, writer, hello.optInt("protocol", 2) >= 3)
            client = peer
            writer.println(
                JSONObject()
                    .put("type", "hello")
                    .put("ok", true)
                    .put("name", Build.MODEL ?: "QStar Hub")
                    .put("protocol", 3)
                    .put("configRevision", prefs.configRevision).put("configOrigin", prefs.configVersion.origin)
                    .put("versionCode", UpdateManager.versionCode(context))
                    .put("versionName", UpdateManager.versionName(context))
            )
            clients += peer
            DiagnosticLog.write("HUB", "Client authorized: $id; version=${hello.optString("versionName")}")
            sendStatus(peer, "Магнітола готова")
            sendConfig(peer)

            while (running.get() && !socket.isClosed) {
                val line = try { reader.readLine() } catch (_: SocketTimeoutException) {
                    send(peer, JSONObject().put("type", "pong"))
                    continue
                } ?: break
                DiagnosticLog.write("HUB RX", "peer=$id $line")
                val json = try { JSONObject(line) } catch (_: Throwable) { continue }
                when (json.optString("type")) {
                    "ping" -> send(peer, JSONObject().put("type", "pong"))
                    "status_request" -> sendStatus(peer, "Магнітола online")
                    "config_request" -> sendConfig(peer)
                    "config_sync" -> {
                        val config = json.optJSONObject("config")
                        if (config != null && config.optLong("revision", 0L) > 0L) {
                            listener.onRemoteConfig(config) { changed ->
                                val actual = prefs.syncConfigJson()
                                val requested = ConfigVersion(config.optLong("revision"), config.optString("origin", ""))
                                val accepted = requested == ConfigVersion(actual.optLong("revision"), actual.optString("origin", ""))
                                send(peer, JSONObject().put("type", "config_ack")
                                    .put("revision", requested.revision).put("origin", requested.origin)
                                    .put("accepted", accepted).put("config", actual))
                                DiagnosticLog.write("SYNC HUB", "rev=${requested.revision} accepted=$accepted changed=$changed")
                                if (changed) {
                                    publishConfig()
                                    publishStatus("Налаштування синхронізовано")
                                } else sendConfig(peer)
                            }
                        } else sendConfig(peer)
                    }
                    "config_ack" -> {
                        val ack = ConfigVersion(json.optLong("revision", -1L), json.optString("origin", ""))
                        sender.execute {
                            if (ack.acknowledges(peer.pendingVersion, prefs.configVersion, json.optBoolean("accepted", false))) {
                                peer.pendingVersion = null
                                peer.confirmedVersion = ack
                                val count = clients.count { it.confirmedVersion == ack }
                                if (count == clients.size) ConfigSyncStatus.confirmed(ack, "телефонами $count/${clients.size}")
                                else ConfigSyncStatus.waiting("Підтверджено телефонами $count/${clients.size}")
                            }
                        }
                    }
                    "takeover" -> {
                        val current = takeoverOwner
                        if (current == null || current == id) {
                            takeoverOwner = id
                            listener.onTakeoverChanged(true)
                            send(peer, JSONObject().put("type", "ack").put("message", "direct_takeover_granted"))
                            publishStatus("Телефон керує лампами напряму")
                        } else {
                            send(peer, JSONObject().put("type", "ack").put("message", "takeover_busy"))
                        }
                    }
                    "resume" -> {
                        if (takeoverOwner == null || takeoverOwner == id) {
                            takeoverOwner = null
                            listener.onTakeoverChanged(false)
                            send(peer, JSONObject().put("type", "ack").put("message", "hub_resumed"))
                            publishStatus("Магнітола керує лампами")
                        }
                    }
                    "command" -> {
                        if (takeoverOwner != null) {
                            send(peer, JSONObject().put("type", "ack").put("message", "direct_takeover_active"))
                        } else {
                            listener.onRemoteCommand(json.optString("command"), json)
                            send(peer, JSONObject().put("type", "ack").put("message", "command_accepted"))
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            DiagnosticLog.write("HUB", "Client error: ${t.javaClass.simpleName}: ${t.message}", "ERROR")
        } finally {
            client?.let {
                clients.remove(it)
                DiagnosticLog.write("HUB", "Client disconnected: ${it.id}")
                if (clients.isEmpty()) ConfigSyncStatus.waiting("Телефон відключено • очікую з’єднання")
            }
            try { socket.close() } catch (_: Throwable) { }
            val ownerId = takeoverOwner
            if (client != null && ownerId == client.id) {
                thread(name = "QStarHubResume", isDaemon = true) {
                    try { Thread.sleep(3000) } catch (_: InterruptedException) { }
                    if (takeoverOwner == ownerId) {
                        takeoverOwner = null
                        listener.onTakeoverChanged(false)
                        publishStatus("Телефон зник, керування повернено магнітолі")
                    }
                }
            }
        }
    }

    private fun sendStatus(client: Client, text: String) {
        send(client, JSONObject()
            .put("type", "status").put("text", text)
            .put("power", prefs.power).put("white", prefs.white).put("brightness", prefs.brightness)
            .put("takeover", takeoverOwner != null)
            .put("configRevision", prefs.configRevision).put("configOrigin", prefs.configVersion.origin)
            .put("versionCode", UpdateManager.versionCode(context))
            .put("versionName", UpdateManager.versionName(context)))
    }

    private fun sendConfig(client: Client) {
        if (sender.isShutdown) return
        sender.execute {
            if (client !in clients) return@execute
            val config = prefs.syncConfigJson()
            client.pendingVersion = if (client.supportsConfigAck) ConfigVersion(config.optLong("revision"), config.optString("origin", "")) else null
            if (!client.supportsConfigAck) ConfigSyncStatus.waiting("Надіслано • онови телефон для підтвердження синхронізації")
            write(client, JSONObject().put("type", "config_sync").put("config", config).toString())
        }
    }

    private fun send(client: Client, json: JSONObject) {
        val payload = json.toString()
        if (!sender.isShutdown) sender.execute { write(client, payload) }
    }

    private fun write(client: Client, payload: String) {
        if (client !in clients) return
        try {
            client.writer.println(payload)
            if (client.writer.checkError()) throw IllegalStateException("socket write failed")
            DiagnosticLog.write("HUB TX", "peer=${client.id} $payload")
        } catch (t: Throwable) {
            DiagnosticLog.write("HUB TX", "peer=${client.id} ${t.javaClass.simpleName}: ${t.message}", "ERROR")
            clients.remove(client)
            runCatching { client.socket.close() }
        }
    }

    private fun broadcast(json: JSONObject) {
        clients.forEach { send(it, json) }
    }

    private fun discoveryLoop() {
        var socket: DatagramSocket? = null
        try {
            socket = DatagramSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress(RemoteLinkService.DISCOVERY_PORT))
                soTimeout = 1500
            }
            discoverySocket = socket
            val buffer = ByteArray(512)
            while (running.get()) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
                    if (text == RemoteLinkService.DISCOVER_MAGIC) {
                        val response = "${RemoteLinkService.RESPONSE_MAGIC}|${RemoteLinkService.COMMAND_PORT}|${Build.MODEL ?: "QStar Hub"}"
                            .toByteArray(Charsets.UTF_8)
                        socket.send(DatagramPacket(response, response.size, packet.address, packet.port))
                    }
                } catch (_: SocketTimeoutException) {
                }
            }
        } catch (t: Throwable) {
            if (running.get()) DiagnosticLog.write("HUB", "Discovery: ${t.javaClass.simpleName}: ${t.message}", "ERROR")
        } finally {
            try { socket?.close() } catch (_: Throwable) { }
            discoverySocket = null
        }
    }

    /** Separate authenticated binary channel for transferring the phone's installed APK to the hub. */
    private fun updateLoop() {
        try {
            ServerSocket().use { server ->
                server.reuseAddress = true
                server.bind(InetSocketAddress(RemoteLinkService.UPDATE_PORT))
                server.soTimeout = 1500
                updateServerSocket = server
                while (running.get()) {
                    try {
                        val socket = server.accept()
                        thread(name = "QStarHubUpdateClient", isDaemon = true) { handleUpdateClient(socket) }
                    } catch (_: SocketTimeoutException) { }
                }
            }
        } catch (t: Throwable) {
            if (running.get()) DiagnosticLog.write("HUB", "Update server: ${t.javaClass.simpleName}: ${t.message}", "ERROR")
        } finally {
            updateServerSocket = null
        }
    }

    private fun handleUpdateClient(socket: Socket) {
        var temp: File? = null
        try {
            socket.soTimeout = 60_000
            val input = BufferedInputStream(socket.getInputStream())
            val output = BufferedOutputStream(socket.getOutputStream())
            val metaLine = readAsciiLine(input) ?: return
            val meta = JSONObject(metaLine)
            if (meta.optString("type") != "update" || meta.optString("pin") != prefs.hubPin) {
                writeJsonLine(output, JSONObject().put("ok", false).put("reason", "auth"))
                return
            }
            val versionCode = meta.optLong("versionCode", -1L)
            val length = meta.optLong("length", -1L)
            val expectedSha = meta.optString("sha256", "").lowercase()
            if (versionCode <= UpdateManager.versionCode(context)) {
                writeJsonLine(output, JSONObject().put("ok", false).put("reason", "not_newer").put("current", UpdateManager.versionCode(context)))
                return
            }
            if (length !in 1..100_000_000 || expectedSha.length != 64) {
                writeJsonLine(output, JSONObject().put("ok", false).put("reason", "bad_meta"))
                return
            }
            writeJsonLine(output, JSONObject().put("ok", true).put("ready", true))

            val dir = UpdateManager.updateDir(context)
            temp = File(dir, "qstarlight-$versionCode.apk.part")
            FileOutputStream(temp).use { fileOut ->
                var remaining = length
                val buffer = ByteArray(64 * 1024)
                while (remaining > 0) {
                    val n = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                    if (n <= 0) throw IllegalStateException("transfer ended early")
                    fileOut.write(buffer, 0, n)
                    remaining -= n
                }
            }
            if (UpdateManager.sha256(temp) != expectedSha) {
                temp.delete()
                writeJsonLine(output, JSONObject().put("ok", false).put("reason", "sha256"))
                listener.onUpdateStatus("Оновлення відхилено: SHA-256")
                return
            }
            val validationError = UpdateManager.validateReceivedApk(context, temp, versionCode)
            if (validationError != null) {
                temp.delete()
                writeJsonLine(output, JSONObject().put("ok", false).put("reason", validationError))
                listener.onUpdateStatus("Оновлення відхилено: $validationError")
                return
            }
            val finalFile = File(dir, "QStarLight-$versionCode.apk")
            if (finalFile.exists()) finalFile.delete()
            if (!temp.renameTo(finalFile)) {
                temp.copyTo(finalFile, overwrite = true)
                temp.delete()
            }
            writeJsonLine(output, JSONObject().put("ok", true).put("received", true).put("versionCode", versionCode))
            listener.onUpdateStatus("Оновлення v$versionCode отримано з телефону")
            thread(name = "QStarInstall", isDaemon = true) {
                try { Thread.sleep(400) } catch (_: InterruptedException) { }
                UpdateManager.requestInstall(context, finalFile, prefs.silentRootInstall)
            }
        } catch (t: Throwable) {
            temp?.delete()
            listener.onUpdateStatus("Помилка оновлення: ${t.javaClass.simpleName}")
        } finally {
            try { socket.close() } catch (_: Throwable) { }
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
}
