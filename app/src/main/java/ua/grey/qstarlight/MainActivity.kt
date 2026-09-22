package ua.grey.qstarlight

import android.Manifest
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Rect
import androidx.activity.result.contract.ActivityResultContracts
import kotlin.concurrent.thread
import java.util.concurrent.atomic.AtomicBoolean
import ua.grey.qstarlight.diagnostics.DiagnosticLog
import ua.grey.qstarlight.sync.ConfigSyncStatus
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.VelocityTracker
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.SeekBar
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import org.json.JSONObject
import ua.grey.qstarlight.ble.BlePrefs
import ua.grey.qstarlight.ble.BlePrefs.RuntimeLinkState as UiLinkState
import ua.grey.qstarlight.ble.QStarBleService
import ua.grey.qstarlight.ble.StrobeTimeline
import ua.grey.qstarlight.control.ControlDispatcher
import ua.grey.qstarlight.remote.RemoteLinkService
import ua.grey.qstarlight.update.UpdateManager
import ua.grey.qstarlight.update.UpdateScheduler
import ua.grey.qstarlight.ui.LinkIndicator
import ua.grey.qstarlight.ui.StrobePreviewView
import ua.grey.qstarlight.widget.QStarWidgetProvider
import java.util.LinkedHashMap
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private lateinit var prefs: BlePrefs
    private val handler = Handler(Looper.getMainLooper())
    private val found = LinkedHashMap<String, BlePrefs.DeviceRef>()
    private val statusByMac = LinkedHashMap<String, String>()
    private val stateByMac = LinkedHashMap<String, UiLinkState>()
    private var hubUiState = UiLinkState.OFFLINE

    private var pendingSliderSend: Runnable? = null
    private var pendingConfigSync: Runnable? = null
    private var updatingUi = false
    private var strobeOnUi = false

    private lateinit var controlPage: ScrollView
    private lateinit var settingsPage: ScrollView
    private lateinit var diagnosticsPage: View
    private lateinit var diagnosticsLogScroll: ScrollView
    private lateinit var tabDiagnostics: Button
    private lateinit var tvDiagnosticsInfo: TextView
    private lateinit var tvLogCount: TextView
    private lateinit var pageContainer: View
    private lateinit var tabControl: Button
    private lateinit var tabSettings: Button

    private lateinit var tvVersion: TextView
    private lateinit var tvRoleBadge: TextView
    private lateinit var tvLinkStatus: TextView
    private lateinit var tvLamp1: TextView
    private lateinit var tvLamp2: TextView
    private lateinit var tvTemp: TextView
    private lateinit var tvBrightness: TextView
    private lateinit var tvStrobeMode: TextView
    private lateinit var tvRoute: TextView
    private lateinit var tvHubPin: TextView
    private lateinit var tvRemoteStatus: TextView
    private lateinit var tvSyncStatus: TextView
    private lateinit var tvUpdateStatus: TextView
    private lateinit var tvStartWhite: TextView
    private lateinit var tvTargetWhite: TextView
    private lateinit var tvStartBrightness: TextView
    private lateinit var tvFadeDuration: TextView
    private lateinit var tvFadeSteps: TextView
    private lateinit var tvStrobeWhite: TextView
    private lateinit var tvStrobeBrightness: TextView
    private lateinit var tvStrobeOn: TextView
    private lateinit var tvStrobeOff: TextView
    private lateinit var tvStrobePause: TextView
    private lateinit var tvLog: TextView

    private lateinit var seekTemp: SeekBar
    private lateinit var seekBrightness: SeekBar
    private lateinit var seekStartWhite: SeekBar
    private lateinit var seekTargetWhite: SeekBar
    private lateinit var seekStartBrightness: SeekBar
    private lateinit var seekFadeDuration: SeekBar
    private lateinit var seekFadeSteps: SeekBar
    private lateinit var seekStrobeWhite: SeekBar
    private lateinit var seekStrobeBrightness: SeekBar
    private lateinit var seekStrobeOn: SeekBar
    private lateinit var seekStrobeOff: SeekBar
    private lateinit var seekStrobePause: SeekBar

    private lateinit var switchPower: Switch
    private lateinit var switchAutoBoot: Switch
    private lateinit var switchWelcome: Switch
    private lateinit var switchKeep: Switch
    private lateinit var switchDirectFallback: Switch
    private lateinit var switchForceDirect: Switch
    private lateinit var switchRemoteKeepAlive: Switch
    private lateinit var switchAutoPushUpdates: Switch
    private lateinit var switchSilentRootInstall: Switch

    private lateinit var spinnerStartupMode: Spinner
    private lateinit var spinnerStrobeMode: Spinner
    private lateinit var strobePreview: StrobePreviewView
    private lateinit var editRemotePin: EditText
    private lateinit var editHubIp: EditText
    private lateinit var hubPinGroup: View
    private lateinit var phonePairGroup: View
    private lateinit var routeCard: View
    private lateinit var btnRoleHub: Button
    private lateinit var btnRolePhone: Button
    private lateinit var btnRouteHub: Button
    private lateinit var btnRouteDirect: Button
    private lateinit var btnStrobeToggle: Button
    private lateinit var btnYellow: Button
    private lateinit var btnWarm: Button
    private lateinit var btnWhite: Button
    private lateinit var btnPushUpdate: Button
    private lateinit var btnInstallPermission: Button

    private var currentPage = 0
    private var swipeTargetPage = 0
    private var pageFinish: Runnable? = null
    @Volatile private var activityStarted = false
    private val logRefreshScheduled = AtomicBoolean(false)
    private var pendingLogExport: String? = null
    private var logAutoScroll = true
    private var logTouching = false
    private var strobeBlink: AlphaAnimation? = null
    private val strobeStatusTicker = object : Runnable {
        override fun run() {
            if (!strobeOnUi) return
            updateStrobeIndicators()
            handler.postDelayed(this, 40L)
        }
    }
    private val logRefresh = Runnable {
        logRefreshScheduled.set(false)
        updateSyncStatus()
        UpdateScheduler.lastStatus?.let { tvUpdateStatus.text = it }
        if (currentPage == 2) renderDiagnostics()
    }
    private val logListener: () -> Unit = {
        if (activityStarted && logRefreshScheduled.compareAndSet(false, true)) handler.postDelayed(logRefresh, 150)
    }
    private val exportLog = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) {
            val report = pendingLogExport ?: DiagnosticLog.report(this)
            thread(name = "QStarLogExport") {
                val result = runCatching {
                    val output = contentResolver.openOutputStream(uri) ?: error("Не вдалося відкрити файл")
                    output.bufferedWriter(Charsets.UTF_8).use { it.write(report) }
                }
                handler.post {
                    val message = if (result.isSuccess) "Журнал збережено" else "Помилка експорту: ${result.exceptionOrNull()?.message}"
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                    DiagnosticLog.write("EXPORT", message, if (result.isSuccess) "INFO" else "ERROR")
                }
            }
        }
        pendingLogExport = null
    }
    private var swipeDownX = 0f
    private var swipeDownY = 0f
    private var pageSwipeActive = false
    private var pageSwipeBlocked = false
    private var velocityTracker: VelocityTracker? = null
    private val pageSwipeSlop by lazy { ViewConfiguration.get(this).scaledTouchSlop }
    private val pageSwipeMinDistance by lazy { 64f * resources.displayMetrics.density }

    private val startupLabels = arrayOf(
        "Відновити останній стан",
        "Тільки стартовий колір",
        "Стартовий → робочий",
        "Плавний жовтий → білий",
        "Почати вимкненими"
    )

    private val strobeLabels = arrayOf(
        "Класичний",
        "Подвійний",
        "Потрійний",
        "Ліво ↔ право",
        "Подвійний ліво ↔ право",
        "Жовтий ↔ білий • швидкий"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        prefs = BlePrefs(this).also { it.ensureDefaults() }
        applyRoleOrientation()
        applyRoleTheme()
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)
        applySystemBarInsets()
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        bindViews()
        configureUi()
        bindActions()
        showPage(savedInstanceState?.getInt("page", 0) ?: 0)
        ensurePermissions()
    }

    private fun bindViews() {
        controlPage = findViewById(R.id.controlPage)
        settingsPage = findViewById(R.id.settingsPage)
        diagnosticsPage = findViewById(R.id.diagnosticsPage)
        diagnosticsLogScroll = findViewById(R.id.diagnosticsLogScroll)
        tabDiagnostics = findViewById(R.id.tabDiagnostics)
        tvDiagnosticsInfo = findViewById(R.id.tvDiagnosticsInfo)
        tvLogCount = findViewById(R.id.tvLogCount)
        pageContainer = findViewById(R.id.pageContainer)
        tabControl = findViewById(R.id.tabControl)
        tabSettings = findViewById(R.id.tabSettings)

        tvVersion = findViewById(R.id.tvVersion)
        tvRoleBadge = findViewById(R.id.tvRoleBadge)
        tvLinkStatus = findViewById(R.id.tvLinkStatus)
        tvLamp1 = findViewById(R.id.tvLamp1)
        tvLamp2 = findViewById(R.id.tvLamp2)
        tvTemp = findViewById(R.id.tvTemp)
        tvBrightness = findViewById(R.id.tvBrightness)
        tvStrobeMode = findViewById(R.id.tvStrobeMode)
        tvRoute = findViewById(R.id.tvRoute)
        tvHubPin = findViewById(R.id.tvHubPin)
        tvRemoteStatus = findViewById(R.id.tvRemoteStatus)
        tvSyncStatus = findViewById(R.id.tvSyncStatus)
        tvUpdateStatus = findViewById(R.id.tvUpdateStatus)
        tvStartWhite = findViewById(R.id.tvStartWhite)
        tvTargetWhite = findViewById(R.id.tvTargetWhite)
        tvStartBrightness = findViewById(R.id.tvStartBrightness)
        tvFadeDuration = findViewById(R.id.tvFadeDuration)
        tvFadeSteps = findViewById(R.id.tvFadeSteps)
        tvStrobeWhite = findViewById(R.id.tvStrobeWhite)
        tvStrobeBrightness = findViewById(R.id.tvStrobeBrightness)
        tvStrobeOn = findViewById(R.id.tvStrobeOn)
        tvStrobeOff = findViewById(R.id.tvStrobeOff)
        tvStrobePause = findViewById(R.id.tvStrobePause)
        tvLog = findViewById(R.id.tvLog)

        seekTemp = findViewById(R.id.seekTemp)
        seekBrightness = findViewById(R.id.seekBrightness)
        seekStartWhite = findViewById(R.id.seekStartWhite)
        seekTargetWhite = findViewById(R.id.seekTargetWhite)
        seekStartBrightness = findViewById(R.id.seekStartBrightness)
        seekFadeDuration = findViewById(R.id.seekFadeDuration)
        seekFadeSteps = findViewById(R.id.seekFadeSteps)
        seekStrobeWhite = findViewById(R.id.seekStrobeWhite)
        seekStrobeBrightness = findViewById(R.id.seekStrobeBrightness)
        seekStrobeOn = findViewById(R.id.seekStrobeOn)
        seekStrobeOff = findViewById(R.id.seekStrobeOff)
        seekStrobePause = findViewById(R.id.seekStrobePause)

        switchPower = findViewById(R.id.switchPower)
        switchAutoBoot = findViewById(R.id.switchAutoBoot)
        switchWelcome = findViewById(R.id.switchWelcome)
        switchKeep = findViewById(R.id.switchKeep)
        switchDirectFallback = findViewById(R.id.switchDirectFallback)
        switchForceDirect = findViewById(R.id.switchForceDirect)
        switchRemoteKeepAlive = findViewById(R.id.switchRemoteKeepAlive)
        switchAutoPushUpdates = findViewById(R.id.switchAutoPushUpdates)
        switchSilentRootInstall = findViewById(R.id.switchSilentRootInstall)

        spinnerStartupMode = findViewById(R.id.spinnerStartupMode)
        spinnerStrobeMode = findViewById(R.id.spinnerStrobeMode)
        strobePreview = findViewById(R.id.strobePreview)
        editRemotePin = findViewById(R.id.editRemotePin)
        editHubIp = findViewById(R.id.editHubIp)
        hubPinGroup = findViewById(R.id.hubPinGroup)
        phonePairGroup = findViewById(R.id.phonePairGroup)
        routeCard = findViewById(R.id.routeCard)
        btnRoleHub = findViewById(R.id.btnRoleHub)
        btnRolePhone = findViewById(R.id.btnRolePhone)
        btnRouteHub = findViewById(R.id.btnRouteHub)
        btnRouteDirect = findViewById(R.id.btnRouteDirect)
        btnStrobeToggle = findViewById(R.id.btnStrobeToggle)
        btnYellow = findViewById(R.id.btnYellow)
        btnWarm = findViewById(R.id.btnWarm)
        btnWhite = findViewById(R.id.btnWhite)
        btnPushUpdate = findViewById(R.id.btnPushUpdate)
        btnInstallPermission = findViewById(R.id.btnInstallPermission)
        diagnosticsLogScroll.setOnScrollChangeListener { view, _, scrollY, _, _ ->
            if (!logTouching) {
                val scroll = view as ScrollView
                val child = scroll.getChildAt(0)
                logAutoScroll = child == null || scrollY + scroll.height >= child.height - 16.dp()
            }
        }
        diagnosticsLogScroll.setOnTouchListener { view, event ->
            val scroll = view as ScrollView
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    // Freeze both the position and the rendered content while the user interacts.
                    logTouching = true
                    logAutoScroll = false
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    logTouching = false
                    val child = scroll.getChildAt(0)
                    logAutoScroll = child == null || scroll.scrollY + scroll.height >= child.height - 16.dp()
                    handler.post { if (currentPage == 2) renderDiagnostics() }
                }
            }
            false
        }
    }

    private fun configureUi() {
        updatingUi = true
        val packageInfo = packageManager.getPackageInfo(packageName, 0)
        val updated = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(packageInfo.lastUpdateTime))
        tvVersion.text = "v${UpdateManager.versionName(this)} • оновлено $updated"

        spinnerStartupMode.adapter = ArrayAdapter(this, R.layout.spinner_selected, startupLabels)
            .also { it.setDropDownViewResource(R.layout.spinner_dropdown_item) }
        spinnerStrobeMode.adapter = ArrayAdapter(this, R.layout.spinner_selected, strobeLabels)
            .also { it.setDropDownViewResource(R.layout.spinner_dropdown_item) }

        seekTemp.max = 100
        seekBrightness.min = 5
        seekBrightness.max = 100

        seekStartWhite.max = 100
        seekTargetWhite.max = 100
        seekStartBrightness.min = 5
        seekStartBrightness.max = 100
        seekFadeDuration.max = 100
        seekFadeSteps.min = 2
        seekFadeSteps.max = 80

        seekStrobeWhite.max = 100
        seekStrobeBrightness.min = 5
        seekStrobeBrightness.max = 100
        seekStrobeOn.min = 40
        seekStrobeOn.max = 1000
        seekStrobeOff.min = 40
        seekStrobeOff.max = 1000
        seekStrobePause.min = 100
        seekStrobePause.max = 2000

        refreshAllControlsFromPrefs()
        strobeOnUi = prefs.strobeActive
        updatingUi = false
        showPage(0)
        updateRoleUi()
        updateLampCards()
        updateStrobeButton()
    }

    private fun refreshAllControlsFromPrefs() {
        val old = updatingUi
        val controlScrollY = controlPage.scrollY
        val settingsScrollY = settingsPage.scrollY
        updatingUi = true

        seekTemp.progress = prefs.white
        seekBrightness.progress = prefs.brightness
        switchPower.isChecked = prefs.power

        switchAutoBoot.isChecked = prefs.autoBoot
        switchWelcome.isChecked = prefs.welcomeOnConnect
        switchKeep.isChecked = prefs.keepConnected
        switchDirectFallback.isChecked = prefs.directFallback
        switchForceDirect.isChecked = prefs.forceDirect
        switchRemoteKeepAlive.isChecked = true
        switchRemoteKeepAlive.isEnabled = false
        switchAutoPushUpdates.isChecked = prefs.autoPushUpdates
        switchSilentRootInstall.isChecked = prefs.silentRootInstall

        if (!editRemotePin.hasFocus() && editRemotePin.text.toString() != prefs.remotePin) editRemotePin.setText(prefs.remotePin)
        if (!editHubIp.hasFocus() && editHubIp.text.toString() != prefs.manualHubHost) editHubIp.setText(prefs.manualHubHost)

        spinnerStartupMode.setSelection(prefs.startupMode.ordinal, false)
        seekStartWhite.progress = prefs.startWhite
        seekTargetWhite.progress = prefs.targetWhite
        seekStartBrightness.progress = prefs.startBrightness
        seekFadeDuration.progress = (prefs.fadeDurationMs / 100).coerceIn(0, 100)
        seekFadeSteps.progress = prefs.fadeSteps

        spinnerStrobeMode.setSelection(prefs.strobeMode.ordinal, false)
        seekStrobeWhite.progress = prefs.strobeWhite
        seekStrobeBrightness.progress = prefs.strobeBrightness
        seekStrobeOn.progress = prefs.strobeOnMs
        seekStrobeOff.progress = prefs.strobeOffMs
        seekStrobePause.progress = prefs.strobePauseMs

        updateLabels()
        updateSettingsLabels()
        updateSyncStatus()
        updatingUi = old
        handler.post { controlPage.scrollTo(0, controlScrollY); settingsPage.scrollTo(0, settingsScrollY) }
    }

    private fun bindActions() {
        tabControl.setOnClickListener { animateToPage(0) }
        tabSettings.setOnClickListener { animateToPage(1) }
        tabDiagnostics.setOnClickListener { animateToPage(2) }
        findViewById<Button>(R.id.btnSyncNow).setOnClickListener {
            pendingConfigSync?.let(handler::removeCallbacks)
            pendingConfigSync = null
            pendingSliderSend?.let(handler::removeCallbacks)
            pendingSliderSend = null
            DiagnosticLog.write("UI", "Manual settings synchronization requested")
            ConfigSyncStatus.waiting("Примусова синхронізація • очікую інший пристрій")
            ControlDispatcher.configChanged(this)
            updateSyncStatus()
        }
        findViewById<Button>(R.id.btnCheckUpdate).setOnClickListener {
            UpdateScheduler.checkNow(this)
            tvUpdateStatus.text = UpdateScheduler.lastStatus
        }
        findViewById<Button>(R.id.btnCopyLogs).setOnClickListener {
            val report = DiagnosticLog.report(this)
            val clipboard = getSystemService(ClipboardManager::class.java)
            // Binder caps clipboard payloads; a larger journal is always available as a TXT export.
            val text = if (report.toByteArray(Charsets.UTF_8).size <= 450_000) report else
                "Журнал скорочено для буфера; повна версія — Експорт .txt.\n" + report.takeLast(100_000)
            clipboard.setPrimaryClip(ClipData.newPlainText("QSTAR LIGHT diagnostics", text))
            Toast.makeText(this, "Журнал скопійовано", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.btnExportLogs).setOnClickListener {
            pendingLogExport = DiagnosticLog.report(this)
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            runCatching { exportLog.launch("QStarLight-${prefs.role().name}-$stamp.txt") }
                .onFailure { pendingLogExport = null; Toast.makeText(this, "Немає системного вибору файлів: ${it.message}", Toast.LENGTH_LONG).show() }
        }

        findViewById<Button>(R.id.btnReconnect).setOnClickListener {
            if (ensurePermissions()) {
                ControlDispatcher.connect(this)
                Toast.makeText(this, "Підключаю те, що зараз offline…", Toast.LENGTH_SHORT).show()
            }
        }
        tvLinkStatus.setOnClickListener { retryHubConnection() }
        tvRemoteStatus.setOnClickListener { retryHubConnection() }
        tvLamp1.setOnClickListener { retryLamp(0) }
        tvLamp2.setOnClickListener { retryLamp(1) }
        btnYellow.setOnClickListener { setPreset(0) }
        btnWarm.setOnClickListener { setPreset(50) }
        btnWhite.setOnClickListener { setPreset(100) }
        findViewById<Button>(R.id.btnBrightnessDown).setOnClickListener {
            ControlDispatcher.brightnessDelta(this, -10)
            refreshFromPrefsDelayed()
        }
        findViewById<Button>(R.id.btnBrightnessUp).setOnClickListener {
            ControlDispatcher.brightnessDelta(this, 10)
            refreshFromPrefsDelayed()
        }

        btnStrobeToggle.setOnClickListener {
            if (!ensurePermissions(false) && prefs.forceDirect) return@setOnClickListener
            ControlDispatcher.strobe(this, !strobeOnUi)
            strobeOnUi = !strobeOnUi
            updateStrobeButton()
        }

        switchPower.setOnCheckedChangeListener { _, checked ->
            if (updatingUi) return@setOnCheckedChangeListener
            prefs.power = checked
            if (ensurePermissions(false) || prefs.role() == BlePrefs.Role.PHONE) {
                ControlDispatcher.power(this, checked)
            }
        }

        val mainSeekListener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                prefs.updateLight(white = seekTemp.progress, brightness = seekBrightness.progress)
                updateLabels()
                scheduleSliderSend()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) { sendCurrentCct() }
        }
        seekTemp.setOnSeekBarChangeListener(mainSeekListener)
        seekBrightness.setOnSeekBarChangeListener(mainSeekListener)
        listOf(seekTemp, seekBrightness).forEach(::lockScrollWhileSeeking)

        btnRouteHub.setOnClickListener {
            prefs.forceDirect = false
            updatingUi = true
            switchForceDirect.isChecked = false
            updatingUi = false
            ControlDispatcher.routeChanged(this)
            updateRoleUi()
        }
        btnRouteDirect.setOnClickListener {
            if (!ensurePermissions()) return@setOnClickListener
            prefs.forceDirect = true
            updatingUi = true
            switchForceDirect.isChecked = true
            updatingUi = false
            ControlDispatcher.routeChanged(this)
            updateRoleUi()
        }

        btnRoleHub.setOnClickListener { changeRole(BlePrefs.Role.HUB) }
        btnRolePhone.setOnClickListener { changeRole(BlePrefs.Role.PHONE) }

        findViewById<Button>(R.id.btnSavePair).setOnClickListener {
            prefs.remotePin = editRemotePin.text.toString()
            prefs.manualHubHost = editHubIp.text.toString()
            RemoteLinkService.start(this)
            Toast.makeText(this, "Шукаю магнітолу…", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.btnNewPin).setOnClickListener {
            tvHubPin.text = prefs.regenerateHubPin()
            Toast.makeText(this, "Новий PIN. На телефоні введи його заново.", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.btnScan).setOnClickListener {
            found.clear()
            if (ensurePermissions()) ControlDispatcher.scanDirect(this)
        }
        findViewById<Button>(R.id.btnSelect).setOnClickListener { showDevicePicker() }
        findViewById<Button>(R.id.btnPasswords).setOnClickListener { showPasswordDialog() }

        switchAutoBoot.setOnCheckedChangeListener { _, v -> if (!updatingUi) prefs.autoBoot = v }
        switchWelcome.setOnCheckedChangeListener { _, v ->
            if (!updatingUi) {
                prefs.welcomeOnConnect = v
                scheduleConfigSync()
            }
        }
        switchKeep.setOnCheckedChangeListener { _, v -> if (!updatingUi) prefs.keepConnected = v }
        switchDirectFallback.setOnCheckedChangeListener { _, v -> if (!updatingUi) prefs.directFallback = v }
        // Background lifetime is role policy, not a user toggle: PHONE keeps
        // links plus a 60-minute reconnect window; HUB stays alive permanently.
        switchAutoPushUpdates.setOnCheckedChangeListener { _, v -> if (!updatingUi) prefs.autoPushUpdates = v }
        switchSilentRootInstall.setOnCheckedChangeListener { _, v -> if (!updatingUi) prefs.silentRootInstall = v }
        switchForceDirect.setOnCheckedChangeListener { _, v ->
            if (updatingUi) return@setOnCheckedChangeListener
            prefs.forceDirect = v
            if (v && !ensurePermissions()) return@setOnCheckedChangeListener
            ControlDispatcher.routeChanged(this)
            updateRoleUi()
        }

        btnPushUpdate.setOnClickListener {
            if (prefs.role() == BlePrefs.Role.PHONE) {
                RemoteLinkService.pushUpdate(this)
                tvUpdateStatus.text = "Передаю APK на HUB…"
            }
        }
        btnInstallPermission.setOnClickListener { openInstallPermission() }

        spinnerStartupMode.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (updatingUi) return
                val mode = BlePrefs.StartupMode.entries.getOrNull(position) ?: return
                if (prefs.startupMode != mode) {
                    prefs.startupMode = mode
                    updateSettingsLabels()
                    scheduleConfigSync()
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        spinnerStrobeMode.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (updatingUi) return
                val mode = BlePrefs.StrobeMode.entries.getOrNull(position) ?: return
                if (prefs.strobeMode != mode) {
                    prefs.strobeMode = mode
                    updateSettingsLabels()
                    scheduleConfigSync()
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        bindSyncSeek(seekStartWhite, { prefs.startWhite = it })
        bindSyncSeek(seekTargetWhite, { prefs.targetWhite = it })
        bindSyncSeek(seekStartBrightness, { prefs.startBrightness = it })
        bindSyncSeek(seekFadeDuration, { prefs.fadeDurationMs = it * 100 })
        bindSyncSeek(seekFadeSteps, { prefs.fadeSteps = it })
        bindSyncSeek(seekStrobeWhite, { prefs.strobeWhite = it })
        bindSyncSeek(seekStrobeBrightness, { prefs.strobeBrightness = it })
        bindSyncSeek(seekStrobeOn, { prefs.strobeOnMs = it })
        bindSyncSeek(seekStrobeOff, { prefs.strobeOffMs = it })
        bindSyncSeek(seekStrobePause, { prefs.strobePauseMs = it })
    }

    private fun bindSyncSeek(seek: SeekBar, setter: (Int) -> Unit) {
        lockScrollWhileSeeking(seek)
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser || updatingUi) return
                setter(progress)
                updateSettingsLabels()
                scheduleConfigSync()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                if (!updatingUi) scheduleConfigSync(0)
            }
        })
    }

    private fun lockScrollWhileSeeking(seek: SeekBar) {
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var horizontalDrag = false
        seek.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    horizontalDrag = false
                    view.parent?.requestDisallowInterceptTouchEvent(false)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = kotlin.math.abs(event.x - downX)
                    val dy = kotlin.math.abs(event.y - downY)
                    if (!horizontalDrag && maxOf(dx, dy) > slop) horizontalDrag = dx > dy
                    view.parent?.requestDisallowInterceptTouchEvent(horizontalDrag)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    view.parent?.requestDisallowInterceptTouchEvent(false)
            }
            false
        }
    }

    override fun onStart() {
        super.onStart()
        activityStarted = true
        DiagnosticLog.addListener(logListener)
        handler.post(logRefresh)
        if (strobeOnUi) updateStrobeButton()
        ContextCompat.registerReceiver(
            this, receiver, IntentFilter(QStarBleService.ACTION_EVENT), ContextCompat.RECEIVER_NOT_EXPORTED
        )
        ContextCompat.registerReceiver(
            this, remoteReceiver, IntentFilter(RemoteLinkService.ACTION_EVENT), ContextCompat.RECEIVER_NOT_EXPORTED
        )
        PresenceMonitor.ensure(this)
        UpdateScheduler.ensure(this)
        runCatching { if (ensurePermissions(false) || prefs.role() == BlePrefs.Role.PHONE) ControlDispatcher.connect(this) }
            .onFailure { appendLog("START ERROR ${it.javaClass.simpleName}: ${it.message.orEmpty()}") }
    }

    override fun onStop() {
        if (pageFinish != null || pageSwipeActive) showPage(currentPage)
        activityStarted = false
        DiagnosticLog.removeListener(logListener)
        handler.removeCallbacks(logRefresh)
        handler.removeCallbacks(strobeStatusTicker)
        logRefreshScheduled.set(false)
        // Flush the final value even when the user leaves before the UI throttle fires.
        pendingConfigSync?.let { handler.removeCallbacks(it); pendingConfigSync = null; it.run() }
        super.onStop()
        try { unregisterReceiver(receiver) } catch (_: Throwable) { }
        try { unregisterReceiver(remoteReceiver) } catch (_: Throwable) { }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("page", currentPage)
        super.onSaveInstanceState(outState)
    }

    private fun pages(): List<View> = listOf(controlPage, settingsPage, diagnosticsPage)

    private fun showPage(index: Int) {
        pageFinish?.let(handler::removeCallbacks)
        pageFinish = null
        pageSwipeActive = false
        currentPage = index.coerceIn(0, 2)
        pages().forEachIndexed { page, view ->
            view.animate().cancel()
            view.visibility = if (page == currentPage) View.VISIBLE else View.GONE
            view.translationX = 0f
            view.alpha = 1f
        }
        listOf(tabControl, tabSettings, tabDiagnostics).forEachIndexed { page, tab -> tab.isSelected = page == currentPage }
        if (currentPage == 2) renderDiagnostics()
    }

    private fun animateToPage(index: Int) {
        if (index == currentPage) { showPage(index); return }
        showPage(currentPage)
        swipeTargetPage = index
        updatePageSwipe(0f)
        finishPageSwipe(true)
    }

    private fun applySystemBarInsets() {
        val root = findViewById<View>(R.id.rootLayout)
        val initialStart = root.paddingLeft
        val initialTop = root.paddingTop
        val initialEnd = root.paddingRight
        val initialBottom = root.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, windowInsets ->
            val bars = windowInsets.getInsets(
                WindowInsetsCompat.Type.statusBars() or
                    WindowInsetsCompat.Type.navigationBars() or
                    WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(
                initialStart + bars.left,
                initialTop + bars.top,
                initialEnd + bars.right,
                initialBottom + bars.bottom
            )
            windowInsets
        }
        ViewCompat.requestApplyInsets(root)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (pageFinish != null) showPage(currentPage)
                swipeDownX = event.rawX
                swipeDownY = event.rawY
                pageSwipeActive = false
                pageSwipeBlocked = !isInsideView(event.rawX, event.rawY, pageContainer) ||
                    allSeekBars().any { isInsideView(event.rawX, event.rawY, it) }
                velocityTracker?.recycle()
                velocityTracker = VelocityTracker.obtain().also { it.addMovement(event) }
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                pageSwipeBlocked = true
                if (pageSwipeActive) { finishPageSwipe(false); return true }
            }
            MotionEvent.ACTION_MOVE -> {
                velocityTracker?.addMovement(event)
                if (!pageSwipeBlocked) {
                    val dx = event.rawX - swipeDownX
                    val dy = event.rawY - swipeDownY
                    if (!pageSwipeActive && kotlin.math.abs(dy) > pageSwipeSlop && kotlin.math.abs(dy) >= kotlin.math.abs(dx)) {
                        pageSwipeBlocked = true
                    } else if (!pageSwipeActive && kotlin.math.abs(dx) > pageSwipeSlop && kotlin.math.abs(dx) > kotlin.math.abs(dy) * 1.25f) {
                        swipeTargetPage = currentPage + if (dx < 0) 1 else -1
                        if (swipeTargetPage in 0..2) {
                            pageSwipeActive = true
                            val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
                            super.dispatchTouchEvent(cancel)
                            cancel.recycle()
                        } else pageSwipeBlocked = true
                    }
                    if (pageSwipeActive) { updatePageSwipe(dx); return true }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                velocityTracker?.addMovement(event)
                if (pageSwipeActive) {
                    val direction = if (swipeTargetPage > currentPage) -1f else 1f
                    val dx = (event.rawX - swipeDownX) * direction
                    velocityTracker?.computeCurrentVelocity(1000)
                    val velocity = (velocityTracker?.xVelocity ?: 0f) * direction
                    val complete = event.actionMasked == MotionEvent.ACTION_UP &&
                        (dx >= pageSwipeMinDistance || (dx > pageSwipeSlop && velocity >= 700f * resources.displayMetrics.density))
                    finishPageSwipe(complete)
                    velocityTracker?.recycle()
                    velocityTracker = null
                    return true
                }
                velocityTracker?.recycle()
                velocityTracker = null
            }
        }
        return super.dispatchTouchEvent(event)
    }

    private fun updatePageSwipe(dx: Float) {
        val width = pageContainer.width.toFloat().coerceAtLeast(1f)
        val direction = if (swipeTargetPage > currentPage) 1f else -1f
        val offset = if (direction > 0) dx.coerceIn(-width, 0f) else dx.coerceIn(0f, width)
        val current = pages()[currentPage]
        val incoming = pages()[swipeTargetPage]
        incoming.visibility = View.VISIBLE
        current.translationX = offset
        incoming.translationX = offset + direction * width
    }

    private fun finishPageSwipe(complete: Boolean) {
        pageSwipeActive = false
        val target = if (complete) swipeTargetPage else currentPage
        val width = pageContainer.width.toFloat().coerceAtLeast(1f)
        val direction = if (swipeTargetPage > currentPage) 1f else -1f
        listOf(
            pages()[currentPage] to if (complete) -direction * width else 0f,
            pages()[swipeTargetPage] to if (complete) 0f else direction * width
        ).forEach { (view, offset) ->
            view.animate().translationX(offset).setDuration(180L)
                .setInterpolator(AccelerateDecelerateInterpolator()).start()
        }
        pageFinish = Runnable { showPage(target) }.also { handler.postDelayed(it, 180L) }
    }

    private fun allSeekBars(): List<SeekBar> = listOf(
        seekTemp, seekBrightness, seekStartWhite, seekTargetWhite, seekStartBrightness,
        seekFadeDuration, seekFadeSteps, seekStrobeWhite, seekStrobeBrightness,
        seekStrobeOn, seekStrobeOff, seekStrobePause
    )

    private fun isInsideView(rawX: Float, rawY: Float, view: View): Boolean {
        if (!view.isShown) return false
        val bounds = Rect()
        return view.getGlobalVisibleRect(bounds) && bounds.contains(rawX.toInt(), rawY.toInt())
    }

    private fun changeRole(role: BlePrefs.Role) {
        prefs.roleOverride = if (role == BlePrefs.Role.HUB) "hub" else "phone"
        applyRoleOrientation()
        strobeOnUi = false
        if (role == BlePrefs.Role.HUB) {
            prefs.forceDirect = false
            RemoteLinkService.stop(this)
        } else {
            QStarBleService.start(this, Intent().setAction(QStarBleService.ACTION_RELEASE))
        }
        refreshAllControlsFromPrefs()
        updateRoleUi()
        updateStrobeButton()
        ControlDispatcher.connect(this)
        QStarWidgetProvider.refresh(this)
        applyRoleTheme()
    }

    private fun applyRoleTheme() {
        delegate.localNightMode = if (prefs.role() == BlePrefs.Role.HUB) {
            AppCompatDelegate.MODE_NIGHT_NO
        } else {
            AppCompatDelegate.MODE_NIGHT_YES
        }
    }

    private fun applyRoleOrientation() {
        requestedOrientation = if (prefs.role() == BlePrefs.Role.HUB) {
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }

    private fun updateRoleUi() {
        val role = prefs.role()
        val hub = role == BlePrefs.Role.HUB
        tvRoleBadge.text = if (hub) "МАГНІТОЛА • HUB" else "ТЕЛЕФОН • REMOTE"
        tvHubPin.text = prefs.hubPin
        hubPinGroup.visibility = if (hub) View.VISIBLE else View.GONE
        phonePairGroup.visibility = if (hub) View.GONE else View.VISIBLE
        routeCard.visibility = if (hub) View.GONE else View.VISIBLE
        btnRoleHub.isSelected = hub
        btnRolePhone.isSelected = !hub
        btnRouteHub.isSelected = !prefs.forceDirect
        btnRouteDirect.isSelected = prefs.forceDirect

        btnPushUpdate.visibility = if (hub) View.GONE else View.VISIBLE
        switchAutoPushUpdates.visibility = if (hub) View.GONE else View.VISIBLE
        switchSilentRootInstall.visibility = if (hub) View.VISIBLE else View.GONE
        btnInstallPermission.visibility = if (hub) View.VISIBLE else View.GONE

        tvRoute.text = if (hub) {
            "Магнітола тримає BLE-з'єднання з обома лампами"
        } else if (prefs.forceDirect) {
            "Прямий BLE: телефон забирає лампи собі, зв'язок з HUB лишається"
        } else {
            "Основний канал: телефон → магнітола → лампи"
        }
        if (hub) {
            val phoneState = prefs.hubRuntimeState
            val phoneText = when (phoneState) {
                BlePrefs.RuntimeLinkState.CONNECTED -> "Телефон • підключено"
                BlePrefs.RuntimeLinkState.CONNECTING -> "Телефон • підключення…"
                BlePrefs.RuntimeLinkState.OFFLINE -> "Телефон • немає з'єднання"
            }
            setHubStatus(phoneState, phoneText)
        } else if (RemoteLinkService.connected || prefs.hubRuntimeState == BlePrefs.RuntimeLinkState.CONNECTED) {
            setHubStatus(UiLinkState.CONNECTED, "Магнітола • підключено")
        } else if (hubUiState == UiLinkState.CONNECTING || prefs.hubRuntimeState == BlePrefs.RuntimeLinkState.CONNECTING) {
            setHubStatus(UiLinkState.CONNECTING, "Магнітола • підключення…")
        } else {
            setHubStatus(UiLinkState.OFFLINE, "Магнітола • немає з'єднання")
        }

        tvUpdateStatus.text = UpdateScheduler.lastStatus ?: if (hub) {
            if (prefs.silentRootInstall) "HUB готовий приймати APK • root install увімкнено" else "HUB готовий приймати APK з телефона"
        } else {
            val hubVersion = RemoteLinkService.hubVersionCode
            if (hubVersion > 0) "Телефон v${UpdateManager.versionName(this)} • HUB code $hubVersion" else "Версія HUB: невідомо"
        }
    }

    private fun setPreset(white: Int) {
        seekTemp.progress = white
        prefs.white = white
        updateLabels()
        ControlDispatcher.preset(this, white)
    }

    private fun scheduleSliderSend() {
        if (pendingSliderSend != null) return
        val task = Runnable { pendingSliderSend = null; sendCurrentCct() }
        pendingSliderSend = task
        handler.postDelayed(task, 120)
    }

    private fun sendCurrentCct() {
        pendingSliderSend?.let(handler::removeCallbacks)
        pendingSliderSend = null
        if (!ensurePermissions(false) && prefs.forceDirect) return
        ControlDispatcher.apply(this, prefs.white, prefs.brightness)
    }

    private fun scheduleConfigSync(delayMs: Long = 120L) {
        ConfigSyncStatus.waiting("Є зміни • передаю іншому пристрою")
        updateSyncStatus()
        if (pendingConfigSync != null && delayMs > 0) return
        pendingConfigSync?.let(handler::removeCallbacks)
        pendingConfigSync = Runnable {
            pendingConfigSync = null
            ControlDispatcher.configChanged(this)
        }.also { handler.postDelayed(it, delayMs) }
    }

    private fun updateSyncStatus() {
        tvSyncStatus.text = ConfigSyncStatus.summary(prefs.configVersion)
    }

    private fun updateLabels() {
        tvTemp.text = "Колір  •  ${colorLabel(prefs.white)}   Білий ${prefs.white}% / Жовтий ${100 - prefs.white}%"
        tvBrightness.text = "Яскравість  •  ${prefs.brightness}%"
        tvStrobeMode.text = "${strobeModeLabel(prefs.strobeMode)} • ${colorLabel(prefs.strobeWhite)} • ${prefs.strobeBrightness}%"
        updatePresetSelection()
    }

    private fun updateSettingsLabels() {
        val fixedYellowWhite = prefs.startupMode == BlePrefs.StartupMode.SMOOTH_YELLOW_WHITE
        tvStartWhite.text = if (fixedYellowWhite) {
            "Стартовий колір • Жовтий • Білий 0%"
        } else {
            "Стартовий колір • ${colorLabel(prefs.startWhite)} • Білий ${prefs.startWhite}%"
        }
        tvTargetWhite.text = if (fixedYellowWhite) {
            "Перехід на • Білий • Білий 100%"
        } else {
            "Перехід на • ${colorLabel(prefs.targetWhite)} • Білий ${prefs.targetWhite}%"
        }
        seekStartWhite.isEnabled = !fixedYellowWhite
        seekTargetWhite.isEnabled = !fixedYellowWhite
        tvStartBrightness.text = "Стартова яскравість • ${prefs.startBrightness}%"
        tvFadeDuration.text = "Тривалість переходу • ${formatDuration(prefs.fadeDurationMs)}"
        tvFadeSteps.text = "Плавність • ${prefs.fadeSteps} кроків"
        tvStrobeWhite.text = "Колір мигалок • ${colorLabel(prefs.strobeWhite)} • Білий ${prefs.strobeWhite}%"
        tvStrobeBrightness.text = "Яскравість мигалок • ${prefs.strobeBrightness}%"
        tvStrobeOn.text = "Імпульс ON • ${prefs.strobeOnMs} мс"
        tvStrobeOff.text = "Пауза між імпульсами • ${prefs.strobeOffMs} мс"
        tvStrobePause.text = "Пауза між серіями • ${prefs.strobePauseMs} мс"
        tvStrobeMode.text = "${strobeModeLabel(prefs.strobeMode)} • ${colorLabel(prefs.strobeWhite)} • ${prefs.strobeBrightness}%"
        strobePreview.setConfig(
            prefs.strobeMode,
            prefs.strobeWhite,
            prefs.strobeBrightness,
            prefs.strobeOnMs,
            prefs.strobeOffMs,
            prefs.strobePauseMs
        )
    }

    private fun colorLabel(white: Int): String = when {
        white <= 15 -> "Жовтий"
        white >= 85 -> "Білий"
        else -> "Теплий"
    }

    private fun strobeModeLabel(mode: BlePrefs.StrobeMode): String = strobeLabels.getOrElse(mode.ordinal) { mode.name }

    private fun formatDuration(ms: Int): String = if (ms < 1000) "$ms мс" else String.format(java.util.Locale.US, "%.1f с", ms / 1000.0)

    private fun updateStrobeButton() {
        btnStrobeToggle.text = if (strobeOnUi) "СТОП" else "МИГАЛКИ"
        btnStrobeToggle.isSelected = strobeOnUi
        strobeBlink?.cancel()
        strobeBlink = null
        handler.removeCallbacks(strobeStatusTicker)
        btnStrobeToggle.alpha = 1f
        if (strobeOnUi) {
            strobeBlink = AlphaAnimation(0.35f, 1f).apply {
                duration = 125L
                repeatMode = Animation.REVERSE
                repeatCount = Animation.INFINITE
            }
            btnStrobeToggle.startAnimation(strobeBlink)
            handler.post(strobeStatusTicker)
        }
        updateStrobeIndicators()
        updateLabels()
    }

    private fun updateStrobeIndicators() {
        if (!::tvLamp1.isInitialized || !::tvLamp2.isInitialized) return
        val phase = if (strobeOnUi) StrobeTimeline.phaseAt(
            System.currentTimeMillis(),
            prefs.strobeStartedAt,
            prefs.strobeMode,
            prefs.devices().size,
            prefs.strobeOnMs,
            prefs.strobeOffMs,
            prefs.strobePauseMs
        ) else null
        val devices = prefs.devices()
        tvLamp1.alpha = if (phase == null || devices.isEmpty() || phase.leftOn) 1f else 0.28f
        tvLamp2.alpha = if (phase == null || devices.size < 2 || phase.rightOn) 1f else 0.28f
    }

    private fun updatePresetSelection() {
        val selected = when {
            prefs.white <= 15 -> btnYellow
            prefs.white >= 85 -> btnWhite
            else -> btnWarm
        }
        btnYellow.isSelected = selected === btnYellow
        btnWarm.isSelected = selected === btnWarm
        btnWhite.isSelected = selected === btnWhite
        btnYellow.setTextColor(ContextCompat.getColor(this, if (selected === btnYellow) R.color.button_accent_text else R.color.preset_yellow_text))
        btnWarm.setTextColor(ContextCompat.getColor(this, if (selected === btnWarm) R.color.button_accent_text else R.color.preset_warm_text))
        btnWhite.setTextColor(ContextCompat.getColor(this, if (selected === btnWhite) R.color.button_accent_text else R.color.preset_white_text))
    }

    private fun refreshFromPrefsDelayed() {
        handler.postDelayed({
            updatingUi = true
            seekBrightness.progress = prefs.brightness
            updateLabels()
            updatingUi = false
        }, 100)
    }

    private fun stateColor(state: UiLinkState): Int =
        LinkIndicator.color(this, state, prefs.role() == BlePrefs.Role.HUB)

    private fun statusLabel(view: TextView, state: UiLinkState, text: String): CharSequence =
        LinkIndicator.label(state, text, resources.getDimension(R.dimen.status_symbol_size) / view.textSize)

    private fun setHubStatus(state: UiLinkState, text: String) {
        hubUiState = state
        tvLinkStatus.text = statusLabel(tvLinkStatus, state, text)
        tvLinkStatus.setTextColor(stateColor(state))
        tvRemoteStatus.text = statusLabel(tvRemoteStatus, state, text)
        tvRemoteStatus.setTextColor(stateColor(state))
    }

    private fun updateLampCards() {
        val devices = prefs.devices()
        fun apply(view: TextView, index: Int) {
            val d = devices.getOrNull(index)
            if (d == null) {
                view.text = statusLabel(view, UiLinkState.OFFLINE, "Фара не вибрана")
                view.setTextColor(stateColor(UiLinkState.OFFLINE))
                return
            }
            // RuntimeLinkState is the merged local + remote/phone state.
            // Do not let a stale local event hide a lamp connected by another device.
            val state = when (prefs.lampRuntimeState(d.mac)) {
                BlePrefs.RuntimeLinkState.CONNECTED -> UiLinkState.CONNECTED
                BlePrefs.RuntimeLinkState.CONNECTING -> UiLinkState.CONNECTING
                BlePrefs.RuntimeLinkState.OFFLINE -> UiLinkState.OFFLINE
            }
            val status = if (stateByMac[d.mac] == state) statusByMac[d.mac] else when (state) {
                UiLinkState.CONNECTED -> "підключено"
                UiLinkState.CONNECTING -> "підключення…"
                UiLinkState.OFFLINE -> "немає з'єднання"
            }
            view.text = statusLabel(view, state, "${prefs.lampSide(d)}\n$status")
            view.contentDescription = "${prefs.lampDisplayName(d)} • $status"
            view.setTextColor(stateColor(state))
        }
        apply(tvLamp1, 0)
        apply(tvLamp2, 1)
    }

    private fun retryLamp(index: Int) {
        val d = prefs.devices().getOrNull(index) ?: return
        val live = prefs.lampRuntimeState(d.mac) == BlePrefs.RuntimeLinkState.CONNECTED
        if (live) return
        stateByMac[d.mac] = UiLinkState.CONNECTING
        statusByMac[d.mac] = "підключення…"
        updateLampCards()
        ControlDispatcher.connectDevice(this, d.mac)
    }

    private fun retryHubConnection() {
        if (prefs.role() == BlePrefs.Role.HUB) {
            ControlDispatcher.connect(this)
            return
        }
        if (RemoteLinkService.connected || prefs.hubRuntimeState == BlePrefs.RuntimeLinkState.CONNECTED) return
        setHubStatus(UiLinkState.CONNECTING, "Магнітола • підключення…")
        RemoteLinkService.start(this)
    }

    private fun applyLampEvent(mac: String, type: String, message: String) {
        val previous = stateByMac[mac]
        when (type) {
            QStarBleService.EVENT_READY -> {
                stateByMac[mac] = UiLinkState.CONNECTED
                statusByMac[mac] = "підключено"
            }
            QStarBleService.EVENT_RSSI -> {
                if (previous == UiLinkState.CONNECTED) statusByMac[mac] = "підключено • $message"
            }
            QStarBleService.EVENT_DISCONNECTED, QStarBleService.EVENT_ERROR -> {
                stateByMac[mac] = UiLinkState.OFFLINE
                statusByMac[mac] = "немає з'єднання"
            }
            QStarBleService.EVENT_PHASE -> {
                if (message == "READY") {
                    stateByMac[mac] = UiLinkState.CONNECTED
                    statusByMac[mac] = "підключено"
                } else if (message in setOf("CONNECTING", "DISCOVERING", "SUBSCRIBING", "HANDSHAKE")) {
                    stateByMac[mac] = UiLinkState.CONNECTING
                    statusByMac[mac] = "підключення…"
                }
            }
        }
        updateLampCards()
    }

    private fun showDevicePicker() {
        if (found.isEmpty()) {
            Toast.makeText(this, R.string.scan_first, Toast.LENGTH_SHORT).show()
            return
        }
        val items = found.values.toList()
        val labels = items.map { "${prefs.lampDisplayName(it)}\n${it.mac}" }.toTypedArray()
        val checked = BooleanArray(items.size) { item -> prefs.devices().any { it.mac == items[item].mac } }
        AlertDialog.Builder(this)
            .setTitle(R.string.choose_devices)
            .setMultiChoiceItems(labels, checked) { dialog, which, isChecked ->
                checked[which] = isChecked
                if (checked.count { it } > 2) {
                    checked[which] = false
                    (dialog as AlertDialog).listView.setItemChecked(which, false)
                    Toast.makeText(this, R.string.only_two, Toast.LENGTH_SHORT).show()
                }
            }
            .setPositiveButton(android.R.string.ok) { _, _ ->
                prefs.setDevices(items.filterIndexed { i, _ -> checked[i] }.take(2))
                statusByMac.clear()
                updateLampCards()
                ControlDispatcher.configChanged(this)
                ControlDispatcher.connect(this)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showPasswordDialog() {
        val devices = prefs.devices()
        if (devices.isEmpty()) return
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 12, 48, 0)
        }
        val fields = devices.map { d ->
            val label = TextView(this).apply { text = prefs.lampDisplayName(d) }
            val edit = EditText(this).apply {
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
                setText(prefs.password(d.mac))
                filters = arrayOf(android.text.InputFilter.LengthFilter(4))
            }
            container.addView(label)
            container.addView(edit)
            d to edit
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.passwords)
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                var changed = false
                fields.forEach { (d, e) ->
                    val value = e.text.toString()
                    if (value.length == 4 && value.all(Char::isDigit) && prefs.password(d.mac) != value) {
                        prefs.setPassword(d.mac, value)
                        changed = true
                    }
                }
                if (changed) ControlDispatcher.configChanged(this)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun openInstallPermission() {
        if (Build.VERSION.SDK_INT < 26 || packageManager.canRequestPackageInstalls()) {
            Toast.makeText(this, "Дозвіл уже є", Toast.LENGTH_SHORT).show()
            return
        }
        val i = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))
        runCatching { startActivity(i) }
            .onFailure { Toast.makeText(this, "Не вдалося відкрити системний дозвіл", Toast.LENGTH_SHORT).show() }
    }

    private fun ensurePermissions(request: Boolean = true): Boolean {
        val bleNeeded = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 31) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED)
                bleNeeded += Manifest.permission.BLUETOOTH_SCAN
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
                bleNeeded += Manifest.permission.BLUETOOTH_CONNECT
        } else {
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
                bleNeeded += Manifest.permission.ACCESS_FINE_LOCATION
        }
        val requestList = bleNeeded.toMutableList()
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestList += Manifest.permission.POST_NOTIFICATIONS
        if (requestList.isNotEmpty() && request) requestPermissions(requestList.toTypedArray(), 100)
        return bleNeeded.isEmpty()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100 && ensurePermissions(false)) {
            PresenceMonitor.ensure(this)
            ControlDispatcher.connect(this)
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != QStarBleService.ACTION_EVENT) return
            val type = intent.getStringExtra(QStarBleService.EXTRA_EVENT).orEmpty()
            val mac = intent.getStringExtra(QStarBleService.EXTRA_MAC)
            val name = intent.getStringExtra(QStarBleService.EXTRA_NAME)
            val msg = intent.getStringExtra(QStarBleService.EXTRA_MESSAGE).orEmpty()
            if (type == QStarBleService.EVENT_DEVICE_FOUND && mac != null && name != null) {
                found[mac] = BlePrefs.DeviceRef(mac, name)
            }
            if (mac != null && type in setOf(
                    QStarBleService.EVENT_PHASE,
                    QStarBleService.EVENT_READY,
                    QStarBleService.EVENT_STATE,
                    QStarBleService.EVENT_RSSI,
                    QStarBleService.EVENT_ERROR,
                    QStarBleService.EVENT_DISCONNECTED
                )) {
                applyLampEvent(mac, type, msg)
            }
            when (type) {
                QStarBleService.EVENT_STROBE -> {
                    strobeOnUi = msg.startsWith("strobe_on")
                    prefs.strobeActive = strobeOnUi
                    updateStrobeButton()
                }
                QStarBleService.EVENT_PHONE_LINK -> updateRoleUi()
                QStarBleService.EVENT_UI_STATE -> {
                    refreshAllControlsFromPrefs()
                    strobeOnUi = prefs.strobeActive
                    updateStrobeButton()
                }
                QStarBleService.EVENT_CONFIG_SYNC -> {
                    refreshAllControlsFromPrefs()
                    updateSyncStatus()
                }
                QStarBleService.EVENT_UPDATE -> tvUpdateStatus.text = msg
            }

        }
    }

    private val remoteReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != RemoteLinkService.ACTION_EVENT) return
            val type = intent.getStringExtra(RemoteLinkService.EXTRA_EVENT).orEmpty()
            val msg = intent.getStringExtra(RemoteLinkService.EXTRA_MESSAGE).orEmpty()
            val host = intent.getStringExtra(RemoteLinkService.EXTRA_HOST).orEmpty()
            val raw = intent.getStringExtra(RemoteLinkService.EXTRA_RAW)
            when (type) {
                RemoteLinkService.EVENT_CONNECTING ->
                    setHubStatus(UiLinkState.CONNECTING, "Магнітола • підключення…")
                RemoteLinkService.EVENT_CONNECTED ->
                    setHubStatus(UiLinkState.CONNECTED, "Магнітола • підключено" + if (host.isBlank()) "" else " • $host")
                RemoteLinkService.EVENT_DISCONNECTED, RemoteLinkService.EVENT_ERROR ->
                    setHubStatus(UiLinkState.OFFLINE, "Магнітола • немає з'єднання")
            }
            if (type == RemoteLinkService.EVENT_HUB_BLE && raw != null) {
                try {
                    val json = JSONObject(raw)
                    val lampMac = json.optString("mac")
                    val lampEvent = json.optString("event")
                    val lampMessage = json.optString("message")
                    if (lampMac.isNotBlank()) applyLampEvent(lampMac, lampEvent, lampMessage)
                } catch (_: Throwable) { }
            }
            if (type == RemoteLinkService.EVENT_HUB_STATUS && raw != null) {
                try {
                    if (pendingSliderSend != null || pendingConfigSync != null) return
                    updatingUi = true
                    switchPower.isChecked = prefs.power
                    seekTemp.progress = prefs.white
                    seekBrightness.progress = prefs.brightness
                    updateLabels()
                    updatingUi = false
                } catch (_: Throwable) { }
            }
            when (type) {
                RemoteLinkService.EVENT_CONFIG_SYNC -> {
                    refreshAllControlsFromPrefs()
                    updateSyncStatus()
                }
                RemoteLinkService.EVENT_UPDATE -> tvUpdateStatus.text = msg
            }
            updateRoleUi()
            if (type == RemoteLinkService.EVENT_UPDATE) tvUpdateStatus.text = msg

        }
    }

    private fun renderDiagnostics() {
        // Never mutate the log TextView while the user is away from the tail.
        // Replacing its text changes the measured child height and steals the user's position.
        if (logTouching || !logAutoScroll) return

        val log = DiagnosticLog.snapshot()
        val deviceLines = prefs.devices().map { device ->
            "${prefs.lampSide(device)}: ${formatConnectionTimestamp(prefs.lastLampConnectionAt(device.mac))}"
        }.joinToString("\n")
        tvDiagnosticsInfo.text = "v${UpdateManager.versionName(this)} • ${Build.MODEL} • ${prefs.role()}\n" +
            "HUB: ${prefs.hubRuntimeState} • ${ConfigSyncStatus.summary(prefs.configVersion)}\n" +
            "Останнє підключення до мафона: ${formatConnectionTimestamp(prefs.lastHubConnectionAt)}\n" +
            deviceLines
        tvLogCount.text = "Показано ${minOf(log.size, 300)} з ${log.size} записів. Копія та експорт містять до 2000 останніх записів і параметри пристрою."
        val renderedLog = log.takeLast(300).joinToString("\n\n")
        val textChanged = tvLog.text.toString() != renderedLog
        if (textChanged) tvLog.text = renderedLog
        if (textChanged) {
            diagnosticsLogScroll.post {
                if (!logTouching && logAutoScroll) diagnosticsLogScroll.fullScroll(View.FOCUS_DOWN)
            }
        }
    }

    private fun Int.dp(): Int = (this * resources.displayMetrics.density).toInt()

    private fun formatConnectionTimestamp(value: Long): String = if (value <= 0L) {
        "ще не було"
    } else {
        SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.getDefault()).format(Date(value))
    }

    private fun appendLog(line: String) = DiagnosticLog.write("UI", line)
}
