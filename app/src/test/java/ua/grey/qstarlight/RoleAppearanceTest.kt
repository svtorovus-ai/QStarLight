package ua.grey.qstarlight

import android.app.Application
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import ua.grey.qstarlight.ble.BlePrefs
import ua.grey.qstarlight.ble.BlePrefs.RuntimeLinkState
import ua.grey.qstarlight.widget.QStarWidgetProvider

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], qualifiers = "w400dp-h800dp-mdpi")
class RoleAppearanceTest {
    private lateinit var context: Context
    private lateinit var prefs: BlePrefs

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("qstar_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        prefs = BlePrefs(context).also { it.ensureDefaults() }
        prefs.setLampRuntimeState(BlePrefs.LEFT_DEFAULT_MAC, RuntimeLinkState.CONNECTED)
        prefs.setLampRuntimeState(BlePrefs.RIGHT_DEFAULT_MAC, RuntimeLinkState.OFFLINE)
    }

    @Test @Config(qualifiers = "+notnight")
    fun phoneIsDarkEvenWithLightSystemAndKeepsExistingSettings() {
        prefs.roleOverride = "phone"
        prefs.hubRuntimeState = RuntimeLinkState.CONNECTING
        assertActivityAppearance(Configuration.UI_MODE_NIGHT_YES)
    }

    @Test @Config(qualifiers = "+night")
    fun hubIsLightEvenWithDarkSystemAndKeepsExistingSettings() {
        prefs.roleOverride = "hub"
        prefs.hubRuntimeState = RuntimeLinkState.CONNECTED
        assertActivityAppearance(Configuration.UI_MODE_NIGHT_NO)
    }

    private fun assertActivityAppearance(expectedMode: Int) {
        val settings = prefs.syncConfigJson().toString()
        val controller = Robolectric.buildActivity(MainActivity::class.java).create().visible()
        val activity = controller.get()
        assertEquals(expectedMode, activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK)
        val light = expectedMode == Configuration.UI_MODE_NIGHT_NO
        val background = activity.findViewById<android.view.View>(R.id.rootLayout).background as GradientDrawable
        assertTrue(background.colors!!.all { if (light) Color.luminance(it) > 0.5f else Color.luminance(it) < 0.01f })
        val text = activity.findViewById<TextView>(R.id.tvTemp).currentTextColor
        assertTrue(if (light) Color.luminance(text) < 0.1f else Color.luminance(text) > 0.8f)
        assertTrue(activity.findViewById<TextView>(R.id.tvLamp1).text.startsWith("+ "))
        assertTrue(activity.findViewById<TextView>(R.id.tvLamp2).text.startsWith("− "))
        val hubSymbol = if (light) "+ " else "… "
        assertTrue(activity.findViewById<TextView>(R.id.tvLinkStatus).text.startsWith(hubSymbol))
        assertTrue(activity.findViewById<TextView>(R.id.tvRemoteStatus).text.startsWith(hubSymbol))
        assertEquals(settings, prefs.syncConfigJson().toString())
        controller.destroy()
    }

    @Test @Config(qualifiers = "+notnight")
    fun widgetStaysDarkForPhoneInLightLauncher() = assertWidgetAppearance(false)

    @Test @Config(qualifiers = "+night")
    fun widgetStaysLightForHubInDarkLauncher() = assertWidgetAppearance(true)

    private fun assertWidgetAppearance(light: Boolean) {
        prefs.roleOverride = if (light) "hub" else "phone"
        prefs.hubRuntimeState = RuntimeLinkState.CONNECTING
        val manager = AppWidgetManager.getInstance(context)
        val shadow = shadowOf(manager)
        val id = shadow.createWidget(QStarWidgetProvider::class.java, R.layout.widget_qstar)
        val root = shadow.getViewFor(id)
        val background = root.background as GradientDrawable
        assertTrue(background.colors!!.all { if (light) Color.luminance(it) > 0.5f else Color.luminance(it) < 0.01f })
        val title = root.findViewById<TextView>(R.id.widgetTitle).currentTextColor
        assertTrue(if (light) Color.luminance(title) < 0.1f else Color.luminance(title) > 0.8f)
        assertEquals("+\nЛІВА", root.findViewById<TextView>(R.id.widgetLeftLink).text.toString())
        assertEquals("−\nПРАВА", root.findViewById<TextView>(R.id.widgetRightLink).text.toString())
        assertEquals(if (light) "…\nТЕЛЕФОН" else "…\nМАФОН", root.findViewById<TextView>(R.id.widgetHubLink).text.toString())
        prefs.setLampRuntimeState(BlePrefs.LEFT_DEFAULT_MAC, RuntimeLinkState.CONNECTING)
        prefs.setLampRuntimeState(BlePrefs.RIGHT_DEFAULT_MAC, RuntimeLinkState.CONNECTED)
        QStarWidgetProvider.refresh(context)
        val refreshed = shadow.getViewFor(id)
        assertEquals("…\nЛІВА", refreshed.findViewById<TextView>(R.id.widgetLeftLink).text.toString())
        assertEquals("+\nПРАВА", refreshed.findViewById<TextView>(R.id.widgetRightLink).text.toString())
    }
}
