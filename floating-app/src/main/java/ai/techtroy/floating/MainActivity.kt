package ai.techtroy.floating

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
            setBackgroundColor(Color.rgb(17, 23, 20))
        }
        root.addView(TextView(this).apply {
            text = "Floating Fan\nOpen mini-apps over other apps"
            textSize = 22f
            setTextColor(Color.WHITE)
        })
        fun button(text: String, action: () -> Unit) {
            root.addView(Button(this).apply { this.text = text; setOnClickListener { action() } })
        }
        button("1. Allow display over other apps") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        button("2. Allow optional contacts and microphone") {
            val permissions = mutableListOf(Manifest.permission.READ_CONTACTS, Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            val missing = permissions.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
            if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 1)
        }
        root.addView(TextView(this).apply {
            text = "Choose a mini-app below. Drag its title to move, use ↘ to resize, − to minimize, and × to close. Microphone and contacts are optional."
            setTextColor(Color.LTGRAY)
        })
        MiniApp.values().forEach { app ->
            button(app.title) {
                if (!Settings.canDrawOverlays(this)) {
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                } else {
                    val intent = Intent(this, FloatingService::class.java).putExtra("app", app.name)
                    startForegroundService(intent)
                }
            }
        }
        button("Close all floating windows") { stopService(Intent(this, FloatingService::class.java)) }
        setContentView(ScrollView(this).apply { addView(root) })
    }
}
