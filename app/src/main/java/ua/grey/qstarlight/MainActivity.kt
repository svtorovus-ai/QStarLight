package ua.grey.qstarlight

import android.Manifest
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.MotionEvent
import android.view.ViewConfiguration
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
import androidx.core.content.ContextCompat
import org.json.JSONObject
import ua.grey.qstarlight.ble.BlePrefs
import ua.grey.qstarlight.ble.QStarBleService
import ua.grey.qstarlight.control.ControlDispatcher
import ua.grey.qstarlight.remote.RemoteLinkService
import ua.grey.qstarlight.update.UpdateManager
import ua.grey.qstarlight.update.UpdateScheduler
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

    private enum class UiLinkState { CONNECTED, CONNECTING, OFFLINE }
    private var pendingSliderSend: Runnable? = null
    private var pendingConfigSync: Runnable? = null
    private var updatingUi = false
    private var strobeOnUi = false

    private lateinit var controlPage: ScrollView
    private lateinit var settingsPage: ScrollView
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
    private lateinit var switchKeep: Switch
    private lateinit var switchDirectFallback: Switch
    private lateinit var switchForceDirect: Switch
    private lateinit var switchRemoteKeepAlive: Switch
    private lateinit var switchAutoPushUpdates: Switch
    private lateinit var switchSilentRootInstall: Switch

    private lateinit var spinnerStartupMode: Spinner
    private lateinit var spinnerStrobeMode: Spinner
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
    private lateinit var btnPushUpdate: Button
    private lateinit var btnInstallPermission: Button

    private val startupLabels = arrayOf(
        "Відновити останній стан",
        "Тільки стартовий колір",
        "Стартовий → робочий",
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
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = BlePrefs(this).also { it.ensureDefaults() }
        bindViews()
        configureUi()
        bindActions()
        ensurePermissions()
    }

    private fun bindViews() {
        controlPage = findViewById(R.id.controlPage)
        settingsPage = findViewById(R.id.settingsPage)
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
        switchKeep = findViewById(R.id.switchKeep)
        switchDirectFallback = findViewById(R.id.switchDirectFallback)
        switchForceDirect = findViewById(R.id.switchForceDirect)
        switchRemoteKeepAlive = findViewById(R.id.switchRemoteKeepAlive)
        switchAutoPushUpdates = findViewById(R.id.switchAutoPushUpdates)
        switchSilentRootInstall = findViewById(R.id.switchSilentRootInstall)

        spinnerStartupMode = findViewById(R.id.spinnerStartupMode)
        spinnerStrobeMode = findViewById(R.id.spinnerStrobeMode)
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
        btnPushUpdate = findViewById(R.id.btnPushUpdate)
        btnInstallPermission = findViewById(R.id.btnInstallPermission)
    }

    private fun configureUi() {
        updatingUi = true
        val packageInfo = packageManager.getPackageInfo(packageName, 0)
        val updated = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(packageInfo.lastUpdateTime))
        tvVersion.text = "v${UpdateManager.versionName(this)} • оновлено $updated"

        spinnerStartupMode.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, startupLabels)
        spinnerStrobeMode.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, strobeLabels)

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
        updatingUi = false
        showPage(false)
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
        switchKeep.isChecked = prefs.keepConnected
        switchDirectFallback.isChecked = prefs.directFallback
        switchForceDirect.isChecked = prefs.forceDirect
        switchRemoteKeepAlive.isChecked = prefs.remoteKeepAlive
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
        updateSyncStatus("Синхронізовано")
        updatingUi = old
        handler.post { controlPage.scrollTo(0, controlScrollY); settingsPage.scrollTo(0, settingsScrollY) }
    }

    private fun bindActions() {
        tabControl.setOnClickListener { showPage(false) }
        tabSettings.setOnClickListener { showPage(true) }

        findViewById<Button>(R.id.btnReconnect).setOnClickListener {
            if (ensurePermissions()) ControlDispatcher.connect(this)
        }
        tvLinkStatus.setOnClickListener { retryHubConnection() }
        tvRemoteStatus.setOnClickListener { retryHubConnection() }
        tvLamp1.setOnClickListener { retryLamp(0) }
        tvLamp2.setOnClickListener { retryLamp(1) }
        findViewById<Button>(R.id.btnYellow).setOnClickListener { setPreset(0) }
        findViewById<Button>(R.id.btnWarm).setOnClickListener { setPreset(50) }
        findViewById<Button>(R.id.btnWhite).setOnClickListener { setPreset(100) }
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
                prefs.white = seekTemp.progress
                prefs.brightness = seekBrightness.progress
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
        switchKeep.setOnCheckedChangeListener { _, v -> if (!updatingUi) prefs.keepConnected = v }
        switchDirectFallback.setOnCheckedChangeListener { _, v -> if (!updatingUi) prefs.directFallback = v }
        switchRemoteKeepAlive.setOnCheckedChangeListener { _, v -> if (!updatingUi) prefs.remoteKeepAlive = v }
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
                if (!updatingUi) scheduleConfigSync(80)
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
        super.onStop()
        try { unregisterReceiver(receiver) } catch (_: Throwable) { }
        try { unregisterReceiver(remoteReceiver) } catch (_: Throwable) { }
    }

    private fun showPage(settings: Boolean) {
        controlPage.visibility = if (settings) View.GONE else View.VISIBLE
        settingsPage.visibility = if (settings) View.VISIBLE else View.GONE
        tabControl.isSelected = !settings
        tabSettings.isSelected = settings
    }

    private fun changeRole(role: BlePrefs.Role) {
        prefs.roleOverride = if (role == BlePrefs.Role.HUB) "hub" else "phone"
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
            setHubStatus(UiLinkState.CONNECTED, "Магнітола • HUB активний")
        } else if (RemoteLinkService.connected) {
            setHubStatus(UiLinkState.CONNECTED, "Магнітола • підключено")
        } else if (hubUiState == UiLinkState.CONNECTING) {
            setHubStatus(UiLinkState.CONNECTING, "Магнітола • підключення…")
        } else {
            setHubStatus(UiLinkState.OFFLINE, "Магнітола • немає з'єднання")
        }

        tvUpdateStatus.text = if (hub) {
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
        pendingSliderSend?.let(handler::removeCallbacks)
        val task = Runnable { sendCurrentCct() }
        pendingSliderSend = task
        handler.postDelayed(task, 120)
    }

    private fun sendCurrentCct() {
        if (!ensurePermissions(false) && prefs.forceDirect) return
        ControlDispatcher.apply(this, prefs.white, prefs.brightness)
    }

    private fun scheduleConfigSync(delayMs: Long = 350L) {
        updateSyncStatus("Зміни очікують синхронізації…")
        pendingConfigSync?.let(handler::removeCallbacks)
        val task = Runnable {
            ControlDispatcher.configChanged(this)
            updateSyncStatus("Відправлено • очікую HUB")
        }
        pendingConfigSync = task
        handler.postDelayed(task, delayMs)
    }

    private fun updateSyncStatus(prefix: String) {
        tvSyncStatus.text = "$prefix • rev ${prefs.configRevision}"
    }

    private fun updateLabels() {
        tvTemp.text = "Колір  •  ${colorLabel(prefs.white)}   Білий ${prefs.white}% / Жовтий ${100 - prefs.white}%"
        tvBrightness.text = "Яскравість  •  ${prefs.brightness}%"
        tvStrobeMode.text = "${strobeModeLabel(prefs.strobeMode)} • ${colorLabel(prefs.strobeWhite)} • ${prefs.strobeBrightness}%"
    }

    private fun updateSettingsLabels() {
        tvStartWhite.text = "Стартовий колір • ${colorLabel(prefs.startWhite)} • Білий ${prefs.startWhite}%"
        tvTargetWhite.text = "Перехід на • ${colorLabel(prefs.targetWhite)} • Білий ${prefs.targetWhite}%"
        tvStartBrightness.text = "Стартова яскравість • ${prefs.startBrightness}%"
        tvFadeDuration.text = "Тривалість переходу • ${formatDuration(prefs.fadeDurationMs)}"
        tvFadeSteps.text = "Плавність • ${prefs.fadeSteps} кроків"
        tvStrobeWhite.text = "Колір строба • ${colorLabel(prefs.strobeWhite)} • Білий ${prefs.strobeWhite}%"
        tvStrobeBrightness.text = "Яскравість строба • ${prefs.strobeBrightness}%"
        tvStrobeOn.text = "Імпульс ON • ${prefs.strobeOnMs} мс"
        tvStrobeOff.text = "Пауза між імпульсами • ${prefs.strobeOffMs} мс"
        tvStrobePause.text = "Пауза між серіями • ${prefs.strobePauseMs} мс"
        tvStrobeMode.text = "${strobeModeLabel(prefs.strobeMode)} • ${colorLabel(prefs.strobeWhite)} • ${prefs.strobeBrightness}%"
    }

    private fun colorLabel(white: Int): String = when {
        white <= 15 -> "Жовтий"
        white >= 85 -> "Білий"
        else -> "Теплий"
    }

    private fun strobeModeLabel(mode: BlePrefs.StrobeMode): String = strobeLabels.getOrElse(mode.ordinal) { mode.name }

    private fun formatDuration(ms: Int): String = if (ms < 1000) "$ms мс" else String.format(java.util.Locale.US, "%.1f с", ms / 1000.0)

    private fun updateStrobeButton() {
        btnStrobeToggle.text = if (strobeOnUi) "СТОП" else "СТРОБ"
        btnStrobeToggle.isSelected = strobeOnUi
        updateLabels()
    }

    private fun refreshFromPrefsDelayed() {
        handler.postDelayed({
            updatingUi = true
            seekBrightness.progress = prefs.brightness
            updateLabels()
            updatingUi = false
        }, 100)
    }

    private fun stateColor(state: UiLinkState): Int = when (state) {
        UiLinkState.CONNECTED -> Color.rgb(76, 175, 80)
        UiLinkState.CONNECTING -> Color.rgb(255, 193, 7)
        UiLinkState.OFFLINE -> Color.rgb(239, 83, 80)
    }

    private fun setHubStatus(state: UiLinkState, text: String) {
        hubUiState = state
        tvLinkStatus.text = "● $text"
        tvLinkStatus.setTextColor(stateColor(state))
        tvRemoteStatus.text = "● $text"
        tvRemoteStatus.setTextColor(stateColor(state))
    }

    private fun updateLampCards() {
        val devices = prefs.devices()
        fun apply(view: TextView, index: Int) {
            val d = devices.getOrNull(index)
            if (d == null) {
                view.text = "● Фара не вибрана"
                view.setTextColor(stateColor(UiLinkState.OFFLINE))
                return
            }
            val state = stateByMac[d.mac] ?: UiLinkState.OFFLINE
            val status = statusByMac[d.mac] ?: when (state) {
                UiLinkState.CONNECTED -> "підключено"
                UiLinkState.CONNECTING -> "підключення…"
                UiLinkState.OFFLINE -> "немає з'єднання"
            }
            view.text = "● ${prefs.lampDisplayName(d)}\n$status"
            view.setTextColor(stateColor(state))
        }
        apply(tvLamp1, 0)
        apply(tvLamp2, 1)
    }

    private fun retryLamp(index: Int) {
        val d = prefs.devices().getOrNull(index) ?: return
        if (stateByMac[d.mac] == UiLinkState.CONNECTED) return
        stateByMac[d.mac] = UiLinkState.CONNECTING
        statusByMac[d.mac] = "підключення…"
        updateLampCards()
        ControlDispatcher.connectDevice(this, d.mac)
    }

    private fun retryHubConnection() {
        if (prefs.role() == BlePrefs.Role.HUB || RemoteLinkService.connected) return
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
                    updateStrobeButton()
                }
                QStarBleService.EVENT_CONFIG_SYNC -> {
                    refreshAllControlsFromPrefs()
                    updateSyncStatus("Отримано з телефона")
                }
                QStarBleService.EVENT_UPDATE -> tvUpdateStatus.text = msg
            }
            appendLog("BLE  $type  ${name ?: mac.orEmpty()}  $msg")
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
                    val json = JSONObject(raw)
                    prefs.power = json.optBoolean("power", prefs.power)
                    prefs.white = json.optInt("white", prefs.white).coerceIn(0, 100)
                    prefs.brightness = json.optInt("brightness", prefs.brightness).coerceIn(5, 100)
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
                    updateSyncStatus(if (msg.contains("отримано", true)) "Отримано з HUB" else "Синхронізовано")
                }
                RemoteLinkService.EVENT_UPDATE -> tvUpdateStatus.text = msg
            }
            updateRoleUi()
            if (type == RemoteLinkService.EVENT_UPDATE) tvUpdateStatus.text = msg
            appendLog("LINK $type $host $msg")
        }
    }

    private fun appendLog(line: String) {
        val old = tvLog.text.toString().lines().takeLast(28)
        tvLog.text = (old + line).filter { it.isNotBlank() }.joinToString("\n")
    }
}
