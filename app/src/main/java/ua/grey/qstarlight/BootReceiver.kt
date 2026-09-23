package ua.grey.qstarlight

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import ua.grey.qstarlight.ble.BlePrefs
import ua.grey.qstarlight.ble.QStarBleService
import ua.grey.qstarlight.update.UpdateScheduler

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in WAKE_ACTIONS) return
        val prefs = BlePrefs(context).also { it.ensureDefaults() }
        if (prefs.role() == BlePrefs.Role.HUB ||
            prefs.anyPhoneLinkConnected() || prefs.phoneOfflineGraceActive()) {
            PresenceMonitor.ensure(context)
        } else {
            PresenceMonitor.stop(context)
        }
        UpdateScheduler.ensure(context)
        if (prefs.role() == BlePrefs.Role.HUB) {
            // HUB is an appliance mode: the service must come back after a real
            // boot, an APK replacement and the quick-sleep wake used by Android
            // head units.  autoBoot only controls the lamp greeting, not whether
            // the HUB transport/GATT owner itself is alive.
            val action = if (intent.action == Intent.ACTION_SCREEN_ON) {
                QStarBleService.ACTION_HUB_WAKE
            } else {
                QStarBleService.ACTION_BOOT
            }
            QStarBleService.start(context, Intent().setAction(action))
        }
    }

    companion object {
        private val WAKE_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_USER_UNLOCKED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_SCREEN_ON,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON",
            "android.intent.action.REBOOT"
        )
    }
}
