package ua.grey.qstarlight

import android.app.Application
import ua.grey.qstarlight.diagnostics.DiagnosticLog
import ua.grey.qstarlight.update.UpdateScheduler
import ua.grey.qstarlight.ble.BlePrefs
import ua.grey.qstarlight.ble.QStarBleService
import android.content.Intent
import ua.grey.qstarlight.remote.RemoteLinkService

class QStarApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val prefs = BlePrefs(this).also { it.ensureDefaults(); it.resetRuntimeLinkStates() }
        DiagnosticLog.initialize(this)
        if (prefs.role() == BlePrefs.Role.HUB ||
            prefs.anyPhoneLinkConnected() || prefs.phoneOfflineGraceActive()) {
            PresenceMonitor.ensure(this)
        } else {
            PresenceMonitor.stop(this)
            // A process recreation must not leave a stale foreground service
            // from an earlier session visible on a PHONE with no connection.
            stopService(Intent(this, QStarBleService::class.java))
            stopService(Intent(this, RemoteLinkService::class.java))
        }
        UpdateScheduler.ensure(this)
        // Any process recreation on a head unit is also a recovery point. HUB
        // mode must not depend on the Activity being opened by the driver.
        if (prefs.role() == BlePrefs.Role.HUB) {
            QStarBleService.start(this, Intent().setAction(QStarBleService.ACTION_HUB_START))
        }
    }
}
