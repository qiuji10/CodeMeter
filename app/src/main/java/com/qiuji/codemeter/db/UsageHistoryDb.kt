package com.qiuji.codemeter.db

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.qiuji.codemeter.data.ProfileStore
import com.qiuji.codemeter.model.Profile
import com.qiuji.codemeter.model.ProviderId
import com.qiuji.codemeter.model.ProviderUsage
import com.qiuji.codemeter.model.UsageSnapshot
import com.qiuji.codemeter.model.UsageWindow

class UsageHistoryDb(context: Context) : SQLiteOpenHelper(context, "usage_history.db", null, 2) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE usage_snapshot (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                profile_id TEXT NOT NULL,
                provider TEXT NOT NULL,
                window_key TEXT NOT NULL,
                label TEXT NOT NULL,
                used_percent REAL NOT NULL,
                resets_at INTEGER,
                captured_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX idx_usage_profile_time ON usage_snapshot(profile_id, captured_at DESC)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE usage_snapshot ADD COLUMN profile_id TEXT")
            db.execSQL(
                """
                UPDATE usage_snapshot
                SET profile_id = CASE provider
                    WHEN 'CLAUDE' THEN '${ProfileStore.LEGACY_CLAUDE_PROFILE_ID}'
                    WHEN 'CODEX' THEN '${ProfileStore.LEGACY_CODEX_PROFILE_ID}'
                    ELSE 'legacy_unknown'
                END
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_usage_profile_time ON usage_snapshot(profile_id, captured_at DESC)")
        }
    }

    @Synchronized
    fun insert(usage: ProviderUsage) {
        require(usage.profileId.isNotBlank()) { "Usage profile id cannot be blank." }
        val db = writableDatabase
        db.beginTransaction()
        try {
            usage.windows.forEach { window ->
                db.insertOrThrow(
                    "usage_snapshot",
                    null,
                    ContentValues().apply {
                        put("profile_id", usage.profileId)
                        put("provider", usage.provider.name)
                        put("window_key", window.key)
                        put("label", window.label)
                        put("used_percent", window.usedPercent)
                        if (window.resetsAtEpochMs != null) put("resets_at", window.resetsAtEpochMs) else putNull("resets_at")
                        put("captured_at", usage.updatedAtEpochMs)
                    },
                )
            }
            val cutoff = System.currentTimeMillis() - 31L * 24 * 60 * 60 * 1000
            db.delete("usage_snapshot", "captured_at < ?", arrayOf(cutoff.toString()))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun latest(profile: Profile): ProviderUsage? {
        val db = readableDatabase
        val capturedAt = db.rawQuery(
            "SELECT MAX(captured_at) FROM usage_snapshot WHERE profile_id = ?",
            arrayOf(profile.id),
        ).use { cursor -> if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null } ?: return null

        val windows = mutableListOf<UsageWindow>()
        db.query(
            "usage_snapshot",
            arrayOf("window_key", "label", "used_percent", "resets_at"),
            "profile_id = ? AND captured_at = ?",
            arrayOf(profile.id, capturedAt.toString()),
            null,
            null,
            "id ASC",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                windows += UsageWindow(
                    key = cursor.getString(0),
                    label = cursor.getString(1),
                    usedPercent = cursor.getDouble(2),
                    resetsAtEpochMs = if (cursor.isNull(3)) null else cursor.getLong(3),
                )
            }
        }
        if (windows.isEmpty()) return null
        return ProviderUsage(
            provider = profile.provider,
            profileId = profile.id,
            profileName = profile.name,
            windows = windows,
            updatedAtEpochMs = capturedAt,
        )
    }

    @Synchronized
    fun history(profile: Profile, sinceEpochMs: Long): List<UsageSnapshot> {
        val result = mutableListOf<UsageSnapshot>()
        readableDatabase.query(
            "usage_snapshot",
            arrayOf("window_key", "label", "used_percent", "resets_at", "captured_at"),
            "profile_id = ? AND captured_at >= ?",
            arrayOf(profile.id, sinceEpochMs.toString()),
            null,
            null,
            "captured_at ASC",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result += UsageSnapshot(
                    profileId = profile.id,
                    provider = profile.provider,
                    windowKey = cursor.getString(0),
                    label = cursor.getString(1),
                    usedPercent = cursor.getDouble(2),
                    resetsAtEpochMs = if (cursor.isNull(3)) null else cursor.getLong(3),
                    capturedAtEpochMs = cursor.getLong(4),
                )
            }
        }
        return result
    }

    @Synchronized
    fun deleteProfile(profileId: String) {
        writableDatabase.delete("usage_snapshot", "profile_id = ?", arrayOf(profileId))
    }
}
