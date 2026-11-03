package app.badger.kiosk

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

/** Global registry: an employee registers once (ID card) and is reusable across all events. */
@Entity(tableName = "employees")
data class EmployeeEntity(
    @PrimaryKey val nfcUid: String,
    val name: String,
    val registeredAt: Long,
    val isAdmin: Boolean = false
)

/** Dedicated admin cards that may or may not be registered as employees. */
@Entity(tableName = "admin_cards")
data class AdminCardEntity(
    @PrimaryKey val nfcUid: String,
    val label: String,
    val addedAt: Long
)

enum class EventType { ONE_TIME, ENTRY_EXIT }
enum class EventStatus { ACTIVE, ENDED }

@Entity(tableName = "id_card_templates")
data class IdCardTemplateEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val leftAnchor: String? = null,
    val rightAnchor: String? = null,
    val isPortrait: Boolean = true,
    val relativeYMin: Float? = null,
    val relativeYMax: Float? = null,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "events")
data class EventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val type: EventType,
    val status: EventStatus = EventStatus.ACTIVE,
    val templateId: Long? = null,
    val createdAt: Long,
    val endedAt: Long? = null,
    // Messages shown on the scanner screens.
    val msgIdle: String,
    val msgSuccess: String,
    val msgAlready: String,
    val msgExitSuccess: String,
    val msgAlreadyExited: String,
    val msgNoEntry: String,
    val msgNotRegistered: String,
    // Location lock (geofence) configuration
    val locationLockEnabled: Boolean = false,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val radiusMeters: Float? = null,
    // Send each successful scan to a connected Bluetooth host as keyboard (HID) input
    val hidEnabled: Boolean = false
) {
    val hasExit get() = type == EventType.ENTRY_EXIT
}

@Entity(
    tableName = "attendance",
    primaryKeys = ["eventId", "nfcUid"],
    foreignKeys = [
        ForeignKey(EventEntity::class, ["id"], ["eventId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(EmployeeEntity::class, ["nfcUid"], ["nfcUid"], onDelete = ForeignKey.CASCADE)
    ],
    indices = [Index("nfcUid")]
)
data class AttendanceEntity(
    val eventId: Long,
    val nfcUid: String,
    val checkInAt: Long,
    val checkOutAt: Long? = null
)

data class EventWithCounts(
    @Embedded val event: EventEntity,
    val checkedIn: Int,
    val exited: Int
)

data class AttendanceRow(
    val nfcUid: String,
    val name: String,
    val checkInAt: Long,
    val checkOutAt: Long?
)

@Dao
interface AppDao {
    // ID Card Templates
    @Query("SELECT * FROM id_card_templates ORDER BY createdAt DESC")
    fun observeTemplates(): Flow<List<IdCardTemplateEntity>>

    @Query("SELECT * FROM id_card_templates WHERE id = :id LIMIT 1")
    suspend fun getTemplate(id: Long): IdCardTemplateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTemplate(template: IdCardTemplateEntity): Long

    @Query("DELETE FROM id_card_templates WHERE id = :id")
    suspend fun deleteTemplate(id: Long)

    // Employees
    @Query("SELECT * FROM employees WHERE nfcUid = :uid LIMIT 1")
    suspend fun findEmployee(uid: String): EmployeeEntity?

    @Query("SELECT * FROM employees ORDER BY registeredAt DESC")
    fun observeEmployees(): Flow<List<EmployeeEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertEmployee(e: EmployeeEntity)

    @Query("DELETE FROM employees WHERE nfcUid = :uid")
    suspend fun deleteEmployee(uid: String)

    @Query("UPDATE employees SET isAdmin = :isAdmin WHERE nfcUid = :uid")
    suspend fun setEmployeeAdmin(uid: String, isAdmin: Boolean)

    // Admin cards
    @Query("SELECT * FROM admin_cards ORDER BY addedAt DESC")
    fun observeAdminCards(): Flow<List<AdminCardEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAdminCard(card: AdminCardEntity)

    @Query("DELETE FROM admin_cards WHERE nfcUid = :uid")
    suspend fun deleteAdminCard(uid: String)

    @Query("SELECT EXISTS(SELECT 1 FROM employees WHERE nfcUid = :uid AND isAdmin = 1) OR EXISTS(SELECT 1 FROM admin_cards WHERE nfcUid = :uid)")
    suspend fun isAdminUid(uid: String): Boolean

    // Events
    @Insert
    suspend fun insertEvent(e: EventEntity): Long

    @Query("SELECT * FROM events WHERE id = :id")
    suspend fun getEvent(id: Long): EventEntity?

    @Query("SELECT * FROM events WHERE id = :id")
    fun observeEvent(id: Long): Flow<EventEntity?>

    @Query(
        "SELECT e.*, " +
            "(SELECT COUNT(*) FROM attendance a WHERE a.eventId = e.id) AS checkedIn, " +
            "(SELECT COUNT(*) FROM attendance a WHERE a.eventId = e.id AND a.checkOutAt IS NOT NULL) AS exited " +
            "FROM events e WHERE e.status = :status " +
            "ORDER BY CASE WHEN :status = 'ENDED' THEN e.endedAt ELSE e.createdAt END DESC"
    )
    fun observeEvents(status: EventStatus): Flow<List<EventWithCounts>>

    @Query("UPDATE events SET status = :status, endedAt = :endedAt WHERE id = :id")
    suspend fun setStatus(id: Long, status: EventStatus, endedAt: Long?)

    // Attendance
    @Query("SELECT * FROM attendance WHERE eventId = :eventId AND nfcUid = :uid LIMIT 1")
    suspend fun findAttendance(eventId: Long, uid: String): AttendanceEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAttendance(a: AttendanceEntity)

    @Query(
        "SELECT a.nfcUid AS nfcUid, e.name AS name, a.checkInAt AS checkInAt, a.checkOutAt AS checkOutAt " +
            "FROM attendance a JOIN employees e ON e.nfcUid = a.nfcUid " +
            "WHERE a.eventId = :eventId ORDER BY a.checkInAt DESC"
    )
    fun observeAttendance(eventId: Long): Flow<List<AttendanceRow>>

    @Query(
        "SELECT a.nfcUid AS nfcUid, e.name AS name, a.checkInAt AS checkInAt, a.checkOutAt AS checkOutAt " +
            "FROM attendance a JOIN employees e ON e.nfcUid = a.nfcUid " +
            "WHERE a.eventId = :eventId ORDER BY a.checkInAt ASC"
    )
    suspend fun getAttendance(eventId: Long): List<AttendanceRow>

    @Query("DELETE FROM attendance WHERE eventId = :eventId AND nfcUid IN (:uids)")
    suspend fun deleteAttendance(eventId: Long, uids: List<String>)
}

@Database(
    entities = [
        EmployeeEntity::class,
        EventEntity::class,
        AttendanceEntity::class,
        AdminCardEntity::class,
        IdCardTemplateEntity::class
    ],
    version = 7,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): AppDao

    companion object {
        private val MIGRATION_5_6 = object : androidx.room.migration.Migration(5, 6) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE events ADD COLUMN locationLockEnabled INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE events ADD COLUMN latitude REAL")
                db.execSQL("ALTER TABLE events ADD COLUMN longitude REAL")
                db.execSQL("ALTER TABLE events ADD COLUMN radiusMeters REAL")
            }
        }

        private val MIGRATION_6_7 = object : androidx.room.migration.Migration(6, 7) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE events ADD COLUMN hidEnabled INTEGER NOT NULL DEFAULT 0")
            }
        }

        @Volatile private var instance: AppDatabase? = null
        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext, AppDatabase::class.java, "badger.db"
            ).addMigrations(MIGRATION_5_6, MIGRATION_6_7)
                .fallbackToDestructiveMigration()
                .build().also { instance = it }
        }
    }
}

class Converters {
    @TypeConverter fun typeToString(v: EventType): String = v.name
    @TypeConverter fun stringToType(v: String): EventType = EventType.valueOf(v)
    @TypeConverter fun statusToString(v: EventStatus): String = v.name
    @TypeConverter fun stringToStatus(v: String): EventStatus = EventStatus.valueOf(v)
}
