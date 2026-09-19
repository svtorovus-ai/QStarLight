package ua.grey.qstarlight

import android.app.Application
import android.appwidget.AppWidgetManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import ua.grey.qstarlight.ble.BlePrefs
import ua.grey.qstarlight.widget.QStarWidgetProvider
import java.io.File
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TouchLayoutTest {
    @Test @Config(qualifiers = "w1024dp-h600dp-land-mdpi")
    fun hubDashboardAt1024HasLargeTargetsAndSeparateColumns() = checkActivity("hub", "hub-1024", true)

    @Test @Config(qualifiers = "w800dp-h480dp-land-mdpi")
    fun hubDashboardAt800KeepsControlsReadableAndScrollable() = checkActivity("hub", "hub-800", true)

    @Test @Config(qualifiers = "w360dp-h800dp-port-mdpi")
    fun phoneKeepsSingleColumnAndLargeStatusSigns() = checkActivity("phone", "phone-360", false)

    private fun checkActivity(role: String, name: String, wide: Boolean) {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("qstar_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        val prefs = BlePrefs(context).also { it.ensureDefaults(); it.roleOverride = role }
        prefs.setLampRuntimeState(BlePrefs.LEFT_DEFAULT_MAC, BlePrefs.RuntimeLinkState.CONNECTED)
        prefs.setLampRuntimeState(BlePrefs.RIGHT_DEFAULT_MAC, BlePrefs.RuntimeLinkState.OFFLINE)
        val controller = Robolectric.buildActivity(MainActivity::class.java).create().visible()
        val activity = controller.get()
        val decor = activity.window.decorView
        val metrics = activity.resources.displayMetrics
        measure(decor, metrics.widthPixels, metrics.heightPixels)
        val controls = activity.findViewById<View>(R.id.lightControls)
        val connections = activity.findViewById<View>(R.id.connectionControls)
        assertEquals(wide, controls.parent !== connections.parent)
        val minimum = activity.resources.getDimension(R.dimen.control_height).toInt()
        for (id in listOf(R.id.btnYellow, R.id.btnWarm, R.id.btnWhite, R.id.btnReconnect,
                R.id.btnBrightnessDown, R.id.btnBrightnessUp, R.id.btnStrobeToggle, R.id.seekTemp, R.id.seekBrightness)) {
            assertTrue("Touch target $id is too small", activity.findViewById<View>(id).height >= minimum)
        }
        listOf(R.id.tvLamp1, R.id.tvLamp2, R.id.tvLinkStatus).forEach { id ->
            val view = activity.findViewById<TextView>(id)
            val spans = (view.text as Spanned).getSpans(0, 1, RelativeSizeSpan::class.java)
            assertEquals(1, spans.size)
            assertTrue("Status sign is too small", spans[0].sizeChange * view.textSize >=
                activity.resources.getDimension(R.dimen.status_symbol_size) - 0.1f)
        }
        assertButtonTextFits(activity.findViewById(R.id.controlPage))
        capture(decor, name)
        activity.findViewById<Button>(R.id.tabSettings).performClick()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250))
        measure(decor, metrics.widthPixels, metrics.heightPixels)
        capture(decor, "$name-settings")
        assertButtonTextFits(activity.findViewById(R.id.settingsPage))
        controller.destroy()
    }

    @Test @Config(qualifiers = "w400dp-h800dp-night-mdpi")
    fun hubWidgetHasLargeSignsAndReachableBrightnessCellsInDarkLauncher() {
        val context = RuntimeEnvironment.getApplication()
        val prefs = BlePrefs(context).also { it.ensureDefaults(); it.roleOverride = "hub" }
        prefs.setLampRuntimeState(BlePrefs.LEFT_DEFAULT_MAC, BlePrefs.RuntimeLinkState.CONNECTED)
        val manager = shadowOf(AppWidgetManager.getInstance(context))
        val id = manager.createWidget(QStarWidgetProvider::class.java, R.layout.widget_qstar)
        val root = manager.getViewFor(id)
        measure(root, 360, 360)
        listOf(R.id.widgetLeftLink, R.id.widgetHubLink, R.id.widgetRightLink).forEach {
            val view = root.findViewById<TextView>(it)
            val spans = (view.text as Spanned).getSpans(0, 1, RelativeSizeSpan::class.java)
            assertTrue(spans.single().sizeChange * view.textSize >= 39.9f)
        }
        listOf(R.id.b10, R.id.b50, R.id.b60, R.id.b100).forEach {
            val view = root.findViewById<View>(it)
            assertTrue(view.width >= 48)
            assertTrue(view.height >= 48)
            val position = IntArray(2).also(view::getLocationOnScreen)
            assertTrue("Brightness control clipped below widget", position[1] + view.height <= root.height)
        }
        capture(root, "hub-widget")
    }

    private fun assertButtonTextFits(view: View) {
        if (!view.isShown) return
        if (view is Button) {
            val layout = view.layout ?: return
            assertTrue("Button text clipped: ${view.text}", layout.height <=
                view.height - view.compoundPaddingTop - view.compoundPaddingBottom)
        }
        if (view is ViewGroup) for (i in 0 until view.childCount) assertButtonTextFits(view.getChildAt(i))
    }

    private fun measure(view: View, width: Int, height: Int) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, width, height)
    }

    private fun capture(view: View, name: String) {
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        File("build/ui-previews").apply { mkdirs() }.resolve("$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }
}
