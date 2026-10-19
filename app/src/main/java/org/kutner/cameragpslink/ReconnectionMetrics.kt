package org.kutner.cameragpslink

import android.content.Context
import android.os.SystemClock
import android.util.Log
import org.json.JSONObject
import java.io.File

/** Persistent, low-volume instrumentation for field reconnection comparisons. */
object ReconnectionMetrics {
    private const val TAG = "CglMetrics"
    private const val PREFS = "reconnection_metrics"
    private const val EVENTS_FILE = "reconnection_metrics.ndjson"

    fun event(context: Context, name: String, fields: Map<String, Any?> = emptyMap()) {
        val record = JSONObject().apply {
            put("event", name)
            put("wallTimeMs", System.currentTimeMillis())
            put("elapsedRealtimeMs", SystemClock.elapsedRealtime())
            fields.forEach { (key, value) -> put(key, value ?: JSONObject.NULL) }
        }
        Log.i(TAG, record.toString())
        DiagnosticsLog.appendEvent(context, record.toString())
    }

    fun increment(context: Context, key: String, amount: Long = 1) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().putLong(key, prefs.getLong(key, 0) + amount).apply()
    }

    fun markSignal(context: Context, address: String, source: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong("signal_$address", SystemClock.elapsedRealtime())
            .apply()
        increment(context, "presence_signals")
        event(context, "camera_signal", mapOf("address" to address, "source" to source))
    }

    fun markConnected(context: Context, address: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val signal = prefs.getLong("signal_$address", 0)
        val latency = if (signal > 0) SystemClock.elapsedRealtime() - signal else null
        increment(context, "gatt_connections")
        latency?.let {
            increment(context, "gatt_connection_latency_total_ms", it)
            increment(context, "gatt_connection_latency_samples")
        }
        event(context, "gatt_connected", mapOf("address" to address, "latencyFromSignalMs" to latency))
    }

    fun markFirstLocation(context: Context, address: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val signal = prefs.getLong("signal_$address", 0)
        val latency = if (signal > 0) SystemClock.elapsedRealtime() - signal else null
        increment(context, "first_location_writes")
        latency?.let {
            increment(context, "first_location_latency_total_ms", it)
            increment(context, "first_location_latency_samples")
        }
        event(context, "first_location_write", mapOf("address" to address, "latencyFromSignalMs" to latency))
    }

    fun markForegroundStarted(context: Context, address: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.contains("fgs_started_elapsed")) {
            prefs.edit().putLong("fgs_started_elapsed", SystemClock.elapsedRealtime()).apply()
            increment(context, "foreground_service_starts")
        }
        event(context, "foreground_service_started", mapOf("address" to address))
    }

    fun markForegroundStopped(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val started = prefs.getLong("fgs_started_elapsed", 0)
        if (started > 0) {
            increment(context, "foreground_service_total_ms", SystemClock.elapsedRealtime() - started)
            increment(context, "foreground_service_stops")
            prefs.edit().remove("fgs_started_elapsed").apply()
        }
        event(context, "foreground_service_stopped")
    }

    fun markPendingScanRegistered(context: Context, address: String, result: Int, success: Boolean) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = "scan_started_$address"
        if (success && !prefs.contains(key)) {
            prefs.edit().putLong(key, SystemClock.elapsedRealtime()).apply()
            increment(context, "pending_intent_scan_registrations")
        }
        event(
            context,
            "pending_intent_scan_registered",
            mapOf("address" to address, "result" to result, "success" to success),
        )
    }

    fun markPendingScanStopped(context: Context, address: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = "scan_started_$address"
        val started = prefs.getLong(key, 0)
        if (started > 0) {
            increment(context, "pending_intent_scan_registered_total_ms", SystemClock.elapsedRealtime() - started)
            prefs.edit().remove(key).apply()
        }
        event(context, "pending_intent_scan_stopped", mapOf("address" to address))
    }

    fun summary(context: Context): String {
        val values = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).all.toSortedMap()
        return buildString {
            appendLine("--- Reconnection metrics ---")
            values.forEach { (key, value) ->
                if (!key.startsWith("signal_") && !key.startsWith("scan_started_")) appendLine("$key=$value")
            }
            append("events=${File(context.filesDir, EVENTS_FILE).absolutePath}")
        }
    }
}
