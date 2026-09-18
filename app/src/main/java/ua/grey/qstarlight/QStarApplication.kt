package ua.grey.qstarlight

import android.app.Application
import ua.grey.qstarlight.update.UpdateScheduler
import ua.grey.qstarlight.ble.BlePrefs

class QStarApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        BlePrefs(this).also { it.ensureDefaults(); it.resetRuntimeLinkStates() }
        PresenceMonitor.ensure(this)
        UpdateScheduler.ensure(this)
    }
}
