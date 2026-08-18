package com.meshvoice.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.meshvoice.app.audio.AudioEngine
import com.meshvoice.app.databinding.ActivityMainBinding
import com.meshvoice.app.mesh.MeshNode
import com.meshvoice.app.service.RideService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random

class MainActivity : AppCompatActivity(), MeshNode.Listener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var meshNode: MeshNode
    private lateinit var audioEngine: AudioEngine

    private val prefs by lazy { getSharedPreferences("meshvoice", MODE_PRIVATE) }
    private var rideActive = false
    private var peerCount = 0

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) {
            startRide()
        } else {
            log("Microphone / nearby-device permission was denied")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        meshNode = MeshNode(applicationContext, this)
        audioEngine = AudioEngine(
            context = applicationContext,
            onCapturedFrame = { audio -> meshNode.sendAudio(audio) },
            onStatus = { text -> runOnUiThread { binding.audioStatus.text = text } },
        )

        binding.riderName.setText(prefs.getString("rider", Build.MODEL.take(18)))
        binding.rideCode.setText(prefs.getString("code", generateRideCode()))

        binding.startStop.setOnClickListener {
            if (rideActive) stopRide() else requestPermissionsAndStart()
        }

        updateUi()
    }

    private fun requestPermissionsAndStart() {
        val missing = requiredPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) startRide() else permissionLauncher.launch(missing.toTypedArray())
    }

    private fun startRide() {
        if (rideActive) return
        val rider = binding.riderName.text?.toString()?.ifBlank { "Rider" } ?: "Rider"
        val code = binding.rideCode.text?.toString()?.trim()?.uppercase()?.ifBlank { "RIDE01" } ?: "RIDE01"
        binding.riderName.setText(rider)
        binding.rideCode.setText(code)
        prefs.edit().putString("rider", rider).putString("code", code).apply()

        try {
            ContextCompat.startForegroundService(this, Intent(this, RideService::class.java))
        } catch (t: Throwable) {
            log("Background service unavailable: ${t.javaClass.simpleName}. Keep the app open.")
        }

        meshNode.start(rider, code)
        audioEngine.startTransmit()
        rideActive = true
        log("Ride started • code $code")
        updateUi()
    }

    private fun stopRide() {
        audioEngine.stopTransmit()
        meshNode.stop()
        stopService(Intent(this, RideService::class.java))
        rideActive = false
        peerCount = 0
        log("Ride stopped")
        updateUi()
    }

    private fun updateUi() {
        binding.startStop.text = if (rideActive) "END RIDE" else "START RIDE"
        binding.peerCount.text = if (rideActive) "$peerCount rider(s) connected" else "Not riding"
        binding.riderName.isEnabled = !rideActive
        binding.rideCode.isEnabled = !rideActive
    }

    private fun generateRideCode(): String =
        "RM" + (100000..999999).random(Random(System.nanoTime()))

    private fun requiredPermissions(): List<String> = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        when {
            Build.VERSION.SDK_INT >= 33 -> {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_CONNECT)
                add(Manifest.permission.BLUETOOTH_ADVERTISE)
                add(Manifest.permission.NEARBY_WIFI_DEVICES)
            }
            Build.VERSION.SDK_INT >= 31 -> {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_CONNECT)
                add(Manifest.permission.BLUETOOTH_ADVERTISE)
                add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
            else -> add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    // ---- MeshNode.Listener ----

    override fun onLog(message: String) {
        runOnUiThread { log(message) }
    }

    override fun onPeerCountChanged(count: Int) {
        peerCount = count
        runOnUiThread { updateUi() }
    }

    override fun onAudioReceived(audio: ByteArray) {
        if (rideActive) audioEngine.playIncoming(audio)
    }

    private fun log(message: String) {
        val stamp = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        binding.logView.text = "$stamp  $message\n${binding.logView.text}".take(6000)
    }

    override fun onDestroy() {
        if (!rideActive) audioEngine.release()
        super.onDestroy()
    }
}
