package ua.grey.qstarlight.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.TypedValue
import android.widget.RemoteViews
import android.os.SystemClock
import androidx.core.content.ContextCompat
import ua.grey.qstarlight.MainActivity
import ua.grey.qstarlight.R
import ua.grey.qstarlight.ble.BlePrefs
import ua.grey.qstarlight.ble.StrobeTimeline
import ua.grey.qstarlight.control.ControlActionReceiver
import ua.grey.qstarlight.ui.LinkIndicator

class QStarWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val prefs = BlePrefs(context).also { it.ensureDefaults() }
        ids.forEach { id ->
            val light = prefs.role() == BlePrefs.Role.HUB
            // Size follows the device role, regardless of the launcher's theme or screen size.
            // The widget is intentionally the large control surface for both roles.
            // The phone app itself remains compact; only the launcher widget is large.
            val layout = R.layout.widget_qstar
            val views = RemoteViews(context.packageName, layout)
            val strobeActive = prefs.strobeActive
            val strobeBlinkOn = strobeActive && (SystemClock.elapsedRealtime() / 125L) % 2L == 0L
            applyTheme(context, views, light, strobeActive, strobeBlinkOn)
            val colorName = when {
                prefs.white <= 15 -> "Жовтий"
                prefs.white >= 85 -> "Білий"
                else -> "Теплий"
            }
            views.setTextViewText(R.id.widgetStatus, "$colorName • ${prefs.brightness}%")
            views.setOnClickPendingIntent(R.id.widgetRoot, openAppIntent(context))

            val devices = prefs.devices()
            val left = devices.firstOrNull {
                it.name.equals(BlePrefs.LEFT_NAME, true) || it.mac.equals(BlePrefs.LEFT_DEFAULT_MAC, true)
            }
            val right = devices.firstOrNull {
                it.name.equals(BlePrefs.RIGHT_NAME, true) || it.mac.equals(BlePrefs.RIGHT_DEFAULT_MAC, true)
            }
            val hubState = prefs.hubRuntimeState

            applyLink(context, views, light, R.id.widgetHubLink, if (light) "ТЕЛЕФОН" else "МАФОН", hubState)
            applyLink(
                context, views, light,
                R.id.widgetLeftLink,
                "ЛІВА",
                left?.let { prefs.lampRuntimeState(it.mac) } ?: BlePrefs.RuntimeLinkState.OFFLINE
            )
            val phase = if (strobeActive) StrobeTimeline.phaseAt(
                System.currentTimeMillis(),
                prefs.strobeStartedAt,
                prefs.strobeMode,
                devices.size,
                prefs.strobeOnMs,
                prefs.strobeOffMs,
                prefs.strobePauseMs
            ) else null
            views.setFloat(R.id.widgetLeftLink, "setAlpha", if (phase == null || phase.leftOn) 1f else 0.28f)
            views.setFloat(R.id.widgetRightLink, "setAlpha", if (phase == null || devices.size < 2 || phase.rightOn) 1f else 0.28f)
            applyLink(
                context, views, light,
                R.id.widgetRightLink,
                "ПРАВА",
                right?.let { prefs.lampRuntimeState(it.mac) } ?: BlePrefs.RuntimeLinkState.OFFLINE
            )

            views.setOnClickPendingIntent(R.id.widgetHubLink, connectIntent(context, 60))
            left?.let { views.setOnClickPendingIntent(R.id.widgetLeftLink, connectDeviceIntent(context, it.mac, 61)) }
            right?.let { views.setOnClickPendingIntent(R.id.widgetRightLink, connectDeviceIntent(context, it.mac, 62)) }

            views.setOnClickPendingIntent(R.id.widgetYellow, presetIntent(context, 0, 2))
            views.setOnClickPendingIntent(R.id.widgetWarm, presetIntent(context, 50, 3))
            views.setOnClickPendingIntent(R.id.widgetWhite, presetIntent(context, 100, 4))
            views.setOnClickPendingIntent(R.id.widgetStrobe, strobeIntent(context, 5))

            val idsByBrightness = intArrayOf(
                R.id.b10, R.id.b20, R.id.b30, R.id.b40, R.id.b50,
                R.id.b60, R.id.b70, R.id.b80, R.id.b90, R.id.b100
            )
            idsByBrightness.forEachIndexed { index, viewId ->
                val value = (index + 1) * 10
                views.setOnClickPendingIntent(viewId, brightnessIntent(context, value, 20 + index))
                val active = value <= prefs.brightness
                views.setTextColor(
                    viewId,
                    ContextCompat.getColor(context, if (light) {
                        if (active) R.color.widget_light_brightness else R.color.widget_light_track
                    } else {
                        if (active) R.color.widget_dark_brightness else R.color.widget_dark_track
                    })
                )
            }
            manager.updateAppWidget(id, views)
        }
        if (prefs.strobeActive) scheduleBlink(context)
        else blinkHandler.removeCallbacksAndMessages(null)
    }

    private fun applyLink(
        context: Context,
        views: RemoteViews,
        light: Boolean,
        viewId: Int,
        label: String,
        state: BlePrefs.RuntimeLinkState
    ) {
        val labelSize = 19f
        views.setTextViewTextSize(viewId, TypedValue.COMPLEX_UNIT_SP, labelSize)
        views.setTextViewText(viewId, LinkIndicator.label(state, label, 50f / labelSize, stacked = true))
        views.setTextColor(viewId, LinkIndicator.color(context, state, light))
        views.setContentDescription(viewId, "$label • ${LinkIndicator.description(state)}")
    }

    private fun applyTheme(
        context: Context,
        views: RemoteViews,
        light: Boolean,
        strobeActive: Boolean,
        strobeBlinkOn: Boolean
    ) {
        // RemoteViews is inflated by the launcher, so do not depend on its night-mode resources.
        val backgrounds = mapOf(
            R.id.widgetRoot to if (light) R.drawable.widget_bg_light else R.drawable.widget_bg,
            R.id.widgetStatus to if (light) R.drawable.widget_status_pill_light else R.drawable.widget_status_pill,
            R.id.widgetLeftLink to if (light) R.drawable.widget_status_pill_light else R.drawable.widget_status_pill,
            R.id.widgetHubLink to if (light) R.drawable.widget_status_pill_light else R.drawable.widget_status_pill,
            R.id.widgetRightLink to if (light) R.drawable.widget_status_pill_light else R.drawable.widget_status_pill,
            R.id.widgetYellow to if (light) R.drawable.widget_button_gold_light else R.drawable.widget_button_gold,
            R.id.widgetWarm to if (light) R.drawable.widget_button_warm_light else R.drawable.widget_button_warm,
            R.id.widgetWhite to if (light) R.drawable.widget_button_white_light else R.drawable.widget_button_white,
            R.id.widgetStrobe to if (strobeActive && strobeBlinkOn) {
                if (light) R.drawable.widget_button_dark_active_light else R.drawable.widget_button_dark_active
            } else if (light) R.drawable.widget_button_dark_light else R.drawable.widget_button_dark
        )
        backgrounds.forEach { (id, drawable) -> views.setInt(id, "setBackgroundResource", drawable) }
        val colors = mapOf(
            R.id.widgetTitle to if (light) R.color.widget_light_text else R.color.widget_dark_text,
            R.id.widgetStatus to if (light) R.color.widget_light_accent else R.color.widget_dark_accent,
            R.id.widgetBrightnessLabel to if (light) R.color.widget_light_muted else R.color.widget_dark_muted,
            R.id.widgetYellow to if (light) R.color.widget_light_yellow else R.color.widget_dark_yellow,
            R.id.widgetWarm to if (light) R.color.widget_light_warm else R.color.widget_dark_warm,
            R.id.widgetWhite to if (light) R.color.widget_light_text else R.color.widget_dark_text,
            R.id.widgetStrobe to if (light) R.color.widget_light_accent else R.color.widget_dark_accent
        )
        colors.forEach { (id, color) -> views.setTextColor(id, ContextCompat.getColor(context, color)) }
        views.setTextViewTextSize(R.id.widgetTitle, TypedValue.COMPLEX_UNIT_SP, 24f)
        views.setTextViewTextSize(R.id.widgetStatus, TypedValue.COMPLEX_UNIT_SP, 18f)
        views.setTextViewTextSize(R.id.widgetBrightnessLabel, TypedValue.COMPLEX_UNIT_SP, 18f)
        listOf(R.id.widgetYellow, R.id.widgetWarm, R.id.widgetWhite).forEach {
            views.setTextViewTextSize(it, TypedValue.COMPLEX_UNIT_SP, 18f)
        }
        val activeWhite = BlePrefs(context).white
        val active = if (light) R.color.widget_light_active_text else R.color.widget_dark_active_text
        val inactive = intArrayOf(
            if (light) R.color.widget_light_yellow else R.color.widget_dark_yellow,
            if (light) R.color.widget_light_warm else R.color.widget_dark_warm,
            if (light) R.color.widget_light_text else R.color.widget_dark_text
        )
        val activeIndex = when {
            activeWhite <= 15 -> 0
            activeWhite >= 85 -> 2
            else -> 1
        }
        listOf(R.id.widgetYellow, R.id.widgetWarm, R.id.widgetWhite).forEachIndexed { index, id ->
            views.setTextColor(id, ContextCompat.getColor(context, if (index == activeIndex) active else inactive[index]))
        }
        views.setInt(R.id.widgetYellow, "setBackgroundResource", if (activeWhite <= 15) {
            if (light) R.drawable.widget_button_gold_active_light else R.drawable.widget_button_gold_active
        } else if (light) R.drawable.widget_button_gold_light else R.drawable.widget_button_gold)
        views.setInt(R.id.widgetWarm, "setBackgroundResource", if (activeWhite in 16..84) {
            if (light) R.drawable.widget_button_warm_active_light else R.drawable.widget_button_warm_active
        } else if (light) R.drawable.widget_button_warm_light else R.drawable.widget_button_warm)
        views.setInt(R.id.widgetWhite, "setBackgroundResource", if (activeWhite >= 85) {
            if (light) R.drawable.widget_button_white_active_light else R.drawable.widget_button_white_active
        } else if (light) R.drawable.widget_button_white_light else R.drawable.widget_button_white)
    }

    private fun scheduleBlink(context: Context) {
        blinkHandler.removeCallbacksAndMessages(null)
        val appContext = context.applicationContext
        blinkHandler.postDelayed({ refresh(appContext) }, 40L)
    }

    private fun openAppIntent(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context,
            7999,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun connectIntent(context: Context, request: Int): PendingIntent =
        pending(
            context,
            request,
            Intent(context, ControlActionReceiver::class.java)
                .setAction(ControlActionReceiver.ACTION_CONNECT)
        )

    private fun connectDeviceIntent(context: Context, mac: String, request: Int): PendingIntent =
        pending(
            context,
            request,
            Intent(context, ControlActionReceiver::class.java)
                .setAction(ControlActionReceiver.ACTION_CONNECT_DEVICE)
                .putExtra(ControlActionReceiver.EXTRA_MAC, mac)
        )

    private fun presetIntent(context: Context, white: Int, request: Int): PendingIntent {
        val i = Intent(context, ControlActionReceiver::class.java)
            .setAction(ControlActionReceiver.ACTION_PRESET)
            .putExtra(ControlActionReceiver.EXTRA_WHITE, white)
        return pending(context, request, i)
    }

    private fun strobeIntent(context: Context, request: Int): PendingIntent {
        val i = Intent(context, ControlActionReceiver::class.java).setAction(ControlActionReceiver.ACTION_STROBE)
        return pending(context, request, i)
    }

    private fun brightnessIntent(context: Context, brightness: Int, request: Int): PendingIntent {
        val i = Intent(context, ControlActionReceiver::class.java)
            .setAction(ControlActionReceiver.ACTION_BRIGHTNESS_SET)
            .putExtra(ControlActionReceiver.EXTRA_BRIGHTNESS, brightness)
        return pending(context, request, i)
    }

    private fun pending(context: Context, request: Int, i: Intent): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            8000 + request,
            i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    companion object {
        private val blinkHandler = android.os.Handler(android.os.Looper.getMainLooper())

        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val component = ComponentName(context, QStarWidgetProvider::class.java)
            val ids = manager.getAppWidgetIds(component)
            if (ids.isNotEmpty()) {
                QStarWidgetProvider().onUpdate(context, manager, ids)
            }
        }
    }
}
