package com.questcast.app.util

object AppLogger {
    fun i(tag: String, msg: String) {
        try {
            android.util.Log.i(tag, msg)
        } catch (_: Throwable) {
            println("[$tag] INFO: $msg")
        }
    }

    fun d(tag: String, msg: String) {
        try {
            android.util.Log.d(tag, msg)
        } catch (_: Throwable) {
            println("[$tag] DEBUG: $msg")
        }
    }

    fun w(tag: String, msg: String, tr: Throwable? = null) {
        try {
            if (tr != null) {
                android.util.Log.w(tag, msg, tr)
            } else {
                android.util.Log.w(tag, msg)
            }
        } catch (_: Throwable) {
            println("[$tag] WARN: $msg" + (tr?.let { " - ${it.message}" } ?: ""))
        }
    }

    fun e(tag: String, msg: String, tr: Throwable? = null) {
        try {
            if (tr != null) {
                android.util.Log.e(tag, msg, tr)
            } else {
                android.util.Log.e(tag, msg)
            }
        } catch (_: Throwable) {
            println("[$tag] ERROR: $msg" + (tr?.let { " - ${it.message}" } ?: ""))
        }
    }
}
