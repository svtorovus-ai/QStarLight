package ua.grey.qstarlight.ble

/** The single timing definition shared by the BLE runner, app status cards and widget. */
object StrobeTimeline {
    data class Step(
        val states: List<Boolean>,
        val delayMs: Int,
        val whites: List<Int?>? = null
    )

    data class Phase(
        val leftOn: Boolean,
        val rightOn: Boolean,
        val stepIndex: Int,
        val remainingMs: Int
    )

    fun sequence(
        mode: BlePrefs.StrobeMode,
        count: Int,
        onMs: Int,
        offMs: Int,
        pauseMs: Int
    ): List<Step> {
        val safeCount = count.coerceAtLeast(1)
        val allOn = List(safeCount) { true }
        val allOff = List(safeCount) { false }
        val on = onMs.coerceIn(40, 1000)
        val off = offMs.coerceIn(40, 1000)
        val pause = pauseMs.coerceIn(100, 2000)
        return when (mode) {
            BlePrefs.StrobeMode.CLASSIC -> listOf(
                Step(allOn, on), Step(allOff, off)
            )
            BlePrefs.StrobeMode.DOUBLE -> listOf(
                Step(allOn, on), Step(allOff, off),
                Step(allOn, on), Step(allOff, pause)
            )
            BlePrefs.StrobeMode.TRIPLE -> listOf(
                Step(allOn, on), Step(allOff, off),
                Step(allOn, on), Step(allOff, off),
                Step(allOn, on), Step(allOff, pause)
            )
            BlePrefs.StrobeMode.ALTERNATE -> {
                if (safeCount < 2) listOf(Step(allOn, on), Step(allOff, off))
                else listOf(
                    Step(listOf(true, false), on), Step(allOff, off),
                    Step(listOf(false, true), on), Step(allOff, pause)
                )
            }
            BlePrefs.StrobeMode.DOUBLE_ALTERNATE -> {
                if (safeCount < 2) listOf(
                    Step(allOn, on), Step(allOff, off),
                    Step(allOn, on), Step(allOff, pause)
                ) else listOf(
                    Step(listOf(true, false), on), Step(allOff, off),
                    Step(listOf(true, false), on), Step(allOff, pause / 2),
                    Step(listOf(false, true), on), Step(allOff, off),
                    Step(listOf(false, true), on), Step(allOff, pause)
                )
            }
            BlePrefs.StrobeMode.YELLOW_WHITE_SWAP -> {
                if (safeCount < 2) listOf(Step(allOn, on), Step(allOff, off))
                else {
                    val fastOn = on.coerceIn(40, 80)
                    val fastOff = off.coerceIn(40, 60)
                    listOf(
                        Step(listOf(true, false), fastOn, listOf(0, null)),
                        Step(allOff, fastOff),
                        Step(listOf(false, true), fastOn, listOf(null, 100)),
                        Step(allOff, fastOff),
                        Step(listOf(true, false), fastOn, listOf(100, null)),
                        Step(allOff, fastOff),
                        Step(listOf(false, true), fastOn, listOf(null, 0)),
                        Step(allOff, fastOff)
                    )
                }
            }
        }
    }

    fun phaseAt(
        nowMs: Long,
        startedAtMs: Long,
        mode: BlePrefs.StrobeMode,
        count: Int,
        onMs: Int,
        offMs: Int,
        pauseMs: Int
    ): Phase {
        val steps = sequence(mode, count, onMs, offMs, pauseMs)
        val cycleMs = steps.sumOf { it.delayMs.toLong() }.coerceAtLeast(1L)
        var offset = (nowMs - startedAtMs).coerceAtLeast(0L) % cycleMs
        steps.forEachIndexed { index, step ->
            if (offset < step.delayMs) {
                return Phase(
                    leftOn = step.states.getOrNull(0) == true,
                    rightOn = step.states.getOrNull(1) == true,
                    stepIndex = index,
                    remainingMs = (step.delayMs - offset).toInt().coerceAtLeast(1)
                )
            }
            offset -= step.delayMs
        }
        val last = steps.last()
        return Phase(last.states.getOrNull(0) == true, last.states.getOrNull(1) == true, steps.lastIndex, last.delayMs)
    }
}
