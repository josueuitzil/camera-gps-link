package org.kutner.cameragpslink

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log

object SonyPresenceScanner {
    private const val TAG = "SonyPresenceScanner"
    private const val FILTER_SCHEMA_VERSION = 2

    private fun pendingIntent(context: Context, address: String, schemaVersion: Int? = FILTER_SCHEMA_VERSION): PendingIntent {
        val intent = Intent(context, SonyPresenceScanReceiver::class.java).apply {
            val versionPath = schemaVersion?.let { "/v$it" }.orEmpty()
            data = Uri.parse("cameragpslink://sony-presence$versionPath/${address.replace(':', '-')}")
            putExtra(Constants.EXTRA_DEVICE_ADDRESS, address)
        }
        return PendingIntent.getBroadcast(
            context,
            address.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
    }

    private fun scanner(context: Context): BluetoothLeScanner? =
        context.getSystemService(BluetoothManager::class.java).adapter.bluetoothLeScanner

    @SuppressLint("MissingPermission")
    fun ensureRegistered(context: Context, address: String): Boolean {
        val canonicalAddress = BluetoothAddress.normalize(address)
        if (canonicalAddress == null) {
            Log.w(TAG, "Refusing to register scan for invalid Bluetooth address: $address")
            ReconnectionMetrics.event(
                context,
                "pending_intent_scan_invalid_address",
                mapOf("address" to address),
            )
            return false
        }
        val filter = ScanFilter.Builder()
            .setDeviceAddress(canonicalAddress)
            .setManufacturerData(
                Constants.SONY_MANUFACTURER_ID,
                SonyAdvertisement.poweredOnFilterData,
                SonyAdvertisement.poweredOnFilterMask,
            )
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_FIRST_MATCH or ScanSettings.CALLBACK_TYPE_MATCH_LOST)
            .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
            .setNumOfMatches(ScanSettings.MATCH_NUM_ONE_ADVERTISEMENT)
            .build()
        val result = runCatching {
            val bluetoothScanner = scanner(context) ?: return@runCatching ScanCallback.SCAN_FAILED_INTERNAL_ERROR
            val scanIntent = pendingIntent(context, canonicalAddress)
            // A PendingIntent registration keeps its original filter. Retire the
            // unversioned schema once, while leaving an existing current scan intact.
            bluetoothScanner.stopScan(pendingIntent(context, canonicalAddress, schemaVersion = null))
            bluetoothScanner.startScan(listOf(filter), settings, scanIntent)
        }.getOrElse {
            Log.w(TAG, "Unable to register scan for $canonicalAddress", it)
            ScanCallback.SCAN_FAILED_INTERNAL_ERROR
        }
        val success = result == 0 || result == ScanCallback.SCAN_FAILED_ALREADY_STARTED
        ReconnectionMetrics.markPendingScanRegistered(context, canonicalAddress, result, success)
        return success
    }

    @SuppressLint("MissingPermission")
    fun stop(context: Context, address: String) {
        val canonicalAddress = BluetoothAddress.normalize(address) ?: return
        runCatching {
            scanner(context)?.let { bluetoothScanner ->
                bluetoothScanner.stopScan(pendingIntent(context, canonicalAddress, schemaVersion = null))
                bluetoothScanner.stopScan(pendingIntent(context, canonicalAddress))
            }
        }
        ReconnectionMetrics.markPendingScanStopped(context, canonicalAddress)
    }
}

class SonyPresenceScanReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val address = BluetoothAddress.normalize(
            intent.getStringExtra(Constants.EXTRA_DEVICE_ADDRESS),
        ) ?: return
        val error = intent.getIntExtra(BluetoothLeScanner.EXTRA_ERROR_CODE, 0)
        if (error != 0) {
            ReconnectionMetrics.event(context, "pending_intent_scan_error", mapOf("address" to address, "error" to error))
            return
        }

        val callbackType = intent.getIntExtra(BluetoothLeScanner.EXTRA_CALLBACK_TYPE, -1)
        when (callbackType) {
            ScanSettings.CALLBACK_TYPE_MATCH_LOST -> {
                ReconnectionMetrics.event(context, "camera_match_lost", mapOf("address" to address))
                CameraSyncService.notifyCameraAbsent(address)
            }
            else -> {
                ReconnectionMetrics.markSignal(context, address, "pending_intent_power_filter")
                CameraSyncService.startForCamera(context, address)
            }
        }
    }
}
