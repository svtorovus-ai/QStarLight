from pathlib import Path


def read(path):
    return Path(path).read_text()


def write(path, text):
    Path(path).write_text(text)


def rep(path, old, new, count=1):
    text = read(path)
    found = text.count(old)
    if found < count:
        raise SystemExit(f"{path}: expected at least {count}, found {found}: {old[:140]!r}")
    write(path, text.replace(old, new, count))


rep('app/build.gradle.kts', 'versionCode = 5', 'versionCode = 6')
rep('app/build.gradle.kts', 'versionName = "0.3.2"', 'versionName = "0.3.3"')

prefs = 'app/src/main/java/ua/grey/qstarlight/ble/BlePrefs.kt'
rep(
    prefs,
    '    enum class StrobeMode { CLASSIC, DOUBLE, TRIPLE, ALTERNATE, DOUBLE_ALTERNATE }',
    '    enum class StrobeMode { CLASSIC, DOUBLE, TRIPLE, ALTERNATE, DOUBLE_ALTERNATE, YELLOW_WHITE_SWAP }'
)
rep(prefs, 'set(value) = putSyncInt(KEY_STROBE_ON, value.coerceIn(60, 1000))', 'set(value) = putSyncInt(KEY_STROBE_ON, value.coerceIn(40, 1000))')
rep(prefs, 'set(value) = putSyncInt(KEY_STROBE_OFF, value.coerceIn(60, 1000))', 'set(value) = putSyncInt(KEY_STROBE_OFF, value.coerceIn(40, 1000))')
rep(prefs, 'json.optInt("strobeOnMs", strobeOnMs).coerceIn(60, 1000)', 'json.optInt("strobeOnMs", strobeOnMs).coerceIn(40, 1000)')
rep(prefs, 'json.optInt("strobeOffMs", strobeOffMs).coerceIn(60, 1000)', 'json.optInt("strobeOffMs", strobeOffMs).coerceIn(40, 1000)')

main = 'app/src/main/java/ua/grey/qstarlight/MainActivity.kt'
rep(
    main,
    '''    private val strobeLabels = arrayOf(
        "Класичний",
        "Подвійний",
        "Потрійний",
        "Ліво ↔ право",
        "Подвійний ліво ↔ право"
    )
''',
    '''    private val strobeLabels = arrayOf(
        "Класичний",
        "Подвійний",
        "Потрійний",
        "Ліво ↔ право",
        "Подвійний ліво ↔ право",
        "Жовтий ↔ білий • швидкий"
    )
'''
)
rep(main, '        seekStrobeOn.min = 60\n', '        seekStrobeOn.min = 40\n')
rep(main, '        seekStrobeOff.min = 60\n', '        seekStrobeOff.min = 40\n')

svc = 'app/src/main/java/ua/grey/qstarlight/ble/QStarBleService.kt'
rep(
    svc,
    '    private data class StrobeStep(val states: List<Boolean>, val delayMs: Int)\n',
    '''    private data class StrobeStep(
        val states: List<Boolean>,
        val delayMs: Int,
        val whites: List<Int?>? = null
    )
'''
)
rep(
    svc,
    '''    private fun startStrobe() {
        oneShot = false
        interactive = true
        strobeActive = true
        strobeGeneration++
        val generation = strobeGeneration
        prefs.power = true
        ensureConnections {
            sendFrameAll(QStarProtocol.cctFrame(prefs.strobeWhite, prefs.strobeBrightness)) {
                if (strobeActive && generation == strobeGeneration) {
                    event(EVENT_STROBE, message = "strobe_on:${prefs.strobeMode.name}")
                    runStrobeSequence(generation, 0)
                }
            }
        }
    }
''',
    '''    private fun startStrobe() {
        oneShot = false
        interactive = true
        strobeActive = true
        strobeGeneration++
        val generation = strobeGeneration
        ensureConnections {
            if (prefs.strobeMode == BlePrefs.StrobeMode.YELLOW_WHITE_SWAP) {
                // Start from a known dark state. The selected normal color/power remain untouched
                // in prefs so STOP can restore exactly what the user had before the strobe.
                sendFrameAll(QStarProtocol.POWER_OFF) {
                    if (strobeActive && generation == strobeGeneration) {
                        event(EVENT_STROBE, message = "strobe_on:${prefs.strobeMode.name}")
                        runStrobeSequence(generation, 0)
                    }
                }
            } else {
                sendFrameAll(QStarProtocol.cctFrame(prefs.strobeWhite, prefs.strobeBrightness)) {
                    if (strobeActive && generation == strobeGeneration) {
                        event(EVENT_STROBE, message = "strobe_on:${prefs.strobeMode.name}")
                        runStrobeSequence(generation, 0)
                    }
                }
            }
        }
    }
'''
)

rep(
    svc,
    '''            BlePrefs.StrobeMode.DOUBLE_ALTERNATE -> {
                if (count < 2) listOf(
                    StrobeStep(allOn, on), StrobeStep(allOff, off),
                    StrobeStep(allOn, on), StrobeStep(allOff, pause)
                ) else listOf(
                    StrobeStep(listOf(true, false), on), StrobeStep(allOff, off),
                    StrobeStep(listOf(true, false), on), StrobeStep(allOff, pause / 2),
                    StrobeStep(listOf(false, true), on), StrobeStep(allOff, off),
                    StrobeStep(listOf(false, true), on), StrobeStep(allOff, pause)
                )
            }
''',
    '''            BlePrefs.StrobeMode.DOUBLE_ALTERNATE -> {
                if (count < 2) listOf(
                    StrobeStep(allOn, on), StrobeStep(allOff, off),
                    StrobeStep(allOn, on), StrobeStep(allOff, pause)
                ) else listOf(
                    StrobeStep(listOf(true, false), on), StrobeStep(allOff, off),
                    StrobeStep(listOf(true, false), on), StrobeStep(allOff, pause / 2),
                    StrobeStep(listOf(false, true), on), StrobeStep(allOff, off),
                    StrobeStep(listOf(false, true), on), StrobeStep(allOff, pause)
                )
            }
            BlePrefs.StrobeMode.YELLOW_WHITE_SWAP -> {
                if (count < 2) {
                    listOf(StrobeStep(allOn, on), StrobeStep(allOff, off))
                } else {
                    // Requested pattern:
                    // L yellow -> dark -> R white -> dark -> L white -> dark -> R yellow -> dark.
                    // It intentionally ignores the long series pause and caps timings for a rapid effect.
                    val fastOn = on.coerceIn(40, 80)
                    val fastOff = off.coerceIn(40, 60)
                    listOf(
                        StrobeStep(listOf(true, false), fastOn, listOf(0, null)),
                        StrobeStep(allOff, fastOff),
                        StrobeStep(listOf(false, true), fastOn, listOf(null, 100)),
                        StrobeStep(allOff, fastOff),
                        StrobeStep(listOf(true, false), fastOn, listOf(100, null)),
                        StrobeStep(allOff, fastOff),
                        StrobeStep(listOf(false, true), fastOn, listOf(null, 0)),
                        StrobeStep(allOff, fastOff)
                    )
                }
            }
'''
)

rep(
    svc,
    '''    private fun runStrobeSequence(generation: Long, index: Int) {
        if (!strobeActive || generation != strobeGeneration) return
        val seq = strobeSequence()
        if (seq.isEmpty()) return
        val stepIndex = index % seq.size
        val step = seq[stepIndex]
        applyPowerStates(step.states) {
            if (!strobeActive || generation != strobeGeneration) return@applyPowerStates
            handler.postDelayed({ runStrobeSequence(generation, (stepIndex + 1) % seq.size) }, step.delayMs.toLong())
        }
    }
''',
    '''    private fun applyStrobeStep(step: StrobeStep, done: () -> Unit) {
        val refs = prefs.devices()
        fun sendAt(index: Int) {
            if (index >= refs.size) { done(); return }
            val connection = connections[refs[index].mac]
            if (connection == null || !connection.isReady()) {
                sendAt(index + 1)
                return
            }
            val desiredOn = step.states.getOrElse(index) { false }
            val desiredWhite = step.whites?.getOrNull(index)

            fun writePower() {
                connection.writeControl(if (desiredOn) QStarProtocol.POWER_ON else QStarProtocol.POWER_OFF) {
                    handler.postDelayed({ sendAt(index + 1) }, 8)
                }
            }

            if (desiredWhite != null) {
                connection.writeControl(QStarProtocol.cctFrame(desiredWhite, prefs.strobeBrightness)) {
                    writePower()
                }
            } else {
                writePower()
            }
        }
        sendAt(0)
    }

    private fun runStrobeSequence(generation: Long, index: Int) {
        if (!strobeActive || generation != strobeGeneration) return
        val seq = strobeSequence()
        if (seq.isEmpty()) return
        val stepIndex = index % seq.size
        val step = seq[stepIndex]
        applyStrobeStep(step) {
            if (!strobeActive || generation != strobeGeneration) return@applyStrobeStep
            handler.postDelayed({ runStrobeSequence(generation, (stepIndex + 1) % seq.size) }, step.delayMs.toLong())
        }
    }
'''
)

# STOP already restores prefs.white / prefs.brightness / prefs.power. Since startStrobe no longer
# overwrites prefs.power, this now truly restores the selected normal settings.

Path('.hotfix-v033-applied').write_text('QStarLight 0.3.3 rapid yellow-white swap strobe applied\n')
