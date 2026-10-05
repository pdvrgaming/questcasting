package com.questcast.app.util

import android.content.Context
import android.hardware.input.InputManager
import android.os.Build
import com.questcast.app.util.AppLogger as Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

data class ControllerBatteryInfo(
    val left: Int? = null,
    val right: Int? = null
)

object ControllerBatteryUtils {
    private const val TAG = "QuestCast"
    private const val CACHE_TTL_MS = 3000L

    @Volatile
    private var cachedInfo = ControllerBatteryInfo()
    @Volatile
    private var lastFetchTime = 0L

    @Synchronized
    fun getBatteryLevels(context: Context): ControllerBatteryInfo {
        val now = System.currentTimeMillis()
        if (now - lastFetchTime < CACHE_TTL_MS) {
            return cachedInfo
        }

        var left: Int? = null
        var right: Int? = null

        // 1. First attempt: Standard Android 12+ (API 31+) InputDevice Battery API
        val inputInfo = getFromInputDevices(context)
        if (inputInfo != null) {
            left = inputInfo.left
            right = inputInfo.right
        }

        // 2. Second attempt: Meta Quest OVRRemoteService dumpsys
        if (left == null || right == null) {
            val ovrInfo = getFromOvrRemoteService()
            if (ovrInfo != null) {
                if (left == null) left = ovrInfo.left
                if (right == null) right = ovrInfo.right
            }
        }

        // 3. Third attempt: dumpsys input
        if (left == null || right == null) {
            val dumpsysInfo = getFromDumpsysInput()
            if (dumpsysInfo != null) {
                if (left == null) left = dumpsysInfo.left
                if (right == null) right = dumpsysInfo.right
            }
        }

        cachedInfo = ControllerBatteryInfo(left, right)
        lastFetchTime = now
        return cachedInfo
    }

    fun getFromInputDevices(context: Context): ControllerBatteryInfo? {
        return try {
            val inputManager = context.getSystemService(Context.INPUT_SERVICE) as? InputManager ?: return null
            val deviceIds = inputManager.inputDeviceIds ?: return null
            var left: Int? = null
            var right: Int? = null

            for (id in deviceIds) {
                val dev = inputManager.getInputDevice(id) ?: continue
                val name = dev.name.lowercase()
                val isLeft = name.contains("left") || name.contains("(left)") || name.contains("_l")
                val isRight = name.contains("right") || name.contains("(right)") || name.contains("_r")
                if (!isLeft && !isRight) continue

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val batteryState = dev.batteryState
                    if (batteryState.isPresent) {
                        val cap = batteryState.capacity
                        val pct = when {
                            cap in 0.0f..1.0f -> (cap * 100).toInt()
                            cap > 1.0f && cap <= 100.0f -> cap.toInt()
                            else -> null
                        }
                        if (pct != null && pct in 0..100) {
                            if (isLeft && left == null) left = pct
                            if (isRight && right == null) right = pct
                        }
                    }
                }
            }
            if (left != null || right != null) {
                ControllerBatteryInfo(left, right)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.d(TAG, "InputDevice query error: ${e.message}")
            null
        }
    }

    fun getFromOvrRemoteService(): ControllerBatteryInfo? {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("dumpsys", "OVRRemoteService"))
            val output = BufferedReader(InputStreamReader(process.inputStream)).use { it.readText() }
            process.waitFor(500, TimeUnit.MILLISECONDS)
            process.destroy()
            parseOvrRemoteOutput(output)
        } catch (e: Exception) {
            Log.d(TAG, "OVRRemoteService read error: ${e.message}")
            null
        }
    }

    fun getFromDumpsysInput(): ControllerBatteryInfo? {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("dumpsys", "input"))
            val output = BufferedReader(InputStreamReader(process.inputStream)).use { it.readText() }
            process.waitFor(500, TimeUnit.MILLISECONDS)
            process.destroy()
            parseDumpsysInputOutput(output)
        } catch (e: Exception) {
            Log.d(TAG, "dumpsys input read error: ${e.message}")
            null
        }
    }

    fun parseOvrRemoteOutput(output: String): ControllerBatteryInfo {
        var left: Int? = null
        var right: Int? = null

        val singleLineRegex = Regex(
            """(?:type|device|controller|remote)?\s*[:=]?\s*(?:oculus\s+|meta\s+)?(?:touch\s+)?(left|right).*?(?:battery(?:\s*level|_level)?|capacity)\s*[:=]?\s*(\d+)%?""",
            RegexOption.IGNORE_CASE
        )
        val batteryFirstRegex = Regex(
            """(?:battery(?:\s*level|_level)?|capacity)\s*[:=]?\s*(\d+)%?.*?(?:type|device|controller|remote)?\s*[:=]?\s*(?:oculus\s+|meta\s+)?(?:touch\s+)?(left|right)""",
            RegexOption.IGNORE_CASE
        )
        val sectionBatteryRegex = Regex(
            """(?:battery(?:\s*level|_level)?|capacity)\s*[:=]?\s*(\d+)%?""",
            RegexOption.IGNORE_CASE
        )

        var currentSection: String? = null

        for (line in output.lines()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue

            // 1. Single line match
            val match1 = singleLineRegex.find(trimmed)
            if (match1 != null) {
                val side = match1.groupValues[1].lowercase()
                val pct = match1.groupValues[2].toIntOrNull()
                if (pct != null && pct in 0..100) {
                    if (side == "left" && left == null) left = pct
                    if (side == "right" && right == null) right = pct
                }
                continue
            }

            val match2 = batteryFirstRegex.find(trimmed)
            if (match2 != null) {
                val pct = match2.groupValues[1].toIntOrNull()
                val side = match2.groupValues[2].lowercase()
                if (pct != null && pct in 0..100) {
                    if (side == "left" && left == null) left = pct
                    if (side == "right" && right == null) right = pct
                }
                continue
            }

            // 2. Multi-line section match
            val lower = trimmed.lowercase()
            if (lower.contains("left") && (lower.contains("remote") || lower.contains("controller") || lower.contains("device") || lower.startsWith("left:"))) {
                currentSection = "left"
            } else if (lower.contains("right") && (lower.contains("remote") || lower.contains("controller") || lower.contains("device") || lower.startsWith("right:"))) {
                currentSection = "right"
            }

            val battMatch = sectionBatteryRegex.find(trimmed)
            if (battMatch != null && currentSection != null) {
                val pct = battMatch.groupValues[1].toIntOrNull()
                if (pct != null && pct in 0..100) {
                    if (currentSection == "left" && left == null) left = pct
                    if (currentSection == "right" && right == null) right = pct
                }
            }
        }

        return ControllerBatteryInfo(left, right)
    }

    fun parseDumpsysInputOutput(output: String): ControllerBatteryInfo {
        var left: Int? = null
        var right: Int? = null
        var currentSide: String? = null

        val batteryPercentRegex = Regex("""(?:battery|capacity)\s*[:=]\s*(\d+)%?""", RegexOption.IGNORE_CASE)
        val batteryFloatRegex = Regex("""capacity\s*=\s*(0\.\d+|1\.0)""", RegexOption.IGNORE_CASE)

        for (line in output.lines()) {
            val trimmed = line.trim()
            val lower = trimmed.lowercase()

            if (lower.startsWith("device ") || lower.contains("input device")) {
                currentSide = when {
                    lower.contains("left") || lower.contains("(left)") || lower.contains("_l") -> "left"
                    lower.contains("right") || lower.contains("(right)") || lower.contains("_r") -> "right"
                    else -> null
                }
            }

            if (currentSide != null) {
                val floatMatch = batteryFloatRegex.find(trimmed)
                if (floatMatch != null) {
                    val floatVal = floatMatch.groupValues[1].toFloatOrNull()
                    if (floatVal != null) {
                        val pct = (floatVal * 100).toInt().coerceIn(0, 100)
                        if (currentSide == "left" && left == null) left = pct
                        if (currentSide == "right" && right == null) right = pct
                    }
                } else {
                    val pctMatch = batteryPercentRegex.find(trimmed)
                    if (pctMatch != null) {
                        val pct = pctMatch.groupValues[1].toIntOrNull()
                        if (pct != null && pct in 0..100) {
                            if (currentSide == "left" && left == null) left = pct
                            if (currentSide == "right" && right == null) right = pct
                        }
                    }
                }
            }
        }

        return ControllerBatteryInfo(left, right)
    }
}
