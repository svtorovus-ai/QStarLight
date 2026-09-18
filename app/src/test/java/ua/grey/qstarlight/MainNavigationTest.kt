package ua.grey.qstarlight

import android.app.Application
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.ScrollView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], qualifiers = "w400dp-h800dp-mdpi")
class MainNavigationTest {
    private fun layout(activity: MainActivity) {
        val decor = activity.window.decorView
        val metrics = activity.resources.displayMetrics
        decor.measure(View.MeasureSpec.makeMeasureSpec(metrics.widthPixels, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(metrics.heightPixels, View.MeasureSpec.EXACTLY))
        decor.layout(0, 0, metrics.widthPixels, metrics.heightPixels)
    }

    private fun settle() { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250)) }

    @Test fun diagnosticsIsAnIndependentThirdTabAndButtonsRemainSynchronized() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create().visible()
        val activity = controller.get()
        layout(activity)
        activity.findViewById<Button>(R.id.tabDiagnostics).performClick()
        settle()
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.diagnosticsPage).visibility)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.settingsPage).visibility)
        assertTrue(activity.findViewById<Button>(R.id.tabDiagnostics).isSelected)
        assertNotNull(activity.findViewById<View>(R.id.diagnosticsPage).findViewById<View>(R.id.tvLog))
        assertNull(activity.findViewById<View>(R.id.settingsPage).findViewById<View>(R.id.tvLog))
        activity.findViewById<Button>(R.id.tabControl).performClick()
        settle()
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.controlPage).visibility)
        assertTrue(activity.findViewById<Button>(R.id.tabControl).isSelected)
        controller.destroy()
    }

    @Test fun swipesReachTheThirdPageButSliderStartsNeverSwitchPages() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create().visible()
        val activity = controller.get()
        layout(activity)
        swipeLeft(activity, activity.findViewById(R.id.controlPage), 15f)
        settle()
        assertTrue(activity.findViewById<Button>(R.id.tabSettings).isSelected)
        layout(activity)
        val page = activity.findViewById<ScrollView>(R.id.settingsPage)
        val seekIds = listOf(R.id.seekStartWhite, R.id.seekTargetWhite, R.id.seekStartBrightness,
            R.id.seekFadeDuration, R.id.seekFadeSteps, R.id.seekStrobeWhite, R.id.seekStrobeBrightness,
            R.id.seekStrobeOn, R.id.seekStrobeOff, R.id.seekStrobePause)
        seekIds.forEach { id ->
            val seek = activity.findViewById<View>(id)
            val bounds = android.graphics.Rect(0, 0, seek.width, seek.height)
            seek.requestRectangleOnScreen(bounds, true)
            swipeLeft(activity, seek, seek.height / 2f)
            settle()
            assertTrue("Slider $id switched the page", activity.findViewById<Button>(R.id.tabSettings).isSelected)
        }
        page.scrollTo(0, 0)
        swipeLeft(activity, page, 15f)
        settle()
        assertTrue(activity.findViewById<Button>(R.id.tabDiagnostics).isSelected)
        controller.destroy()
    }

    private fun swipeLeft(activity: MainActivity, view: View, yOffset: Float) {
        val visible = android.graphics.Rect()
        assertTrue("Swipe target must be visible: ${view.id}, shown=${view.isShown}, size=${view.width}x${view.height}", view.getGlobalVisibleRect(visible))
        val start = visible.left + visible.width() * 0.8f
        val end = visible.left + visible.width() * 0.2f
        val y = visible.top + yOffset.coerceAtMost(visible.height() - 1f)
        val now = SystemClock.uptimeMillis()
        listOf(Triple(MotionEvent.ACTION_DOWN, start, 0L),
            Triple(MotionEvent.ACTION_MOVE, end, 120L),
            Triple(MotionEvent.ACTION_UP, end, 150L)).forEach { (action, x, elapsed) ->
            val event = MotionEvent.obtain(now, now + elapsed, action, x, y, 0)
            activity.dispatchTouchEvent(event)
            event.recycle()
        }
    }
}
