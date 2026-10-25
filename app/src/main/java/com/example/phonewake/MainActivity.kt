package com.example.phonewake

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var status: TextView
    private var pendingStart = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(28, 32, 28, 24)
        }
        layout.addView(TextView(this).apply { text = "Phone Wake"; textSize = 28f; gravity = Gravity.CENTER })
        status = TextView(this).apply {
            text = "Test vibration first. Then grant microphone permission and start listening."
            textSize = 16f
            setPadding(0, 20, 0, 24)
        }
        layout.addView(status)
        layout.addView(button("Test vibration") { testVibration() })
        layout.addView(button("Start listening") { ensurePermissionsAndStart() })
        layout.addView(button("Stop listening") {
            stopService(Intent(this, ListenService::class.java))
            status.text = "Listening stopped."
        })
        setContentView(ScrollView(this).apply { addView(layout) })
    }

    private fun button(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 10 }
    }

    private fun testVibration() {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                (getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            else {
                @Suppress("DEPRECATION")
                getSystemService(VIBRATOR_SERVICE) as Vibrator
            }
            if (!vibrator.hasVibrator()) {
                status.text = "Android reports that this phone has no vibrator."
                return
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 250, 150, 250), -1))
            else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(longArrayOf(0, 250, 150, 250), -1)
            }
            status.text = "Vibration test sent. If you felt nothing, check Sounds and vibration settings."
        } catch (e: Exception) { status.text = "Vibration error: ${e.message}" }
    }

    private fun ensurePermissionsAndStart() {
        val needed = mutableListOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= 33) needed.add(Manifest.permission.POST_NOTIFICATIONS)
        if (needed.any { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }) {
            pendingStart = true
            requestPermissions(needed.toTypedArray(), 420)
        } else startListener()
    }

    private fun startListener() {
        try {
            val intent = Intent(this, ListenService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
            status.text = "Starting offline listening. Keep the Phone Wake notification visible."
        } catch (e: Exception) { status.text = "Could not start: ${e.message}" }
    }

    @Deprecated("Deprecated in Android API")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 420) {
            val granted = permissions.indices.any { permissions[it] == Manifest.permission.RECORD_AUDIO && grantResults.getOrNull(it) == PackageManager.PERMISSION_GRANTED }
            if (pendingStart && granted) startListener() else status.text = "Microphone permission is required."
            pendingStart = false
        }
    }
}
