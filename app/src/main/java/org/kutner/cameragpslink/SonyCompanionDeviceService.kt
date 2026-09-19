package org.kutner.cameragpslink

import android.companion.CompanionDeviceService
import android.companion.DevicePresenceEvent

class SonyCompanionDeviceService : CompanionDeviceService() {
    override fun onDevicePresenceEvent(event: DevicePresenceEvent) {
        val info = getSystemService(android.companion.CompanionDeviceManager::class.java)
            .myAssociations
            .firstOrNull { it.id == event.associationId }
            ?: return
        val address = BluetoothAddress.normalize(info.deviceMacAddress?.toString()) ?: return

        when (event.event) {
            DevicePresenceEvent.EVENT_BLE_APPEARED,
            DevicePresenceEvent.EVENT_BT_CONNECTED -> {
                ReconnectionMetrics.event(
                    this,
                    "companion_device_appeared",
                    mapOf("address" to address, "eventType" to event.event),
                )
                // This service is only observed when the power-aware scan could not be
                // registered, so presence is deliberately the less precise fallback.
                SonyPresenceScanner.ensureRegistered(this, address)
                ReconnectionMetrics.markSignal(this, address, "companion_presence_fallback")
                CameraSyncService.startForCamera(this, address)
            }
            DevicePresenceEvent.EVENT_BLE_DISAPPEARED,
            DevicePresenceEvent.EVENT_BT_DISCONNECTED -> {
                ReconnectionMetrics.event(
                    this,
                    "companion_device_disappeared",
                    mapOf("address" to address, "eventType" to event.event),
                )
                CameraSyncService.notifyCameraAbsent(address)
            }
        }
    }
}
