package ua.grey.qstarlight.ui

import android.content.Context
import androidx.core.content.ContextCompat
import ua.grey.qstarlight.R
import ua.grey.qstarlight.ble.BlePrefs.RuntimeLinkState

/** Shared app/widget indicators. Fixed palette IDs also work in an opposite-theme launcher. */
object LinkIndicator {
    fun symbol(state: RuntimeLinkState): String = when (state) {
        RuntimeLinkState.CONNECTED -> "+"
        RuntimeLinkState.CONNECTING -> "…"
        RuntimeLinkState.OFFLINE -> "−"
    }

    fun description(state: RuntimeLinkState): String = when (state) {
        RuntimeLinkState.CONNECTED -> "підключено"
        RuntimeLinkState.CONNECTING -> "підключення"
        RuntimeLinkState.OFFLINE -> "немає з’єднання"
    }

    fun color(context: Context, state: RuntimeLinkState, light: Boolean): Int =
        ContextCompat.getColor(context, when (state) {
            RuntimeLinkState.CONNECTED -> if (light) R.color.link_light_ok else R.color.link_dark_ok
            RuntimeLinkState.CONNECTING -> if (light) R.color.link_light_wait else R.color.link_dark_wait
            RuntimeLinkState.OFFLINE -> if (light) R.color.link_light_bad else R.color.link_dark_bad
        })
}
