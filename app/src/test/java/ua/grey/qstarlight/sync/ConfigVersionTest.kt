package ua.grey.qstarlight.sync

import org.junit.Assert.*
import org.junit.Test

class ConfigVersionTest {
    @Test fun lateAcknowledgementCannotConfirmANewerEdit() {
        val sent = ConfigVersion(100, "phone")
        val current = ConfigVersion(101, "phone")
        assertFalse(sent.acknowledges(sent, current, true))
        assertFalse(sent.acknowledges(current, current, true))
    }

    @Test fun rejectedOrUnsolicitedAcknowledgementsCannotConfirmSync() {
        val sent = ConfigVersion(100, "phone")
        assertFalse(sent.acknowledges(sent, sent, false))
        assertFalse(sent.acknowledges(null, sent, true))
        assertTrue(sent.acknowledges(sent, sent, true))
    }

    @Test fun simultaneousEditsConvergeInEitherArrivalOrder() {
        val phone = ConfigVersion(100, "phone")
        val hub = ConfigVersion(100, "hub")
        assertEquals(maxOf(phone, hub), maxOf(hub, phone))
        assertFalse(hub.acknowledges(phone, phone, true))
        assertTrue(ConfigVersion(101, "") > phone)
    }
}
