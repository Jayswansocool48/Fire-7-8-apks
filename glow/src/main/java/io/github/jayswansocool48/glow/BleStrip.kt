package io.github.jayswansocool48.glow

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.UUID

/**
 * Talks straight to the LED strip controller over Bluetooth LE.
 *
 * "elk": LEDBLE / ELK-BLEDOM / MELK controllers (what the Lotus Lantern app drives), 9-byte 7E … EF commands.
 * "triones": older Triones / LEDBlue / HappyLighting controllers.
 */
@SuppressLint("MissingPermission")
object BleStrip {
    enum class State { OFF, CONNECTING, CONNECTED }

    data class Found(val name: String, val address: String, val rssi: Int, val likely: Boolean)

    private val WRITE_UUIDS = mapOf(
        UUID.fromString("0000fff3-0000-1000-8000-00805f9b34fb") to "elk",
        UUID.fromString("0000ffd9-0000-1000-8000-00805f9b34fb") to "triones",
        UUID.fromString("0000ffe9-0000-1000-8000-00805f9b34fb") to "triones",
    )

    private val main = Handler(Looper.getMainLooper())
    private lateinit var app: Context
    private var gatt: BluetoothGatt? = null
    private var char: BluetoothGattCharacteristic? = null
    var proto = "elk"
        private set
    var state = State.OFF
        private set
    var status = "Not set up yet"
        private set
    var address: String? = null
        private set
    var name: String? = null
        private set
    private var wantConnected = false
    private val listeners = ArrayList<() -> Unit>()

    // One GATT write at a time; live colors replace each other so only the newest is sent.
    private val queue = ArrayDeque<ByteArray>()
    private var liveNext: ByteArray? = null
    private var writing = false

    fun init(ctx: Context) {
        app = ctx.applicationContext
        val p = app.getSharedPreferences("strip", Context.MODE_PRIVATE)
        address = p.getString("address", null)
        name = p.getString("name", null)
        proto = p.getString("proto", "elk") ?: "elk"
        if (address != null) status = "Not connected"
    }

    fun onChange(l: () -> Unit) { listeners.add(l) }
    fun removeChange(l: () -> Unit) { listeners.remove(l) }
    private fun changed() = main.post { for (l in ArrayList(listeners)) l() }

    private fun adapter(): BluetoothAdapter? =
        (app.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    fun bluetoothOn() = adapter()?.isEnabled == true
    fun hasBle() = app.packageManager.hasSystemFeature("android.hardware.bluetooth_le")

    private fun likely(n: String): Boolean {
        val u = n.uppercase()
        return listOf("LEDBLE", "ELK", "BLEDOM", "MELK", "TRIONES", "LEDBLUE", "QHM", "DREAM", "LEDNET", "HAPPYLIGHT", "LED-", "LAMP").any { u.contains(it) }
    }

    private var scanCb: ScanCallback? = null

    /** Looks for strips for 6 seconds. */
    fun scan(done: (List<Found>?, String?) -> Unit) {
        val a = adapter()
        if (a == null || !hasBle()) return done(null, "This tablet has no Bluetooth LE")
        if (!a.isEnabled) return done(null, "Turn Bluetooth on first")
        val scanner = a.bluetoothLeScanner ?: return done(null, "Turn Bluetooth on first")
        val found = LinkedHashMap<String, Found>()
        scanCb?.let { scanner.stopScan(it) }
        val cb = object : ScanCallback() {
            override fun onScanResult(type: Int, r: ScanResult) {
                val n = r.scanRecord?.deviceName ?: try { r.device.name } catch (e: SecurityException) { null } ?: return
                if (n.isBlank()) return
                found[r.device.address] = Found(n, r.device.address, r.rssi, likely(n))
            }
            override fun onScanFailed(errorCode: Int) {
                main.post { done(null, "Bluetooth search failed (error $errorCode). Turn Bluetooth off and on.") }
            }
        }
        scanCb = cb
        try {
            scanner.startScan(cb)
        } catch (e: SecurityException) {
            return done(null, "Glow needs permission to use Bluetooth")
        }
        main.postDelayed({
            try { scanner.stopScan(cb) } catch (e: Exception) { }
            scanCb = null
            done(found.values.sortedWith(compareBy({ !it.likely }, { -it.rssi })), null)
        }, 6000)
    }

    fun connect(addr: String, devName: String?) {
        val a = adapter() ?: return
        if (addr != address) {
            disconnect()
            address = addr
            name = devName
            app.getSharedPreferences("strip", Context.MODE_PRIVATE).edit()
                .putString("address", addr).putString("name", devName).apply()
        }
        wantConnected = true
        if (state != State.OFF) return
        val dev: BluetoothDevice = try { a.getRemoteDevice(addr) } catch (e: IllegalArgumentException) { return }
        state = State.CONNECTING
        status = "Connecting…"
        changed()
        gatt = try {
            if (Build.VERSION.SDK_INT >= 23) dev.connectGatt(app, false, callback, BluetoothDevice.TRANSPORT_LE)
            else dev.connectGatt(app, false, callback)
        } catch (e: SecurityException) {
            state = State.OFF
            status = "Glow needs permission to use Bluetooth"
            changed()
            null
        }
        // Give up after 15 s; reconnect() tries again.
        main.postDelayed({
            if (state == State.CONNECTING) {
                closeGatt("Couldn't reach the strip. Is it plugged in? Close Lotus Lantern on your phone.")
            }
        }, 15000)
    }

    /** Called when the app comes to the front. */
    fun reconnect() {
        val addr = address ?: return
        if (state == State.OFF && bluetoothOn()) connect(addr, name)
    }

    fun disconnect() {
        wantConnected = false
        closeGatt(if (address != null) "Not connected" else "Not set up yet")
    }

    fun forget() {
        disconnect()
        address = null
        name = null
        app.getSharedPreferences("strip", Context.MODE_PRIVATE).edit().clear().apply()
        status = "Not set up yet"
        changed()
    }

    private fun closeGatt(msg: String) {
        try { gatt?.disconnect(); gatt?.close() } catch (e: Exception) { }
        gatt = null
        char = null
        queue.clear()
        liveNext = null
        writing = false
        state = State.OFF
        status = msg
        changed()
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, st: Int, newState: Int) {
            main.post {
                if (g != gatt) return@post
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    status = "Getting ready…"
                    changed()
                    g.discoverServices()
                } else {
                    closeGatt(if (wantConnected) "Lost the strip. Reconnecting…" else "Not connected")
                    if (wantConnected) main.postDelayed({ reconnect() }, 4000)
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, st: Int) {
            main.post {
                if (g != gatt) return@post
                var found: BluetoothGattCharacteristic? = null
                for (s in g.services) for (c in s.characteristics) {
                    val p = WRITE_UUIDS[c.uuid]
                    if (p != null && found == null) {
                        found = c
                        proto = p
                    }
                }
                if (found == null) {
                    wantConnected = false
                    closeGatt("That device isn't an LED strip Glow knows")
                    return@post
                }
                char = found
                app.getSharedPreferences("strip", Context.MODE_PRIVATE).edit().putString("proto", proto).apply()
                state = State.CONNECTED
                status = "Connected"
                changed()
                StripState.applyAll()
            }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, st: Int) {
            main.post { writing = false; pump() }
        }
    }

    fun send(b: ByteArray?) {
        if (b == null || state != State.CONNECTED) return
        queue.addLast(b)
        pump()
    }

    fun sendLive(b: ByteArray) {
        if (state != State.CONNECTED) return
        liveNext = b
        pump()
    }

    private var writeStarted = 0L

    @Suppress("DEPRECATION")
    private fun pump() {
        val g = gatt ?: return
        val c = char ?: return
        if (writing && System.currentTimeMillis() - writeStarted < 300) return
        val next = queue.removeFirstOrNull() ?: liveNext?.also { liveNext = null } ?: return
        val noResp = c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0
        c.writeType = if (noResp) BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        c.value = next
        writing = try { g.writeCharacteristic(c) } catch (e: SecurityException) { false }
        writeStarted = System.currentTimeMillis()
        // Some phones never confirm writes without response: move on after a moment.
        main.postDelayed({ if (writing && System.currentTimeMillis() - writeStarted >= 280) { writing = false; pump() } }, 300)
        if (!writing) main.postDelayed({ pump() }, 30)
    }

    // ---- commands ----
    private fun pct(v: Int) = v.coerceIn(0, 100).toByte()

    fun power(on: Boolean): ByteArray = if (proto == "triones") {
        if (on) byteArrayOf(0xCC.toByte(), 0x23, 0x33) else byteArrayOf(0xCC.toByte(), 0x24, 0x33)
    } else {
        if (on) bytes(0x7E, 0x04, 0x04, 0xF0, 0x00, 0x01, 0xFF, 0x00, 0xEF) else bytes(0x7E, 0x04, 0x04, 0x00, 0x00, 0x00, 0xFF, 0x00, 0xEF)
    }

    fun color(c: Int): ByteArray {
        val r = (c shr 16) and 0xFF
        val g = (c shr 8) and 0xFF
        val b = c and 0xFF
        return if (proto == "triones") bytes(0x56, r, g, b, 0x00, 0xF0, 0xAA) else bytes(0x7E, 0x07, 0x05, 0x03, r, g, b, 0x10, 0xEF)
    }

    /** null when the controller has no brightness command (the color gets scaled instead). */
    fun brightness(p: Int): ByteArray? = if (proto == "triones") null else bytes(0x7E, 0x04, 0x01, pct(p).toInt(), 0x01, 0xFF, 0xFF, 0x00, 0xEF)

    fun effect(id: Int, speed: Int): ByteArray = if (proto == "triones") {
        bytes(0xBB, id, 31 - speed.coerceIn(0, 100) * 30 / 100, 0x44)
    } else bytes(0x7E, 0x05, 0x03, id, 0x03, 0xFF, 0xFF, 0x00, 0xEF)

    fun speed(p: Int): ByteArray? = if (proto == "triones") null else bytes(0x7E, 0x04, 0x02, pct(p).toInt(), 0xFF, 0xFF, 0xFF, 0x00, 0xEF)

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    val elkEffects = listOf(
        0x87 to "Jump (RGB)", 0x88 to "Jump (7 colors)", 0x89 to "Fade (RGB)", 0x8a to "Fade (7 colors)",
        0x8b to "Pulse red", 0x8c to "Pulse green", 0x8d to "Pulse blue", 0x8e to "Pulse yellow",
        0x8f to "Pulse cyan", 0x90 to "Pulse purple", 0x91 to "Pulse white",
        0x92 to "Fade red ↔ green", 0x93 to "Fade red ↔ blue", 0x94 to "Fade green ↔ blue",
        0x95 to "Strobe (7 colors)", 0x96 to "Strobe red", 0x97 to "Strobe green", 0x98 to "Strobe blue",
        0x99 to "Strobe yellow", 0x9a to "Strobe cyan", 0x9b to "Strobe purple", 0x9c to "Strobe white",
    )
    val trionesEffects = listOf(
        0x25 to "Fade (7 colors)", 0x26 to "Pulse red", 0x27 to "Pulse green", 0x28 to "Pulse blue",
        0x29 to "Pulse yellow", 0x2a to "Pulse cyan", 0x2b to "Pulse purple", 0x2c to "Pulse white",
        0x2d to "Fade red ↔ green", 0x2e to "Fade red ↔ blue", 0x2f to "Fade green ↔ blue",
        0x30 to "Strobe (7 colors)", 0x31 to "Strobe red", 0x32 to "Strobe green", 0x33 to "Strobe blue",
        0x34 to "Strobe yellow", 0x35 to "Strobe cyan", 0x36 to "Strobe purple", 0x37 to "Strobe white",
        0x38 to "Jump (7 colors)",
    )
    fun effects() = if (proto == "triones") trionesEffects else elkEffects
}
