package ua.grey.qstarlight.sync

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import ua.grey.qstarlight.ble.BlePrefs

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class BlePrefsSyncTest {
    private fun device(name: String): BlePrefs {
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getSharedPreferences(file: String, mode: Int): SharedPreferences =
                super.getSharedPreferences("$name-$file", mode)
        }
        context.getSharedPreferences("qstar_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        return BlePrefs(context).also { it.ensureDefaults() }
    }

    @Test fun reconnectingPhoneCannotOverwriteNewerHubSettings() {
        val phone = device("phone")
        val hub = device("hub")
        val oldPhone = phone.syncConfigJson()
        hub.fadeDurationMs = 2300
        val changedHub = hub.configVersion
        assertFalse(hub.applySyncConfig(oldPhone))
        assertEquals(changedHub, hub.configVersion)
        assertEquals(2300, hub.fadeDurationMs)
        assertTrue(phone.applySyncConfig(hub.syncConfigJson()))
        assertEquals(2300, phone.fadeDurationMs)
        assertEquals(hub.configVersion, phone.configVersion)
    }

    @Test fun synchronizedProfilesAndDeviceCredentialsRoundTrip() {
        val phone = device("phone")
        val hub = device("hub")
        phone.startupMode = BlePrefs.StartupMode.START_ONLY
        phone.startWhite = 27
        phone.targetWhite = 83
        phone.startBrightness = 67
        phone.fadeDurationMs = 2900
        phone.fadeSteps = 36
        phone.welcomeOnConnect = false
        phone.strobeMode = BlePrefs.StrobeMode.TRIPLE
        phone.strobeWhite = 43
        phone.strobeBrightness = 62
        phone.strobeOnMs = 160
        phone.strobeOffMs = 170
        phone.strobePauseMs = 680
        phone.setPassword(phone.devices().first().mac, "4321")
        phone.updateLight(white = 71, brightness = 52, power = false)
        val expected = phone.syncConfigJson()
        assertTrue(hub.applySyncConfig(expected))
        val actual = hub.syncConfigJson()
        expected.keys().forEach { key -> assertEquals(key, expected.get(key).toString(), actual.get(key).toString()) }
        assertFalse(phone.applySyncConfig(actual))
        assertFalse(hub.welcomeOnConnect)
    }

    @Test fun smoothYellowWhiteStartupModeSurvivesSync() {
        val phone = device("phone-smooth")
        val hub = device("hub-smooth")
        phone.startupMode = BlePrefs.StartupMode.SMOOTH_YELLOW_WHITE
        assertTrue(hub.applySyncConfig(phone.syncConfigJson()))
        assertEquals(BlePrefs.StartupMode.SMOOTH_YELLOW_WHITE, hub.startupMode)
    }

    @Test fun localAndRemoteLampLinksAreMerged() {
        val hub = device("hub-links")
        val mac = hub.devices().first().mac
        hub.setLampRuntimeState(mac, BlePrefs.RuntimeLinkState.OFFLINE)
        hub.setRemoteLampRuntimeState(mac, BlePrefs.RuntimeLinkState.CONNECTED)
        assertEquals(BlePrefs.RuntimeLinkState.CONNECTED, hub.lampRuntimeState(mac))
        hub.setRemoteLampRuntimeState(mac, BlePrefs.RuntimeLinkState.OFFLINE)
        hub.setLampRuntimeState(mac, BlePrefs.RuntimeLinkState.CONNECTING)
        assertEquals(BlePrefs.RuntimeLinkState.CONNECTING, hub.lampRuntimeState(mac))
    }

    @Test fun phoneLampStateFollowsTheCurrentOwner() {
        val phone = device("phone-links")
        val mac = phone.devices().first().mac
        phone.roleOverride = "phone"
        phone.setLampRuntimeState(mac, BlePrefs.RuntimeLinkState.CONNECTED)
        phone.setDirectLampRuntimeState(mac, BlePrefs.RuntimeLinkState.CONNECTED)
        phone.setRemoteLampRuntimeState(mac, BlePrefs.RuntimeLinkState.OFFLINE)
        phone.hubRuntimeState = BlePrefs.RuntimeLinkState.OFFLINE
        assertEquals(BlePrefs.RuntimeLinkState.CONNECTED, phone.lampRuntimeState(mac))
        assertTrue(phone.directControlActive())

        // A stale direct flag must not mask the HUB owner.
        phone.hubRuntimeState = BlePrefs.RuntimeLinkState.CONNECTED
        assertEquals(BlePrefs.RuntimeLinkState.OFFLINE, phone.lampRuntimeState(mac))
        phone.setRemoteLampRuntimeState(mac, BlePrefs.RuntimeLinkState.CONNECTED)
        assertEquals(BlePrefs.RuntimeLinkState.CONNECTED, phone.lampRuntimeState(mac))
        assertFalse(phone.directControlActive())
    }

    @Test fun localEditAfterReceivingAFutureClockStillWins() {
        val phone = device("phone")
        val hub = device("hub")
        val future = phone.syncConfigJson().put("revision", System.currentTimeMillis() + 600_000).put("origin", "phone")
        assertTrue(hub.applySyncConfig(future))
        hub.startWhite = 44
        assertTrue(hub.configRevision > future.getLong("revision"))
        assertNotEquals("phone", hub.configVersion.origin)
    }

    @Test fun deviceLocalRoleAndConnectionOptionsAreNotOverwritten() {
        val phone = device("phone")
        val hub = device("hub")
        hub.roleOverride = "hub"
        hub.autoBoot = false
        hub.keepConnected = false
        val pin = hub.hubPin
        phone.strobeWhite = 15
        assertTrue(hub.applySyncConfig(phone.syncConfigJson()))
        assertEquals(BlePrefs.Role.HUB, hub.role())
        assertEquals(pin, hub.hubPin)
        assertFalse(hub.autoBoot)
        assertFalse(hub.keepConnected)
    }

    @Test fun clearedDeviceListStaysClearedAfterSynchronization() {
        val phone = device("phone")
        val hub = device("hub")
        phone.setDevices(emptyList())
        assertTrue(hub.applySyncConfig(phone.syncConfigJson()))
        hub.ensureDefaults()
        assertTrue(hub.devices().isEmpty())
    }
}
