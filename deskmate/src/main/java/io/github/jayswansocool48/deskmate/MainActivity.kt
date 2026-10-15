package io.github.jayswansocool48.deskmate

import android.app.Activity
import android.app.AlertDialog
import android.content.res.Configuration
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject

class MainActivity : Activity() {
    private val stack = ArrayList<Screen>()
    private lateinit var container: FrameLayout
    private lateinit var bar: LinearLayout
    private lateinit var back: PillButton
    private lateinit var titleView: TextView
    private lateinit var actionsBox: LinearLayout
    private var pairDialog: AlertDialog? = null
    private var toast: Toast? = null
    private var resumed = false

    private val stateListener: () -> Unit = {
        stack.lastOrNull()?.onConnectionChanged()
        if (stack.size > 1) stack.first().onConnectionChanged()
        if (DeskClient.state == DeskClient.State.NEED_PAIR) askForPairCode() else {
            pairDialog?.dismiss()
            pairDialog = null
        }
    }

    private val messageListener: (JSONObject) -> Unit = { m ->
        when (m.optString("op")) {
            "error" -> toast(m.optString("message"))
            "pairFailed" -> {
                toast(m.optString("message", "Wrong code"))
                pairDialog = null
                askForPairCode()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Theme.BG
        window.navigationBarColor = Theme.BG
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val root = vbox().apply { setBackgroundColor(Theme.BG) }
        bar = hbox().apply {
            val p = dpi(8f)
            setPadding(p, p, p, p)
        }
        back = PillButton(this, "", Icon.BACK, Theme.SURFACE).apply { iconSize = dp(24f) }
        back.setOnClickListener { pop() }
        bar.addView(back, lp(dpi(48f), dpi(48f)))
        titleView = label("", 20f, Theme.TEXT, bold = true)
        bar.addView(titleView, lp(0, WRAP, 1f).apply { leftMargin = dpi(12f) })
        actionsBox = hbox()
        bar.addView(actionsBox)
        root.addView(bar, lp(MATCH, WRAP))
        container = FrameLayout(this)
        root.addView(container, lp(MATCH, 0, 1f))
        setContentView(root)

        DeskClient.init(this)
        DeskClient.onState(stateListener)
        DeskClient.onMessage(messageListener)
        push(HomeScreen(this))
        if (DeskClient.state == DeskClient.State.NO_PC) push(ConnectScreen(this))
    }

    override fun onDestroy() {
        DeskClient.removeState(stateListener)
        DeskClient.removeMessage(messageListener)
        stack.lastOrNull()?.onHide()
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        DeskClient.reconnectNow()
        stack.lastOrNull()?.onShow()
    }

    override fun onPause() {
        resumed = false
        stack.lastOrNull()?.onHide()
        super.onPause()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        for (s in stack) s.onOrientationChanged()
    }

    fun push(s: Screen) {
        stack.lastOrNull()?.let {
            it.onHide()
            it.visibility = View.GONE
        }
        stack.add(s)
        container.addView(s, FrameLayout.LayoutParams(MATCH, MATCH))
        showTop()
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
        showTop()
        top.onConnectionChanged()
        if (resumed) top.onShow()
    }

    private fun showTop() {
        val s = stack.last()
        val home = stack.size == 1
        bar.visibility = if (home) View.GONE else View.VISIBLE
        back.visibility = if (home) View.GONE else View.VISIBLE
        titleView.text = s.title
        titleView.setTextColor(s.accent)
        actionsBox.removeAllViews()
        for ((icon, run) in s.actions) {
            val b = PillButton(this, "", icon, Theme.SURFACE).apply { iconSize = dp(22f) }
            b.setOnClickListener { run() }
            actionsBox.addView(b, lp(dpi(48f), dpi(48f)).apply { leftMargin = dpi(8f) })
        }
    }

    @Deprecated("Back on Android 7")
    override fun onBackPressed() = pop()

    fun toast(text: String) {
        toast?.cancel()
        toast = Toast.makeText(this, text, Toast.LENGTH_SHORT).also { it.show() }
    }

    private fun askForPairCode() {
        if (pairDialog != null || isFinishing) return
        val input = EditText(this).apply {
            hint = "6-digit code"
            inputType = InputType.TYPE_CLASS_NUMBER
            textSize = 26f
            gravity = Gravity.CENTER
            letterSpacing = 0.3f
            setSingleLine(true)
        }
        val box = FrameLayout(this).apply {
            val p = dpi(24f)
            setPadding(p, dpi(8f), p, 0)
            addView(input)
        }
        pairDialog = AlertDialog.Builder(this)
            .setTitle("Pair with ${DeskClient.pcName}")
            .setMessage("Enter the code shown in Deskmate on your PC.")
            .setView(box)
            .setCancelable(false)
            .setPositiveButton("Pair") { _, _ ->
                pairDialog = null
                DeskClient.pair(input.text.toString().trim())
            }
            .setNegativeButton("Cancel") { _, _ ->
                pairDialog = null
                DeskClient.forget()
            }
            .show()
        input.requestFocus()
        pairDialog?.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
    }
}
