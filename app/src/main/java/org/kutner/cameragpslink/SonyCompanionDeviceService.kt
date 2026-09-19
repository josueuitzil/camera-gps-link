package org.kutner.cameragpslink

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.companion.CompanionDeviceService
import android.companion.DevicePresenceEvent
import android.os.Handler
import android.os.Looper

class SonyCompanionDeviceService : CompanionDeviceService() {
    private data class PowerConfirmation(
        val callback: ScanCallback,
        val timeout: Runnable,
    )

    private val handler = Handler(Looper.getMainLooper())
    private val confirmations = mutableMapOf<String, PowerConfirmation>()

    override fun onDevicePresenceEvent(event: DevicePresenceEvent) {
        val info = getSystemService(android.companion.CompanionDeviceManager::class.java)
            .myAssociations
            .firstOrNull { it.id == event.associationId }
            ?: return
        val address = BluetoothAddress.normalize(info.deviceMacAddress?.toString()) ?: return

        when (event.event) {
            DevicePresenceEvent.EVENT_BLE_APPEARED -> {
                ReconnectionMetrics.event(
                    this,
                    "companion_device_appeared",
                    mapOf("address" to address, "eventType" to event.event),
                )
                // Companion presence is intentionally broad: Sony can advertise while
                // powered off. Confirm the on-bit with a short exact scan before starting
                // GATT, location, or a foreground service.
                SonyPresenceScanner.ensureRegistered(this, address)
                confirmCameraPoweredOn(address)
            }
            DevicePresenceEvent.EVENT_BLE_DISAPPEARED,
            DevicePresenceEvent.EVENT_BT_DISCONNECTED -> {
                stopPowerConfirmation(address, "device_disappeared")
                ReconnectionMetrics.event(
                    this,
                    "companion_device_disappeared",
                    mapOf("address" to address, "eventType" to event.event),
                )
                CameraSyncService.notifyCameraAbsent(address)
            }
            DevicePresenceEvent.EVENT_BT_CONNECTED -> {
                ReconnectionMetrics.event(
                    this,
                    "companion_device_bt_connected",
                    mapOf("address" to address, "eventType" to event.event),
                )
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun confirmCameraPoweredOn(address: String) {
        if (confirmations.containsKey(address)) return
        val scanner = getSystemService(BluetoothManager::class.java)
            .adapter
            .bluetoothLeScanner
            ?: run {
                ReconnectionMetrics.event(
                    this,
                    "companion_power_confirmation_unavailable",
                    mapOf("address" to address),
                )
                return
            }
        val filter = ScanFilter.Builder()
            .setDeviceAddress(address)
            .setManufacturerData(
                Constants.SONY_MANUFACTURER_ID,
                SonyAdvertisement.poweredOnFilterData,
                SonyAdvertisement.poweredOnFilterMask,
            )
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
            .setNumOfMatches(ScanSettings.MATCH_NUM_ONE_ADVERTISEMENT)
            .build()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val sonyData = result.scanRecord
                    ?.getManufacturerSpecificData(Constants.SONY_MANUFACTURER_ID)
                    ?: return
                if (SonyAdvertisement.parse(sonyData)?.isPoweredOn != true) return
                stopPowerConfirmation(address, "powered_on_match")
                ReconnectionMetrics.markSignal(
                    this@SonyCompanionDeviceService,
                    address,
                    "companion_presence_confirmed",
                )
                CameraSyncService.startForCamera(this@SonyCompanionDeviceService, address)
            }

            override fun onScanFailed(errorCode: Int) {
                stopPowerConfirmation(address, "scan_failed_$errorCode")
            }
        }
        val timeout = Runnable { stopPowerConfirmation(address, "timeout") }
        confirmations[address] = PowerConfirmation(callback, timeout)
        ReconnectionMetrics.event(
            this,
            "companion_power_confirmation_started",
            mapOf("address" to address),
        )
        runCatching { scanner.startScan(listOf(filter), settings, callback) }
            .onSuccess { handler.postDelayed(timeout, POWER_CONFIRMATION_TIMEOUT_MS) }
            .onFailure { stopPowerConfirmation(address, "start_failed") }
    }

    @SuppressLint("MissingPermission")
    private fun stopPowerConfirmation(address: String, outcome: String) {
        val confirmation = confirmations.remove(address) ?: return
        handler.removeCallbacks(confirmation.timeout)
        runCatching {
            getSystemService(BluetoothManager::class.java)
                .adapter
                .bluetoothLeScanner
                ?.stopScan(confirmation.callback)
        }
        ReconnectionMetrics.event(
            this,
            "companion_power_confirmation_finished",
            mapOf("address" to address, "outcome" to outcome),
        )
    }

    override fun onDestroy() {
        confirmations.keys.toList().forEach { stopPowerConfirmation(it, "service_destroyed") }
        super.onDestroy()
    }

    private companion object {
        const val POWER_CONFIRMATION_TIMEOUT_MS = 12_000L
    }
}
