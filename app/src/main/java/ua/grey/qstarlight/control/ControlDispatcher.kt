package ua.grey.qstarlight.control

import android.content.Context
import android.content.Intent
import ua.grey.qstarlight.ble.BlePrefs
import ua.grey.qstarlight.ble.QStarBleService
import ua.grey.qstarlight.remote.RemoteLinkService
import ua.grey.qstarlight.widget.QStarWidgetProvider

object ControlDispatcher {
    const val CMD_PRESET = "preset"
    const val CMD_APPLY = "apply"
    const val CMD_POWER = "power"
    const val CMD_BRIGHTNESS_DELTA = "brightness_delta"
    const val CMD_STROBE = "strobe"
    const val CMD_CONNECT = "connect"
    const val CMD_SCAN = "scan"

    fun connect(context: Context) {
        val prefs = BlePrefs(context).also { it.ensureDefaults() }
        if (prefs.role() == BlePrefs.Role.HUB) {
            QStarBleService.start(context, Intent().setAction(QStarBleService.ACTION_HUB_START))
        } else {
            RemoteLinkService.start(context)
        }
    }

    fun preset(context: Context, white: Int) {
        val prefs = BlePrefs(context).also { it.ensureDefaults() }
        prefs.white = white
        prefs.strobeWhite = white
        dispatch(context, CMD_PRESET, white = white)
        configChanged(context)
        QStarWidgetProvider.refresh(context)
    }

    fun apply(context: Context, white: Int, brightness: Int) {
        val prefs = BlePrefs(context).also { it.ensureDefaults() }
        prefs.white = white
        prefs.brightness = brightness
        prefs.touchConfig()
        dispatch(context, CMD_APPLY, white = white, brightness = brightness)
        configChanged(context)
        QStarWidgetProvider.refresh(context)
    }

    fun power(context: Context, on: Boolean) {
        val prefs = BlePrefs(context).also { it.ensureDefaults() }
        prefs.power = on
        prefs.touchConfig()
        dispatch(context, CMD_POWER, power = on)
        configChanged(context)
        QStarWidgetProvider.refresh(context)
    }

    fun brightnessSet(context: Context, value: Int) {
        val prefs = BlePrefs(context).also { it.ensureDefaults() }
        prefs.brightness = value.coerceIn(5, 100)
        prefs.touchConfig()
        dispatch(context, CMD_APPLY, white = prefs.white, brightness = prefs.brightness)
        configChanged(context)
        QStarWidgetProvider.refresh(context)
    }

    fun brightnessDelta(context: Context, delta: Int) {
        val prefs = BlePrefs(context).also { it.ensureDefaults() }
        prefs.brightness = (prefs.brightness + delta).coerceIn(5, 100)
        prefs.touchConfig()
        dispatch(context, CMD_BRIGHTNESS_DELTA, delta = delta)
        configChanged(context)
        QStarWidgetProvider.refresh(context)
    }

    fun strobe(context: Context, enabled: Boolean? = null) {
        dispatch(context, CMD_STROBE, strobe = enabled)
    }

    fun scanDirect(context: Context) {
        QStarBleService.start(context, Intent().setAction(QStarBleService.ACTION_SCAN))
    }

    fun routeChanged(context: Context) {
        val prefs = BlePrefs(context).also { it.ensureDefaults() }
        if (prefs.role() == BlePrefs.Role.HUB) {
            RemoteLinkService.stop(context)
            QStarBleService.start(context, Intent().setAction(QStarBleService.ACTION_HUB_START))
        } else {
            RemoteLinkService.routeChanged(context)
        }
    }

    fun configChanged(context: Context) {
        val prefs = BlePrefs(context).also { it.ensureDefaults() }
        if (prefs.role() == BlePrefs.Role.HUB) {
            QStarBleService.start(context, Intent().setAction(QStarBleService.ACTION_CONFIG_CHANGED))
        } else {
            RemoteLinkService.pushConfig(context)
        }
    }

    private fun dispatch(
        context: Context,
        command: String,
        white: Int? = null,
        brightness: Int? = null,
        power: Boolean? = null,
        delta: Int? = null,
        strobe: Boolean? = null
    ) {
        val prefs = BlePrefs(context).also { it.ensureDefaults() }
        if (prefs.role() == BlePrefs.Role.HUB) {
            val action = when (command) {
                CMD_PRESET -> QStarBleService.ACTION_PRESET
                CMD_APPLY -> QStarBleService.ACTION_APPLY
                CMD_POWER -> QStarBleService.ACTION_POWER
                CMD_BRIGHTNESS_DELTA -> QStarBleService.ACTION_BRIGHTNESS_DELTA
                CMD_STROBE -> QStarBleService.ACTION_STROBE
                else -> QStarBleService.ACTION_CONNECT
            }
            val i = Intent().setAction(action)
            white?.let { i.putExtra(QStarBleService.EXTRA_WHITE, it) }
            brightness?.let { i.putExtra(QStarBleService.EXTRA_BRIGHTNESS, it) }
            power?.let { i.putExtra(QStarBleService.EXTRA_POWER, it) }
            delta?.let { i.putExtra(QStarBleService.EXTRA_DELTA, it) }
            strobe?.let { i.putExtra(QStarBleService.EXTRA_STROBE_ENABLED, it) }
            QStarBleService.start(context, i)
        } else {
            RemoteLinkService.sendCommand(context, command, white, brightness, power, delta, strobe)
        }
    }
}
