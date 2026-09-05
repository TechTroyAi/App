package ai.techtroy.notebook.ui

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.core.widget.ImageViewCompat
import ai.techtroy.notebook.R
import ai.techtroy.notebook.core.circle
import ai.techtroy.notebook.core.dp
import ai.techtroy.notebook.core.gold
import ai.techtroy.notebook.core.hairline
import ai.techtroy.notebook.core.muted
import ai.techtroy.notebook.core.textPrimary
import ai.techtroy.notebook.core.tint
import ai.techtroy.notebook.sys.Lock

/**
 * PIN pad (4–8 digits) with optional fingerprint. Modes: UNLOCK (verify) and SETUP (create + confirm).
 * Result: RESULT_OK when unlocked / PIN set. Callers use [unlock] / [setupPin] with a one-shot callback.
 */
class LockActivity : BaseActivity() {

    private var mode = MODE_UNLOCK
    private var entered = StringBuilder()
    private var firstPin: String? = null
    private lateinit var dots: LinearLayout
    private lateinit var prompt: TextView
    private lateinit var sub: TextView
    private var attempts = 0

    private val credential = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) { mode = MODE_SETUP; firstPin = null; entered.clear(); render() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mode = intent.getIntExtra(EXTRA_MODE, MODE_UNLOCK)
        blockScreenshots(true)
        setContentView(buildUi())
        if (mode == MODE_UNLOCK && app.prefs.biometric) biometric()
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setBackgroundColor(ai.techtroy.notebook.core.ThemeManager.attr(this@LockActivity, android.R.attr.colorBackground)); setPadding(dp(24), dp(48), dp(24), dp(24)) }
        val icon = ImageView(this).apply { setImageResource(R.drawable.ic_lock); ImageViewCompat.setImageTintList(this, tint(gold())) }
        root.addView(icon, LinearLayout.LayoutParams(dp(44), dp(44)).apply { topMargin = dp(24) })
        prompt = TextView(this).apply { textSize = 18f; setTextColor(textPrimary()); gravity = Gravity.CENTER; setPadding(0, dp(18), 0, dp(4)); typeface = android.graphics.Typeface.DEFAULT_BOLD }
        sub = TextView(this).apply { textSize = 13f; setTextColor(muted()); gravity = Gravity.CENTER }
        root.addView(prompt); root.addView(sub)
        dots = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(0, dp(28), 0, dp(28)) }
        root.addView(dots)
        val grid = GridLayout(this).apply { columnCount = 3; rowCount = 4; alignmentMode = GridLayout.ALIGN_BOUNDS }
        val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "bio", "0", "del")
        keys.forEach { k ->
            val cell = FrameLayout(this).apply {
                layoutParams = GridLayout.LayoutParams().apply { width = dp(84); height = dp(74); setMargins(dp(6), dp(6), dp(6), dp(6)) }
                isClickable = true; isFocusable = true
                foreground = with(android.util.TypedValue()) { theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, this, true); getDrawable(resourceId) }
            }
            when (k) {
                "bio" -> {
                    val canBio = mode == MODE_UNLOCK && app.prefs.biometric && BiometricManager.from(this).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) == BiometricManager.BIOMETRIC_SUCCESS
                    if (canBio) { cell.addView(ImageView(this).apply { setImageResource(R.drawable.ic_fingerprint); ImageViewCompat.setImageTintList(this, tint(gold())) }, FrameLayout.LayoutParams(dp(28), dp(28), Gravity.CENTER)); cell.setOnClickListener { biometric() } }
                }
                "del" -> { cell.addView(ImageView(this).apply { setImageResource(R.drawable.ic_backspace); ImageViewCompat.setImageTintList(this, tint(muted())) }, FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER)); cell.setOnClickListener { if (entered.isNotEmpty()) { entered.setLength(entered.length - 1); render() } }; cell.setOnLongClickListener { entered.clear(); render(); true } }
                else -> {
                    cell.addView(TextView(this).apply { text = k; textSize = 26f; setTextColor(textPrimary()); gravity = Gravity.CENTER; background = circle(0, hairline(), dp(1)) }, FrameLayout.LayoutParams(dp(64), dp(64), Gravity.CENTER))
                    cell.setOnClickListener { it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); if (entered.length < 8) { entered.append(k); render(); if (entered.length >= 4) checkAuto() } }
                }
            }
            grid.addView(cell)
        }
        root.addView(grid, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        val ok = TextView(this).apply { text = getString(R.string.ok); textSize = 15f; setTextColor(0xFF0B0B0C.toInt()); typeface = android.graphics.Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER; background = ai.techtroy.notebook.core.roundRect(gold(), 22f, this@LockActivity); setPadding(dp(40), dp(12), dp(40), dp(12)); setOnClickListener { submit() } }
        root.addView(ok, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(18) })
        val forgot = TextView(this).apply { text = getString(R.string.forgot_pin); textSize = 13f; setTextColor(muted()); setPadding(dp(12), dp(18), dp(12), dp(12)); setOnClickListener { forgot() }; visibility = if (mode == MODE_UNLOCK) View.VISIBLE else View.GONE }
        root.addView(forgot)
        render()
        return root
    }

    private fun render() {
        prompt.text = when { mode == MODE_UNLOCK -> getString(R.string.enter_pin); firstPin == null -> getString(R.string.create_pin); else -> getString(R.string.confirm_pin) }
        if (mode != MODE_UNLOCK && sub.text.isNullOrEmpty()) sub.text = "4–8 digits"
        dots.removeAllViews()
        val n = maxOf(4, entered.length)
        for (i in 0 until n) dots.addView(View(this).apply { background = if (i < entered.length) circle(gold()) else circle(0, hairline(), dp(1)); layoutParams = LinearLayout.LayoutParams(dp(14), dp(14)).apply { marginStart = dp(7); marginEnd = dp(7) } })
    }

    /** In unlock mode, auto-submit when length matches the stored pin length (we don't store length, so submit on 4+ only via OK) */
    private fun checkAuto() { if (mode == MODE_UNLOCK && entered.length == storedLenGuess()) submit() }
    private fun storedLenGuess(): Int = getSharedPreferences("notebook", Context.MODE_PRIVATE).getInt("pin_len", 0)

    private fun submit() {
        val pin = entered.toString()
        if (pin.length < 4) { shake(); return }
        if (mode == MODE_UNLOCK) {
            if (Lock.check(app.prefs, pin)) done() else { attempts++; entered.clear(); sub.text = getString(R.string.wrong_pin); shake(); render(); if (attempts >= 5) { sub.text = getString(R.string.wrong_pin) + " · slow down"; window.decorView.postDelayed({ }, 2000) } }
        } else {
            if (firstPin == null) { firstPin = pin; entered.clear(); sub.text = ""; render() }
            else if (firstPin == pin) { Lock.setPin(app.prefs, pin); getSharedPreferences("notebook", Context.MODE_PRIVATE).edit().putInt("pin_len", pin.length).apply(); done() }
            else { firstPin = null; entered.clear(); sub.text = getString(R.string.pin_mismatch); shake(); render() }
        }
    }

    private fun shake() { dots.animate().translationX(-dp(12).toFloat()).setDuration(50).withEndAction { dots.animate().translationX(dp(12).toFloat()).setDuration(50).withEndAction { dots.animate().translationX(0f).setDuration(50).start() }.start() }.start(); dots.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS) }

    private fun biometric() {
        val bm = BiometricManager.from(this)
        if (bm.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) != BiometricManager.BIOMETRIC_SUCCESS) return
        val prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { Lock.markUnlocked(); done() }
        })
        prompt.authenticate(BiometricPrompt.PromptInfo.Builder().setTitle(getString(R.string.biometric_title)).setSubtitle(getString(R.string.biometric_subtitle)).setNegativeButtonText(getString(R.string.use_pin)).setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK).build())
    }

    private fun forgot() {
        val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        if (!km.isDeviceSecure) { sub.text = getString(R.string.forgot_pin_hint); return }
        @Suppress("DEPRECATION") val i = km.createConfirmDeviceCredentialIntent(getString(R.string.forgot_pin), getString(R.string.forgot_pin_hint))
        if (i != null) credential.launch(i)
    }

    private fun done() { setResult(Activity.RESULT_OK); finish(); overridePendingTransition(R.anim.fade_in, R.anim.fade_out) }

    companion object {
        const val EXTRA_MODE = "mode"; const val MODE_UNLOCK = 0; const val MODE_SETUP = 1
        private var pending: ((Boolean) -> Unit)? = null

        /** One-shot: runs [then] after a successful unlock. */
        fun unlock(a: BaseActivity, then: () -> Unit) { launch(a, MODE_UNLOCK) { if (it) then() } }
        fun setupPin(a: BaseActivity, then: (Boolean) -> Unit) { launch(a, MODE_SETUP, then) }

        private fun launch(a: BaseActivity, mode: Int, cb: (Boolean) -> Unit) {
            val host = a as? LockHost ?: run { a.startActivity(Intent(a, LockActivity::class.java).putExtra(EXTRA_MODE, mode)); return }
            host.launchLock(Intent(a, LockActivity::class.java).putExtra(EXTRA_MODE, mode), cb)
        }
    }
}

/** Activities that want a callback from LockActivity implement this via [LockLauncher]. */
interface LockHost { fun launchLock(intent: Intent, cb: (Boolean) -> Unit) }
