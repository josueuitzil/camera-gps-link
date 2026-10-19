package org.kutner.cameragpslink

import android.app.ForegroundServiceStartNotAllowedException
import android.content.Context

/** Policy rejection is a recoverable condition, never a reason to restart/crash in a loop. */
object SessionStartGuard {
    private const val PREFS = "session_start_guard"
    private const val PENDING = "rejected_cameras"

    fun run(context: Context, address: String, stage: String, start: () -> Unit): Boolean = try {
        start()
        true
    } catch (error: SecurityException) {
        rejected(context, address, stage, error)
    } catch (error: ForegroundServiceStartNotAllowedException) {
        rejected(context, address, stage, error)
    }

    private fun rejected(context: Context, address: String, stage: String, error: Exception): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        synchronized(this) {
            val pending = prefs.getStringSet(PENDING, emptySet()).orEmpty() + address
            prefs.edit().putStringSet(PENDING, pending).apply()
        }
        DiagnosticsLog.append(context, "Session start rejected at $stage for $address: $error")
        ReconnectionMetrics.event(context, "session_start_rejected", mapOf(
            "address" to address, "stage" to stage, "reason" to error.javaClass.simpleName,
        ))
        // This is an actionable error, not an idle searching service or a recurring reminder.
        NotificationHelper(context).showSessionBlockedNotification()
        return false
    }

    fun permissionsMissing(context: Context, address: String) {
        rejected(context, address, "permissions", SecurityException("Bluetooth/background location access missing"))
    }

    @Synchronized
    fun clearRejection(context: Context, address: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().putStringSet(PENDING, prefs.getStringSet(PENDING, emptySet()).orEmpty() - address).apply()
    }

    fun rearmRejectedSessions(context: Context) {
        val pending = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(PENDING, emptySet()).orEmpty().toSet()
        val companions = CompanionCameraManager(context)
        pending.forEach { address ->
            if (companions.associationFor(address) != null && SonyPresenceScanner.restart(context, address)) {
                // Opening the notification after fixing permissions must retrigger even
                // if FIRST_MATCH was consumed earlier. Never connect to an off camera.
                clearRejection(context, address)
                DiagnosticsLog.append(context, "Rearmed rejected session for $address from the UI")
            }
        }
    }
}
