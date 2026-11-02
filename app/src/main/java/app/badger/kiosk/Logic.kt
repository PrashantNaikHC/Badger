package app.badger.kiosk

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.*

enum class ScanMode { ENTRY, EXIT }

/** Current device location snapshot used to evaluate event geofences. */
data class DeviceLocationState(
    val hasPermission: Boolean = false,
    val isLocationEnabled: Boolean = false,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val isMock: Boolean = false
)

/** Result of evaluating an event's geofence lock against the device's current location. */
enum class GeofenceStatus {
    NOT_LOCKED,
    INSIDE,
    OUTSIDE_BOUNDS,
    MOCK_DETECTED,
    PERMISSION_REQUIRED,
    LOCATION_DISABLED,
    ACQUIRING;

    val isAllowed: Boolean
        get() = this == NOT_LOCKED || this == INSIDE

    val alertTitle: String
        get() = when (this) {
            NOT_LOCKED, INSIDE -> ""
            MOCK_DETECTED -> "Mock location detected"
            OUTSIDE_BOUNDS -> "Outside the bounds of event"
            PERMISSION_REQUIRED -> "Outside the bounds of event (Location permission required)"
            LOCATION_DISABLED -> "Outside the bounds of event (Location services off)"
            ACQUIRING -> "Outside the bounds of event (Verifying location…)"
        }
}

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
    val GEOFENCE_RADIUS_OPTIONS = listOf(50f, 100f, 250f, 500f)
    const val DEFAULT_GEOFENCE_RADIUS_M = 100f
    private const val EARTH_RADIUS_M = 6371000.0

    private val timeFmt get() = SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault())
    fun fmt(t: Long?): String = t?.let { timeFmt.format(Date(it)) } ?: ""

    /** Great-circle Haversine distance in meters between two WGS84 coordinates. */
    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val rLat1 = Math.toRadians(lat1)
        val rLat2 = Math.toRadians(lat2)
        val a = sin(dLat / 2).pow(2) + cos(rLat1) * cos(rLat2) * sin(dLon / 2).pow(2)
        val c = 2 * atan2(sqrt(a), sqrt((1.0 - a).coerceAtLeast(0.0)))
        return EARTH_RADIUS_M * c
    }

    /** Evaluates whether the device is currently allowed to access a location-locked event. */
    fun evaluateGeofence(event: EventEntity, location: DeviceLocationState): GeofenceStatus {
        if (!event.locationLockEnabled) return GeofenceStatus.NOT_LOCKED
        val targetLat = event.latitude ?: return GeofenceStatus.NOT_LOCKED
        val targetLon = event.longitude ?: return GeofenceStatus.NOT_LOCKED
        val radius = event.radiusMeters ?: DEFAULT_GEOFENCE_RADIUS_M

        if (!location.hasPermission) return GeofenceStatus.PERMISSION_REQUIRED
        if (!location.isLocationEnabled) return GeofenceStatus.LOCATION_DISABLED
        if (location.isMock) return GeofenceStatus.MOCK_DETECTED
        val curLat = location.latitude ?: return GeofenceStatus.ACQUIRING
        val curLon = location.longitude ?: return GeofenceStatus.ACQUIRING

        val distance = distanceMeters(targetLat, targetLon, curLat, curLon)
        return if (distance <= radius) GeofenceStatus.INSIDE else GeofenceStatus.OUTSIDE_BOUNDS
    }

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

    /** Generates rich Excel spreadsheet (.xlsx) with event banner and formatted attendance records. */
    fun buildXlsx(event: EventEntity, rows: List<AttendanceRow>): ByteArray =
        XlsxExporter.build(event, rows)

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

