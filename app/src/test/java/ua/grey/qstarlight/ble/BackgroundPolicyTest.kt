package ua.grey.qstarlight.ble

import android.app.Application
import android.content.Context
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class BackgroundPolicyTest {
    private lateinit var context: Context
    private lateinit var prefs: BlePrefs

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("qstar_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        prefs = BlePrefs(context).also { it.ensureDefaults() }
        prefs.resetRuntimeLinkStates()
        prefs.clearPhoneOfflineGrace()
    }

    @Test fun canonicalLampOrderIsLeftThenRight() {
        val devices = prefs.devices()
        assertEquals(BlePrefs.LEFT_NAME, devices[0].name)
        assertEquals(BlePrefs.LEFT_DEFAULT_MAC, devices[0].mac)
        assertEquals(BlePrefs.RIGHT_NAME, devices[1].name)
        assertEquals(BlePrefs.RIGHT_DEFAULT_MAC, devices[1].mac)
    }

    @Test fun offlineGraceStartsOnlyWhenEveryPhoneLinkIsGone() {
        val now = 1_000_000L
        val until = prefs.startPhoneOfflineGrace(now)
        assertEquals(60L * 60L * 1000L, BlePrefs.PHONE_OFFLINE_GRACE_MS)
        assertEquals(now + BlePrefs.PHONE_OFFLINE_GRACE_MS, until)
        assertTrue(prefs.phoneOfflineGraceActive(now + 1))
        assertFalse(prefs.phoneOfflineGraceActive(until + 1))
    }

    @Test fun hubConnectionCancelsOfflineGrace() {
        prefs.startPhoneOfflineGrace(1_000L)
        prefs.hubRuntimeState = BlePrefs.RuntimeLinkState.CONNECTED
        assertEquals(0L, prefs.startPhoneOfflineGrace(2_000L))
        assertFalse(prefs.phoneOfflineGraceActive(2_000L))
    }

    @Test fun directLampConnectionCancelsOfflineGrace() {
        prefs.startPhoneOfflineGrace(1_000L)
        prefs.setDirectLampRuntimeState(BlePrefs.LEFT_DEFAULT_MAC, BlePrefs.RuntimeLinkState.CONNECTED)
        assertEquals(0L, prefs.startPhoneOfflineGrace(2_000L))
        assertFalse(prefs.phoneOfflineGraceActive(2_000L))
    }
}
