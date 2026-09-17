package ua.grey.qstarlight

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import ua.grey.qstarlight.ble.BlePrefs
import ua.grey.qstarlight.ble.QStarBleService

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val prefs = BlePrefs(context).also { it.ensureDefaults() }
        if (!prefs.autoBoot) return
        if (prefs.role() == BlePrefs.Role.HUB) {
            QStarBleService.start(context, Intent().setAction(QStarBleService.ACTION_BOOT))
        }
    }
}
