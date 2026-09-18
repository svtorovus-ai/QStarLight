package ua.grey.qstarlight.update

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import ua.grey.qstarlight.R
import java.io.File
import java.security.MessageDigest

object UpdateManager {
    private const val CHANNEL_ID = "qstar_updates"
    private const val NOTIFICATION_ID = 7030

    fun versionCode(context: Context): Long {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
    }

    fun versionName(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
    }.getOrDefault("?")

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n <= 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun updateDir(context: Context): File = File(context.cacheDir, "updates").apply { mkdirs() }

    fun archiveVersionCode(context: Context, apk: File): Long? {
        val archive = context.packageManager.getPackageArchiveInfo(apk.absolutePath, 0) ?: return null
        return if (Build.VERSION.SDK_INT >= 28) archive.longVersionCode else @Suppress("DEPRECATION") archive.versionCode.toLong()
    }

    /**
     * Validate package name, version and signing certificate against the currently installed app.
     * This prevents an arbitrary APK received over the local network from being installed.
     */
    fun validateReceivedApk(context: Context, apk: File, expectedVersion: Long): String? {
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES
        val archive = pm.getPackageArchiveInfo(apk.absolutePath, flags) ?: return "APK не читається"
        if (archive.packageName != context.packageName) return "Інший package: ${archive.packageName}"
        val archiveVersion = if (Build.VERSION.SDK_INT >= 28) archive.longVersionCode else @Suppress("DEPRECATION") archive.versionCode.toLong()
        if (archiveVersion != expectedVersion) return "Версія APK не збігається"
        if (archiveVersion <= versionCode(context)) return "Версія не новіша"

        val installed = pm.getPackageInfo(context.packageName, flags)
        val installedCerts = signatureDigests(installed)
        val archiveCerts = signatureDigests(archive)
        if (installedCerts.isEmpty() || archiveCerts.isEmpty() || installedCerts != archiveCerts) {
            return "Підпис APK не збігається"
        }
        return null
    }

    private fun signatureDigests(info: android.content.pm.PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= 28) {
            val si = info.signingInfo ?: return emptySet()
            if (si.hasMultipleSigners()) si.apkContentsSigners else si.signingCertificateHistory
        } else {
            @Suppress("DEPRECATION") info.signatures ?: return emptySet()
        }
        return signatures.map { sig ->
            MessageDigest.getInstance("SHA-256").digest(sig.toByteArray()).joinToString("") { "%02x".format(it) }
        }.toSet()
    }

    fun requestInstall(context: Context, apk: File, tryRoot: Boolean): Boolean {
        if (tryRoot && rootInstall(apk)) return true
        val intent = Intent(context, UpdateInstallerActivity::class.java)
            .putExtra(UpdateInstallerActivity.EXTRA_APK_PATH, apk.absolutePath)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
        postInstallNotification(context, apk)
        return false
    }

    private fun rootInstall(apk: File): Boolean {
        return runCatching {
            val escaped = apk.absolutePath.replace("'", "'\\''")
            val process = ProcessBuilder("su", "-c", "pm install -r -d -g '$escaped'")
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            val code = process.waitFor()
            code == 0 && output.contains("Success", ignoreCase = true)
        }.getOrDefault(false)
    }

    private fun postInstallNotification(context: Context, apk: File) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "QStar updates", NotificationManager.IMPORTANCE_HIGH)
            )
        }
        val pi = PendingIntent.getActivity(
            context,
            7031,
            Intent(context, UpdateInstallerActivity::class.java)
                .putExtra(UpdateInstallerActivity.EXTRA_APK_PATH, apk.absolutePath),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        nm.notify(
            NOTIFICATION_ID,
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_headlight)
                .setContentTitle("QStar Light • оновлення готове")
                .setContentText("Натисни, якщо системне вікно встановлення не відкрилось автоматично")
                .setAutoCancel(true)
                .setContentIntent(pi)
                .build()
        )
    }

    fun apkUri(context: Context, apk: File): Uri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.files",
        apk
    )
}
