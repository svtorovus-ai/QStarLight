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

    @Test @Config(qualifiers = "w800dp-h480dp-land-mdpi")
    fun widePhoneKeepsCompactControlsInsteadOfHeadUnitSizes() = checkActivity("phone", "phone-800", false)

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
        val hub = role == "hub"
        val minimum = if (hub) 64 else 48
        for (id in listOf(R.id.btnYellow, R.id.btnWarm, R.id.btnWhite, R.id.btnReconnect,
                R.id.btnBrightnessDown, R.id.btnBrightnessUp, R.id.btnStrobeToggle)) {
            val view = activity.findViewById<View>(id)
            assertTrue("Touch target $id is too small", view.height >= minimum)
            if (!hub) assertTrue("Phone control $id inherited head-unit sizing", view.height <= 48)
        }
        for (id in listOf(R.id.seekTemp, R.id.seekBrightness)) {
            assertEquals(if (hub) 64 else 42, activity.findViewById<View>(id).height)
        }
        assertEquals(if (hub) 20f else 14f, activity.findViewById<TextView>(R.id.btnYellow).textSize, 0.1f)
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

    @Test @Config(qualifiers = "w400dp-h800dp-notnight-mdpi")
    fun phoneWidgetKeepsCompactControlsAndLargeSignsWhenRoleChanges() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("qstar_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        val prefs = BlePrefs(context).also { it.ensureDefaults(); it.roleOverride = "phone" }
        prefs.setLampRuntimeState(BlePrefs.LEFT_DEFAULT_MAC, BlePrefs.RuntimeLinkState.CONNECTED)
        val manager = shadowOf(AppWidgetManager.getInstance(context))
        val id = manager.createWidget(QStarWidgetProvider::class.java, R.layout.widget_qstar)
        val root = manager.getViewFor(id)
        measure(root, 360, 248)
        listOf(R.id.widgetYellow, R.id.widgetWarm, R.id.widgetWhite, R.id.widgetStrobe).forEach {
            assertEquals(46, root.findViewById<View>(it).height)
        }
        listOf(R.id.widgetLeftLink, R.id.widgetHubLink, R.id.widgetRightLink).forEach {
            val view = root.findViewById<TextView>(it)
            val spans = (view.text as Spanned).getSpans(0, 1, RelativeSizeSpan::class.java)
            assertTrue("Phone status sign must stay large", spans.single().sizeChange * view.textSize >= 35.9f)
        }
        val first = root.findViewById<View>(R.id.b10)
        val last = root.findViewById<View>(R.id.b100)
        assertSame(first.parent, last.parent)
        assertEquals(28, last.height)
        val position = IntArray(2).also(last::getLocationOnScreen)
        assertTrue(position[1] + last.height <= root.height)
        assertButtonTextFits(root)
        capture(root, "phone-widget")

        prefs.roleOverride = "hub"
        QStarWidgetProvider.refresh(context)
        val hubRoot = manager.getViewFor(id)
        measure(hubRoot, 360, 360)
        assertEquals(64, hubRoot.findViewById<View>(R.id.widgetYellow).height)
        assertNotSame(hubRoot.findViewById<View>(R.id.b10).parent, hubRoot.findViewById<View>(R.id.b100).parent)

        prefs.roleOverride = "phone"
        QStarWidgetProvider.refresh(context)
        val phoneRoot = manager.getViewFor(id)
        measure(phoneRoot, 360, 248)
        assertEquals(46, phoneRoot.findViewById<View>(R.id.widgetYellow).height)
        assertSame(phoneRoot.findViewById<View>(R.id.b10).parent, phoneRoot.findViewById<View>(R.id.b100).parent)
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
