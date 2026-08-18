package com.bikemesh.ridemesh

import android.Manifest
import android.app.AlertDialog
import android.bluetooth.BluetoothManager
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.bikemesh.ridemesh.audio.AudioEngine
import com.bikemesh.ridemesh.audio.AudioRoute
import com.bikemesh.ridemesh.databinding.ActivityMainBinding
import com.bikemesh.ridemesh.mesh.LobbyNode
import com.bikemesh.ridemesh.mesh.MeshNode
import com.bikemesh.ridemesh.service.RideService
import com.bikemesh.ridemesh.transport.InternetNode
import com.google.android.gms.mlkit.barcode.GmsBarcodeScannerOptions
import com.google.android.gms.mlkit.barcode.GmsBarcodeScanning
import com.google.android.material.button.MaterialButton
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity(), MeshNode.Listener, LobbyNode.Listener, InternetNode.Listener {
    private lateinit var binding: ActivityMainBinding
    private lateinit var meshNode: MeshNode
    private lateinit var lobbyNode: LobbyNode
    private lateinit var internetNode: InternetNode
    private lateinit var audioEngine: AudioEngine

    private val prefs by lazy { getSharedPreferences("ridemesh", MODE_PRIVATE) }
    private val nearbyButtons = linkedMapOf<String, MaterialButton>()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var rideStarted = false
    private var pendingAction = PendingAction.NONE
    private var directPeerCount = 0
    private var internetPeerCount = 0
    private var meshRunning = false
    private var internetConnectedSinceMs = 0L
    private var lastMeshRefreshMs = 0L

    private enum class PendingAction { NONE, START_RIDE, FIND_RIDERS }
    private enum class Screen { HOME, SETUP, ACTIVE }

    private val stopLobbyScan = Runnable {
        lobbyNode.stop()
        binding.findNearby.text = "FIND NEARBY RIDERS"
        if (rideStarted) {
            log("Nearby invite scan finished")
            if (!internetNode.isConnected() || !binding.batterySaver.isChecked) {
                ensureLocalMeshRunning("invite scan finished")
            }
        } else {
            log("Nearby scan paused to save battery. Tap FIND to scan again.")
        }
    }

    /**
     * Keeps the ride recoverable after a complete outage.
     * InternetNode independently retries the Internet relay. When Internet is
     * absent, local mesh stays awake and periodically refreshes discovery.
     */
    private val rideWatchdog = object : Runnable {
        override fun run() {
            if (!rideStarted) return

            val now = System.currentTimeMillis()
            if (internetNode.isConnected()) {
                val stableFor = now - internetConnectedSinceMs
                if (binding.batterySaver.isChecked && stableFor >= INTERNET_STABLE_BEFORE_MESH_SLEEP_MS) {
                    sleepLocalMesh("Internet stable")
                } else {
                    ensureLocalMeshRunning("warm handover fallback")
                }
            } else {
                ensureLocalMeshRunning("Internet unavailable")
                if (meshRunning && directPeerCount == 0 && now - lastMeshRefreshMs >= LOCAL_MESH_REFRESH_MS) {
                    restartLocalMesh()
                }
            }

            updateTransportStatus()
            updateCapturePolicy()
            mainHandler.postDelayed(this, WATCHDOG_INTERVAL_MS)
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        if (!hasRequiredPermissions()) {
            log("Required microphone / nearby permission was denied")
            pendingAction = PendingAction.NONE
            return@registerForActivityResult
        }

        val action = pendingAction
        pendingAction = PendingAction.NONE
        when (action) {
            PendingAction.START_RIDE -> startRideNow()
            PendingAction.FIND_RIDERS -> startNearbyLobby()
            PendingAction.NONE -> Unit
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Must run before super.onCreate() so the system splash is owned by us
        // and can be dismissed with a custom exit instead of popping abruptly.
        val splash = installSplashScreen()
        splash.setOnExitAnimationListener { provider ->
            val view = provider.view
            view.animate()
                .alpha(0f)
                .scaleX(1.06f)
                .scaleY(1.06f)
                .setDuration(280L)
                .withEndAction { provider.remove() }
                .start()
        }

        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Read the real version rather than hardcoding it, so the badge a
        // tester quotes in a bug report always matches the installed build.
        binding.buildBadge.text = runCatching {
            "BETA 1.1  •  v" + packageManager.getPackageInfo(packageName, 0).versionName
        }.getOrDefault("BETA 1.1")

        restoreSettings()

        meshNode = MeshNode(applicationContext, this)
        lobbyNode = LobbyNode(applicationContext, this)
        internetNode = InternetNode(this)
        audioEngine = AudioEngine(
            context = applicationContext,
            onCapturedFrame = ::sendHybridAudio,
            onStatus = { text -> runOnUiThread { updateAudioUi(text) } },
        )

        applySelectedAudioRoute()
        showScreen(Screen.HOME)
        clearNearbyRiders("Tap FIND to discover RideMesh riders nearby.")
        applyPowerUi()

        binding.createRide.setOnClickListener {
            binding.setupTitle.text = "CREATE RIDE"
            binding.rideCode.setText(generateRideCode())
            showScreen(Screen.SETUP)
        }

        binding.joinRide.setOnClickListener {
            binding.setupTitle.text = "JOIN RIDE"
            showScreen(Screen.SETUP)
            binding.rideCode.requestFocus()
        }

        binding.backHome.setOnClickListener {
            stopLobbyDiscovery()
            showScreen(Screen.HOME)
        }

        binding.openSettings.setOnClickListener { showSettingsAndHelpDialog() }
        binding.activeStop.setOnClickListener { confirmStopRide() }
        binding.activeRiders.setOnClickListener { showRidersDialog() }
        binding.activeInvite.setOnClickListener { showLiveInviteOptions() }
        binding.activeAudio.setOnClickListener { showAudioRouteDialog() }
        binding.activeStatus.setOnClickListener { showRideStatusDialog() }

        binding.audioRoute.setOnCheckedChangeListener { _, _ ->
            applySelectedAudioRoute()
            saveSettings()
        }

        binding.batterySaver.setOnCheckedChangeListener { _, _ ->
            saveSettings()
            applyBatteryPolicy()
        }

        binding.startRide.setOnClickListener {
            if (rideStarted) stopRide() else ensurePermissionsAndRun(PendingAction.START_RIDE)
        }

        binding.findNearby.setOnClickListener {
            ensurePermissionsAndRun(PendingAction.FIND_RIDERS)
        }

        binding.showQr.setOnClickListener { showRideQr() }
        binding.scanQr.setOnClickListener { scanRideQr() }
    }

    private fun showScreen(screen: Screen) {
        binding.screenHome.visibility = if (screen == Screen.HOME) View.VISIBLE else View.GONE
        binding.screenSetup.visibility = if (screen == Screen.SETUP) View.VISIBLE else View.GONE
        binding.screenActive.visibility = if (screen == Screen.ACTIVE) View.VISIBLE else View.GONE
    }

    private fun ensurePermissionsAndRun(action: PendingAction) {
        val missing = requiredPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            when (action) {
                PendingAction.START_RIDE -> startRideNow()
                PendingAction.FIND_RIDERS -> startNearbyLobby()
                PendingAction.NONE -> Unit
            }
        } else {
            pendingAction = action
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    /**
     * Starts a short lobby scan. During an active ride we only do this while the
     * Internet voice path is healthy, so adding riders cannot interrupt a local-only call.
     */
    private fun startNearbyLobby() {
        if (!radiosReady()) {
            log("Nearby riders unavailable: turn ON Bluetooth and Wi-Fi, then try again")
            return
        }

        if (rideStarted && !internetNode.isConnected()) {
            AlertDialog.Builder(this)
                .setTitle("Keep local voice uninterrupted")
                .setMessage("Nearby rider scanning during a local-only mesh call can compete with the same radio. Share the QR now, or use FIND NEARBY when Internet voice is available.")
                .setPositiveButton("SHARE QR") { _, _ -> shareRideQr() }
                .setNegativeButton("CLOSE", null)
                .show()
            return
        }

        stopLobbyDiscovery()
        clearNearbyRiders("Scanning nearby…")

        if (rideStarted && meshRunning) {
            // Voice stays on Internet while the short invite scan uses Nearby.
            sleepLocalMesh("live nearby invite scan")
        }

        lobbyNode.start(
            binding.riderName.text?.toString().orEmpty(),
            normalizedRideCode(),
        )
        binding.findNearby.text = "SCANNING…"
        mainHandler.postDelayed(stopLobbyScan, LOBBY_SCAN_WINDOW_MS)
        log(if (rideStarted) "Live nearby rider scan started • Internet voice continues" else "Short nearby scan started")
    }

    private fun stopLobbyDiscovery() {
        mainHandler.removeCallbacks(stopLobbyScan)
        if (::lobbyNode.isInitialized) lobbyNode.stop()
        if (::binding.isInitialized) binding.findNearby.text = "FIND NEARBY RIDERS"
    }

    private fun showLiveInviteOptions() {
        val options = arrayOf(
            "Show QR code",
            "Share QR code",
            "Find nearby RideMesh riders",
        )
        AlertDialog.Builder(this)
            .setTitle("Add riders without ending the call")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showRideQr()
                    1 -> shareRideQr()
                    2 -> ensurePermissionsAndRun(PendingAction.FIND_RIDERS)
                }
            }
            .setNegativeButton("CLOSE", null)
            .show()
    }

    private fun buildRideQrBitmap(code: String): Bitmap {
        val payload = "ridemesh://join?ride=${Uri.encode(code)}"
        val size = 720
        val matrix = QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, size, size)
        return Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).apply {
            for (y in 0 until size) {
                for (x in 0 until size) {
                    setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
                }
            }
        }
    }

    private fun showRideQr() {
        val code = normalizedRideCode()
        binding.rideCode.setText(code)
        saveSettings()

        try {
            val bitmap = buildRideQrBitmap(code)
            val image = ImageView(this).apply {
                setImageBitmap(bitmap)
                adjustViewBounds = true
                setPadding(24, 24, 24, 24)
            }

            AlertDialog.Builder(this)
                .setTitle("Invite to $code")
                .setMessage("Scan this QR to join. Your current conversation stays active.")
                .setView(image)
                .setPositiveButton("SHARE") { _, _ -> shareRideQr() }
                .setNegativeButton("CLOSE", null)
                .show()
            log("Showing QR invite for $code")
        } catch (t: Throwable) {
            log("Could not create QR: ${t.message ?: t.javaClass.simpleName}")
        }
    }

    private fun shareRideQr() {
        val code = normalizedRideCode()
        try {
            val bitmap = buildRideQrBitmap(code)
            val shareDir = File(cacheDir, "shared").apply { mkdirs() }
            val file = File(shareDir, "RideMesh-$code.png")
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_TEXT, "Join my RideMesh ride: $code\nOpen RideMesh → Join a Ride → Scan QR")
                clipData = ClipData.newUri(contentResolver, "RideMesh invite QR", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Share RideMesh QR"))
        } catch (t: Throwable) {
            log("Could not share QR: ${t.message ?: t.javaClass.simpleName}")
            AlertDialog.Builder(this)
                .setTitle("Could not share QR")
                .setMessage("Ride code: $code")
                .setPositiveButton("OK", null)
                .show()
        }
    }

    private fun scanRideQr() {
        val options = GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .enableAutoZoom()
            .build()

        GmsBarcodeScanning.getClient(this, options).startScan()
            .addOnSuccessListener { barcode ->
                val code = parseRideQr(barcode.rawValue.orEmpty())
                if (code == null) {
                    log("That QR is not a RideMesh invite")
                    return@addOnSuccessListener
                }

                binding.rideCode.setText(code)
                saveSettings()
                AlertDialog.Builder(this)
                    .setTitle("Join $code?")
                    .setMessage("Ride code loaded successfully.")
                    .setNegativeButton("LATER", null)
                    .setPositiveButton("JOIN") { _, _ ->
                        ensurePermissionsAndRun(PendingAction.START_RIDE)
                    }
                    .show()
            }
            .addOnCanceledListener { log("QR scan cancelled") }
            .addOnFailureListener { log("QR scanner error: ${it.message ?: "unknown"}") }
    }

    private fun parseRideQr(raw: String): String? = runCatching {
        val uri = Uri.parse(raw)
        if (!uri.scheme.equals("ridemesh", true) || !uri.host.equals("join", true)) {
            return@runCatching null
        }
        uri.getQueryParameter("ride")
            ?.trim()
            ?.uppercase()
            ?.takeIf { it.isNotBlank() }
            ?.take(12)
    }.getOrNull()

    private fun startRideNow() {
        if (rideStarted) return

        val rider = binding.riderName.text?.toString().orEmpty().ifBlank { Build.MODEL.take(18) }
        val code = normalizedRideCode()
        binding.riderName.setText(rider)
        binding.rideCode.setText(code)
        saveSettings()

        try {
            stopLobbyDiscovery()
            startRideServiceSafely()

            rideStarted = true
            directPeerCount = 0
            internetPeerCount = 0
            meshRunning = false
            internetConnectedSinceMs = 0L
            lastMeshRefreshMs = 0L

            applySelectedAudioRoute()
            audioEngine.selectCommunicationDevice()

            ensureLocalMeshRunning("initial fallback")
            internetNode.start(code)

            binding.activeRideCode.text = code
            showScreen(Screen.ACTIVE)
            updateTransportStatus()
            updateCapturePolicy()

            mainHandler.removeCallbacks(rideWatchdog)
            mainHandler.postDelayed(rideWatchdog, WATCHDOG_INTERVAL_MS)
            log("Ride started • noise reduction + automatic Internet / local reconnect enabled")
        } catch (t: Throwable) {
            recoverFromStartFailure(t)
        }
    }

    private fun sendHybridAudio(audio: ByteArray) {
        if (!rideStarted || audio.isEmpty()) return

        if (internetNode.isConnected()) {
            if (!internetNode.sendLocalAudio(audio)) {
                ensureLocalMeshRunning("Internet send failed")
                meshNode.sendLocalAudio(audio)
            }
        } else {
            ensureLocalMeshRunning("local voice path")
            meshNode.sendLocalAudio(audio)
        }
    }

    private fun ensureLocalMeshRunning(reason: String) {
        if (!rideStarted || meshRunning || !radiosReady()) return
        meshNode.start(
            binding.riderName.text?.toString().orEmpty(),
            normalizedRideCode(),
            MeshNode.LabRole.NORMAL,
        )
        meshRunning = true
        lastMeshRefreshMs = System.currentTimeMillis()
        log("Local mesh awake • $reason")
    }

    private fun sleepLocalMesh(reason: String) {
        if (!meshRunning) return
        meshRunning = false
        meshNode.stop()
        directPeerCount = 0
        log("Local mesh sleeping • $reason")
    }

    private fun restartLocalMesh() {
        if (!rideStarted || internetNode.isConnected() || !radiosReady()) return
        log("Refreshing local discovery for automatic reconnect")
        meshRunning = false
        meshNode.stop()
        directPeerCount = 0
        ensureLocalMeshRunning("automatic reconnect refresh")
    }

    private fun applyBatteryPolicy() {
        applyPowerUi()
        if (!rideStarted) return

        if (!binding.batterySaver.isChecked) {
            ensureLocalMeshRunning("Max Link selected")
        } else if (!internetNode.isConnected()) {
            ensureLocalMeshRunning("Internet unavailable")
        }

        updateTransportStatus()
        updateCapturePolicy()
    }

    private fun updateCapturePolicy() {
        if (!rideStarted) return
        if (internetNode.isConnected() || directPeerCount > 0) {
            audioEngine.startTransmit()
        } else {
            audioEngine.stopTransmit()
            updateAudioUi("Reconnecting • microphone sleeping")
        }
    }

    private fun startRideServiceSafely() {
        try {
            ContextCompat.startForegroundService(this, Intent(this, RideService::class.java))
        } catch (t: Throwable) {
            log("Background ride service unavailable: ${t.javaClass.simpleName}. App must remain open.")
        }
    }

    private fun recoverFromStartFailure(t: Throwable) {
        mainHandler.removeCallbacks(rideWatchdog)
        runCatching { audioEngine.stopTransmit() }
        runCatching { internetNode.stop() }
        runCatching { meshNode.stop() }
        runCatching { stopService(Intent(this, RideService::class.java)) }

        rideStarted = false
        meshRunning = false
        directPeerCount = 0
        internetPeerCount = 0
        internetConnectedSinceMs = 0L
        log("START ERROR — ${t.javaClass.simpleName}: ${t.message ?: "unknown"}")
        showScreen(Screen.SETUP)

        AlertDialog.Builder(this)
            .setTitle("Could not start ride")
            .setMessage("RideMesh stayed open. Check Bluetooth, Wi-Fi and permissions, then try again.")
            .setPositiveButton("REPORT BUG") { _, _ -> openWhatsAppBugReport() }
            .setNegativeButton("CLOSE", null)
            .show()
    }

    private fun radiosReady(): Boolean {
        val bluetoothOn = try {
            getSystemService(BluetoothManager::class.java).adapter?.isEnabled == true
        } catch (_: Throwable) {
            false
        }

        val wifiOn = try {
            applicationContext.getSystemService(WifiManager::class.java).isWifiEnabled
        } catch (_: Throwable) {
            false
        }

        return bluetoothOn && wifiOn
    }

    private fun confirmStopRide() {
        AlertDialog.Builder(this)
            .setTitle("End ride?")
            .setMessage("This disconnects your RideMesh voice session.")
            .setNegativeButton("CANCEL", null)
            .setPositiveButton("END RIDE") { _, _ -> stopRide() }
            .show()
    }

    private fun stopRide() {
        mainHandler.removeCallbacks(rideWatchdog)
        stopLobbyDiscovery()
        audioEngine.stopTransmit()
        internetNode.stop()
        meshRunning = false
        meshNode.stop()
        stopService(Intent(this, RideService::class.java))

        rideStarted = false
        directPeerCount = 0
        internetPeerCount = 0
        internetConnectedSinceMs = 0L
        binding.riderCount.text = "RIDE ACTIVE"
        binding.meshStatus.text = "CONNECTING…"
        binding.networkTile.text = "CONNECTING"
        binding.homeNetworkStatus.text = "●  READY TO RIDE"
        log("Ride stopped")
        showScreen(Screen.HOME)
    }

    private fun applySelectedAudioRoute() {
        if (!::audioEngine.isInitialized) return
        val route = when (binding.audioRoute.checkedRadioButtonId) {
            R.id.routePhone -> AudioRoute.PHONE
            R.id.routeHelmet -> AudioRoute.HELMET
            else -> AudioRoute.AUTO
        }
        audioEngine.setRoute(route)
        if (rideStarted) updateAudioUi(audioEngine.selectCommunicationDevice())
    }

    private fun updateAudioUi(text: String) {
        binding.audioStatus.text = text
        binding.homeAudioStatus.text = when {
            text.contains("Bluetooth", true) || text.contains("headset", true) -> "Helmet audio • noise reduction ready"
            text.contains("sleep", true) || text.contains("Reconnect", true) || text.contains("Waiting", true) -> "Audio waiting for connection"
            else -> "Phone audio • noise reduction ready"
        }

        binding.audioTile.text = when {
            text.contains("Bluetooth", true) || text.contains("headset", true) -> "HELMET AUDIO"
            text.contains("sleep", true) || text.contains("Reconnect", true) || text.contains("Waiting", true) -> "MIC STANDBY"
            else -> "VOICE CLEAN"
        }
    }

    private fun restoreSettings() {
        binding.riderName.setText(prefs.getString("rider", Build.MODEL.take(18)))
        binding.rideCode.setText(prefs.getString("code", "RIDE01"))
        binding.batterySaver.isChecked = prefs.getBoolean("battery_smart", true)

        when (prefs.getString("audio_route", "AUTO")) {
            "PHONE" -> binding.routePhone.isChecked = true
            "HELMET" -> binding.routeHelmet.isChecked = true
            else -> binding.routeAuto.isChecked = true
        }
    }

    private fun saveSettings() {
        val audioRoute = when (binding.audioRoute.checkedRadioButtonId) {
            R.id.routePhone -> "PHONE"
            R.id.routeHelmet -> "HELMET"
            else -> "AUTO"
        }

        prefs.edit()
            .putString("rider", binding.riderName.text?.toString().orEmpty())
            .putString("code", normalizedRideCode())
            .putString("audio_route", audioRoute)
            .putBoolean("battery_smart", binding.batterySaver.isChecked)
            .apply()
    }

    private fun normalizedRideCode(): String = binding.rideCode.text
        ?.toString()
        .orEmpty()
        .trim()
        .uppercase()
        .ifBlank { "RIDE01" }
        .take(12)

    /**
     * Ride codes are the only thing separating one group's audio from another
     * on the shared relay, so they must not be cheap to enumerate.
     *
     * Beta 1 used Random.nextInt(1000, 9999) -- about 9,000 possibilities from
     * a non-cryptographic RNG, which is small enough to walk through
     * exhaustively. v1.1 uses SecureRandom over an unambiguous alphabet
     * (no 0/O or 1/I, so codes stay easy to read out over a helmet intercom)
     * giving roughly 32^6 combinations.
     *
     * NOTE: this raises the cost of guessing a ride; it is not authentication.
     * Anyone who learns a code can still join. Real membership auth is tracked
     * as a pre-launch item in ARCHITECTURE.md.
     */
    private fun generateRideCode(): String {
        val rng = java.security.SecureRandom()
        val sb = StringBuilder("RM")
        repeat(RIDE_CODE_LENGTH) { sb.append(RIDE_CODE_ALPHABET[rng.nextInt(RIDE_CODE_ALPHABET.length)]) }
        return sb.toString()
    }

    private fun hasRequiredPermissions(): Boolean = requiredPermissions().all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

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

            Build.VERSION.SDK_INT >= 29 -> add(Manifest.permission.ACCESS_FINE_LOCATION)
            else -> add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
    }

    override fun onLog(message: String) {
        runOnUiThread { log(message) }
    }

    override fun onDirectPeerCount(count: Int) {
        directPeerCount = count
        if (count > 0) lastMeshRefreshMs = System.currentTimeMillis()
        runOnUiThread {
            updateTransportStatus()
            updateCapturePolicy()
        }
    }

    override fun onAudioPacket(audio: ByteArray) {
        if (rideStarted) audioEngine.playIncoming(audio)
    }

    override fun onInternetState(connected: Boolean, message: String) {
        runOnUiThread {
            log(message)
            if (connected) {
                if (internetConnectedSinceMs == 0L) internetConnectedSinceMs = System.currentTimeMillis()
            } else {
                internetConnectedSinceMs = 0L
                // If an invite scan was using Nearby, stop it before waking the local voice mesh.
                stopLobbyDiscovery()
                if (rideStarted) ensureLocalMeshRunning("Internet path lost")
            }
            updateTransportStatus()
            updateCapturePolicy()
        }
    }

    override fun onInternetPeerCount(count: Int) {
        internetPeerCount = count
        runOnUiThread { updateTransportStatus() }
    }

    override fun onInternetAudio(audio: ByteArray) {
        if (rideStarted) audioEngine.playIncoming(audio)
    }

    private fun updateTransportStatus() {
        if (!rideStarted) return

        when {
            internetNode.isConnected() -> {
                val total = internetPeerCount + 1
                binding.networkTile.text = "INTERNET"
                binding.riderCount.text = if (internetPeerCount > 0) "$total RIDERS CONNECTED" else "RIDE ACTIVE"
                binding.meshStatus.text = if (binding.batterySaver.isChecked && !meshRunning) {
                    "INTERNET VOICE • AUTO LOCAL FALLBACK"
                } else {
                    "INTERNET VOICE • LOCAL MESH WARM"
                }
            }

            directPeerCount > 0 -> {
                val total = directPeerCount + 1
                binding.networkTile.text = "LOCAL MESH"
                binding.riderCount.text = "$total RIDERS NEARBY"
                binding.meshStatus.text = "LOCAL VOICE • AUTO RECONNECT ACTIVE"
            }

            else -> {
                binding.networkTile.text = "SEARCHING"
                binding.riderCount.text = "RECONNECTING…"
                binding.meshStatus.text = "AUTO RECONNECT • INTERNET + NEARBY SEARCH"
            }
        }

        binding.homeNetworkStatus.text = when {
            internetNode.isConnected() -> "●  INTERNET VOICE ACTIVE"
            directPeerCount > 0 -> "●  LOCAL MESH ACTIVE"
            else -> "●  READY TO RIDE"
        }
        applyPowerUi()
    }

    private fun applyPowerUi() {
        binding.powerTile.text = if (binding.batterySaver.isChecked) "SMART POWER" else "MAX LINK"
        binding.powerTile.setTextColor(
            ContextCompat.getColor(this, if (binding.batterySaver.isChecked) R.color.green else R.color.amber)
        )
    }

    override fun onLobbyLog(message: String) {
        runOnUiThread { log(message) }
    }

    override fun onNearbyRiderFound(endpointId: String, riderName: String, rideCode: String) {
        runOnUiThread {
            if (nearbyButtons.containsKey(endpointId)) return@runOnUiThread

            val marker = MaterialButton(this).apply {
                isAllCaps = false
                text = "$riderName   •   $rideCode     INVITE"
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.white))
                strokeColor = ContextCompat.getColorStateList(this@MainActivity, R.color.border)
                setOnClickListener {
                    lobbyNode.invite(endpointId, normalizedRideCode(), binding.riderName.text?.toString().orEmpty())
                }
            }
            nearbyButtons[endpointId] = marker

            if (rideStarted) {
                AlertDialog.Builder(this)
                    .setTitle("Nearby RideMesh rider found")
                    .setMessage("$riderName is nearby${if (rideCode.isNotBlank()) " • currently showing $rideCode" else ""}. Invite them to ${normalizedRideCode()}?\n\nYour current Internet conversation continues while you invite.")
                    .setPositiveButton("INVITE") { _, _ ->
                        lobbyNode.invite(endpointId, normalizedRideCode(), binding.riderName.text?.toString().orEmpty())
                    }
                    .setNegativeButton("LATER", null)
                    .show()
            } else {
                if (nearbyButtons.size == 1) binding.nearbyUsers.removeAllViews()
                binding.nearbyUsers.addView(marker)
            }
        }
    }

    override fun onNearbyRiderLost(endpointId: String) {
        runOnUiThread {
            val button = nearbyButtons.remove(endpointId) ?: return@runOnUiThread
            binding.nearbyUsers.removeView(button)
            if (!rideStarted && nearbyButtons.isEmpty()) {
                clearNearbyRiders("No riders visible. Tap FIND to scan again.")
            }
        }
    }

    override fun onRideInviteReceived(inviterName: String, rideCode: String) {
        runOnUiThread {
            if (rideStarted) {
                val sameRide = normalizedRideCode().equals(rideCode, true)
                AlertDialog.Builder(this)
                    .setTitle(if (sameRide) "Already in this ride" else "Ride invitation received")
                    .setMessage(if (sameRide) "$inviterName invited you to the ride you are already using." else "$inviterName invited you to $rideCode. End your current ride before switching groups.")
                    .setPositiveButton("OK", null)
                    .show()
                return@runOnUiThread
            }

            AlertDialog.Builder(this)
                .setTitle("Ride invitation")
                .setMessage("$inviterName invited you to $rideCode")
                .setNegativeButton("DECLINE", null)
                .setPositiveButton("JOIN") { _, _ ->
                    binding.rideCode.setText(rideCode)
                    saveSettings()
                    stopLobbyDiscovery()
                    ensurePermissionsAndRun(PendingAction.START_RIDE)
                }
                .show()
        }
    }

    private fun showRidersDialog() {
        val internetTotal = if (internetNode.isConnected()) internetPeerCount + 1 else 0

        val message = buildString {
            if (internetNode.isConnected()) {
                append("Internet group: $internetTotal rider${if (internetTotal == 1) "" else "s"}\n")
            }
            append("Nearby direct peers: $directPeerCount\n")
            append("Local mesh: ${if (meshRunning) "ready" else "sleeping"}\n")
            append("Noise reduction: ON\n")
            append("Auto reconnect: ON\n\n")
            append("Use INVITE to add riders without ending the conversation.")
        }

        AlertDialog.Builder(this)
            .setTitle("Riders")
            .setMessage(message)
            .setPositiveButton("INVITE") { _, _ -> showLiveInviteOptions() }
            .setNegativeButton("CLOSE", null)
            .show()
    }

    private fun showAudioRouteDialog() {
        val choices = arrayOf(
            "Auto — helmet if connected, otherwise phone",
            "Phone speaker + microphone",
            "Bluetooth helmet / headset",
        )
        val checked = when (binding.audioRoute.checkedRadioButtonId) {
            R.id.routePhone -> 1
            R.id.routeHelmet -> 2
            else -> 0
        }

        AlertDialog.Builder(this)
            .setTitle("Audio route • noise reduction ON")
            .setSingleChoiceItems(choices, checked) { dialog, which ->
                when (which) {
                    1 -> binding.routePhone.isChecked = true
                    2 -> binding.routeHelmet.isChecked = true
                    else -> binding.routeAuto.isChecked = true
                }
                applySelectedAudioRoute()
                saveSettings()
                dialog.dismiss()
            }
            .setNegativeButton("CANCEL", null)
            .show()
    }

    private fun showSettingsAndHelpDialog() {
        val modes = arrayOf(
            "Battery Smart — recommended",
            "Max Link — keep Internet + local mesh active",
        )
        val checked = if (binding.batterySaver.isChecked) 0 else 1

        AlertDialog.Builder(this)
            .setTitle("RideMesh settings & help")
            .setSingleChoiceItems(modes, checked) { dialog, which ->
                binding.batterySaver.isChecked = which == 0
                saveSettings()
                applyBatteryPolicy()
                dialog.dismiss()
            }
            .setMessage(
                "Noise reduction is always enabled for group voice. Battery Smart keeps local mesh warm during handover, then saves power when Internet is stable.\n\n" +
                    "Bug reports: WhatsApp group or direct support +91 9188664823."
            )
            .setPositiveButton("REPORT BUG") { _, _ -> openWhatsAppBugReport() }
            .setNeutralButton("COMMUNITY") { _, _ -> openRideMeshCommunity() }
            .setNegativeButton("CLOSE", null)
            .show()
    }

    private fun showRideStatusDialog() {
        val path = when {
            internetNode.isConnected() -> "Internet"
            directPeerCount > 0 -> "Local mesh"
            else -> "Reconnecting"
        }

        AlertDialog.Builder(this)
            .setTitle("Ride status")
            .setMessage(
                "Path: $path\n" +
                    "Internet riders: ${if (internetNode.isConnected()) internetPeerCount + 1 else 0}\n" +
                    "Direct local peers: $directPeerCount\n" +
                    "Audio: ${binding.audioTile.text}\n" +
                    "Noise reduction: ON\n" +
                    "Power: ${binding.powerTile.text}\n" +
                    "Auto reconnect: ON\n\n" +
                    "INVITE can add more riders without stopping the current call."
            )
            .setPositiveButton("REPORT BUG") { _, _ -> openWhatsAppBugReport() }
            .setNeutralButton("INVITE") { _, _ -> showLiveInviteOptions() }
            .setNegativeButton("CLOSE", null)
            .show()
    }

    private fun openWhatsAppBugReport() {
        val options = arrayOf(
            "Join RideMesh bug report group",
            "Send direct WhatsApp report to +91 9188664823",
        )
        AlertDialog.Builder(this)
            .setTitle("Report a RideMesh bug")
            .setItems(options) { _, which ->
                if (which == 0) {
                    openExternalUri(BUG_REPORT_GROUP_URL, "Could not open the RideMesh bug report group")
                } else {
                    openDirectWhatsAppBugReport()
                }
            }
            .setNegativeButton("CLOSE", null)
            .show()
    }

    private fun openDirectWhatsAppBugReport() {
        val message = buildString {
            append("RideMesh bug report\n")
            append("Ride code: ${normalizedRideCode()}\n")
            append("Phone: ${Build.MANUFACTURER} ${Build.MODEL}\n")
            append("Android: ${Build.VERSION.RELEASE}\n")
            append("Current path: ${if (rideStarted) binding.networkTile.text else "Not riding"}\n")
            append("Problem: ")
        }
        val url = "https://wa.me/$SUPPORT_WHATSAPP?text=${Uri.encode(message)}"
        openExternalUri(url, "Could not open WhatsApp bug report")
    }

    private fun openRideMeshCommunity() {
        openExternalUri(COMMUNITY_URL, "Could not open RideMesh community link")
    }

    private fun openExternalUri(url: String, failureMessage: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: ActivityNotFoundException) {
            AlertDialog.Builder(this)
                .setTitle("Link unavailable")
                .setMessage(failureMessage)
                .setPositiveButton("OK", null)
                .show()
        } catch (_: Throwable) {
            AlertDialog.Builder(this)
                .setTitle("Link unavailable")
                .setMessage(failureMessage)
                .setPositiveButton("OK", null)
                .show()
        }
    }

    private fun clearNearbyRiders(message: String) {
        nearbyButtons.clear()
        binding.nearbyUsers.removeAllViews()
        val text = android.widget.TextView(this).apply {
            this.text = message
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.muted))
            textSize = 12f
            setPadding(4, 10, 4, 10)
        }
        binding.nearbyUsers.addView(text)
    }

    private fun log(message: String) {
        val stamp = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        val old = binding.logView.text?.toString().orEmpty()
        binding.logView.text = "$stamp  $message\n$old".take(7000)
    }

    override fun onDestroy() {
        saveSettings()
        mainHandler.removeCallbacks(stopLobbyScan)
        mainHandler.removeCallbacks(rideWatchdog)
        if (::lobbyNode.isInitialized) lobbyNode.stop()
        if (::internetNode.isInitialized && !rideStarted) internetNode.stop()
        if (!rideStarted && ::audioEngine.isInitialized) audioEngine.release()
        super.onDestroy()
    }

    companion object {
        private const val LOBBY_SCAN_WINDOW_MS = 20_000L
        private const val WATCHDOG_INTERVAL_MS = 5_000L
        private const val INTERNET_STABLE_BEFORE_MESH_SLEEP_MS = 15_000L
        private const val LOCAL_MESH_REFRESH_MS = 25_000L
        private const val SUPPORT_WHATSAPP = "919188664823"
        private const val BUG_REPORT_GROUP_URL = "https://chat.whatsapp.com/CGToJCBDG6XFGUpeTp7uKW"
        private const val COMMUNITY_URL = "https://chat.whatsapp.com/CGToJCBDG6XFGUpeTp7uKW"

        // Ride code entropy. Alphabet excludes 0/O and 1/I so codes read
        // unambiguously over an intercom; 32^6 keyspace with SecureRandom.
        private const val RIDE_CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        private const val RIDE_CODE_LENGTH = 6
    }
}
