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

    fun associationFor(address: String): AssociationInfo? {
        val canonicalAddress = BluetoothAddress.normalize(address) ?: return null
        return associations().firstOrNull {
            BluetoothAddress.normalize(it.deviceMacAddress?.toString()) == canonicalAddress
        }
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
        val canonicalAddress = address?.let(BluetoothAddress::normalize)
        if (address != null && canonicalAddress == null) {
            onFailure("Invalid Bluetooth address")
            return
        }
        val scanFilter = ScanFilter.Builder()
            .setManufacturerData(
                Constants.SONY_MANUFACTURER_ID,
                Constants.SONY_DEVICE_TYPE_CAMERA,
                byteArrayOf(0xff.toByte(), 0xff.toByte()),
            )
            .apply { canonicalAddress?.let(::setDeviceAddress) }
            .build()
        val deviceFilter = BluetoothLeDeviceFilter.Builder().setScanFilter(scanFilter).build()
        val request = AssociationRequest.Builder()
            .addDeviceFilter(deviceFilter)
            .setSingleDevice(canonicalAddress != null)
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
        val rawAddress = info.deviceMacAddress?.toString() ?: return
        val address = BluetoothAddress.normalize(rawAddress)
        if (address == null) {
            Log.w("CompanionCameraManager", "Ignoring association ${info.id} with invalid address: $rawAddress")
            return
        }
        if (!AppSettingsManager.hasSavedCamera(context, address)) {
            AppSettingsManager.addSavedCamera(context, address, Constants.PROTOCOL_VERSION_CREATORS_APP)
        }
        AppSettingsManager.updateAssociation(context, address, info.id)
        val request = ObservingDevicePresenceRequest.Builder().setAssociationId(info.id).build()
        val powerAwareScanArmed = SonyPresenceScanner.ensureRegistered(context, address)
        val companionPresenceArmed = runCatching { manager.startObservingDevicePresence(request) }
            .onFailure {
                // Re-registering an observation is harmless on builds that report it as
                // already active. Keep the PendingIntent scan armed as an independent path.
                Log.i("CompanionCameraManager", "Presence observation already active or unavailable for $address", it)
            }
            .isSuccess
        ReconnectionMetrics.event(
            context,
            "companion_observation_armed",
            mapOf(
                "address" to address,
                "associationId" to info.id,
                "strategy" to "hybrid_companion_and_power_scan",
                "companionPresenceArmed" to companionPresenceArmed,
                "powerAwareScanArmed" to powerAwareScanArmed,
            ),
        )
    }

    fun removeAssociation(address: String) {
        val canonicalAddress = BluetoothAddress.normalize(address) ?: return
        val info = associationFor(canonicalAddress) ?: return
        val request = ObservingDevicePresenceRequest.Builder().setAssociationId(info.id).build()
        runCatching { manager.stopObservingDevicePresence(request) }
        SonyPresenceScanner.stop(context, canonicalAddress)
        manager.disassociate(info.id)
    }
}
