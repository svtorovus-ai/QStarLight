package ua.grey.qstarlight.update

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
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

    override fun doWork(): Result = try {
        checkForUpdate()
        Result.success()
    } catch (_: Throwable) {
        Result.retry()
    }

    private fun checkForUpdate() {
        val release = getJson(LATEST_RELEASE_URL) ?: return
        val tag = release.optString("tag_name").removePrefix("v")
        if (!isNewer(tag, UpdateManager.versionName(applicationContext))) return

        val assets = release.optJSONArray("assets") ?: return
        var downloadUrl: String? = null
        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            if (asset.optString("name") == "QStarLight.apk") {
                downloadUrl = asset.optString("browser_download_url")
                break
            }
        }
        val url = downloadUrl?.takeIf { it.startsWith("https://") } ?: return
        val file = File(UpdateManager.updateDir(applicationContext), "github-latest.apk")
        download(url, file)

        val archiveVersion = UpdateManager.archiveVersionCode(applicationContext, file) ?: run {
            file.delete()
            return
        }
        val validationError = UpdateManager.validateReceivedApk(applicationContext, file, archiveVersion)
        if (validationError != null) {
            file.delete()
            return
        }

        // Root/system devices can install silently. Stock Android will show its required confirmation UI.
        UpdateManager.requestInstall(applicationContext, file, tryRoot = true)
    }

    private fun getJson(url: String): JSONObject? {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 5_000
            readTimeout = 7_000
            requestMethod = "GET"
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "QStarLight/" + UpdateManager.versionName(applicationContext))
        }
        return try {
            if (conn.responseCode !in 200..299) return null
            JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
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
        private const val LATEST_RELEASE_URL =
            "https://api.github.com/repos/svtorovus-ai/QStarLight/releases/latest"
    }
}
