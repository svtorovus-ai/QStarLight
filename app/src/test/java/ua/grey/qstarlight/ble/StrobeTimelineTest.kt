package ua.grey.qstarlight.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StrobeTimelineTest {
    @Test fun tripleKeepsTheConfiguredSeriesPause() {
        val steps = StrobeTimeline.sequence(BlePrefs.StrobeMode.TRIPLE, 2, 120, 110, 420)
        assertEquals(listOf(120, 110, 120, 110, 120, 420), steps.map { it.delayMs })
        assertTrue(steps[0].states.all { it })
        assertFalse(steps[1].states.any { it })
    }

    @Test fun alternateShowsOnlyTheSelectedLampInEachImpulse() {
        val steps = StrobeTimeline.sequence(BlePrefs.StrobeMode.ALTERNATE, 2, 120, 110, 420)
        assertEquals(listOf(true, false), steps[0].states)
        assertEquals(listOf(false, true), steps[2].states)
        assertEquals(420, steps[3].delayMs)
    }

    @Test fun phaseUsesTheSameScheduleAsTheService() {
        val phase = StrobeTimeline.phaseAt(
            nowMs = 120L,
            startedAtMs = 0L,
            mode = BlePrefs.StrobeMode.CLASSIC,
            count = 2,
            onMs = 120,
            offMs = 110,
            pauseMs = 420
        )
        assertFalse(phase.leftOn)
        assertFalse(phase.rightOn)
        assertEquals(110, phase.remainingMs)
    }
}
