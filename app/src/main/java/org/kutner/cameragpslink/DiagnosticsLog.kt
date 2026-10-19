package org.kutner.cameragpslink

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.PowerManager
import android.os.Process
import android.util.Log
import java.io.File
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Adapted from upstream's 53641dc diagnostic approach (MIT license).
 * No polling or network: only lifecycle/error events, written off the callback/UI thread.
 */
object DiagnosticsLog {
    private const val TAG = "CglDiagnostics"
    private const val FILE_NAME = "diagnostics.log"
    private val writer = Executors.newSingleThreadExecutor { task ->
        Thread(task, "camera-diagnostics").apply { isDaemon = true }
    }
    private val stores = ConcurrentHashMap<String, RotatingLogFile>()
    private var installed = false
    private var lastStateSummary: String? = null

    private fun store(context: Context, name: String): RotatingLogFile {
        val file = File(context.filesDir, name)
        return stores.getOrPut(file.absolutePath) { RotatingLogFile(file) }
    }

    private fun persist(context: Context, message: String) {
        runCatching { store(context, FILE_NAME).append("${Instant.now()} $message") }
            .onFailure { Log.w(TAG, "Unable to persist diagnostics", it) }
    }

    fun append(context: Context, message: String) {
        Log.i(TAG, message)
        val appContext = context.applicationContext
        writer.execute { persist(appContext, message) }
    }

    fun appendEvent(context: Context, record: String) {
        val appContext = context.applicationContext
        writer.execute {
            runCatching { store(appContext, "reconnection_metrics.ndjson").append(record) }
                .onFailure { Log.w(TAG, "Unable to persist reconnection event", it) }
        }
    }

    @Synchronized
    fun initialize(context: Context) {
        if (installed) return
        installed = true
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            // Crash evidence must be flushed before delegating to Android's crash handler.
            try {
                persist(appContext, "Uncaught exception on ${thread.name}: ${Log.getStackTraceString(error)}")
            } finally {
                if (previous != null) previous.uncaughtException(thread, error)
                else Process.killProcess(Process.myPid())
            }
        }
        append(appContext, "Process started pid=${Process.myPid()} package=${appContext.packageName}")
        writer.execute {
            runCatching { recordEnvironment(appContext) }
                .onFailure { persist(appContext, "Environment unavailable: $it") }
            runCatching { recordPreviousExits(appContext) }
                .onFailure { persist(appContext, "Process exit history unavailable: $it") }
        }
    }

    private fun recordEnvironment(context: Context) {
        val power = context.getSystemService(PowerManager::class.java)
        val activity = context.getSystemService(ActivityManager::class.java)
        val usage = context.getSystemService(UsageStatsManager::class.java)
        val version = context.packageManager.getPackageInfo(context.packageName, 0).versionName
        persist(context, "Environment version=$version idle=${power.isDeviceIdleMode} " +
            "powerSave=${power.isPowerSaveMode} backgroundRestricted=${activity.isBackgroundRestricted} " +
            "standbyBucket=${usage.appStandbyBucket} " +
            "batteryExempt=${power.isIgnoringBatteryOptimizations(context.packageName)}")
    }

    private fun recordPreviousExits(context: Context) {
        val prefs = context.getSharedPreferences("diagnostics", Context.MODE_PRIVATE)
        val lastTimestamp = prefs.getLong("last_exit_timestamp", 0)
        val activity = context.getSystemService(ActivityManager::class.java)
        val exits = activity.getHistoricalProcessExitReasons(null, 0, 16)
            .filter { it.timestamp > lastTimestamp }.sortedBy { it.timestamp }
        exits.forEach { exit ->
            persist(context, "Previous process exit: $exit lastState=" +
                exit.processStateSummary?.toString(Charsets.UTF_8))
            if (exit.reason == ApplicationExitInfo.REASON_ANR) {
                runCatching {
                    exit.traceInputStream?.bufferedReader()?.use { reader ->
                        val trace = CharArray(4096)
                        val length = reader.read(trace)
                        if (length > 0) persist(context, "ANR trace: ${String(trace, 0, length)}")
                    }
                }.onFailure { persist(context, "ANR trace unavailable: $it") }
            }
        }
        exits.lastOrNull()?.let { prefs.edit().putLong("last_exit_timestamp", it.timestamp).apply() }
    }

    @Synchronized
    fun setProcessState(context: Context, summary: String) {
        if (summary == lastStateSummary) return
        runCatching {
            context.getSystemService(ActivityManager::class.java)
                .setProcessStateSummary(summary.toByteArray(Charsets.UTF_8).take(128).toByteArray())
            lastStateSummary = summary
        }.onFailure { Log.w(TAG, "Unable to set process state summary", it) }
    }

    fun read(context: Context): String = runCatching { store(context, FILE_NAME).read() }
        .getOrElse { "Unable to read diagnostics: $it" }
}
