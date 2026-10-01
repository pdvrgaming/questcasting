package com.questcast.app

import com.questcast.app.tracker.AppSessionRecord
import com.questcast.app.tracker.AppTrackerManager
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AppTrackerAuditLogTest {

    @Test
    fun testAppSessionRecordJsonSerialization() {
        val record = AppSessionRecord(
            id = "test-session-123",
            date = "2026-10-01",
            packageName = "com.beatgames.beatsaber",
            appName = "Beat Saber",
            startTimeMs = 1727780000000L,
            endTimeMs = 1727781820000L,
            formattedStartTime = "15:00:00",
            formattedEndTime = "15:30:20",
            durationSeconds = 1820,
            isActive = false
        )

        val json = record.toJsonObject()
        assertEquals("test-session-123", json.getString("id"))
        assertEquals("2026-10-01", json.getString("date"))
        assertEquals("Beat Saber", json.getString("appName"))
        assertEquals("com.beatgames.beatsaber", json.getString("packageName"))
        assertEquals(1820L, json.getLong("durationSeconds"))
        assertEquals("30m 20s", json.getString("formattedDuration"))
        assertFalse(json.getBoolean("isActive"))

        val restored = AppSessionRecord.fromJsonObject(json)
        assertEquals(record.id, restored.id)
        assertEquals(record.date, restored.date)
        assertEquals(record.appName, restored.appName)
        assertEquals(record.packageName, restored.packageName)
        assertEquals(record.durationSeconds, restored.durationSeconds)
        assertEquals(record.isActive, restored.isActive)
    }

    @Test
    fun testFormatDurationFormatting() {
        assertEquals("45s", AppTrackerManager.formatDuration(45))
        assertEquals("15m 30s", AppTrackerManager.formatDuration(930))
        assertEquals("2h 15m", AppTrackerManager.formatDuration(8100))
    }

    @Test
    fun testAppAndDateSessionRecordIntegrity() {
        val activeRecord = AppSessionRecord(
            date = "2026-10-01",
            packageName = "com.superhot.vr",
            appName = "Superhot VR",
            startTimeMs = 1727782000000L,
            formattedStartTime = "16:00:00",
            durationSeconds = 600,
            isActive = true
        )

        val json = activeRecord.toJsonObject()
        assertTrue(json.getBoolean("isActive"))
        assertEquals("Active", json.getString("formattedEndTime"))
        assertEquals("10m 0s", json.getString("formattedDuration"))
    }
}
