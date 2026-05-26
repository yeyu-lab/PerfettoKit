package io.github.perfettokit.history

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import io.github.perfettokit.report.DiagnosisReport

/**
 * 本地 SQLite 存储 — 保存每次 Session 的诊断结果，用于回归检测。
 */
class SessionStore(context: Context) : SQLiteOpenHelper(
    context, DB_NAME, null, DB_VERSION
) {
    companion object {
        private const val DB_NAME = "perfettokit_history.db"
        private const val DB_VERSION = 1
        private const val TABLE = "sessions"
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE $TABLE (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                scene TEXT NOT NULL,
                timestamp INTEGER NOT NULL,
                duration_ms INTEGER NOT NULL,
                total_frames INTEGER NOT NULL,
                avg_frame_ms REAL NOT NULL,
                max_frame_ms REAL NOT NULL,
                jank_count INTEGER NOT NULL,
                jank_ratio REAL NOT NULL,
                issue_count INTEGER NOT NULL,
                root_causes TEXT NOT NULL,
                summary TEXT NOT NULL
            )
        """)
        db.execSQL("CREATE INDEX idx_scene ON $TABLE(scene)")
        db.execSQL("CREATE INDEX idx_timestamp ON $TABLE(timestamp)")
    }

    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE")
        onCreate(db)
    }

    /**
     * 保存一次诊断结果。
     */
    fun save(report: DiagnosisReport, frameDetails: FrameStats) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put("scene", report.scene)
            put("timestamp", System.currentTimeMillis())
            put("duration_ms", report.durationMs)
            put("total_frames", report.totalFrames)
            put("avg_frame_ms", frameDetails.avgMs)
            put("max_frame_ms", frameDetails.maxMs)
            put("jank_count", frameDetails.jankCount)
            put("jank_ratio", frameDetails.jankRatio)
            put("issue_count", report.issues.size)
            put("root_causes", report.rootCauses.joinToString(",") { it.type.name })
            put("summary", report.summary)
        }
        db.insert(TABLE, null, values)
    }

    /**
     * 获取某场景的历史记录（最近 N 条）。
     */
    fun getHistory(scene: String, limit: Int = 10): List<SessionRecord> {
        val db = readableDatabase
        val cursor = db.query(
            TABLE, null,
            "scene = ?", arrayOf(scene),
            null, null,
            "timestamp DESC",
            "$limit"
        )

        val records = mutableListOf<SessionRecord>()
        cursor.use {
            while (it.moveToNext()) {
                records.add(
                    SessionRecord(
                        id = it.getLong(it.getColumnIndexOrThrow("id")),
                        scene = it.getString(it.getColumnIndexOrThrow("scene")),
                        timestamp = it.getLong(it.getColumnIndexOrThrow("timestamp")),
                        durationMs = it.getLong(it.getColumnIndexOrThrow("duration_ms")),
                        totalFrames = it.getInt(it.getColumnIndexOrThrow("total_frames")),
                        avgFrameMs = it.getDouble(it.getColumnIndexOrThrow("avg_frame_ms")),
                        maxFrameMs = it.getDouble(it.getColumnIndexOrThrow("max_frame_ms")),
                        jankCount = it.getInt(it.getColumnIndexOrThrow("jank_count")),
                        jankRatio = it.getDouble(it.getColumnIndexOrThrow("jank_ratio")),
                        issueCount = it.getInt(it.getColumnIndexOrThrow("issue_count")),
                        rootCauses = it.getString(it.getColumnIndexOrThrow("root_causes")),
                        summary = it.getString(it.getColumnIndexOrThrow("summary"))
                    )
                )
            }
        }
        return records
    }

    /**
     * 清除指定场景的历史数据。
     */
    fun clearScene(scene: String) {
        writableDatabase.delete(TABLE, "scene = ?", arrayOf(scene))
    }

    /**
     * 清除所有历史数据。
     */
    fun clearAll() {
        writableDatabase.delete(TABLE, null, null)
    }
}

data class SessionRecord(
    val id: Long,
    val scene: String,
    val timestamp: Long,
    val durationMs: Long,
    val totalFrames: Int,
    val avgFrameMs: Double,
    val maxFrameMs: Double,
    val jankCount: Int,
    val jankRatio: Double,
    val issueCount: Int,
    val rootCauses: String,
    val summary: String
)

data class FrameStats(
    val avgMs: Double,
    val maxMs: Double,
    val jankCount: Int,
    val jankRatio: Double
)
