package app.badger.kiosk

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.provider.Settings
import androidx.core.location.LocationManagerCompat
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity(), NfcAdapter.ReaderCallback {

    private val vm: AppViewModel by viewModels()
    private var nfcAdapter: NfcAdapter? = null
    private var exportEventId: Long = -1
    private var pendingAuthAction: (() -> Unit)? = null

    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    ) { uri ->
        uri ?: return@registerForActivityResult
        lifecycleScope.launch {
            val xlsx = vm.buildXlsx(exportEventId)
            if (xlsx.isEmpty()) {
                runCatching { android.provider.DocumentsContract.deleteDocument(contentResolver, uri) }
                android.widget.Toast.makeText(this@MainActivity, "Export blocked: outside event bounds", android.widget.Toast.LENGTH_LONG).show()
                return@launch
            }
            contentResolver.openOutputStream(uri)?.use { it.write(xlsx) }
            android.widget.Toast.makeText(this@MainActivity, "Exported Excel file", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private var hidPicker by mutableStateOf(false)
    private var resendEventId by mutableStateOf<Long?>(null)

    private val hidPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) HidSender.start(this) }

    /** Registers the phone as a Bluetooth keyboard (asks for the Bluetooth permission if needed). */
    private fun ensureHid() {
        if (Build.VERSION.SDK_INT >= 31 &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) hidPermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
        else HidSender.start(this)
    }

    private val authLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        val action = pendingAuthAction
        pendingAuthAction = null
        if (res.resultCode == Activity.RESULT_OK) {
            action?.invoke()
        }
    }

    private fun isDeviceSecure(): Boolean {
        val km = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        return km?.isDeviceSecure == true
    }

    // ---- location (geofence) tracking via platform LocationManager (no Play Services) ----
    private var lastLocation: Location? = null

    private val locationPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        startLocationUpdates()
        publishLocation()
    }

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            val prev = lastLocation
            // Prefer the freshest fix; keep a mock fix flagged until a genuine one arrives.
            if (prev == null || location.time >= prev.time || isMock(location)) lastLocation = location
            publishLocation()
        }
        override fun onProviderEnabled(provider: String) = publishLocation()
        override fun onProviderDisabled(provider: String) = publishLocation()
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    private val locationModeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = publishLocation()
    }

    private fun hasLocationPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun isMock(l: Location): Boolean =
        if (Build.VERSION.SDK_INT >= 31) l.isMock else @Suppress("DEPRECATION") l.isFromMockProvider

    private fun requestLocationPermission() {
        locationPermLauncher.launch(
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        )
    }

    private fun openLocationSettings() {
        runCatching { startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
    }

    /** Opens turn-by-turn navigation to the event's geofence centre: Google Maps, then any maps app, then browser. */
    private fun navigateToEvent(event: EventEntity) {
        val lat = event.latitude ?: return
        val lon = event.longitude ?: return
        val attempts = listOf(
            Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=$lat,$lon")).setPackage("com.google.android.apps.maps"),
            Intent(Intent.ACTION_VIEW, Uri.parse("geo:$lat,$lon?q=$lat,$lon")),
            Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/dir/?api=1&destination=$lat,$lon&travelmode=driving"))
        )
        val launched = attempts.any { runCatching { startActivity(it) }.isSuccess }
        if (!launched) android.widget.Toast.makeText(this, "No maps app available", android.widget.Toast.LENGTH_SHORT).show()
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        if (!hasLocationPermission()) return
        val lm = getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
        runCatching { lm.removeUpdates(locationListener) }
        listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).forEach { p ->
            if (runCatching { lm.isProviderEnabled(p) }.getOrDefault(false)) {
                runCatching { lm.requestLocationUpdates(p, 2000L, 0f, locationListener, Looper.getMainLooper()) }
                runCatching { lm.getLastKnownLocation(p) }.getOrNull()?.let {
                    val prev = lastLocation
                    if (prev == null || it.time > prev.time) lastLocation = it
                }
            }
        }
        publishLocation()
    }

    private fun publishLocation() {
        val lm = getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        val enabled = lm?.let { LocationManagerCompat.isLocationEnabled(it) } == true
        val perm = hasLocationPermission()
        val loc = if (perm && enabled) lastLocation else null
        vm.updateLocationState(
            DeviceLocationState(
                hasPermission = perm,
                isLocationEnabled = enabled,
                latitude = loc?.latitude,
                longitude = loc?.longitude,
                isMock = loc?.let(::isMock) == true
            )
        )
    }

    @Suppress("DEPRECATION")
    private fun requireDeviceAuth(title: String, description: String, onMissingLock: () -> Unit, onAuthenticated: () -> Unit) {
        val km = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        val intent = km?.createConfirmDeviceCredentialIntent(title, description)
        if (intent != null) {
            pendingAuthAction = onAuthenticated
            authLauncher.launch(intent)
        } else {
            onMissingLock()
        }
    }

    private fun export(event: EventEntity) {
        exportEventId = event.id
        val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
        val safe = event.title.replace(Regex("[^A-Za-z0-9]+"), "_").trim('_').ifEmpty { "event" }
        exportLauncher.launch("${safe}_$stamp.xlsx")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)

        setContent {
            AppTheme {
                val route by vm.route.collectAsStateWithLifecycle()
                val scan by vm.scan.collectAsStateWithLifecycle()
                val active by vm.activeEvents.collectAsStateWithLifecycle()
                val past by vm.pastEvents.collectAsStateWithLifecycle()
                val employees by vm.employees.collectAsStateWithLifecycle()
                val adminCards by vm.adminCards.collectAsStateWithLifecycle()
                val templates by vm.templates.collectAsStateWithLifecycle()
                val unlockPromptOpen by vm.unlockPromptOpen.collectAsStateWithLifecycle()
                val unlockStatus by vm.unlockStatus.collectAsStateWithLifecycle()
                val enrollingAdminCard by vm.enrollingAdminCard.collectAsStateWithLifecycle()
                val locationState by vm.locationState.collectAsStateWithLifecycle()

                var missingLockDialogMessage by remember { mutableStateOf<String?>(null) }
                val hasAdminCards = remember(employees, adminCards) {
                    adminCards.isNotEmpty() || employees.any { it.isAdmin }
                }

                val context = LocalContext.current
                var hasCamera by remember {
                    mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
                }
                val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasCamera = it }
                LaunchedEffect(route is Route.Scanner || route is Route.CreateTemplate) {
                    if ((route is Route.Scanner || route is Route.CreateTemplate) && !hasCamera) permLauncher.launch(Manifest.permission.CAMERA)
                }

                when (val r = route) {
                    Route.Home -> HomeScreen(
                        active = active,
                        past = past,
                        employees = employees,
                        adminCards = adminCards,
                        templates = templates,
                        enrollingAdminCard = enrollingAdminCard,
                        locationState = locationState,
                        onRequestLocationPermission = ::requestLocationPermission,
                        onOpenLocationSettings = ::openLocationSettings,
                        onNavigateToEvent = ::navigateToEvent,
                        nfcAvailable = nfcAdapter?.isEnabled == true,
                        onOpen = { vm.go(Route.Detail(it)) },
                        onCreate = { vm.go(Route.Create) },
                        onCreateTemplate = { vm.go(Route.CreateTemplate) },
                        onDeleteTemplate = { vm.deleteTemplate(it) },
                        onRemoveEmployee = { uid ->
                            val isEmpAdmin = employees.any { it.nfcUid == uid && it.isAdmin }
                            if (isEmpAdmin) {
                                requireDeviceAuth(
                                    title = "Remove Admin Employee",
                                    description = "Verify device lock to remove an employee with Admin card access",
                                    onMissingLock = {
                                        missingLockDialogMessage = "Please set up a Device Screen Lock (PIN, pattern, or biometric) in Android Settings to remove Admin cards."
                                    },
                                    onAuthenticated = { vm.removeEmployee(uid) }
                                )
                            } else {
                                vm.removeEmployee(uid)
                            }
                        },
                        onToggleEmployeeAdmin = { uid, isAdmin ->
                            requireDeviceAuth(
                                title = "Manage Admin Access",
                                description = "Verify device lock to update Admin card role",
                                onMissingLock = {
                                    missingLockDialogMessage = "Please set up a Device Screen Lock (PIN, pattern, or biometric) in Android Settings to manage Admin cards."
                                },
                                onAuthenticated = { vm.toggleEmployeeAdmin(uid, isAdmin) }
                            )
                        },
                        onStartEnrollAdminCard = {
                            requireDeviceAuth(
                                title = "Add Admin NFC Card",
                                description = "Verify device lock to enroll a new Admin NFC card",
                                onMissingLock = {
                                    missingLockDialogMessage = "Please set up a Device Screen Lock (PIN, pattern, or biometric) in Android Settings to enroll Admin cards."
                                },
                                onAuthenticated = { vm.startEnrollingAdminCard() }
                            )
                        },
                        onCancelEnrollAdminCard = vm::cancelEnrollingAdminCard,
                        onRemoveAdminCard = { uid ->
                            requireDeviceAuth(
                                title = "Remove Admin NFC Card",
                                description = "Verify device lock to remove this Admin card",
                                onMissingLock = {
                                    missingLockDialogMessage = "Please set up a Device Screen Lock (PIN, pattern, or biometric) in Android Settings to remove Admin cards."
                                },
                                onAuthenticated = { vm.removeAdminCard(uid) }
                            )
                        }
                    )
                    Route.Create -> {
                        BackHandler { vm.go(Route.Home) }
                        CreateEventScreen(
                            templates = templates,
                            locationState = locationState,
                            onRequestLocationPermission = ::requestLocationPermission,
                            onOpenLocationSettings = ::openLocationSettings,
                            onRefreshLocation = { startLocationUpdates(); publishLocation() },
                            onBack = { vm.go(Route.Home) },
                            onCreateTemplate = { vm.go(Route.CreateTemplate) },
                            onCreate = vm::createEvent
                        )
                    }
                    Route.CreateTemplate -> {
                        BackHandler { vm.go(Route.Home) }
                        CreateTemplateScreen(
                            hasCamera = hasCamera,
                            onBack = { vm.go(Route.Home) },
                            onSaveTemplate = { name, leftAnchor, rightAnchor, isPortrait, relYMin, relYMax ->
                                vm.createTemplate(name, leftAnchor, rightAnchor, isPortrait, relYMin, relYMax)
                            }
                        )
                    }
                    is Route.Detail -> {
                        BackHandler { vm.go(Route.Home) }
                        val event by remember(r.eventId) { vm.event(r.eventId) }.collectAsStateWithLifecycle(null)
                        val rows by remember(r.eventId) { vm.attendance(r.eventId) }.collectAsStateWithLifecycle(emptyList())
                        EventDetailScreen(
                            event, rows,
                            locationState = locationState,
                            onRequestLocationPermission = ::requestLocationPermission,
                            onOpenLocationSettings = ::openLocationSettings,
                            onBack = { vm.go(Route.Home) },
                            onScan = { mode ->
                                val tplId = event?.templateId
                                if (tplId == null && templates.isEmpty()) {
                                    missingLockDialogMessage = "Please create an ID Card Template in the Data tab first so new employee cards can be registered."
                                } else if (isDeviceSecure() || hasAdminCards) {
                                    vm.go(Route.Scanner(r.eventId, mode))
                                } else {
                                    missingLockDialogMessage = "Before starting the public scanner, please configure a Device Screen Lock (PIN, pattern, or biometric) in Android Settings or register an Admin NFC card in the Data tab so visitors cannot exit the scanner."
                                }
                            },
                            onExport = { event?.let(::export) },
                            onResendHid = { resendEventId = r.eventId; ensureHid() },
                            onEnd = { vm.endEvent(r.eventId) },
                            onReopen = { vm.reopenEvent(r.eventId) },
                            onRemove = { vm.removeEntries(r.eventId, it) }
                        )
                    }
                    is Route.Scanner -> {
                        val event by remember(r.eventId) { vm.event(r.eventId) }.collectAsStateWithLifecycle(null)
                        val eventTemplate = remember(event, templates) {
                            templates.find { it.id == event?.templateId } ?: templates.firstOrNull()
                        }
                        Box(Modifier.fillMaxSize()) {
                        ScannerScreen(
                            event, r.mode, scan,
                            locationState = locationState,
                            nfcAvailable = nfcAdapter?.isEnabled == true, hasCamera = hasCamera,
                            unlockPromptOpen = unlockPromptOpen, unlockStatus = unlockStatus,
                            hasDeviceLock = isDeviceSecure(), hasAdminCards = hasAdminCards,
                            template = eventTemplate,
                            onOcrCandidate = vm::onOcrCandidate,
                            onCancelRegistration = vm::cancelRegistration,
                            onRequestUnlock = vm::openUnlockPrompt,
                            onCancelUnlock = vm::closeUnlockPrompt,
                            onTriggerDeviceUnlock = {
                                requireDeviceAuth(
                                    title = "Exit Scanner",
                                    description = "Authenticate to exit the public scanner",
                                    onMissingLock = {},
                                    onAuthenticated = { vm.onDeviceUnlockSuccess() }
                                )
                            }
                        )
                        if (event?.hidEnabled == true) {
                            val hs by HidSender.state.collectAsStateWithLifecycle()
                            val st by vm.hidStatus.collectAsStateWithLifecycle()
                            LaunchedEffect(Unit) { ensureHid() }
                            Surface(
                                Modifier.align(Alignment.BottomCenter).padding(12.dp),
                                shape = RoundedCornerShape(12.dp), tonalElevation = 6.dp, shadowElevation = 4.dp
                            ) {
                                Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.padding(end = 8.dp)) {
                                        Text(
                                            when {
                                                !hs.supported -> "Keyboard (HID) not supported on this phone"
                                                hs.connected -> "Keyboard: ${hs.connectedName}"
                                                else -> "Keyboard: not connected"
                                            }, fontSize = 12.sp
                                        )
                                        if (st.text.isNotEmpty()) Text(
                                            "${st.text} · ${st.sent}/${st.total} rows delivered", fontSize = 11.sp,
                                            color = if (st.ok == false) Color(0xFFB3261E) else Color.Unspecified
                                        )
                                    }
                                    TextButton({ ensureHid(); hidPicker = true }) { Text(if (hs.connected) "Change" else "Connect") }
                                }
                            }
                        }
                        }
                    }
                }

                if (hidPicker) {
                    val hs by HidSender.state.collectAsStateWithLifecycle()
                    val devices = remember(hidPicker, hs) { HidSender.pairedDevices(this@MainActivity) }
                    AlertDialog(
                        onDismissRequest = { hidPicker = false },
                        title = { Text("Connect to a PC") },
                        text = {
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .verticalScroll(rememberScrollState())
                            ) {
                                Text("Pair the phone with the computer in Bluetooth settings, then pick it here (or connect from the computer side).", fontSize = 13.sp)
                                Spacer(Modifier.height(8.dp))
                                if (devices.isEmpty()) {
                                    Text("No paired devices found.", fontSize = 13.sp, color = Color.Gray)
                                }
                                devices.forEach { d ->
                                    TextButton(
                                        onClick = {
                                            HidSender.connect(d)
                                            hidPicker = false
                                        },
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(HidSender.deviceName(d), modifier = Modifier.fillMaxWidth())
                                    }
                                }
                            }
                        },
                        confirmButton = {
                            TextButton({
                                hidPicker = false
                                runCatching { startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
                            }) { Text("Bluetooth settings") }
                        },
                        dismissButton = { TextButton({ hidPicker = false }) { Text("Close") } }
                    )
                }

                resendEventId?.let { evId ->
                    var from by remember { mutableStateOf("1") }
                    AlertDialog(
                        onDismissRequest = { resendEventId = null },
                        title = { Text("Resend rows to PC") },
                        text = {
                            Column {
                                Text("Rows saved: ${vm.hidRowCount(evId)}. Put the cursor in the first empty cell in Excel, then choose the row number to resend from.", fontSize = 13.sp)
                                OutlinedTextField(
                                    from, { from = it.filter(Char::isDigit).take(6) },
                                    label = { Text("From row #") }, singleLine = true
                                )
                            }
                        },
                        confirmButton = {
                            TextButton({
                                val n = from.toIntOrNull() ?: 1
                                resendEventId = null
                                lifecycleScope.launch { toast(vm.resendHid(evId, n)) }
                            }) { Text("Resend") }
                        },
                        dismissButton = { TextButton({ resendEventId = null }) { Text("Cancel") } }
                    )
                }

                missingLockDialogMessage?.let { msg ->
                    AlertDialog(
                        onDismissRequest = { missingLockDialogMessage = null },
                        title = { Text("Security Setup Required") },
                        text = { Text(msg) },
                        confirmButton = {
                            TextButton({
                                missingLockDialogMessage = null
                                runCatching { startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS)) }
                            }) { Text("Open Settings") }
                        },
                        dismissButton = {
                            TextButton({ missingLockDialogMessage = null }) { Text("Close") }
                        }
                    )
                }
            }
        }
    }

    // NFC foreground reader mode: only this activity receives tags while resumed.
    override fun onResume() {
        super.onResume()
        nfcAdapter?.enableReaderMode(
            this, this,
            NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or
                NfcAdapter.FLAG_READER_NFC_F or NfcAdapter.FLAG_READER_NFC_V or
                NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS,
            null
        )
        ContextCompat.registerReceiver(
            this, locationModeReceiver, IntentFilter(LocationManager.MODE_CHANGED_ACTION),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        startLocationUpdates()
        publishLocation()
    }

    private fun toast(msg: String) =
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_LONG).show()

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) HidSender.stop(this)
    }

    override fun onPause() {
        super.onPause()
        nfcAdapter?.disableReaderMode(this)
        runCatching { unregisterReceiver(locationModeReceiver) }
        runCatching { (getSystemService(Context.LOCATION_SERVICE) as? LocationManager)?.removeUpdates(locationListener) }
    }

    /** Runs on a binder thread. Tags are only acted on while a scanner is open. */
    override fun onTagDiscovered(tag: Tag) {
        val uid = tag.id.joinToString("") { "%02X".format(it) }
        vm.onTagScanned(uid)
    }
}
