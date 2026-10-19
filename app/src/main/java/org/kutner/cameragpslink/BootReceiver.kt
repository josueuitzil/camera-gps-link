package org.kutner.cameragpslink

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            DiagnosticsLog.append(context, "Rearming companion observation after ${intent.action}")
            // Re-register durable system callbacks only. No process or foreground
            // service remains running once this receiver returns.
            runCatching { CompanionCameraManager(context).reconcileAndObserve() }
                .onFailure {
                    ReconnectionMetrics.event(
                        context,
                        "boot_rearm_failed",
                        mapOf("action" to intent.action, "error" to it.javaClass.simpleName),
                    )
                }
            ReconnectionMetrics.event(context, "boot_rearm_completed", mapOf("action" to intent.action))
        }
    }


}
