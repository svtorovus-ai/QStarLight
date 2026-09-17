package ua.grey.qstarlight.controls

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.controls.Control
import android.service.controls.ControlsProviderService
import android.service.controls.DeviceTypes
import android.service.controls.actions.BooleanAction
import android.service.controls.actions.ControlAction
import android.service.controls.actions.FloatAction
import android.service.controls.templates.ControlButton
import android.service.controls.templates.RangeTemplate
import android.service.controls.templates.StatelessTemplate
import android.service.controls.templates.ToggleTemplate
import androidx.annotation.RequiresApi
import ua.grey.qstarlight.MainActivity
import ua.grey.qstarlight.ble.BlePrefs
import ua.grey.qstarlight.control.ControlDispatcher
import java.util.concurrent.Flow
import java.util.function.Consumer

@RequiresApi(Build.VERSION_CODES.R)
class QStarControlsProviderService : ControlsProviderService() {
    private val prefs by lazy { BlePrefs(this).also { it.ensureDefaults() } }

    override fun createPublisherForAllAvailable(): Flow.Publisher<Control> = ListPublisher(statelessControls())

    override fun createPublisherFor(controlIds: List<String>): Flow.Publisher<Control> {
        val all = statefulControls().associateBy { it.controlId }
        return ListPublisher(controlIds.mapNotNull(all::get))
    }

    override fun performControlAction(controlId: String, action: ControlAction, consumer: Consumer<Int>) {
        when (controlId) {
            ID_POWER -> {
                val on = (action as? BooleanAction)?.newState ?: !prefs.power
                ControlDispatcher.power(this, on)
            }
            ID_YELLOW -> ControlDispatcher.preset(this, 0)
            ID_WARM -> ControlDispatcher.preset(this, 50)
            ID_WHITE -> ControlDispatcher.preset(this, 100)
            ID_BRIGHTNESS -> {
                val value = (action as? FloatAction)?.newValue?.toInt()?.coerceIn(5, 100) ?: prefs.brightness
                ControlDispatcher.apply(this, prefs.white, value)
            }
            ID_STROBE -> ControlDispatcher.strobe(this, null)
        }
        consumer.accept(ControlAction.RESPONSE_OK)
    }

    private fun statelessControls(): List<Control> {
        val pi = openPendingIntent()
        return listOf(
            Control.StatelessBuilder(ID_POWER, pi).setTitle("Фари").setSubtitle("QStar Light").setStructure("Hover").setDeviceType(DeviceTypes.TYPE_LIGHT).build(),
            Control.StatelessBuilder(ID_YELLOW, pi).setTitle("Жовтий").setSubtitle("QStar").setStructure("Hover").setDeviceType(DeviceTypes.TYPE_LIGHT).build(),
            Control.StatelessBuilder(ID_WARM, pi).setTitle("Теплий").setSubtitle("QStar").setStructure("Hover").setDeviceType(DeviceTypes.TYPE_LIGHT).build(),
            Control.StatelessBuilder(ID_WHITE, pi).setTitle("Білий").setSubtitle("QStar").setStructure("Hover").setDeviceType(DeviceTypes.TYPE_LIGHT).build(),
            Control.StatelessBuilder(ID_BRIGHTNESS, pi).setTitle("Яскравість").setSubtitle("${prefs.brightness}%").setStructure("Hover").setDeviceType(DeviceTypes.TYPE_LIGHT).build(),
            Control.StatelessBuilder(ID_STROBE, pi).setTitle("Строб").setSubtitle(strobeLabel()).setStructure("Hover").setDeviceType(DeviceTypes.TYPE_LIGHT).build()
        )
    }

    private fun statefulControls(): List<Control> {
        val pi = openPendingIntent()
        return listOf(
            Control.StatefulBuilder(ID_POWER, pi)
                .setTitle("Фари")
                .setSubtitle(if (prefs.power) "Увімкнено" else "Вимкнено")
                .setStructure("Hover")
                .setDeviceType(DeviceTypes.TYPE_LIGHT)
                .setStatus(Control.STATUS_OK)
                .setControlTemplate(ToggleTemplate("power_toggle", ControlButton(prefs.power, "Живлення фар")))
                .build(),
            presetControl(ID_YELLOW, "Жовтий", 0, pi),
            presetControl(ID_WARM, "Теплий", 50, pi),
            presetControl(ID_WHITE, "Білий", 100, pi),
            Control.StatefulBuilder(ID_BRIGHTNESS, pi)
                .setTitle("Яскравість")
                .setSubtitle("${prefs.brightness}%")
                .setStructure("Hover")
                .setDeviceType(DeviceTypes.TYPE_LIGHT)
                .setStatus(Control.STATUS_OK)
                .setControlTemplate(RangeTemplate("brightness_range", 5f, 100f, prefs.brightness.toFloat(), 5f, "%.0f%%"))
                .build(),
            Control.StatefulBuilder(ID_STROBE, pi)
                .setTitle("Строб")
                .setSubtitle(strobeLabel())
                .setStructure("Hover")
                .setDeviceType(DeviceTypes.TYPE_LIGHT)
                .setStatus(Control.STATUS_OK)
                .setControlTemplate(StatelessTemplate("strobe_command"))
                .build()
        )
    }

    private fun presetControl(id: String, title: String, white: Int, pi: PendingIntent): Control {
        val active = kotlin.math.abs(prefs.white - white) <= 10
        return Control.StatefulBuilder(id, pi)
            .setTitle(title)
            .setSubtitle(if (active) "Активний" else "QStar")
            .setStructure("Hover")
            .setDeviceType(DeviceTypes.TYPE_LIGHT)
            .setStatus(Control.STATUS_OK)
            .setControlTemplate(StatelessTemplate("preset_$id"))
            .build()
    }

    private fun strobeLabel(): String {
        val mode = when (prefs.strobeMode) {
            BlePrefs.StrobeMode.CLASSIC -> "Класичний"
            BlePrefs.StrobeMode.DOUBLE -> "Подвійний"
            BlePrefs.StrobeMode.TRIPLE -> "Потрійний"
            BlePrefs.StrobeMode.ALTERNATE -> "Ліво ↔ право"
            BlePrefs.StrobeMode.DOUBLE_ALTERNATE -> "Подвійний L/R"
            else -> "Жовтий ↔ білий"
        }
        return "$mode • ${prefs.strobeBrightness}%"
    }

    private fun openPendingIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        900,
        Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private class ListPublisher(private val controls: List<Control>) : Flow.Publisher<Control> {
        override fun subscribe(subscriber: Flow.Subscriber<in Control>?) {
            if (subscriber == null) return
            subscriber.onSubscribe(object : Flow.Subscription {
                private var sent = false
                override fun request(n: Long) {
                    if (sent || n <= 0L) return
                    sent = true
                    try {
                        controls.forEach { subscriber.onNext(it) }
                        subscriber.onComplete()
                    } catch (t: Throwable) {
                        subscriber.onError(t)
                    }
                }
                override fun cancel() { sent = true }
            })
        }
    }

    companion object {
        const val ID_POWER = "qstar_power"
        const val ID_YELLOW = "qstar_yellow"
        const val ID_WARM = "qstar_warm"
        const val ID_WHITE = "qstar_white"
        const val ID_BRIGHTNESS = "qstar_brightness"
        const val ID_STROBE = "qstar_strobe"
    }
}
