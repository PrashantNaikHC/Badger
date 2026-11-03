package app.badger.kiosk

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class HidState(
    val supported: Boolean = true,
    val registered: Boolean = false,
    val connectedName: String? = null
) {
    val connected get() = connectedName != null
}

/**
 * Makes the phone act as a Bluetooth keyboard (HID device profile, Android 9+) and types text
 * into whichever host (e.g. a Windows PC with Excel focused) is connected. US keyboard layout.
 */
@SuppressLint("NewApi", "MissingPermission")
object HidSender {
    private val _state = MutableStateFlow(HidState())
    val state: StateFlow<HidState> = _state

    private var hid: BluetoothHidDevice? = null
    private var host: BluetoothDevice? = null
    private var starting = false

    fun adapter(c: Context): BluetoothAdapter? =
        (c.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    /** Every paired device; no restriction. */
    fun pairedDevices(c: Context): List<BluetoothDevice> =
        adapter(c)?.bondedDevices.orEmpty().sortedBy { it.name ?: it.address }

    fun deviceName(d: BluetoothDevice): String = d.name ?: d.address

    fun start(context: Context) {
        val c = context.applicationContext
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            _state.value = HidState(supported = false); return
        }
        if (hid != null || starting) return
        val bt = adapter(c)
        if (bt == null || !bt.isEnabled) return
        starting = true
        val ok = bt.getProfileProxy(c, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                starting = false
                val h = proxy as BluetoothHidDevice
                hid = h
                val sdp = BluetoothHidDeviceAppSdpSettings(
                    "Badger Keyboard", "Badger scan keyboard", "Badger",
                    BluetoothHidDevice.SUBCLASS1_KEYBOARD, DESCRIPTOR
                )
                val registered = h.registerApp(sdp, null, null, ContextCompat.getMainExecutor(c), callback)
                if (!registered) _state.value = HidState(supported = false)
            }

            override fun onServiceDisconnected(profile: Int) {
                hid = null; host = null; starting = false
                _state.value = HidState()
            }
        }, BluetoothProfile.HID_DEVICE)
        if (!ok) { starting = false; _state.value = HidState(supported = false) }
    }

    fun stop(context: Context) {
        val h = hid ?: return
        runCatching { h.unregisterApp() }
        runCatching { adapter(context)?.closeProfileProxy(BluetoothProfile.HID_DEVICE, h) }
        hid = null; host = null; starting = false
        _state.value = HidState()
    }

    fun connect(device: BluetoothDevice) {
        hid?.connect(device)
    }

    fun disconnect() {
        val d = host ?: return
        hid?.disconnect(d)
    }

    private val callback by lazy {
        object : BluetoothHidDevice.Callback() {
            override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
                _state.value = _state.value.copy(registered = registered)
            }

            override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
                when (state) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        host = device
                        _state.value = _state.value.copy(connectedName = deviceName(device))
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> if (host?.address == device.address) {
                        host = null
                        _state.value = _state.value.copy(connectedName = null)
                    }
                }
            }
        }
    }

    /** Types [text] on the connected host. Returns true only if every key report was accepted. */
    suspend fun type(text: String): Boolean {
        val h = hid ?: return false
        val d = host ?: return false
        val release = ByteArray(8)
        for (ch in text) {
            val (mod, key) = keyFor(ch) ?: keyFor('?')!!
            val press = ByteArray(8).also { it[0] = mod.toByte(); it[2] = key.toByte() }
            if (!h.sendReport(d, 0, press)) return false
            delay(6)
            if (!h.sendReport(d, 0, release)) return false
            delay(6)
        }
        return true
    }

    private const val SHIFT = 0x02

    /** Maps a character to (modifier, usage id) for a US layout, or null if unsupported. */
    private fun keyFor(c: Char): Pair<Int, Int>? = when (c) {
        in 'a'..'z' -> 0 to (0x04 + (c - 'a'))
        in 'A'..'Z' -> SHIFT to (0x04 + (c - 'A'))
        in '1'..'9' -> 0 to (0x1E + (c - '1'))
        '0' -> 0 to 0x27
        '\n' -> 0 to 0x28
        '\t' -> 0 to 0x2B
        ' ' -> 0 to 0x2C
        '-' -> 0 to 0x2D; '_' -> SHIFT to 0x2D
        '=' -> 0 to 0x2E; '+' -> SHIFT to 0x2E
        '[' -> 0 to 0x2F; '{' -> SHIFT to 0x2F
        ']' -> 0 to 0x30; '}' -> SHIFT to 0x30
        '\\' -> 0 to 0x31; '|' -> SHIFT to 0x31
        ';' -> 0 to 0x33; ':' -> SHIFT to 0x33
        '\'' -> 0 to 0x34; '"' -> SHIFT to 0x34
        '`' -> 0 to 0x35; '~' -> SHIFT to 0x35
        ',' -> 0 to 0x36; '<' -> SHIFT to 0x36
        '.' -> 0 to 0x37; '>' -> SHIFT to 0x37
        '/' -> 0 to 0x38; '?' -> SHIFT to 0x38
        '!' -> SHIFT to 0x1E; '@' -> SHIFT to 0x1F; '#' -> SHIFT to 0x20
        '$' -> SHIFT to 0x21; '%' -> SHIFT to 0x22; '^' -> SHIFT to 0x23
        '&' -> SHIFT to 0x24; '*' -> SHIFT to 0x25; '(' -> SHIFT to 0x26
        ')' -> SHIFT to 0x27
        else -> null
    }

    /** Standard boot-protocol keyboard report descriptor (8-byte input report, no report ID). */
    private val DESCRIPTOR = byteArrayOf(
        0x05, 0x01, 0x09, 0x06, 0xA1.toByte(), 0x01, 0x05, 0x07,
        0x19, 0xE0.toByte(), 0x29, 0xE7.toByte(), 0x15, 0x00, 0x25, 0x01,
        0x75, 0x01, 0x95.toByte(), 0x08, 0x81.toByte(), 0x02,
        0x95.toByte(), 0x01, 0x75, 0x08, 0x81.toByte(), 0x01,
        0x95.toByte(), 0x05, 0x75, 0x01, 0x05, 0x08, 0x19, 0x01, 0x29, 0x05,
        0x91.toByte(), 0x02, 0x95.toByte(), 0x01, 0x75, 0x03, 0x91.toByte(), 0x01,
        0x95.toByte(), 0x06, 0x75, 0x08, 0x15, 0x00, 0x25, 0x65,
        0x05, 0x07, 0x19, 0x00, 0x29, 0x65, 0x81.toByte(), 0x00,
        0xC0.toByte()
    )
}
