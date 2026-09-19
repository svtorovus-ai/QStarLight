package ua.grey.qstarlight

import android.Manifest
import android.app.PendingIntent
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import androidx.core.content.ContextCompat
import ua.grey.qstarlight.ble.BlePrefs
import ua.grey.qstarlight.ble.QStarBleService
import ua.grey.qstarlight.remote.RemoteLinkService
import ua.grey.qstarlight.update.UpdateScheduler

object PresenceMonitor {
    const val ACTION_BLE_PRESENCE = "ua.grey.qstarlight.AUTO_BLE_PRESENCE"
    private const val REQUEST_CODE = 7401

    fun ensure(context: Context) {
        val granted = if (Build.VERSION.SDK_INT >= 31) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
        if (!granted) return

        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter ?: return
        if (!adapter.isEnabled) return
        val scanner = adapter.bluetoothLeScanner ?: return

        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        val pending = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            Intent(context, AutoWakeReceiver::class.java).setAction(ACTION_BLE_PRESENCE),
            flags
        )
        val filters = listOf(
            ScanFilter.Builder().setDeviceName(BlePrefs.RIGHT_NAME).build(),
            ScanFilter.Builder().setDeviceName(BlePrefs.LEFT_NAME).build()
        )
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_FIRST_MATCH)
            .build()
        runCatching { scanner.startScan(filters, settings, pending) }
    }
}

class AutoWakeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val prefs = BlePrefs(context).also { it.ensureDefaults() }
        UpdateScheduler.ensure(context)
        when (intent.action) {
            PresenceMonitor.ACTION_BLE_PRESENCE -> {
                if (prefs.role() == BlePrefs.Role.HUB) {
                    QStarBleService.start(context, Intent().setAction(QStarBleService.ACTION_HUB_START))
                } else {
                    if (!prefs.anyPhoneLinkConnected() && !prefs.phoneOfflineGraceActive()) prefs.startPhoneOfflineGrace()
                    RemoteLinkService.startAuto(context, fromLamp = true)
                }
            }
            WifiManager.NETWORK_STATE_CHANGED_ACTION,
            WifiManager.WIFI_STATE_CHANGED_ACTION -> {
                if (prefs.role() == BlePrefs.Role.PHONE) {
                    // A generic Wi-Fi change is not proof that the car is nearby.
                    // Probe once and only keep running if the QStar HUB actually answers.
                    RemoteLinkService.probeHub(context)
                }
            }
        }
    }
}
