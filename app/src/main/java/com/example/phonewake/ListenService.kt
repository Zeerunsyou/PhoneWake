package com.example.phonewake

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.media.ToneGenerator
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
import java.util.Locale

class ListenService : Service(), RecognitionListener {
    private val handler = Handler(Looper.getMainLooper())
    private var model: Model? = null
    private var speechService: SpeechService? = null
    private var waitingForCommand = false
    private var commandTimeout: Runnable? = null
    private var flashOn = false
    private var cameraId: String? = null
    @Volatile private var stopped = false
    private var toneGenerator: ToneGenerator? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Loading offline speech model"))
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            stopSelf()
            return
        }
        toneGenerator = try { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 85) } catch (_: Exception) { null }
        try {
            val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            cameraId = manager.cameraIdList.firstOrNull { id ->
                manager.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
        } catch (e: Exception) { Log.e(TAG, "Could not find flashlight", e) }

        StorageService.unpack(this, "model-en-us", "model",
            { unpackedModel ->
                if (stopped) { unpackedModel.close(); return@unpack }
                model = unpackedModel
                startRecognition()
            },
            { exception ->
                Log.e(TAG, "Model unpack failed", exception)
                updateNotification("Model failed to load — reinstall the APK")
                stopSelf()
            }
        )
    }

    private fun startRecognition() {
        try {
            val grammar = """["phone","phone locate","phone location","phone flashlight","flashlight","locate","location","[unk]"]"""
            val recognizer = Recognizer(model, 16000.0f, grammar)
            speechService = SpeechService(recognizer, 16000.0f)
            speechService?.startListening(this)
            updateNotification("Listening offline for ‘Phone’")
            Log.i(TAG, "Offline recognition started")
        } catch (e: Exception) {
            Log.e(TAG, "Recognition start failed", e)
            updateNotification("Recognition could not start")
        }
    }

    override fun onPartialResult(hypothesis: String?) { if (!hypothesis.isNullOrBlank()) Log.d(TAG, "Partial: $hypothesis") }
    override fun onResult(hypothesis: String?) = processHypothesis(hypothesis)
    override fun onFinalResult(hypothesis: String?) = processHypothesis(hypothesis)

    private fun processHypothesis(raw: String?) {
        if (stopped || raw.isNullOrBlank()) return
        val phrase = try { JSONObject(raw).optString("text", "").lowercase(Locale.US) }
            catch (_: Exception) { raw.lowercase(Locale.US) }
        if (phrase.isBlank()) return
        Log.i(TAG, "Recognized: $phrase")
        handlePhrase(phrase)
    }

    private fun handlePhrase(phrase: String) {
        val words = phrase.split(Regex("\\s+")).toSet()
        if ("phone" in words && ("locate" in words || "location" in words)) {
            waitingForCommand = false
            cancelCommandTimeout()
            playThreeDings()
            return
        }
        if ("phone" in words) {
            startCommandWindow()
            if ("flashlight" in words || "locate" in words || "location" in words) handleCommand(words)
            return
        }
        if (waitingForCommand) handleCommand(words)
    }

    private fun startCommandWindow() {
        waitingForCommand = true
        vibrate(180)
        cancelCommandTimeout()
        commandTimeout = Runnable {
            waitingForCommand = false
            vibrate(180)
            updateNotification("Listening offline for ‘Phone’")
        }
        handler.postDelayed(commandTimeout!!, 3000)
        updateNotification("Heard ‘Phone’ — say a command within 3 seconds")
    }

    private fun handleCommand(words: Set<String>) {
        when {
            "flashlight" in words -> {
                waitingForCommand = false
                cancelCommandTimeout()
                toggleFlashlight()
                vibrateTwice()
                updateNotification("Flashlight command received")
            }
            "locate" in words || "location" in words -> {
                waitingForCommand = false
                cancelCommandTimeout()
                playThreeDings()
                updateNotification("Locate command received")
            }
        }
    }

    private fun toggleFlashlight() {
        val id = cameraId
        if (id == null) { updateNotification("No flashlight found"); return }
        try {
            flashOn = !flashOn
            (getSystemService(Context.CAMERA_SERVICE) as CameraManager).setTorchMode(id, flashOn)
            Log.i(TAG, "Flashlight toggled: $flashOn")
        } catch (e: Exception) {
            flashOn = !flashOn
            Log.e(TAG, "Flashlight toggle failed", e)
            updateNotification("Flashlight error — check camera permission")
        }
    }

    private fun playThreeDings() {
        repeat(3) { index ->
            handler.postDelayed({
                try { toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP, 180) }
                catch (e: Exception) { Log.e(TAG, "Tone failed", e); vibrate(100) }
            }, index * 3000L)
        }
    }

    private fun vibrateTwice() {
        vibrate(150)
        handler.postDelayed({ vibrate(150) }, 300)
    }

    private fun vibrate(duration: Long) {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                (getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            else {
                @Suppress("DEPRECATION")
                getSystemService(VIBRATOR_SERVICE) as Vibrator
            }
            if (!vibrator.hasVibrator()) { Log.w(TAG, "No vibrator reported by device"); return }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                vibrator.vibrate(VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE))
            else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(duration)
            }
        } catch (e: Exception) { Log.e(TAG, "Vibration failed", e) }
    }

    private fun cancelCommandTimeout() {
        commandTimeout?.let { handler.removeCallbacks(it) }
        commandTimeout = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Phone Wake listening", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(message: String): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder.setContentTitle("Phone Wake").setContentText(message)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm).setOngoing(true).build()
    }

    private fun updateNotification(message: String) {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, buildNotification(message))
    }

    override fun onError(exception: Exception?) {
        Log.e(TAG, "Speech recognition error", exception)
        updateNotification("Recognition error — stop and restart listening")
    }

    override fun onTimeout() {
        Log.w(TAG, "Recognition timeout")
        if (!stopped) {
            try { speechService?.stop(); speechService?.startListening(this) }
            catch (e: Exception) { Log.e(TAG, "Recognition restart failed", e) }
        }
    }

    override fun onDestroy() {
        stopped = true
        cancelCommandTimeout()
        handler.removeCallbacksAndMessages(null)
        try { speechService?.stop(); speechService?.shutdown() } catch (_: Exception) {}
        try { toneGenerator?.release() } catch (_: Exception) {}
        try { if (flashOn && cameraId != null) (getSystemService(Context.CAMERA_SERVICE) as CameraManager).setTorchMode(cameraId!!, false) } catch (_: Exception) {}
        try { model?.close() } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "PhoneWake"
        private const val CHANNEL_ID = "phonewake_listening"
        private const val NOTIFICATION_ID = 4102
    }
}
