package org.kutner.cameragpslink

import android.annotation.SuppressLint
import android.bluetooth.le.ScanFilter
import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothLeDeviceFilter
import android.companion.CompanionDeviceManager
import android.companion.ObservingDevicePresenceRequest
import android.content.Context
import android.content.IntentSender
import android.util.Log

class CompanionCameraManager(private val context: Context) {
    private val manager = context.getSystemService(CompanionDeviceManager::class.java)

    fun associations(): List<AssociationInfo> = manager.myAssociations

    fun associationFor(address: String): AssociationInfo? = associations().firstOrNull {
        it.deviceMacAddress?.toString().equals(address, ignoreCase = true)
    }

    fun missingAssociations(): List<String> = AppSettingsManager.getSavedCameras(context).filter {
        associationFor(it) == null
    }

    fun requestAssociation(
        address: String? = null,
        onPending: (IntentSender) -> Unit,
        onCreated: (AssociationInfo) -> Unit,
        onFailure: (CharSequence?) -> Unit,
    ) {
        val scanFilter = ScanFilter.Builder()
            .setManufacturerData(
                Constants.SONY_MANUFACTURER_ID,
                Constants.SONY_DEVICE_TYPE_CAMERA,
                byteArrayOf(0xff.toByte(), 0xff.toByte()),
            )
            .apply { address?.let(::setDeviceAddress) }
            .build()
        val deviceFilter = BluetoothLeDeviceFilter.Builder().setScanFilter(scanFilter).build()
        val request = AssociationRequest.Builder()
            .addDeviceFilter(deviceFilter)
            .setSingleDevice(address != null)
            .build()

        manager.associate(request, context.mainExecutor, object : CompanionDeviceManager.Callback() {
            override fun onAssociationPending(intentSender: IntentSender) = onPending(intentSender)

            override fun onAssociationCreated(associationInfo: AssociationInfo) {
                register(associationInfo)
                onCreated(associationInfo)
            }

            override fun onFailure(error: CharSequence?) = onFailure(error)

            override fun onFailure(errorCode: Int, error: CharSequence?) = onFailure(error)
        })
    }

    fun reconcileAndObserve() {
        associations().forEach(::register)
    }

    @SuppressLint("MissingPermission")
    private fun register(info: AssociationInfo) {
        val address = info.deviceMacAddress?.toString() ?: return
        if (!AppSettingsManager.hasSavedCamera(context, address)) {
            AppSettingsManager.addSavedCamera(context, address, Constants.PROTOCOL_VERSION_CREATORS_APP)
        }
        AppSettingsManager.updateAssociation(context, address, info.id)
        val request = ObservingDevicePresenceRequest.Builder().setAssociationId(info.id).build()
        val powerAwareScanArmed = SonyPresenceScanner.ensureRegistered(context, address)
        if (powerAwareScanArmed) {
            // Sony's power-off beacon counts as "present". Keeping CompanionDeviceService
            // bound for that beacon would retain our process unnecessarily, so the exact
            // manufacturer-bit PendingIntent scan is preferred for normal operation.
            runCatching { manager.stopObservingDevicePresence(request) }
        } else {
            runCatching { manager.startObservingDevicePresence(request) }
                .onFailure { Log.w("CompanionCameraManager", "Presence fallback failed for $address", it) }
        }
        ReconnectionMetrics.event(
            context,
            "companion_observation_armed",
            mapOf(
                "address" to address,
                "associationId" to info.id,
                "strategy" to if (powerAwareScanArmed) "power_aware_pending_intent" else "companion_presence_fallback",
            ),
        )
    }

    fun removeAssociation(address: String) {
        val info = associationFor(address) ?: return
        val request = ObservingDevicePresenceRequest.Builder().setAssociationId(info.id).build()
        runCatching { manager.stopObservingDevicePresence(request) }
        SonyPresenceScanner.stop(context, address)
        manager.disassociate(info.id)
    }
}
