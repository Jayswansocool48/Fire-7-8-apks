package io.github.jayswansocool48.glow

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast

class MainActivity : Activity() {
    private val stack = ArrayList<Screen>()
    private lateinit var container: FrameLayout
    private var toast: Toast? = null
    private var resumed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Theme.BG
        window.navigationBarColor = Theme.BG
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        BleStrip.init(this)
        Pc.init(this)
        StripState.init(this)
        container = FrameLayout(this).apply { setBackgroundColor(Theme.BG) }
        setContentView(container)
        push(HomeScreen(this))
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        if (hasBlePermission() && BleStrip.bluetoothOn()) BleStrip.reconnect()
        Pc.startPolling()
        stack.lastOrNull()?.onShow()
    }

    override fun onPause() {
        resumed = false
        Pc.stopPolling()
        stack.lastOrNull()?.onHide()
        super.onPause()
    }

    fun push(s: Screen) {
        stack.lastOrNull()?.let {
            it.onHide()
            it.visibility = View.GONE
        }
        stack.add(s)
        container.addView(s, FrameLayout.LayoutParams(MATCH, MATCH))
        if (resumed) s.onShow()
    }

    fun pop() {
        if (stack.size <= 1) {
            finish()
            return
        }
        val s = stack.removeAt(stack.size - 1)
        s.onHide()
        container.removeView(s)
        val top = stack.last()
        top.visibility = View.VISIBLE
        if (resumed) top.onShow()
    }

    @Deprecated("Back on Android 7")
    override fun onBackPressed() = pop()

    fun toast(text: String) {
        toast?.cancel()
        toast = Toast.makeText(this, text, Toast.LENGTH_LONG).also { it.show() }
    }

    // ---- permissions ----

    private var afterPermission: (() -> Unit)? = null
    private var afterBluetoothOn: (() -> Unit)? = null

    private fun blePermissions(): Array<String> = if (Build.VERSION.SDK_INT >= 31)
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    fun hasBlePermission(): Boolean =
        Build.VERSION.SDK_INT < 23 || blePermissions().all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    /** Gets Bluetooth permission, Bluetooth on and (Android 6-11) location on, then runs [then]. */
    fun withBluetooth(then: () -> Unit) {
        if (!BleStrip.hasBle()) return toast("This tablet has no Bluetooth LE")
        if (!hasBlePermission()) {
            afterPermission = { withBluetooth(then) }
            if (Build.VERSION.SDK_INT >= 23) requestPermissions(blePermissions(), 1)
            return
        }
        if (!BleStrip.bluetoothOn()) {
            afterBluetoothOn = then
            try {
                @Suppress("DEPRECATION")
                startActivityForResult(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), 2)
            } catch (e: Exception) {
                toast("Turn Bluetooth on in Settings")
            }
            return
        }
        if (Build.VERSION.SDK_INT in 23..30 && !locationOn()) {
            toast("Android needs Location turned on to find Bluetooth devices. Turn it on, then come back.")
            try { startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) } catch (e: Exception) { }
            return
        }
        then()
    }

    private fun locationOn(): Boolean {
        val lm = getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return true
        return if (Build.VERSION.SDK_INT >= 28) lm.isLocationEnabled
        else legacyLocationMode() != 0
    }

    @Suppress("DEPRECATION")
    private fun legacyLocationMode() = Settings.Secure.getInt(contentResolver, Settings.Secure.LOCATION_MODE, 0)

    fun withMic(then: () -> Unit) {
        if (Build.VERSION.SDK_INT < 23 || checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) return then()
        afterPermission = then
        if (Build.VERSION.SDK_INT >= 23) requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 3)
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        val next = afterPermission
        afterPermission = null
        if (results.isNotEmpty() && results.all { it == PackageManager.PERMISSION_GRANTED }) next?.invoke()
        else toast(if (code == 3) "Music mode needs the microphone" else "Glow needs Bluetooth permission to talk to the strip")
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 2) {
            val next = afterBluetoothOn
            afterBluetoothOn = null
            if (BleStrip.bluetoothOn()) next?.invoke()
        }
    }
}
