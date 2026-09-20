package ua.grey.qstarlight.diagnostics

import android.content.Context
import android.os.Build
import ua.grey.qstarlight.ble.BlePrefs
import ua.grey.qstarlight.sync.ConfigSyncStatus
import ua.grey.qstarlight.update.UpdateManager
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors

/** Service-owned events survive closing the Activity; disk writes never run on the UI thread. */
object DiagnosticLog {
    private const val MAX_LINES = 2000
    private const val MAX_FILE_BYTES = 512 * 1024
    private val lines = ArrayDeque<String>()
    private val lock = Any()
    private val disk = Executors.newSingleThreadExecutor()
    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    private var logFile: File? = null
    private var previousFile: File? = null

    fun initialize(context: Context) {
        val dir = File(context.filesDir, "diagnostics").apply { mkdirs() }
        synchronized(lock) {
            if (logFile != null) return
            previousFile = File(dir, "previous.log")
            logFile = File(dir, "current.log")
            for (file in listOfNotNull(previousFile, logFile)) {
                runCatching { if (file.exists()) file.forEachLine { addLine(it) } }
            }
        }
        write("APP", "Process started • v${UpdateManager.versionName(context)} • ${Build.MANUFACTURER} ${Build.MODEL} • Android ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}")
    }

    private fun addLine(line: String) {
        lines.addLast(line)
        while (lines.size > MAX_LINES) lines.removeFirst()
    }

    fun write(source: String, message: String, level: String = "INFO") {
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS XXX", Locale.US).format(Date())
        val line = "$stamp [$level] [$source] [${Thread.currentThread().name}] ${DiagnosticText.sanitize(message)}"
        synchronized(lock) {
            addLine(line)
            // Submit under the same lock so disk order matches the visible journal.
            disk.execute {
                runCatching {
                    val file = logFile ?: return@runCatching
                    if (file.length() >= MAX_FILE_BYTES) {
                        previousFile?.let { old ->
                            old.delete()
                            if (!file.renameTo(old)) file.writeText("")
                        }
                    }
                    file.appendText("$line\n", Charsets.UTF_8)
                }
            }
        }
        listeners.forEach { it() }
    }

    fun addListener(listener: () -> Unit) { listeners += listener }
    fun removeListener(listener: () -> Unit) { listeners -= listener }
    fun snapshot(): List<String> = synchronized(lock) { lines.toList() }

    fun report(context: Context): String {
        val prefs = BlePrefs(context)
        return buildString {
            appendLine("QSTAR LIGHT • diagnostic report")
            appendLine("App: ${UpdateManager.versionName(context)} (${UpdateManager.versionCode(context)})")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}; Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("Role: ${prefs.role()}; direct BLE: ${prefs.forceDirect}; HUB: ${prefs.hubRuntimeState}")
            appendLine("Last HUB connection: ${formatTimestamp(prefs.lastHubConnectionAt)}")
            prefs.devices().forEach { device ->
                appendLine("Last ${prefs.lampSide(device)} connection (${device.mac}): ${formatTimestamp(prefs.lastLampConnectionAt(device.mac))}")
            }
            appendLine("Timezone: ${TimeZone.getDefault().id}")
            appendLine("Sync: ${ConfigSyncStatus.summary(prefs.configVersion)}")
            appendLine("Config: ${DiagnosticText.sanitize(prefs.syncConfigJson().toString())}")
            appendLine("Recent journal (up to $MAX_LINES entries; PIN/passwords omitted):")
            snapshot().forEach {
                appendLine(it)
                appendLine()
            }
        }
    }

    private fun formatTimestamp(value: Long): String = if (value <= 0L) {
        "ще не було"
    } else {
        SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.getDefault()).format(Date(value))
    }
}
