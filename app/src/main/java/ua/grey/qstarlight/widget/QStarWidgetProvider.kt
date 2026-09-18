package ua.grey.qstarlight.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.widget.RemoteViews
import ua.grey.qstarlight.MainActivity
import ua.grey.qstarlight.R
import ua.grey.qstarlight.ble.BlePrefs
import ua.grey.qstarlight.control.ControlActionReceiver

class QStarWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val prefs = BlePrefs(context).also { it.ensureDefaults() }
        ids.forEach { id ->
            val views = RemoteViews(context.packageName, R.layout.widget_qstar)
            val colorName = when {
                prefs.white <= 15 -> "Жовтий"
                prefs.white >= 85 -> "Білий"
                else -> "Теплий"
            }
            views.setTextViewText(R.id.widgetStatus, "$colorName • \${prefs.brightness}%")
            views.setOnClickPendingIntent(R.id.widgetRoot, openAppIntent(context))

            val devices = prefs.devices()
            val left = devices.firstOrNull {
                it.name.equals(BlePrefs.LEFT_NAME, true) || it.mac.equals(BlePrefs.LEFT_DEFAULT_MAC, true)
            }
            val right = devices.firstOrNull {
                it.name.equals(BlePrefs.RIGHT_NAME, true) || it.mac.equals(BlePrefs.RIGHT_DEFAULT_MAC, true)
            }
            val hubState = if (prefs.role() == BlePrefs.Role.HUB) {
                BlePrefs.RuntimeLinkState.CONNECTED
            } else {
                prefs.hubRuntimeState
            }

            applyLink(views, R.id.widgetHubLink, "МАФОН", hubState)
            applyLink(
                views,
                R.id.widgetLeftLink,
                "ЛІВА",
                left?.let { prefs.lampRuntimeState(it.mac) } ?: BlePrefs.RuntimeLinkState.OFFLINE
            )
            applyLink(
                views,
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
                    if (active) Color.rgb(255, 196, 64) else Color.rgb(51, 63, 78)
                )
            }
            manager.updateAppWidget(id, views)
        }
    }

    private fun applyLink(
        views: RemoteViews,
        viewId: Int,
        label: String,
        state: BlePrefs.RuntimeLinkState
    ) {
        val (dot, color) = when (state) {
            BlePrefs.RuntimeLinkState.CONNECTED -> "●" to Color.rgb(65, 214, 126)
            BlePrefs.RuntimeLinkState.CONNECTING -> "●" to Color.rgb(255, 190, 54)
            BlePrefs.RuntimeLinkState.OFFLINE -> "●" to Color.rgb(255, 79, 94)
        }
        views.setTextViewText(viewId, "$dot $label")
        views.setTextColor(viewId, color)
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
