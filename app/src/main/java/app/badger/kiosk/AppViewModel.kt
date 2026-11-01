package app.badger.kiosk

import android.app.Application
import android.content.Context
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
    fun createTemplate(name: String, leftAnchor: String?, rightAnchor: String?) = viewModelScope.launch {
        dao.insertTemplate(IdCardTemplateEntity(name = name, leftAnchor = leftAnchor, rightAnchor = rightAnchor))
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

    fun endEvent(id: Long) = viewModelScope.launch { dao.setStatus(id, EventStatus.ENDED, System.currentTimeMillis()) }
    fun reopenEvent(id: Long) = viewModelScope.launch { dao.setStatus(id, EventStatus.ACTIVE, null) }

    /** Entry removal is only allowed while the event is active. */
    fun removeEntries(eventId: Long, uids: Collection<String>) {
        viewModelScope.launch {
            if (dao.getEvent(eventId)?.status != EventStatus.ACTIVE) return@launch
            dao.deleteAttendance(eventId, uids.toList())
        }
    }

    suspend fun buildCsv(eventId: Long): String = withContext(Dispatchers.IO) {
        val e = dao.getEvent(eventId) ?: return@withContext ""
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
                val outcome = TapRules.decide(
                    event, r.mode, uid, dao.findEmployee(uid), dao.findAttendance(event.id, uid), System.currentTimeMillis()
                )
                when (outcome) {
                    TapOutcome.NeedsRegistration -> {
                        _scan.value = ScanState.Registering(uid)
                        scheduleReset(REGISTRATION_TIMEOUT_MS)
                    }
                    is TapOutcome.Show -> {
                        outcome.write?.let { dao.upsertAttendance(it) }
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
                val existing = dao.findEmployee(s.uid)
                val wasStandaloneAdmin = dao.isAdminUid(s.uid)
                val emp = EmployeeEntity(s.uid, name, System.currentTimeMillis(), isAdmin = existing?.isAdmin == true || wasStandaloneAdmin)
                dao.upsertEmployee(emp)
                if (wasStandaloneAdmin) dao.deleteAdminCard(s.uid)
                val outcome = TapRules.checkIn(event, emp, dao.findAttendance(event.id, s.uid), System.currentTimeMillis()) as TapOutcome.Show
                outcome.write?.let { dao.upsertAttendance(it) }
                show(outcome.result)
            }
        }
    }

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
