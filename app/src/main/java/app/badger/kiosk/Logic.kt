package app.badger.kiosk

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class ScanMode { ENTRY, EXIT }

/** Predefined starting points; every message stays editable when creating an event. */
enum class EventTemplate(
    val label: String,
    val defaultTitle: String,
    val entryExit: Boolean,
    val success: String,
    val already: String
) {
    GIFT("Gift collection", "Gift Collection", false, "Please collect your gift 🎁", "Gift already collected"),
    WELCOME_KIT("Welcome kit", "Welcome Kit Collection", false, "Please collect your welcome kit", "Welcome kit already collected"),
    ATTENDANCE("Attendance", "Attendance", true, "Your attendance is marked", "Already checked in"),
    CUSTOM("Custom", "", false, "You're all set", "Already done");

    companion object {
        const val IDLE = "Tap your ID card on the back"
        const val EXIT_SUCCESS = "Thank you, see you again"
        const val ALREADY_EXITED = "Already checked out"
        const val NO_ENTRY = "No check-in found for this card"
        const val NOT_REGISTERED = "Card not registered. Please check in first"
    }
}

/** What the scanner shows after a tap. */
data class ScanResult(val ok: Boolean, val title: String, val subtitle: String)

sealed interface TapOutcome {
    /** Unknown card in the Entry scanner: needs ID-card registration. */
    data object NeedsRegistration : TapOutcome
    data class Show(val result: ScanResult, val write: AttendanceEntity? = null) : TapOutcome
}

object TapRules {
    private val timeFmt get() = SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault())
    fun fmt(t: Long?): String = t?.let { timeFmt.format(Date(it)) } ?: ""

    /** Pure decision for one tap; [employee] and [existing] come from the database. */
    fun decide(
        event: EventEntity, mode: ScanMode, uid: String,
        employee: EmployeeEntity?, existing: AttendanceEntity?, now: Long
    ): TapOutcome {
        if (mode == ScanMode.ENTRY) {
            if (employee == null) return TapOutcome.NeedsRegistration
            return checkIn(event, employee, existing, now)
        }
        if (employee == null) return TapOutcome.Show(ScanResult(false, event.msgNotRegistered, ""))
        return when {
            existing == null -> TapOutcome.Show(ScanResult(false, event.msgNoEntry, employee.name))
            existing.checkOutAt != null ->
                TapOutcome.Show(ScanResult(false, event.msgAlreadyExited, "${employee.name}\n${fmt(existing.checkOutAt)}"))
            else -> TapOutcome.Show(
                ScanResult(true, "Goodbye, ${employee.name}!", event.msgExitSuccess),
                existing.copy(checkOutAt = now)
            )
        }
    }

    fun checkIn(event: EventEntity, employee: EmployeeEntity, existing: AttendanceEntity?, now: Long): TapOutcome =
        if (existing != null)
            TapOutcome.Show(ScanResult(false, event.msgAlready, "${employee.name}\n${fmt(existing.checkInAt)}"))
        else TapOutcome.Show(
            ScanResult(true, "Welcome, ${employee.name}!", event.msgSuccess),
            AttendanceEntity(event.id, employee.nfcUid, now)
        )

    // Prefix formula-leading chars to prevent CSV injection in spreadsheets.
    private fun csv(v: String): String {
        val safe = if (v.firstOrNull() in listOf('=', '+', '-', '@')) "'$v" else v
        return "\"" + safe.replace("\"", "\"\"") + "\""
    }

    /** Same column names for every event; `checkOutAt` is appended only for entry/exit events. */
    fun buildCsv(event: EventEntity, rows: List<AttendanceRow>): String {
        val f = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        fun t(v: Long?) = v?.let { f.format(Date(it)) } ?: ""
        return buildString {
            // The NFC UID is sensitive and intentionally NOT exported.
            appendLine(if (event.hasExit) "employeeName,checkInAt,checkOutAt" else "employeeName,checkInAt")
            rows.forEach {
                append(csv(it.name)).append(',').append(t(it.checkInAt))
                if (event.hasExit) append(',').append(t(it.checkOutAt))
                appendLine()
            }
        }
    }
}
