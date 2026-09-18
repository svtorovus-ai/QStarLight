package ua.grey.qstarlight

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import ua.grey.qstarlight.ble.BlePrefs
import ua.grey.qstarlight.ble.QStarBleService
import ua.grey.qstarlight.update.UpdateScheduler

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val prefs = BlePrefs(context).also { it.ensureDefaults() }
        PresenceMonitor.ensure(context)
        UpdateScheduler.ensure(context)
        if (prefs.role() == BlePrefs.Role.HUB && prefs.autoBoot) {
            QStarBleService.start(context, Intent().setAction(QStarBleService.ACTION_BOOT))
        }
    }
}
