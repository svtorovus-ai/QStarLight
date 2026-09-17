package ua.grey.qstarlight.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.ArrayDeque

@SuppressLint("MissingPermission")
class LampConnection(
    private val context: Context,
    private val device: BluetoothDevice,
    private val passwordProvider: (String) -> String,
    private val listener: Listener
) {
    enum class Phase { DISCONNECTED, CONNECTING, DISCOVERING, SUBSCRIBING, HANDSHAKE, READY, ERROR }

    interface Listener {
        fun onPhase(mac: String, phase: Phase)
        fun onReady(mac: String)
        fun onState(mac: String, state: QStarProtocol.LampState)
        fun onPasswordRequired(mac: String, required: Boolean)
        fun onRssi(mac: String, rssi: Int)
        fun onError(mac: String, message: String)
        fun onDisconnected(mac: String, status: Int)
    }

    private sealed class Op {
        class CharWrite(val data: ByteArray, val done: (Boolean) -> Unit) : Op()
        class DescriptorWrite(val descriptor: BluetoothGattDescriptor, val data: ByteArray, val done: (Boolean) -> Unit) : Op()
    }

    val mac: String get() = device.address
    val name: String get() = device.name ?: mac
    var phase: Phase = Phase.DISCONNECTED
        private set

    private val handler = Handler(Looper.getMainLooper())
    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private var notifyChar: BluetoothGattCharacteristic? = null
    private val ops = ArrayDeque<Op>()
    private var activeOp: Op? = null
    private var activeOpTimeout: Runnable? = null
    private var handshakeAttempts = 0
    private var handshakeVerified = false
    private var handshake = QStarProtocol.newHandshake()
    private var passwordKnown = false
    private var passwordRequired = false
    private var closed = false

    private val handshakeTimeout = object : Runnable {
        override fun run() {
            if (handshakeVerified || closed) return
            if (handshakeAttempts >= 4) {
                fail("Handshake timeout")
                disconnect()
                return
            }
            sendHandshake()
        }
    }

    fun isReady(): Boolean = phase == Phase.READY

    fun connect() {
        if (phase != Phase.DISCONNECTED && phase != Phase.ERROR) return
        closed = false
        handshakeAttempts = 0
        handshakeVerified = false
        passwordKnown = false
        passwordRequired = false
        handshake = QStarProtocol.newHandshake()
        setPhase(Phase.CONNECTING)
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
    }

    fun disconnect() {
        closed = true
        handler.removeCallbacksAndMessages(null)
        ops.clear()
        activeOp = null
        try { gatt?.disconnect() } catch (_: Throwable) {}
        try { gatt?.close() } catch (_: Throwable) {}
        gatt = null
        writeChar = null
        notifyChar = null
        setPhase(Phase.DISCONNECTED)
    }

    fun writeControl(data: ByteArray, done: (Boolean) -> Unit = {}) {
        if (!isReady()) {
            done(false)
            return
        }
        enqueue(Op.CharWrite(data.copyOf(), done))
    }

    fun readRssi() {
        if (gatt != null && phase == Phase.READY) gatt?.readRemoteRssi()
    }

    private fun setPhase(value: Phase) {
        phase = value
        listener.onPhase(mac, value)
    }

    private fun fail(message: String) {
        setPhase(Phase.ERROR)
        listener.onError(mac, message)
    }

    private fun findGattPieces(services: List<BluetoothGattService>): Boolean {
        writeChar = services.firstOrNull { it.uuid == QStarProtocol.SERVICE_WRITE }
            ?.getCharacteristic(QStarProtocol.CHAR_WRITE)
        notifyChar = services.firstOrNull { it.uuid == QStarProtocol.SERVICE_NOTIFY }
            ?.getCharacteristic(QStarProtocol.CHAR_NOTIFY)
        return writeChar != null && notifyChar != null
    }

    private fun subscribe() {
        val g = gatt ?: return fail("GATT missing")
        val n = notifyChar ?: return fail("FFD4 missing")
        setPhase(Phase.SUBSCRIBING)
        if (!g.setCharacteristicNotification(n, true)) {
            fail("setCharacteristicNotification failed")
            return
        }
        val cccd = n.getDescriptor(QStarProtocol.CCCD)
        if (cccd == null) {
            fail("CCCD missing")
            return
        }
        enqueue(Op.DescriptorWrite(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) { _ ->
            setPhase(Phase.HANDSHAKE)
            sendHandshake()
        })
    }

    private fun sendHandshake() {
        handshakeAttempts += 1
        enqueue(Op.CharWrite(handshake.request) { ok ->
            if (!ok) {
                if (handshakeAttempts >= 4) fail("Handshake write failed")
                else handler.postDelayed(handshakeTimeout, 400)
            } else {
                handler.removeCallbacks(handshakeTimeout)
                handler.postDelayed(handshakeTimeout, 1000)
            }
        })
    }

    private fun onHandshakeVerified() {
        if (handshakeVerified) return
        handshakeVerified = true
        handler.removeCallbacks(handshakeTimeout)
        enqueue(Op.CharWrite(QStarProtocol.QUERY_STATE) {})
        handler.postDelayed({ enqueue(Op.CharWrite(QStarProtocol.QUERY_CONFIG) {}) }, 250)
        handler.postDelayed({
            if (!passwordKnown && !isReady()) markReady()
        }, 1200)
    }

    private fun handleNotification(data: ByteArray) {
        if (QStarProtocol.validateHandshakeResponse(data, handshake.plain)) {
            onHandshakeVerified()
            return
        }

        QStarProtocol.parseState(data)?.let {
            listener.onState(mac, it)
            return
        }

        QStarProtocol.parsePasswordRequired(data)?.let { required ->
            passwordKnown = true
            passwordRequired = required
            listener.onPasswordRequired(mac, required)
            if (required) {
                handler.postDelayed({
                    val password = passwordProvider(mac)
                    val frame = try { QStarProtocol.passwordFrame(password) }
                    catch (_: Throwable) { QStarProtocol.passwordFrame("1234") }
                    enqueue(Op.CharWrite(frame) { ok ->
                        if (!ok) fail("Password write failed")
                        else handler.postDelayed({ if (!isReady()) markReady() }, 700)
                    })
                }, 500)
            } else {
                markReady()
            }
            return
        }

        if (passwordRequired && QStarProtocol.isPasswordAck(data)) {
            markReady()
        }
    }

    private fun markReady() {
        if (closed || phase == Phase.READY) return
        try { gatt?.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_BALANCED) } catch (_: Throwable) { }
        setPhase(Phase.READY)
        listener.onReady(mac)
    }

    private fun enqueue(op: Op) {
        ops.addLast(op)
        pump()
    }

    private fun pump() {
        if (activeOp != null || closed) return
        val op = ops.pollFirst() ?: return
        activeOp = op
        val g = gatt
        if (g == null) {
            finishActive(false)
            return
        }
        val started = when (op) {
            is Op.CharWrite -> {
                val c = writeChar
                if (c == null) false else writeCharacteristic(g, c, op.data)
            }
            is Op.DescriptorWrite -> writeDescriptor(g, op.descriptor, op.data)
        }
        if (!started) {
            finishActive(false)
        } else {
            val timeout = Runnable {
                if (activeOp === op && !closed) {
                    finishActive(false)
                    fail("GATT operation timeout")
                    disconnect()
                }
            }
            activeOpTimeout = timeout
            handler.postDelayed(timeout, 6000)
        }
    }

    private fun finishActive(ok: Boolean) {
        activeOpTimeout?.let(handler::removeCallbacks)
        activeOpTimeout = null
        val op = activeOp
        activeOp = null
        when (op) {
            is Op.CharWrite -> op.done(ok)
            is Op.DescriptorWrite -> op.done(ok)
            null -> Unit
        }
        handler.postDelayed({ pump() }, 25)
    }

    private fun writeCharacteristic(g: BluetoothGatt, c: BluetoothGattCharacteristic, data: ByteArray): Boolean {
        return if (Build.VERSION.SDK_INT >= 33) {
            g.writeCharacteristic(c, data, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                c.value = data
                g.writeCharacteristic(c)
            }
        }
    }

    private fun writeDescriptor(g: BluetoothGatt, d: BluetoothGattDescriptor, data: ByteArray): Boolean {
        return if (Build.VERSION.SDK_INT >= 33) {
            g.writeDescriptor(d, data) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                d.value = data
                g.writeDescriptor(d)
            }
        }
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                setPhase(Phase.DISCOVERING)
                if (!g.discoverServices()) fail("discoverServices failed")
                return
            }
            if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                try { g.close() } catch (_: Throwable) {}
                if (!closed) {
                    setPhase(Phase.DISCONNECTED)
                    listener.onDisconnected(mac, status)
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS || !findGattPieces(g.services)) {
                fail("Required FFD9/FFD4 characteristics not found")
                return
            }
            subscribe()
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            handleNotification(characteristic.value ?: return)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            handleNotification(value)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            finishActive(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            finishActive(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onReadRemoteRssi(g: BluetoothGatt, rssi: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) listener.onRssi(mac, rssi)
        }
    }
}
