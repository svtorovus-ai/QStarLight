package ua.grey.qstarlight.control

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class ControlActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_PRESET -> ControlDispatcher.preset(context, intent.getIntExtra(EXTRA_WHITE, 100))
            ACTION_POWER -> ControlDispatcher.power(context, intent.getBooleanExtra(EXTRA_POWER, true))
            ACTION_BRIGHTNESS_DELTA -> ControlDispatcher.brightnessDelta(context, intent.getIntExtra(EXTRA_DELTA, 0))
            ACTION_BRIGHTNESS_SET -> ControlDispatcher.brightnessSet(context, intent.getIntExtra(EXTRA_BRIGHTNESS, 100))
            ACTION_STROBE -> ControlDispatcher.strobe(context, if (intent.hasExtra(EXTRA_STROBE)) intent.getBooleanExtra(EXTRA_STROBE, false) else null)
            ACTION_CONNECT -> ControlDispatcher.connect(context)
        }
    }

    companion object {
        const val ACTION_PRESET = "ua.grey.qstarlight.control.PRESET"
        const val ACTION_POWER = "ua.grey.qstarlight.control.POWER"
        const val ACTION_BRIGHTNESS_DELTA = "ua.grey.qstarlight.control.BRIGHTNESS_DELTA"
        const val ACTION_BRIGHTNESS_SET = "ua.grey.qstarlight.control.BRIGHTNESS_SET"
        const val ACTION_STROBE = "ua.grey.qstarlight.control.STROBE"
        const val ACTION_CONNECT = "ua.grey.qstarlight.control.CONNECT"
        const val EXTRA_WHITE = "white"
        const val EXTRA_POWER = "power"
        const val EXTRA_DELTA = "delta"
        const val EXTRA_BRIGHTNESS = "brightness"
        const val EXTRA_STROBE = "strobe"
    }
}
