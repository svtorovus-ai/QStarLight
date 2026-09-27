package ua.grey.qstarlight.update

import android.content.Context
import ua.grey.qstarlight.diagnostics.DiagnosticLog
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import kotlin.concurrent.thread
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

object UpdateScheduler {
    private const val PERIODIC_NAME = "qstar_github_update_periodic"
    private const val NOW_NAME = "qstar_github_update_now"
    @Volatile var lastStatus: String? = null
        private set

    fun report(message: String) {
        lastStatus = message
        DiagnosticLog.write("UPDATE", message)
    }

    fun checkNow(context: Context) {
        report("Перевірку запитано • очікую доступу до мережі")
        val request = OneTimeWorkRequestBuilder<GitHubUpdateWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        val manager = WorkManager.getInstance(context.applicationContext)
        thread(name = "QStarManualUpdate") {
            runCatching {
                val active = manager.getWorkInfosForUniqueWork(NOW_NAME).get().any { it.state == WorkInfo.State.RUNNING }
                if (active) report("Перевірка або завантаження вже виконується")
                else manager.enqueueUniqueWork(NOW_NAME, ExistingWorkPolicy.REPLACE, request)
            }.onFailure { report("Не вдалося запустити перевірку: ${it.message}") }
        }
    }

    fun ensure(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val periodic = PeriodicWorkRequestBuilder<GitHubUpdateWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            periodic
        )

        val now = OneTimeWorkRequestBuilder<GitHubUpdateWorker>()
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(NOW_NAME, ExistingWorkPolicy.KEEP, now)
    }
}

class GitHubUpdateWorker(
    appContext: Context,
    params: WorkerParameters
) : Worker(appContext, params) {

    override fun doWork(): Result = synchronized(UPDATE_LOCK) {
        try {
            UpdateScheduler.report("Перевіряю нову версію на GitHub…")
            checkForUpdate()
            Result.success()
        } catch (t: Throwable) {
            UpdateScheduler.report("Помилка оновлення: ${t.javaClass.simpleName}: ${t.message}. Повторю спробу.")
            Result.retry()
        }
    }

    private fun checkForUpdate() {
        var tag: String? = null
        var downloadUrl: String? = null
        var checksumUrl: String? = null

        // Method 1: Try GitHub REST API
        runCatching {
            val release = getJson(LATEST_RELEASE_URL)
            tag = release.optString("tag_name").removePrefix("v")
            val assets = release.optJSONArray("assets")
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val asset = assets.optJSONObject(i) ?: continue
                    when (asset.optString("name")) {
                        "QStarLight.apk" -> downloadUrl = asset.optString("browser_download_url")
                        "QStarLight.apk.sha256" -> checksumUrl = asset.optString("browser_download_url")
                    }
                }
            }
        }

        // Method 2: Fallback to GitHub Web Releases Redirect (No API Rate Limits)
        if (tag.isNullOrBlank() || downloadUrl == null) {
            runCatching {
                val tagFromWeb = getLatestTagFromWeb()
                if (tagFromWeb != null && tagFromWeb.isNotBlank()) {
                    tag = tagFromWeb
                    downloadUrl = "https://github.com/svtorovus-ai/QStarLight/releases/download/v$tagFromWeb/QStarLight.apk"
                    checksumUrl = "https://github.com/svtorovus-ai/QStarLight/releases/download/v$tagFromWeb/QStarLight.apk.sha256"
                }
            }
        }

        val finalTag = tag?.takeIf { it.isNotBlank() } ?: error("Не вдалося отримати версію релізу з GitHub")
        if (!isNewer(finalTag, UpdateManager.versionName(applicationContext))) {
            UpdateScheduler.report("Встановлено актуальну версію v${UpdateManager.versionName(applicationContext)}")
            return
        }

        val url = downloadUrl?.takeIf { it.startsWith("https://") } ?: error("APK у релізі ще не доступний")
        UpdateScheduler.report("Завантажую QStarLight v$finalTag…")
        val file = File(UpdateManager.updateDir(applicationContext), "github-latest.apk")
        download(url, file)

        checksumUrl?.takeIf { it.startsWith("https://") }?.let { checksumAsset ->
            runCatching {
                val expected = downloadText(checksumAsset)
                    .trim()
                    .substringBefore(' ')
                    .lowercase()
                if (expected.length == 64 && expected.any { it != '0' }) {
                    val actual = UpdateManager.sha256(file).lowercase()
                    if (actual != expected) {
                        file.delete()
                        throw IllegalStateException("GitHub update checksum mismatch")
                    }
                }
            }
        }

        val archiveVersion = UpdateManager.archiveVersionCode(applicationContext, file) ?: run {
            file.delete()
            error("Не вдалося прочитати версію APK")
        }
        val validationError = UpdateManager.validateReceivedApk(applicationContext, file, archiveVersion)
        if (validationError != null) {
            file.delete()
            UpdateScheduler.report("Оновлення відхилено: $validationError")
            return
        }

        // Root/system devices can install silently. Stock Android will show its required confirmation UI.
        UpdateScheduler.report("v$finalTag перевірено • передаю системі для встановлення")
        UpdateManager.requestInstall(applicationContext, file, tryRoot = true)
    }

    private fun getLatestTagFromWeb(): String? {
        val webUrl = "https://github.com/svtorovus-ai/QStarLight/releases/latest"
        val conn = (URL(webUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 12_000
            instanceFollowRedirects = false
            setRequestProperty("User-Agent", "QStarLight/" + UpdateManager.versionName(applicationContext))
            setRequestProperty("Cache-Control", "no-cache")
        }
        return try {
            val code = conn.responseCode
            val location = conn.getHeaderField("Location").orEmpty()
            if ((code in 300..399 || location.contains("/tag/")) && location.isNotEmpty()) {
                location.substringAfterLast("/tag/").removePrefix("v").trim()
            } else {
                val finalUrl = conn.url.toString()
                if (finalUrl.contains("/tag/")) {
                    finalUrl.substringAfterLast("/tag/").removePrefix("v").trim()
                } else null
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun getJson(url: String): JSONObject {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 5_000
            readTimeout = 7_000
            requestMethod = "GET"
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("Cache-Control", "no-cache")
            setRequestProperty("User-Agent", "QStarLight/" + UpdateManager.versionName(applicationContext))
        }
        return try {
            if (conn.responseCode !in 200..299) error("GitHub HTTP ${conn.responseCode}")
            JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        } finally {
            conn.disconnect()
        }
    }

    private fun downloadText(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 15_000
            instanceFollowRedirects = true
            setRequestProperty("Cache-Control", "no-cache")
            setRequestProperty("User-Agent", "QStarLight")
        }
        return try {
            if (conn.responseCode !in 200..299) throw IllegalStateException("HTTP " + conn.responseCode)
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun download(url: String, out: File) {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "QStarLight")
        }
        try {
            if (conn.responseCode !in 200..299) throw IllegalStateException("HTTP " + conn.responseCode)
            conn.inputStream.use { input ->
                out.outputStream().use { output -> input.copyTo(output) }
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun isNewer(remote: String, local: String): Boolean {
        fun parts(v: String) = v.split('.', '-', '+').map { it.toIntOrNull() ?: 0 }
        val a = parts(remote)
        val b = parts(local)
        val max = maxOf(a.size, b.size)
        for (i in 0 until max) {
            val av = a.getOrElse(i) { 0 }
            val bv = b.getOrElse(i) { 0 }
            if (av != bv) return av > bv
        }
        return false
    }

    companion object {
        private val UPDATE_LOCK = Any()
        private const val LATEST_RELEASE_URL =
            "https://api.github.com/repos/svtorovus-ai/QStarLight/releases/latest"
    }
}
