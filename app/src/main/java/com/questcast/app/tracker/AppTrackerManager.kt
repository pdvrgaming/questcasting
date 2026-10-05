package com.questcast.app.tracker

import android.app.ActivityManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import com.questcast.app.util.AppLogger as Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Represents a single VR Game / Application session recorded during casting.
 * Strongly indexed by Date (YYYY-MM-DD) and App Name for arcade accounting and activity tracking.
 */
data class AppSessionRecord(
    val id: String = UUID.randomUUID().toString(),
    val date: String, // YYYY-MM-DD
    val packageName: String,
    val appName: String,
    val startTimeMs: Long,
    var endTimeMs: Long? = null,
    val formattedStartTime: String, // HH:mm:ss
    var formattedEndTime: String? = null, // HH:mm:ss
    var durationSeconds: Long = 0,
    var isActive: Boolean = true
) {
    fun formattedDuration(): String {
        val hours = durationSeconds / 3600
        val mins = (durationSeconds % 3600) / 60
        val secs = durationSeconds % 60
        return if (hours > 0) {
            "${hours}h ${mins}m ${secs}s"
        } else if (mins > 0) {
            "${mins}m ${secs}s"
        } else {
            "${secs}s"
        }
    }

    fun toJsonObject(): JSONObject {
        return JSONObject().apply {
            put("id", id)
            put("date", date)
            put("packageName", packageName)
            put("appName", appName)
            put("startTimeMs", startTimeMs)
            put("endTimeMs", endTimeMs ?: JSONObject.NULL)
            put("formattedStartTime", formattedStartTime)
            put("formattedEndTime", formattedEndTime ?: "Active")
            put("durationSeconds", durationSeconds)
            put("formattedDuration", formattedDuration())
            put("isActive", isActive)
        }
    }

    companion object {
        fun fromJsonObject(json: JSONObject): AppSessionRecord {
            return AppSessionRecord(
                id = json.optString("id", UUID.randomUUID().toString()),
                date = json.optString("date", ""),
                packageName = json.optString("packageName", ""),
                appName = json.optString("appName", "Unknown"),
                startTimeMs = json.optLong("startTimeMs", 0L),
                endTimeMs = if (json.isNull("endTimeMs")) null else json.optLong("endTimeMs"),
                formattedStartTime = json.optString("formattedStartTime", ""),
                formattedEndTime = if (json.isNull("formattedEndTime")) null else json.optString("formattedEndTime"),
                durationSeconds = json.optLong("durationSeconds", 0L),
                isActive = json.optBoolean("isActive", false)
            )
        }
    }
}

/**
 * Monitors foreground VR applications and maintains a persistent, app-based and date-based
 * audit log of all games and experiences played on Meta Quest 2.
 * Strictly filters out system shells, OS UI, and background processes to log ONLY installed games/apps
 * (e.g. Beat Saber, Jurassic World, Henry).
 */
class AppTrackerManager(private val context: Context) {
    companion object {
        private const val TAG = "QuestCast"
        private const val POLL_INTERVAL_MS = 2000L
        private const val LOG_FILE_NAME = "questcast_audit_log.json"

        // Meta Quest OS, shell, system menus, guardian and telemetry packages that must NEVER be in the audit log
        private val EXCLUDED_PACKAGES = setOf(
            "com.oculus.vrshell",
            "com.oculus.systemux",
            "com.oculus.shell",
            "com.oculus.systemactivities",
            "com.oculus.guardian",
            "com.oculus.statscollector",
            "com.oculus.updater",
            "com.oculus.identity",
            "com.oculus.assistant",
            "com.oculus.usersetup",
            "com.oculus.companion",
            "com.oculus.firsttimenux",
            "com.oculus.metacam",
            "com.oculus.bugreport",
            "com.oculus.socialplatform",
            "com.oculus.mediahub",
            "com.oculus.alpenglow",
            "com.oculus.telemetry",
            "com.oculus.unifiedtelemetry",
            "com.oculus.ocms",
            "com.oculus.store",
            "com.oculus.explore",
            "com.oculus.browser",
            "com.android.settings",
            "com.android.systemui"
        )

        private val EXCLUDED_PREFIXES = listOf(
            "android",
            "com.android.",
            "com.google.android.",
            "com.qualcomm.",
            "com.oculus.environment.",
            "com.facebook.spatial_audio"
        )

        fun isSystemOrShell(packageName: String, ownPackage: String): Boolean {
            if (packageName.isBlank()) return true
            if (packageName == ownPackage) return true
            if (EXCLUDED_PACKAGES.contains(packageName)) return true
            if (EXCLUDED_PREFIXES.any { packageName.startsWith(it) }) return true
            return false
        }

        fun formatDuration(seconds: Long): String {
            val hours = seconds / 3600
            val mins = (seconds % 3600) / 60
            val secs = seconds % 60
            return if (hours > 0) {
                "${hours}h ${mins}m"
            } else if (mins > 0) {
                "${mins}m ${secs}s"
            } else {
                "${secs}s"
            }
        }
    }

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    private val sessions = CopyOnWriteArrayList<AppSessionRecord>()
    private val logFile: File = File(context.filesDir, LOG_FILE_NAME)

    private val _currentApp = MutableStateFlow<AppSessionRecord?>(null)
    val currentApp: StateFlow<AppSessionRecord?> = _currentApp.asStateFlow()

    private val _auditLog = MutableStateFlow<List<AppSessionRecord>>(emptyList())
    val auditLog: StateFlow<List<AppSessionRecord>> = _auditLog.asStateFlow()

    private var trackerScope: CoroutineScope? = null
    private var isTracking = false

    private val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
    private val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
    private val packageManager: PackageManager = context.packageManager

    init {
        loadPersistedLog()
    }

    fun isInstalledGameOrApp(packageName: String): Boolean {
        if (isSystemOrShell(packageName, context.packageName)) return false

        return try {
            val appInfo = packageManager.getApplicationInfo(packageName, 0)
            val isUserApp = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) == 0 ||
                            (appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0

            val hasLaunchIntent = packageManager.getLaunchIntentForPackage(packageName) != null

            val isGame = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                appInfo.category == ApplicationInfo.CATEGORY_GAME ||
                (appInfo.flags and ApplicationInfo.FLAG_IS_GAME) != 0
            } else {
                (appInfo.flags and ApplicationInfo.FLAG_IS_GAME) != 0
            }

            isUserApp || hasLaunchIntent || isGame
        } catch (_: PackageManager.NameNotFoundException) {
            false
        } catch (_: Exception) {
            false
        }
    }

    fun startTracking() {
        if (isTracking) return
        isTracking = true
        Log.i(TAG, "QuestCast: Starting App & Game Activity Tracker (Game-only audit mode)")

        trackerScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        trackerScope?.launch {
            while (isActive && isTracking) {
                checkForegroundApp()
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    fun stopTracking() {
        if (!isTracking) return
        isTracking = false
        Log.i(TAG, "QuestCast: Stopping App & Game Activity Tracker")

        // Close currently active session if any
        val active = _currentApp.value
        if (active != null) {
            closeSession(active)
            _currentApp.value = null
        }

        savePersistedLog()
        trackerScope?.cancel()
        trackerScope = null
    }

    private fun checkForegroundApp() {
        val now = System.currentTimeMillis()
        var detectedPackage: String? = null

        // 1. Try UsageStatsManager queryEvents
        if (usageStatsManager != null) {
            try {
                val events = usageStatsManager.queryEvents(now - 10000, now)
                val event = UsageEvents.Event()
                var latestTime = 0L

                while (events.hasNextEvent()) {
                    events.getNextEvent(event)
                    if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED ||
                        event.eventType == 1 /* MOVE_TO_FOREGROUND */) {
                        if (event.timeStamp >= latestTime) {
                            latestTime = event.timeStamp
                            detectedPackage = event.packageName
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        // 2. Fallback to ActivityManager running processes
        if (detectedPackage == null && activityManager != null) {
            try {
                val runningProcesses = activityManager.runningAppProcesses
                val foregroundProcess = runningProcesses?.firstOrNull {
                    it.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
                }
                detectedPackage = foregroundProcess?.processName
            } catch (_: Exception) {}
        }

        val active = _currentApp.value

        if (detectedPackage != null && isInstalledGameOrApp(detectedPackage)) {
            if (active == null) {
                // First tracked game opened
                openNewSession(detectedPackage, now)
            } else if (active.packageName != detectedPackage) {
                // Switched from one game to another
                Log.i(TAG, "QuestCast Audit: Switched from ${active.appName} to $detectedPackage after ${active.durationSeconds}s")
                closeSession(active)
                openNewSession(detectedPackage, now)
            } else {
                // Same game still active, update duration
                active.durationSeconds = (now - active.startTimeMs) / 1000
                _currentApp.value = active
                _auditLog.value = sessions.toList()
            }
        } else {
            // Player is in Quest Home, system shell, guardian, or settings
            if (active != null) {
                Log.i(TAG, "QuestCast Audit: Exited game ${active.appName} (${active.packageName}) after ${active.durationSeconds}s to system shell/home")
                closeSession(active)
                _currentApp.value = null
            }
        }
    }

    private fun openNewSession(packageName: String, timestamp: Long) {
        val appName = resolveAppName(packageName)
        val dateStr = synchronized(dateFormat) { dateFormat.format(Date(timestamp)) }
        val formattedStart = synchronized(timeFormat) { timeFormat.format(Date(timestamp)) }

        val record = AppSessionRecord(
            date = dateStr,
            packageName = packageName,
            appName = appName,
            startTimeMs = timestamp,
            formattedStartTime = formattedStart,
            durationSeconds = 0,
            isActive = true
        )

        Log.i(TAG, "QuestCast Audit: Game opened: $appName ($packageName) on $dateStr at $formattedStart")
        sessions.add(0, record)
        _currentApp.value = record
        _auditLog.value = sessions.toList()
        savePersistedLog()
    }

    private fun closeSession(record: AppSessionRecord) {
        val now = System.currentTimeMillis()
        record.endTimeMs = now
        record.formattedEndTime = synchronized(timeFormat) { timeFormat.format(Date(now)) }
        record.durationSeconds = ((now - record.startTimeMs) / 1000).coerceAtLeast(1)
        record.isActive = false
        _auditLog.value = sessions.toList()
        savePersistedLog()
    }

    private fun resolveAppName(packageName: String): String {
        return try {
            val appInfo = packageManager.getApplicationInfo(packageName, 0)
            val label = packageManager.getApplicationLabel(appInfo).toString()
            if (label.isNotBlank()) label else cleanPackageName(packageName)
        } catch (_: Exception) {
            cleanPackageName(packageName)
        }
    }

    private fun cleanPackageName(packageName: String): String {
        return packageName.substringAfterLast('.')
            .replace('_', ' ')
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
    }

    @Synchronized
    private fun savePersistedLog() {
        try {
            val jsonArray = JSONArray()
            // Keep up to 1000 latest sessions for long-term arcade records
            val slice = sessions.take(1000)
            for (item in slice) {
                jsonArray.put(item.toJsonObject())
            }
            val tempFile = File(context.filesDir, "$LOG_FILE_NAME.tmp")
            tempFile.writeText(jsonArray.toString(2), Charsets.UTF_8)
            if (tempFile.exists()) {
                if (logFile.exists()) logFile.delete()
                tempFile.renameTo(logFile)
            }
        } catch (e: Exception) {
            Log.e(TAG, "QuestCast Audit: Failed to persist log to file", e)
        }
    }

    @Synchronized
    private fun loadPersistedLog() {
        if (!logFile.exists()) return
        try {
            val content = logFile.readText(Charsets.UTF_8)
            val jsonArray = JSONArray(content)
            sessions.clear()
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val record = AppSessionRecord.fromJsonObject(obj)
                // Filter out any legacy system shell entries that were logged previously
                if (!isSystemOrShell(record.packageName, context.packageName)) {
                    sessions.add(record)
                }
            }
            _auditLog.value = sessions.toList()
            Log.i(TAG, "QuestCast Audit: Loaded ${sessions.size} persisted game sessions from disk")
        } catch (e: Exception) {
            Log.w(TAG, "QuestCast Audit: Could not parse persisted log file", e)
        }
    }

    /**
     * Generates a comprehensive, app-based and date-based structured JSON report.
     * Supports filtering by date (YYYY-MM-DD), app name, or view mode.
     */
    fun getStructuredAuditLogJson(
        dateFilter: String? = null,
        appFilter: String? = null,
        view: String? = null
    ): String {
        val allSessions = sessions.toList()

        // Filter sessions
        val filtered = allSessions.filter { session ->
            val matchDate = dateFilter.isNullOrBlank() || session.date.equals(dateFilter.trim(), ignoreCase = true)
            val matchApp = appFilter.isNullOrBlank() || session.appName.contains(appFilter.trim(), ignoreCase = true) ||
                    session.packageName.contains(appFilter.trim(), ignoreCase = true)
            matchDate && matchApp
        }

        // Available dates and apps across full database
        val availableDates = allSessions.map { it.date }.filter { it.isNotBlank() }.distinct().sortedDescending()
        val availableApps = allSessions.map { it.appName }.filter { it.isNotBlank() }.distinct().sorted()

        // 1. Group by App
        val byAppJson = JSONArray()
        val appGroups = allSessions.groupBy { it.appName }
        for ((appName, appSessions) in appGroups.entries.sortedByDescending { it.value.sumOf { s -> s.durationSeconds } }) {
            val totalAppSeconds = appSessions.sumOf { it.durationSeconds }
            val totalSessionsCount = appSessions.size
            val datesPlayed = appSessions.map { it.date }.distinct().sortedDescending()
            val firstPlayed = appSessions.minByOrNull { it.startTimeMs }?.date ?: ""
            val lastPlayed = appSessions.maxByOrNull { it.startTimeMs }?.date ?: ""
            val pkg = appSessions.firstOrNull()?.packageName ?: ""

            val appObj = JSONObject().apply {
                put("appName", appName)
                put("packageName", pkg)
                put("totalSessions", totalSessionsCount)
                put("totalDurationSeconds", totalAppSeconds)
                put("totalDurationFormatted", formatDuration(totalAppSeconds))
                put("firstPlayed", firstPlayed)
                put("lastPlayed", lastPlayed)
                put("datesPlayed", JSONArray(datesPlayed))

                val sessionArr = JSONArray()
                for (s in appSessions.take(50)) {
                    sessionArr.put(s.toJsonObject())
                }
                put("recentSessions", sessionArr)
            }
            byAppJson.put(appObj)
        }

        // 2. Group by Date
        val byDateJson = JSONArray()
        val dateGroups = allSessions.groupBy { it.date }
        for ((dateStr, dateSessions) in dateGroups.entries.sortedByDescending { it.key }) {
            val totalDaySeconds = dateSessions.sumOf { it.durationSeconds }
            val dayApps = dateSessions.map { it.appName }.distinct()
            val daySessionsCount = dateSessions.size

            val dateObj = JSONObject().apply {
                put("date", dateStr)
                put("totalSessions", daySessionsCount)
                put("totalDurationSeconds", totalDaySeconds)
                put("totalDurationFormatted", formatDuration(totalDaySeconds))
                put("appsPlayed", JSONArray(dayApps))

                // Breakdown per app on this date
                val appsOnDate = JSONArray()
                dateSessions.groupBy { it.appName }.forEach { (app, sList) ->
                    val appDur = sList.sumOf { it.durationSeconds }
                    appsOnDate.put(JSONObject().apply {
                        put("appName", app)
                        put("sessionsCount", sList.size)
                        put("durationSeconds", appDur)
                        put("durationFormatted", formatDuration(appDur))
                    })
                }
                put("appsBreakdown", appsOnDate)

                val sessionArr = JSONArray()
                for (s in dateSessions) {
                    sessionArr.put(s.toJsonObject())
                }
                put("sessions", sessionArr)
            }
            byDateJson.put(dateObj)
        }

        // 3. Overall Top-level Summary
        val totalSecs = filtered.sumOf { it.durationSeconds }
        val active = _currentApp.value

        val root = JSONObject().apply {
            put("summary", JSONObject().apply {
                put("totalSessions", filtered.size)
                put("totalDurationSeconds", totalSecs)
                put("totalDurationFormatted", formatDuration(totalSecs))
                put("totalAppsCount", availableApps.size)
                put("totalDatesCount", availableDates.size)
                put("activeApp", active?.appName ?: JSONObject.NULL)
                put("activeDuration", active?.formattedDuration() ?: JSONObject.NULL)
            })

            put("availableDates", JSONArray(availableDates))
            put("availableApps", JSONArray(availableApps))

            if (view == null || view == "full" || view == "app") {
                put("byApp", byAppJson)
            }
            if (view == null || view == "full" || view == "date") {
                put("byDate", byDateJson)
            }

            // Raw session list matching current filter
            val sessionList = JSONArray()
            for (s in filtered) {
                sessionList.put(s.toJsonObject())
            }
            put("sessions", sessionList)
        }

        return root.toString(2)
    }

    /**
     * Exports the audit log to standard CSV format for accounting / Excel.
     */
    fun exportCsv(dateFilter: String? = null, appFilter: String? = null): String {
        val sb = StringBuilder()
        sb.append("Session ID,Date,App Name,Package Name,Start Time,End Time,Duration (Seconds),Duration (Minutes),Status\r\n")

        val list = sessions.filter { session ->
            val matchDate = dateFilter.isNullOrBlank() || session.date.equals(dateFilter.trim(), ignoreCase = true)
            val matchApp = appFilter.isNullOrBlank() || session.appName.contains(appFilter.trim(), ignoreCase = true)
            matchDate && matchApp
        }

        for (s in list) {
            val status = if (s.isActive) "Active" else "Completed"
            val mins = (s.durationSeconds / 60.0)
            val cleanName = s.appName.replace("\"", "\"\"")
            val cleanPkg = s.packageName.replace("\"", "\"\"")
            sb.append("\"${s.id}\",\"${s.date}\",\"$cleanName\",\"$cleanPkg\",\"${s.formattedStartTime}\",\"${s.formattedEndTime ?: "Active"}\",${s.durationSeconds},${String.format(Locale.US, "%.1f", mins)},\"$status\"\r\n")
        }

        return sb.toString()
    }

    /**
     * Clears all session history and wipes the persisted log file.
     */
    @Synchronized
    fun clearAuditLog() {
        sessions.clear()
        _currentApp.value = null
        _auditLog.value = emptyList()
        if (logFile.exists()) {
            logFile.delete()
        }
        Log.i(TAG, "QuestCast Audit: History cleared")
    }

    fun getAuditLogJson(): String {
        return getStructuredAuditLogJson()
    }
}
