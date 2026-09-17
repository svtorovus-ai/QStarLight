package ua.grey.qstarlight.update

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.appcompat.app.AppCompatActivity
import java.io.File

class UpdateInstallerActivity : AppCompatActivity() {
    private var apkPath: String? = null
    private var settingsOpened = false
    private var installOpened = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        apkPath = intent.getStringExtra(EXTRA_APK_PATH)
        proceed()
    }

    override fun onResume() {
        super.onResume()
        if (settingsOpened && !installOpened) proceed()
    }

    private fun proceed() {
        val path = apkPath ?: run { finish(); return }
        val apk = File(path)
        if (!apk.exists()) { finish(); return }

        if (Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
            if (!settingsOpened) {
                settingsOpened = true
                val settingsIntent = Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:$packageName")
                )
                startActivity(settingsIntent)
            }
            return
        }

        if (installOpened) return
        installOpened = true
        val uri = UpdateManager.apkUri(this, apk)
        val install = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(install)
        finish()
    }

    companion object {
        const val EXTRA_APK_PATH = "apk_path"
    }
}
