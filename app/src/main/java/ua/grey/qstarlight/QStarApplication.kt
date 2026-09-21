package ua.grey.qstarlight

import android.app.Application
import ua.grey.qstarlight.diagnostics.DiagnosticLog
import ua.grey.qstarlight.update.UpdateScheduler
import ua.grey.qstarlight.ble.BlePrefs
import ua.grey.qstarlight.ble.QStarBleService
import android.content.Intent

class QStarApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val prefs = BlePrefs(this).also { it.ensureDefaults(); it.resetRuntimeLinkStates() }
        DiagnosticLog.initialize(this)
        PresenceMonitor.ensure(this)
        UpdateScheduler.ensure(this)
        // Any process recreation on a head unit is also a recovery point. HUB
        // mode must not depend on the Activity being opened by the driver.
        if (prefs.role() == BlePrefs.Role.HUB) {
            QStarBleService.start(this, Intent().setAction(QStarBleService.ACTION_HUB_START))
        }
    }
}
