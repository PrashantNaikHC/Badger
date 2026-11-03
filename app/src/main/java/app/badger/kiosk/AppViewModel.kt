package app.badger.kiosk

import android.app.Application
import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface Route {
    data object Home : Route
    data object Create : Route
    data object CreateTemplate : Route
    data class Detail(val eventId: Long) : Route
    data class Scanner(val eventId: Long, val mode: ScanMode) : Route
}

sealed interface ScanState {
    data object Idle : ScanState
    data class Registering(val uid: String) : ScanState
    data class Result(val result: ScanResult) : ScanState
}

enum class UnlockStatus { IDLE, REJECTED }

/** Last HID delivery result shown on the scanner screen. */
data class HidUiStatus(val text: String = "", val ok: Boolean? = null, val sent: Int = 0, val total: Int = 0)

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val dao = AppDatabase.get(app).dao()
    private val prefs = app.getSharedPreferences("badger_prefs", Context.MODE_PRIVATE)
    private val mutex = Mutex()

    private val _route = MutableStateFlow<Route>(Route.Home)
    val route: StateFlow<Route> = _route

    private val _scan = MutableStateFlow<ScanState>(ScanState.Idle)
    val scan: StateFlow<ScanState> = _scan

    private val _unlockPromptOpen = MutableStateFlow(false)
    val unlockPromptOpen: StateFlow<Boolean> = _unlockPromptOpen

    private val _unlockStatus = MutableStateFlow(UnlockStatus.IDLE)
    val unlockStatus: StateFlow<UnlockStatus> = _unlockStatus

    private val _enrollingAdminCard = MutableStateFlow(false)
    val enrollingAdminCard: StateFlow<Boolean> = _enrollingAdminCard

    private val hidMutex = Mutex()
    private val _hidStatus = MutableStateFlow(HidUiStatus())
    val hidStatus: StateFlow<HidUiStatus> = _hidStatus

    private val _locationState = MutableStateFlow(DeviceLocationState())
    val locationState: StateFlow<DeviceLocationState> = _locationState

    val activeEvents = dao.observeEvents(EventStatus.ACTIVE)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val pastEvents = dao.observeEvents(EventStatus.ENDED)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val employees = dao.observeEmployees()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val adminCards = dao.observeAdminCards()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val templates = dao.observeTemplates()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        val savedEventId = prefs.getLong(KEY_SCANNER_EVENT_ID, -1L)
        val savedMode = prefs.getString(KEY_SCANNER_MODE, null)
            ?.let { runCatching { ScanMode.valueOf(it) }.getOrNull() }
        if (savedEventId != -1L && savedMode != null) {
            viewModelScope.launch {
                val ev = dao.getEvent(savedEventId)
                if (ev != null && ev.status == EventStatus.ACTIVE) {
                    _route.value = Route.Scanner(savedEventId, savedMode)
                } else {
                    clearSavedScanner()
                }
            }
        }
    }

    fun updateLocationState(state: DeviceLocationState) {
        _locationState.value = state
        val r = _route.value as? Route.Scanner ?: return
        if (_scan.value is ScanState.Registering) {
            viewModelScope.launch {
                val ev = dao.getEvent(r.eventId) ?: return@launch
                if (!isEventAllowed(ev)) {
                    resetNow()
                }
            }
        }
    }

    fun isEventAllowed(event: EventEntity): Boolean =
        TapRules.evaluateGeofence(event, _locationState.value).isAllowed

    fun event(id: Long): Flow<EventEntity?> = dao.observeEvent(id)
    fun attendance(id: Long): Flow<List<AttendanceRow>> = dao.observeAttendance(id)

    private var resetJob: Job? = null

    private fun saveScanner(eventId: Long, mode: ScanMode) {
        prefs.edit().putLong(KEY_SCANNER_EVENT_ID, eventId).putString(KEY_SCANNER_MODE, mode.name).apply()
    }

    private fun clearSavedScanner() {
        prefs.edit().remove(KEY_SCANNER_EVENT_ID).remove(KEY_SCANNER_MODE).apply()
    }

    // ---- navigation ----
    fun go(r: Route) {
        resetNow()
        _unlockPromptOpen.value = false
        _unlockStatus.value = UnlockStatus.IDLE
        _enrollingAdminCard.value = false
        if (r is Route.Scanner) {
            saveScanner(r.eventId, r.mode)
        } else {
            clearSavedScanner()
        }
        _route.value = r
    }

    // ---- scanner unlock & admin cards ----
    fun openUnlockPrompt() {
        _unlockStatus.value = UnlockStatus.IDLE
        _unlockPromptOpen.value = true
    }

    fun closeUnlockPrompt() {
        _unlockStatus.value = UnlockStatus.IDLE
        _unlockPromptOpen.value = false
    }

    fun onDeviceUnlockSuccess() {
        val r = _route.value as? Route.Scanner ?: return
        feedback(true)
        go(Route.Detail(r.eventId))
    }

    fun startEnrollingAdminCard() {
        _enrollingAdminCard.value = true
    }

    fun cancelEnrollingAdminCard() {
        _enrollingAdminCard.value = false
    }

    fun toggleEmployeeAdmin(uid: String, isAdmin: Boolean) = viewModelScope.launch {
        dao.setEmployeeAdmin(uid, isAdmin)
    }

    fun removeAdminCard(uid: String) = viewModelScope.launch {
        dao.deleteAdminCard(uid)
    }

    // ---- employees ----
    fun removeEmployee(uid: String) = viewModelScope.launch { dao.deleteEmployee(uid) }

    // ---- templates ----
    fun createTemplate(
        name: String,
        leftAnchor: String?,
        rightAnchor: String?,
        isPortrait: Boolean = true,
        relativeYMin: Float? = null,
        relativeYMax: Float? = null
    ) = viewModelScope.launch {
        dao.insertTemplate(
            IdCardTemplateEntity(
                name = name,
                leftAnchor = leftAnchor,
                rightAnchor = rightAnchor,
                isPortrait = isPortrait,
                relativeYMin = relativeYMin,
                relativeYMax = relativeYMax
            )
        )
        _route.value = Route.Home
    }

    fun deleteTemplate(id: Long) = viewModelScope.launch { dao.deleteTemplate(id) }

    suspend fun getTemplate(id: Long): IdCardTemplateEntity? = dao.getTemplate(id)

    // ---- events ----
    fun createEvent(e: EventEntity) {
        viewModelScope.launch {
            val id = dao.insertEvent(e)
            _route.value = Route.Detail(id)
        }
    }

    fun endEvent(id: Long) = viewModelScope.launch {
        val ev = dao.getEvent(id) ?: return@launch
        if (!isEventAllowed(ev)) return@launch
        dao.setStatus(id, EventStatus.ENDED, System.currentTimeMillis())
        if (ev.hidEnabled) hidMutex.withLock {
            val total = hidRows(id).size
            val ok = HidSender.state.value.connected && HidSender.type("TOTAL\t$total\n")
            _hidStatus.value = _hidStatus.value.copy(
                text = if (ok) "Total row ($total) sent ✓" else "Total row NOT sent", ok = ok
            )
        }
    }

    fun reopenEvent(id: Long) = viewModelScope.launch {
        val ev = dao.getEvent(id) ?: return@launch
        if (!isEventAllowed(ev)) return@launch
        dao.setStatus(id, EventStatus.ACTIVE, null)
    }

    /** Entry removal is only allowed while the event is active and within its geofence. */
    fun removeEntries(eventId: Long, uids: Collection<String>) {
        viewModelScope.launch {
            val ev = dao.getEvent(eventId) ?: return@launch
            if (ev.status != EventStatus.ACTIVE || !isEventAllowed(ev)) return@launch
            dao.deleteAttendance(eventId, uids.toList())
        }
    }

    suspend fun buildXlsx(eventId: Long): ByteArray = withContext(Dispatchers.IO) {
        val e = dao.getEvent(eventId) ?: return@withContext ByteArray(0)
        if (!isEventAllowed(e)) return@withContext ByteArray(0)
        TapRules.buildXlsx(e, dao.getAttendance(eventId))
    }

    suspend fun buildCsv(eventId: Long): String = withContext(Dispatchers.IO) {
        val e = dao.getEvent(eventId) ?: return@withContext ""
        if (!isEventAllowed(e)) return@withContext ""
        TapRules.buildCsv(e, dao.getAttendance(eventId))
    }

    // ---- scanning ----
    /** Called from the NFC reader callback (binder thread) with the tag UID in hex. */
    fun onTagScanned(uid: String) {
        viewModelScope.launch {
            mutex.withLock {
                if (_enrollingAdminCard.value) {
                    val existingEmp = dao.findEmployee(uid)
                    if (existingEmp != null) {
                        dao.setEmployeeAdmin(uid, true)
                    } else {
                        val suffix = uid.takeLast(4)
                        dao.upsertAdminCard(AdminCardEntity(uid, "Admin Card (••$suffix)", System.currentTimeMillis()))
                    }
                    feedback(true)
                    _enrollingAdminCard.value = false
                    return@withLock
                }

                val r = _route.value as? Route.Scanner ?: return@withLock

                if (_unlockPromptOpen.value) {
                    if (dao.isAdminUid(uid)) {
                        feedback(true)
                        go(Route.Detail(r.eventId))
                    } else {
                        feedback(false)
                        _unlockStatus.value = UnlockStatus.REJECTED
                    }
                    return@withLock
                }

                // Ignore taps while a registration is in progress or a result is shown.
                if (_scan.value != ScanState.Idle) return@withLock
                val event = dao.getEvent(r.eventId) ?: return@withLock
                if (event.status != EventStatus.ACTIVE) return@withLock
                if (!isEventAllowed(event)) return@withLock
                val outcome = TapRules.decide(
                    event, r.mode, uid, dao.findEmployee(uid), dao.findAttendance(event.id, uid), System.currentTimeMillis()
                )
                when (outcome) {
                    TapOutcome.NeedsRegistration -> {
                        _scan.value = ScanState.Registering(uid)
                        scheduleReset(REGISTRATION_TIMEOUT_MS)
                    }
                    is TapOutcome.Show -> {
                        outcome.write?.let {
                            dao.upsertAttendance(it)
                            val name = dao.findEmployee(uid)?.name
                            if (name != null) emitHid(event, name, r.mode, it.checkOutAt ?: it.checkInAt)
                        }
                        show(outcome.result)
                    }
                }
            }
        }
    }

    /** Called per camera frame with the name read from the ID card (or null). Saved immediately. */
    fun onOcrCandidate(name: String?) {
        val s = _scan.value as? ScanState.Registering ?: return
        val r = _route.value as? Route.Scanner ?: return
        if (name == null) return
        viewModelScope.launch {
            mutex.withLock {
                if (_scan.value != s) return@withLock
                val event = dao.getEvent(r.eventId) ?: return@withLock
                if (!isEventAllowed(event)) return@withLock
                val existing = dao.findEmployee(s.uid)
                val wasStandaloneAdmin = dao.isAdminUid(s.uid)
                val emp = EmployeeEntity(s.uid, name, System.currentTimeMillis(), isAdmin = existing?.isAdmin == true || wasStandaloneAdmin)
                dao.upsertEmployee(emp)
                if (wasStandaloneAdmin) dao.deleteAdminCard(s.uid)
                val outcome = TapRules.checkIn(event, emp, dao.findAttendance(event.id, s.uid), System.currentTimeMillis()) as TapOutcome.Show
                outcome.write?.let {
                    dao.upsertAttendance(it)
                    emitHid(event, emp.name, r.mode, it.checkOutAt ?: it.checkInAt)
                }
                show(outcome.result)
            }
        }
    }

    // ---- HID keyboard output ----
    private fun hidRows(eventId: Long): List<String> =
        prefs.getString("hid_rows_$eventId", "")!!.split("\n").filter { it.isNotEmpty() }

    private fun hidSentSet(eventId: Long): MutableSet<String> =
        prefs.getString("hid_ok_$eventId", "")!!.split(",").filter { it.isNotEmpty() }.toMutableSet()

    /** One spreadsheet cell: no tabs/newlines, and formula-leading chars neutralised. */
    private fun hidCell(v: String): String {
        val t = v.replace(Regex("[\\t\\r\\n]+"), " ").trim()
        return if (t.firstOrNull() in listOf('=', '+', '-', '@')) "'$t" else t
    }

    /** Stores the row (so it can be resent later) and types it on the connected host. */
    private fun emitHid(event: EventEntity, name: String, mode: ScanMode, ts: Long) {
        if (!event.hidEnabled) return
        viewModelScope.launch {
            hidMutex.withLock {
                val rows = hidRows(event.id)
                val n = rows.size + 1
                val time = SimpleDateFormat("dd-MMM-yyyy HH:mm:ss", Locale.US).format(Date(ts))
                val cells = mutableListOf(n.toString(), hidCell(name), time)
                if (event.hasExit) cells += if (mode == ScanMode.EXIT) "Exit" else "Entry"
                val line = cells.joinToString("\t")
                prefs.edit().putString("hid_rows_${event.id}", (rows + line).joinToString("\n")).apply()
                typeHidLine(event.id, n, line)
            }
        }
    }

    private suspend fun typeHidLine(eventId: Long, n: Int, line: String): Boolean {
        val connected = HidSender.state.value.connected
        val ok = connected && HidSender.type(line + "\n")
        val set = hidSentSet(eventId)
        if (ok) {
            set.add(n.toString())
            prefs.edit().putString("hid_ok_$eventId", set.joinToString(",")).apply()
        }
        _hidStatus.value = HidUiStatus(
            text = when {
                ok -> "Row $n sent ✓"
                !connected -> "Row $n NOT sent: not connected"
                else -> "Row $n send failed ✗"
            },
            ok = ok, sent = set.size, total = hidRows(eventId).size
        )
        return ok
    }

    /** Re-types stored rows from [from] to the end. Returns a message for the user. */
    suspend fun resendHid(eventId: Long, from: Int): String = hidMutex.withLock {
        val rows = hidRows(eventId)
        if (rows.isEmpty()) return@withLock "No rows to resend"
        if (!HidSender.state.value.connected) return@withLock "Not connected to a PC"
        val start = from.coerceAtLeast(1)
        if (start > rows.size) return@withLock "Row $start doesn't exist (last is ${rows.size})"
        var sent = 0
        for (i in start..rows.size) {
            if (typeHidLine(eventId, i, rows[i - 1])) sent++ else return@withLock "Stopped at row $i: send failed"
        }
        "Resent $sent row(s)"
    }

    fun hidRowCount(eventId: Long): Int = hidRows(eventId).size

    fun cancelRegistration() { resetNow() }

    private fun show(result: ScanResult) {
        feedback(result.ok)
        _scan.value = ScanState.Result(result)
        scheduleReset(RESULT_MS)
    }

    private fun scheduleReset(delayMs: Long) {
        resetJob?.cancel()
        resetJob = viewModelScope.launch { delay(delayMs); resetNow() }
    }

    private fun resetNow() {
        resetJob?.cancel()
        _scan.value = ScanState.Idle
    }

    @Suppress("DEPRECATION")
    private fun feedback(success: Boolean) {
        val ctx = getApplication<Application>()
        runCatching {
            val tg = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100)
            tg.startTone(if (success) ToneGenerator.TONE_PROP_ACK else ToneGenerator.TONE_SUP_ERROR, 300)
            viewModelScope.launch { delay(600); tg.release() }
        }
        runCatching {
            val v = ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            val effect = if (success) VibrationEffect.createOneShot(200, VibrationEffect.DEFAULT_AMPLITUDE)
            else VibrationEffect.createWaveform(longArrayOf(0, 150, 100, 150, 100, 150), -1)
            v.vibrate(effect)
        }
    }

    companion object {
        const val RESULT_MS = 4000L
        const val REGISTRATION_TIMEOUT_MS = 45000L
        private const val KEY_SCANNER_EVENT_ID = "scanner_event_id"
        private const val KEY_SCANNER_MODE = "scanner_mode"
    }
}
