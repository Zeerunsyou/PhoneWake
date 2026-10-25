package com.example.phonewake

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import org.vosk.android.StorageService
import java.io.IOException
import java.util.Locale

class ListenService : Service(), RecognitionListener {
    private val handler = Handler(Looper.getMainLooper())
    private var model: Model? = null
    private var speechService: SpeechService? = null
    private var waitingForCommand = false
    private var flashlightOn = false
    private var cameraId: String? = null
    private var ringtone: Ringtone? = null
    private var soundCount = 0
    private val commandTimeout = Runnable {
        if (waitingForCommand) {
            waitingForCommand = false
            vibrateOnce()
        }
    }
    private val soundTask = object : Runnable {
        override fun run() {
            if (soundCount <= 0) return
            playNotificationSound()
            soundCount--
            if (soundCount > 0) handler.postDelayed(this, 3000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(7, buildNotification())
        try {
            val manager = getSystemService(CAMERA_SERVICE) as CameraManager
            cameraId = manager.cameraIdList.firstOrNull { id ->
                manager.getCameraCharacteristics(id).get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
        } catch (e: Exception) { Log.w("PhoneWake", "Flashlight unavailable", e) }
        StorageService.unpack(this, "model-en-us", "model", { unpacked ->
            model = unpacked
            startRecognition()
        }, { exception ->
            Log.e("PhoneWake", "Could not load offline speech model", exception)
            stopSelf()
        })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    private fun startRecognition() {
        val loadedModel = model ?: return
        try {
            val recognizer = Recognizer(loadedModel, 16000.0f)
            speechService = SpeechService(recognizer, 16000.0f).also { it.startListening(this) }
        } catch (e: IOException) {
            Log.e("PhoneWake", "Could not start offline recognition", e)
            stopSelf()
        }
    }

    override fun onResult(hypothesis: String?) {
        val text = parseText(hypothesis)
        if (text.isNotBlank()) inspect(text)
    }
    override fun onPartialResult(hypothesis: String?) {
        val text = parseText(hypothesis)
        if (text.isNotBlank()) inspect(text)
    }
    override fun onFinalResult(hypothesis: String?) {
        val text = parseText(hypothesis)
        if (text.isNotBlank()) inspect(text)
    }
    override fun onError(exception: Exception?) { Log.e("PhoneWake", "Recognition error", exception) }
    override fun onTimeout() { }

    private fun parseText(json: String?): String {
        if (json.isNullOrBlank()) return ""
        return try { JSONObject(json).optString("text", "").lowercase(Locale.ROOT).trim() } catch (_: Exception) { "" }
    }

    private fun inspect(phrase: String) {
        val normalized = phrase.replace(Regex("[^a-z ]"), " ").replace(Regex("\\s+"), " ").trim()
        if (normalized.isEmpty()) return
        if (!waitingForCommand) {
            if (normalized.split(" ").any { it == "phone" }) {
                waitingForCommand = true
                vibrateOnce()
                handler.removeCallbacks(commandTimeout)
                handler.postDelayed(commandTimeout, 3000)
                val remainder = normalized.substringAfter("phone", "").trim()
                if (remainder.isNotBlank()) handleCommand(remainder)
            }
            return
        }
        val command = normalized.substringAfterLast("phone", normalized).trim()
        if (command.isNotBlank()) handleCommand(command)
    }

    private fun handleCommand(command: String) {
        val first = command.split(" ").firstOrNull().orEmpty()
        when {
            first == "flashlight" || first == "flash" || command.startsWith("flash light") -> {
                waitingForCommand = false
                handler.removeCallbacks(commandTimeout)
                toggleFlashlight()
                vibrateTwice()
            }
            first == "location" || first == "locate" -> {
                waitingForCommand = false
                handler.removeCallbacks(commandTimeout)
                handler.removeCallbacks(soundTask)
                soundCount = 3
                soundTask.run()
            }
            else -> {
                // Keep the original 3-second window active for a command not recognized.
            }
        }
    }

    private fun toggleFlashlight() {
        val id = cameraId ?: return
        if (checkSelfPermission(android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        try {
            val next = !flashlightOn
            (getSystemService(CAMERA_SERVICE) as CameraManager).setTorchMode(id, next)
            flashlightOn = next
        } catch (e: Exception) { Log.e("PhoneWake", "Could not toggle flashlight", e) }
    }

    private fun playNotificationSound() {
        try {
            ringtone?.stop()
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            ringtone = RingtoneManager.getRingtone(applicationContext, uri)
            ringtone?.audioAttributes = android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION).build()
            ringtone?.play()
        } catch (e: Exception) { Log.e("PhoneWake", "Could not play notification sound", e) }
    }

    private fun vibrateOnce() = vibrate(180)
    private fun vibrateTwice() {
        vibrate(150)
        handler.postDelayed({ vibrate(150) }, 250)
    }
    private fun vibrate(duration: Long) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
                    .vibrate(VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                val vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) vibrator.vibrate(VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE))
                else { @Suppress("DEPRECATION") vibrator.vibrate(duration) }
            }
        } catch (e: Exception) { Log.w("PhoneWake", "Vibration failed", e) }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel("phone_wake_listener", "Phone Wake listening", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
    }
    private fun buildNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, "phone_wake_listener") else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder.setContentTitle("Phone Wake is listening")
            .setContentText("Say ‘Phone’, then ‘flashlight’ or ‘location’")
            .setSmallIcon(android.R.drawable.ic_lock_silent_mode_off).setOngoing(true).build()
    }
    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        speechService?.stop()
        speechService?.shutdown()
        speechService = null
        model?.close()
        model = null
        try { ringtone?.stop() } catch (_: Exception) { }
        if (flashlightOn && cameraId != null) {
            try { (getSystemService(CAMERA_SERVICE) as CameraManager).setTorchMode(cameraId!!, false) } catch (_: Exception) { }
        }
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
}
