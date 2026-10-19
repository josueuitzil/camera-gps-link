package org.kutner.cameragpslink

import android.Manifest
import android.annotation.SuppressLint
import android.app.Service
import android.content.pm.ServiceInfo
import android.bluetooth.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Location
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.app.ServiceCompat
import com.google.android.gms.location.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs

data class CameraConnection(
    val device: BluetoothDevice,
    var gatt: BluetoothGatt? = null,
    var isConnected: Boolean = false,
    var isConnecting: Boolean = false,
    var isBonded: Boolean = true,
    var locationUpdateRunnable: Runnable? = null,
    var disconnectTimestamp: Long? = null,
    var isRcProbeErrorReceived: Boolean = false,
    var retries: Long = 0,
    var isRemoteControlEnabled: Boolean = false,
    var bondAttempts: Int = 0,
    var timeSyncAttempts: Int = 0,
    var isInitialLocationWritePending: Boolean = false,
    var isLocationStreamStartPending: Boolean = false,
    var isPresent: Boolean = false,
    var connectAttempts: Int = 0,
    var connectTimeoutRunnable: Runnable? = null,
    var reconnectRunnable: Runnable? = null,
    var gattQueue: GattOperationQueue? = null,
    var firstLocationWritten: Boolean = false,
) {
    // Override equals and hashCode to ensure updates are detected
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CameraConnection) return false
        return device.address == other.device.address &&
                isConnected == other.isConnected &&
                isConnecting == other.isConnecting &&
                isBonded == other.isBonded
    }

    override fun hashCode(): Int {
        var result = device.address.hashCode()
        result = 31 * result + isConnected.hashCode()
        result = 31 * result + isConnecting.hashCode()
        result = 31 * result + isBonded.hashCode()
        return result
    }
}

class CameraSyncService : Service() {

    // --- Constants ---
    companion object {
        private const val TAG = "CameraSyncService"
        @Volatile private var currentInstance: CameraSyncService? = null

        fun startForCamera(context: Context, address: String): Boolean {
            val canonicalAddress = BluetoothAddress.normalize(address) ?: return false
            val intent = Intent(context, CameraSyncService::class.java).apply {
                action = Constants.ACTION_CONNECT_ASSOCIATED_CAMERA
                putExtra(Constants.EXTRA_DEVICE_ADDRESS, canonicalAddress)
            }
            return SessionStartGuard.run(context, canonicalAddress, "startForegroundService") {
                ContextCompat.startForegroundService(context, intent)
            }
        }

        fun notifyCameraAbsent(address: String) {
            BluetoothAddress.normalize(address)?.let { currentInstance?.handleCameraAbsent(it) }
        }
    }

    // --- Service components ---
    private val binder = LocalBinder()
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var notificationHelper: NotificationHelper

    // --- Bluetooth & Location ---
    private lateinit var bluetoothManager: BluetoothManager
    private lateinit var bluetoothAdapter: BluetoothAdapter
    private lateinit var bleScanner: android.bluetooth.le.BluetoothLeScanner
    private lateinit var fusedLocationClient: FusedLocationProviderClient

    // --- Multiple camera connections ---
    private val cameraConnections = Collections.synchronizedMap(mutableMapOf<String, CameraConnection>())

    // --- State for UI ---
    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log

    private val _connectedCameras = MutableStateFlow<List<CameraConnection>>(emptyList())
    val connectedCameras: StateFlow<List<CameraConnection>> = _connectedCameras

    private var lastKnownLocation: Location? = null
    private lateinit var locationCallback: LocationCallback

    private val _isRemoteControlEnabled = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val isRemoteControlEnabled: StateFlow<Map<String, Boolean>> = _isRemoteControlEnabled

    private val _isFocusAcquired = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val isFocusAcquired: StateFlow<Map<String, Boolean>> = _isFocusAcquired

    private val _isShutterReady = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val isShutterReady: StateFlow<Map<String, Boolean>> = _isShutterReady

    private val _isRecordingVideo = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val isRecordingVideo: StateFlow<Map<String, Boolean>> = _isRecordingVideo

    private val _shutterTriggeredFromNotification = MutableStateFlow<String?>(null)
    private val shutterTriggeredFromNotification: StateFlow<String?> = _shutterTriggeredFromNotification

    private val _shutterErrorMessage = MutableStateFlow<String?>(null)
    val shutterErrorMessage: StateFlow<String?> = _shutterErrorMessage

    private val _showBondingErrorDialog = MutableStateFlow<String?>(null)
    val showBondingErrorDialog: StateFlow<String?> = _showBondingErrorDialog

    private var isForegroundServiceStarted = false
    private var isFetchingLocation = false

    inner class LocalBinder : Binder() {
        fun getService(): CameraSyncService = this@CameraSyncService
    }

    override fun onBind(intent: Intent): IBinder = binder

    // --- Broadcast Receiver for Bluetooth State ---
    private val bluetoothStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                if (state == BluetoothAdapter.STATE_ON) {
                    log("Bluetooth turned on, rearming system-managed camera detection...")
                    bluetoothAdapter.bluetoothLeScanner?.let {
                        bleScanner = it
                        CompanionCameraManager(this@CameraSyncService).reconcileAndObserve()
                    }
                } else if (state == BluetoothAdapter.STATE_OFF) {
                    cameraConnections.keys.toList().forEach(::handleCameraAbsent)
                }
            }
        }
    }

    // --- Broadcast Receiver for Bonding ---
    private val bondStateReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == BluetoothDevice.ACTION_BOND_STATE_CHANGED) {
                val device = intent.getParcelableExtra(
                    BluetoothDevice.EXTRA_DEVICE,
                    BluetoothDevice::class.java,
                ) ?: return

                val state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)
                val previousState = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, BluetoothDevice.ERROR)

                // Check if the state changed from BONDING to BONDED
                if (state == BluetoothDevice.BOND_BONDED && previousState == BluetoothDevice.BOND_BONDING) {
                    log("Bond established with ${device.address}.")

                    val connection = cameraConnections[device.address]
                    // If we have an active GATT connection, proceed to discover services
                    // This resumes the flow interrupted in onConnectionStateChange or onMtuChanged
                    if (connection?.gatt != null) {
                        connection.bondAttempts = 0 // Reset attempts counter
                        connection.isBonded = true
                        log("Proceeding with service discovery for ${device.address}...")

                        // Add a small delay to allow the Bluetooth stack to stabilize after bonding
                        handler.postDelayed({
                            try {
                                connection.gatt?.discoverServices()
                            } catch (e: Exception) {
                                log("Error discovering services after bond: ${e.message}")
                            }
                        }, 500)
                    }
                } else if (state == BluetoothDevice.BOND_NONE && previousState == BluetoothDevice.BOND_BONDING) {
                    log("Bonding failed for ${device.address}.")
                    val connection = cameraConnections[device.address]
                    connection?.isBonded = false
                }
                updateConnectionsList()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        currentInstance = this
        val localizedContext = LanguageManager.wrapContext(baseContext)
        notificationHelper = NotificationHelper(localizedContext)

        // Register the Bond State Receiver
        val filter = IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        registerReceiver(bondStateReceiver, filter)

        // Register the Bluetooth State Receiver (to recover if BT was off at startup)
        val btStateFilter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        registerReceiver(bluetoothStateReceiver, btStateFilter)

        initializeBluetoothAndLocation()
        loadSavedCameras()
        DiagnosticsLog.append(this, "CameraSyncService created")
        log("Service created.")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        log("Service received start command: ${intent?.action}.")
        DiagnosticsLog.append(this, "Session start command action=${intent?.action} flags=$flags startId=$startId")

        // Handle shutter action from notification
        if (intent?.action == Constants.ACTION_TRIGGER_SHUTTER) {
            val deviceAddress = intent.getStringExtra(Constants.EXTRA_DEVICE_ADDRESS)
            if (deviceAddress != null) {
                triggerShutter(deviceAddress, fromNotification = true)
            } else {
                log("Received shutter action without device address.")
            }
            return START_NOT_STICKY
        }

        if (intent?.action == Constants.ACTION_CONNECT_ASSOCIATED_CAMERA) {
            val address = BluetoothAddress.normalize(
                intent.getStringExtra(Constants.EXTRA_DEVICE_ADDRESS),
            )
            if (address == null) {
                stopSelfResult(startId)
                return START_NOT_STICKY
            }
            if (!hasRequiredPermissions()) {
                log("Cannot connect $address: Bluetooth/background location permissions are missing.")
                ReconnectionMetrics.event(this, "session_permission_failure", mapOf("address" to address))
                SessionStartGuard.permissionsMissing(this, address)
                stopSelfResult(startId)
                return START_NOT_STICKY
            }
            if (!startSessionForeground(address)) {
                stopSelfResult(startId)
                return START_NOT_STICKY
            }
            connectAssociatedCamera(address)
            return START_REDELIVER_INTENT
        }

        if (!hasRequiredPermissions()) {
            log("Permissions not granted. Stopping.")
            stopService()
            return START_NOT_STICKY
        }

        // Binding the service for UI does not arm a foreground service or scan.
        return START_NOT_STICKY
    }

    @SuppressLint("MissingPermission")
    override fun onDestroy() {
        currentInstance = null
        super.onDestroy()
        // Unregister broadcast receivers
        try {
            unregisterReceiver(bondStateReceiver)
        } catch (e: IllegalArgumentException) {
            // Receiver not registered or already unregistered
        }
        try {
            unregisterReceiver(bluetoothStateReceiver)
        } catch (e: IllegalArgumentException) {
            // Receiver not registered or already unregistered
        }

        cameraConnections.values.forEach { connection ->
            stopLocationUpdates(connection)
            cancelConnectionTimers(connection)
            connection.gattQueue?.clear()
            connection.gatt?.close()
        }
        cameraConnections.clear()
        stopService()
        log("Service destroyed.")
        DiagnosticsLog.append(this, "CameraSyncService destroyed")
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        DiagnosticsLog.append(this, "Task removed from Recents")
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        DiagnosticsLog.append(this, "Memory pressure level=$level")
    }

    private fun startSessionForeground(deviceAddress: String): Boolean {
        if (!isForegroundServiceStarted) {
            log("Starting on-demand foreground session for ${getDeviceDisplayString(deviceAddress)}...")
            val started = SessionStartGuard.run(this, deviceAddress, "startForeground") {
                ServiceCompat.startForeground(
                    this,
                    Constants.NOTIFICATION_ID_FOREGROUND_SERVICE,
                    notificationHelper.createConnectingNotification(deviceAddress),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
                )
            }
            if (!started) return false
            isForegroundServiceStarted = true
            SessionStartGuard.clearRejection(this, deviceAddress)
            notificationHelper.cancel(Constants.NOTIFICATION_ID_PERMISSIONS_REQUIRED)
            ReconnectionMetrics.markForegroundStarted(this, deviceAddress)
            DiagnosticsLog.append(this, "On-demand foreground session started")
        }
        return true
    }

    private fun stopService() {
        if (isForegroundServiceStarted) {
            log("Stopping foreground service...")
            stopBackgroundLocationFetching()
            stopForeground(STOP_FOREGROUND_REMOVE)
            isForegroundServiceStarted = false
            ReconnectionMetrics.markForegroundStopped(this)
            DiagnosticsLog.append(this, "On-demand foreground session stopped")
        }
        stopSelf()
    }

    private fun finishForegroundIfIdle() {
        val hasWork = cameraConnections.values.any {
            it.isConnected || it.isConnecting || it.reconnectRunnable != null
        }
        if (!hasWork) stopService()
    }

    // --- Core Logic ---

    fun dismissBondingErrorDialog() {
        _showBondingErrorDialog.value = null
    }

    @SuppressLint("MissingPermission")
    private fun connectAssociatedCamera(deviceAddress: String) {
        if (!isAdapterAndScannerReady()) {
            log("Cannot connect $deviceAddress: Bluetooth is unavailable.")
            finishForegroundIfIdle()
            return
        }
        val device = bluetoothAdapter.getRemoteDevice(deviceAddress)
        val connection = cameraConnections.getOrPut(deviceAddress) { CameraConnection(device) }
        connection.isPresent = true
        connectToDevice(device)
    }

    @SuppressLint("MissingPermission")
    private fun handleCameraAbsent(deviceAddress: String) {
        val connection = cameraConnections[deviceAddress] ?: return
        log("System presence reports ${getDeviceDisplayString(deviceAddress)} absent.")
        connection.isPresent = false
        cancelConnectionTimers(connection)
        connection.gattQueue?.clear()
        connection.gattQueue = null
        connection.gatt?.disconnect()
        connection.gatt?.close()
        connection.gatt = null
        connection.isConnected = false
        connection.isConnecting = false
        stopLocationUpdates(connection)
        stopBackgroundLocationFetching()
        updateConnectionsList()
        finishForegroundIfIdle()
    }

    private fun cancelConnectionTimers(connection: CameraConnection) {
        connection.connectTimeoutRunnable?.let(handler::removeCallbacks)
        connection.connectTimeoutRunnable = null
        connection.reconnectRunnable?.let(handler::removeCallbacks)
        connection.reconnectRunnable = null
    }

    @SuppressLint("MissingPermission")
    fun connectToDevice(device: BluetoothDevice) {
        if (!hasRequiredPermissions() || !isAdapterAndScannerReady()) return

        val address = device.address
        if (!startSessionForeground(address)) {
            finishForegroundIfIdle()
            return
        }

        // Don't connect if already connected or connecting
        cameraConnections[address]?.let { connection ->
            if (connection.isConnected || connection.isConnecting) {
                log("connectToDevice() already connected or connecting to ${getDeviceDisplayString(device)}")
                return
            }
        }

        val connection = cameraConnections.getOrPut(address) {
            CameraConnection(device = device)
        }

        cancelConnectionTimers(connection)
        connection.gattQueue?.clear()
        connection.gatt?.close()
        connection.gatt = null
        connection.isPresent = true
        connection.isConnecting = true
        connection.isConnected = false
        ReconnectionMetrics.increment(this, "gatt_connection_attempts")
        ReconnectionMetrics.event(
            this,
            "gatt_connect_started",
            mapOf("address" to address, "attempt" to connection.connectAttempts + 1),
        )
        connection.gatt = device.connectGatt(this, false, createGattCallback(address), BluetoothDevice.TRANSPORT_LE)
        connection.connectTimeoutRunnable = Runnable {
            if (connection.isConnecting) {
                log("GATT connect timed out for ${getDeviceDisplayString(address)}")
                ReconnectionMetrics.increment(this, "gatt_connection_timeouts")
                handleConnectionFailure(address, connection.gatt, -1, "connect_timeout")
            }
        }.also { handler.postDelayed(it, Constants.GATT_CONNECT_TIMEOUT) }


        // Start fetching location in the background so it's
        // ready when the connection is established
        startBackgroundLocationFetching()

        // Association normally saves first; this preserves direct/manual callers.
        if (!AppSettingsManager.hasSavedCamera(this, address)) {
            val protocolVersion = Constants.PROTOCOL_VERSION_CREATORS_APP
            connection.isRemoteControlEnabled = false
            updateStatusMap(_isRemoteControlEnabled, address, connection.isRemoteControlEnabled)
            log("Saving camera settings for $address with protocol version $protocolVersion and remote control enabled ${connection.isRemoteControlEnabled}")
            AppSettingsManager.addSavedCamera(this, address, protocolVersion )
        }

        updateConnectionsList()
//        notificationHelper.updateNotifications(cameraConnections.values, isRemoteControlEnabled.value, isForegroundServiceStarted) { log(it) }
    }

    private fun createGattCallback(deviceAddress: String): BluetoothGattCallback {
        return object : BluetoothGattCallback() {
            @SuppressLint("MissingPermission")
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                val connection = cameraConnections[deviceAddress] ?: return
                if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                    connection.connectTimeoutRunnable?.let(handler::removeCallbacks)
                    connection.connectTimeoutRunnable = null
                    connection.isConnected = true
                    connection.isConnecting = false
                    connection.gatt = gatt
                    connection.gattQueue = GattOperationQueue(
                        gatt = gatt,
                        handler = handler,
                        log = ::log,
                        onTimeout = {
                            handleConnectionFailure(deviceAddress, gatt, -1, "gatt_operation_timeout")
                        },
                    )
                    connection.timeSyncAttempts = 0
                    connection.isInitialLocationWritePending = false
                    connection.isLocationStreamStartPending = false
                    connection.firstLocationWritten = false
                    updateStatusMap(_isFocusAcquired, deviceAddress, false)
                    updateStatusMap(_isShutterReady, deviceAddress, false)
                    updateStatusMap(_isRecordingVideo, deviceAddress, false)

                    ReconnectionMetrics.markConnected(this@CameraSyncService, deviceAddress)
                    notificationHelper.showForegroundConnected(connection)
                    log("Connected to ${getDeviceDisplayString(gatt.device)}. Requesting MTU...")
                    gatt.requestMtu(Constants.REQUEST_MTU_SIZE)
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    handleConnectionFailure(deviceAddress, gatt, status, "disconnected")
                } else if (status != BluetoothGatt.GATT_SUCCESS) {
                    handleConnectionFailure(deviceAddress, gatt, status, "connection_state_error")
                }

                updateConnectionsList()
                notificationHelper.updateNotifications(cameraConnections.values, isRemoteControlEnabled.value, isForegroundServiceStarted, ) { log(it) }
            }

            @SuppressLint("MissingPermission")
            override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
                val connection = cameraConnections[deviceAddress] ?: return
                log("MTU callback for ${getDeviceDisplayString(gatt.device)}: mtu=$mtu status=$status")
                if (gatt.device.bondState == BluetoothDevice.BOND_BONDED) {
                    if (!gatt.discoverServices()) {
                        handleConnectionFailure(deviceAddress, gatt, status, "discover_services_rejected")
                    }
                } else {
                    log("onMtuChanged: Device not bonded. Starting pairing...")
                    gatt.device.createBond()
                    connection.bondAttempts += 1
                    connection.isBonded = false
                    if (connection.bondAttempts >= 3) {
                        _showBondingErrorDialog.value = deviceAddress
                        connection.bondAttempts = 0
                    }
                    updateConnectionsList()
                }
            }

            @SuppressLint("MissingPermission")
            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    log("Service discovery failed for ${getDeviceDisplayString(gatt.device)}: ${gattStatusName(status)}")
                    handleConnectionFailure(deviceAddress, gatt, status, "service_discovery_failed")
                    return
                }
                val cameraSettings = AppSettingsManager.getCameraSettings(this@CameraSyncService, deviceAddress)
                cameraConnections[deviceAddress]?.connectAttempts = 0
                if (cameraSettings.protocolVersion == Constants.PROTOCOL_VERSION_CREATORS_APP) {
                    // Protocol version 0x65 (101) requires the endpoint lock and enable calls to be made before sending location data
                    log("Services discovered for ${getDeviceDisplayString(gatt.device)}. with protocol version ${cameraSettings.protocolVersion} Locking endpoint...")
                    lockLocationEndpoint(gatt, deviceAddress)
                }
                else {
                    // Previous protocol using Imaging Edge didn't need the location endpoint lock and enable commands
                    log("Services discovered for ${getDeviceDisplayString(gatt.device)}. with protocol version ${cameraSettings.protocolVersion} Sending location...")
                    sendLocationData(deviceAddress)
                    handler.postDelayed({ startLocationUpdates(deviceAddress) }, 500)
                    setupStatusNotifications(gatt, cameraSettings)
                }
            }

            @SuppressLint("MissingPermission")
            private fun setupStatusNotifications(gatt: BluetoothGatt, cameraSettings: CameraSettings) : Boolean {
                val deviceAddress = gatt.device.address
                val service = gatt.getService(Constants.REMOTE_CONTROL_SERVICE_UUID)
                val statusChar = service?.getCharacteristic(Constants.REMOTE_CONTROL_STATUS_UUID)

                if (statusChar != null) {
                    log("Enabling status notifications for ${getDeviceDisplayString(gatt.device)}")
                    gatt.setCharacteristicNotification(statusChar, true)

                    val descriptor = statusChar.getDescriptor(Constants.CLIENT_CHARACTERISTIC_CONFIG_UUID)
                    if (descriptor != null) {
                        val connection = cameraConnections[deviceAddress] ?: return false
                        val queued = connection.gattQueue?.enqueueDescriptor(
                            descriptor,
                            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE,
                            "FF02 notifications",
                        ) == true
                        updateStatusMap(_isRemoteControlEnabled, deviceAddress, false)
                        handler.postDelayed({ probeRemoteControl(deviceAddress) }, 250)
                        return queued
                    }
                    else {
                        log("Status characteristic descriptor not found for ${getDeviceDisplayString(gatt.device)}")
                    }
                } else {
                    log("Remote control status characteristic not found for ${getDeviceDisplayString(gatt.device)}")
                }
                val connection = cameraConnections[deviceAddress] ?: return false
                connection.isRcProbeErrorReceived = true
                updateStatusMap(_isRemoteControlEnabled, deviceAddress, false )
                return false
            }

            override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
                val connection = cameraConnections[deviceAddress] ?: return
                if (connection.gatt !== gatt) return
                connection.gattQueue?.onCharacteristicWrite(characteristic)

                if (status == BluetoothGatt.GATT_SUCCESS) {
                    connection.retries = 0

                    when (characteristic.uuid) {
                        Constants.LOCK_LOCATION_ENDPOINT_UUID -> {
                            log("Endpoint locked for ${getDeviceDisplayString(gatt.device)} -> Enabling location updates...")
                            enableLocationUpdates(gatt, deviceAddress)
                        }
                        Constants.ENABLE_LOCATION_UPDATES_UUID -> {
                            log("Location updates enabled for ${getDeviceDisplayString(gatt.device)} -> Syncing time...")
                            // CC13 must complete before DD11 is written. Android only permits one
                            // outstanding GATT write and rejects an overlapping request as BUSY.
                            connection.timeSyncAttempts = 0
                            synchronizeTimeWithRetry(gatt, deviceAddress)
                        }
                        Constants.TIME_CHARACTERISTIC_UUID -> {
                            log("Time synced for ${getDeviceDisplayString(gatt.device)} -> Sending initial location data...")
                            connection.timeSyncAttempts = 0
                            sendInitialLocationData(deviceAddress)
                            setupStatusNotifications(
                                gatt,
                                AppSettingsManager.getCameraSettings(this@CameraSyncService, deviceAddress),
                            )
                        }
                        Constants.LOCATION_CHARACTERISTIC_UUID -> {
                            if (!connection.firstLocationWritten) {
                                connection.firstLocationWritten = true
                                ReconnectionMetrics.markFirstLocation(this@CameraSyncService, deviceAddress)
                            }
                            if (connection.isInitialLocationWritePending) {
                                connection.isInitialLocationWritePending = false
                                log("Initial location data confirmed for ${getDeviceDisplayString(gatt.device)} -> Starting location stream...")
                                scheduleLocationStreaming(deviceAddress)
                            }
                        }
                    }
                } else {
                    val isExpectedRemoteControlRejection =
                        characteristic.uuid == Constants.REMOTE_CONTROL_CHARACTERISTIC_UUID &&
                            status == 144
                    if (isExpectedRemoteControlRejection) {
                        log(
                            "Sony reports remote control unavailable (status=144) for " +
                                "${getDeviceDisplayString(gatt.device)}; GPS streaming remains active."
                        )
                    } else {
                        log(
                            "GATT write callback failed " +
                                "type=${characteristicLabel(characteristic.uuid)} " +
                                "status=$status " +
                                "statusName=${gattStatusName(status)} " +
                                "device=${getDeviceDisplayString(gatt.device)}"
                        )
                    }

                    when (characteristic.uuid) {
                        Constants.ENABLE_LOCATION_UPDATES_UUID -> {
                            if (connection.retries < Constants.MAX_BT_RETRIES) {
                                connection.retries++
                                handler.postDelayed({ enableLocationUpdates(gatt, deviceAddress) },500 * connection.retries)
                            } else {
                                handleConnectionFailure(deviceAddress, gatt, status, "enable_location_failed")
                            }
                        }
                        Constants.LOCK_LOCATION_ENDPOINT_UUID -> {
                            if (connection.retries < Constants.MAX_BT_RETRIES) {
                                handler.postDelayed({ lockLocationEndpoint(gatt, deviceAddress) },500 * connection.retries)
                                connection.retries++
                            } else {
                                handleConnectionFailure(deviceAddress, gatt, status, "lock_location_failed")
                            }
                        }
                        Constants.TIME_CHARACTERISTIC_UUID -> {
                            retryTimeSyncOrContinue(gatt, deviceAddress)
                        }
                        Constants.LOCATION_CHARACTERISTIC_UUID -> {
                            if (connection.isInitialLocationWritePending) {
                                connection.isInitialLocationWritePending = false
                                log("Initial location write failed; starting periodic stream for ${getDeviceDisplayString(gatt.device)}")
                                scheduleLocationStreaming(deviceAddress)
                            }
                        }
                        Constants.REMOTE_CONTROL_CHARACTERISTIC_UUID -> {
                            if (status == 144 || status == 133) {
                                // Check if this shutter was triggered from notification
//                                if (_shutterTriggeredFromNotification.value == deviceAddress) {
//                                    _shutterTriggeredFromNotification.value = null
//                                    notificationHelper.showShutterErrorNotification(deviceAddress, connection)
//                                } else {
//                                    _shutterErrorMessage.value = this@CameraSyncService.getString(R.string.error_shutter_message_long)
//                                }
                                val connection = cameraConnections[deviceAddress] ?: return
                                connection.isRcProbeErrorReceived = true
                                updateStatusMap(_isRemoteControlEnabled, deviceAddress, false )
                                if (status != 144) {
                                    log("Remote control error received for ${getDeviceDisplayString(gatt.device)}")
                                }
                            }
                        }
                    }
                }
            }

            override fun onDescriptorWrite(
                gatt: BluetoothGatt,
                descriptor: BluetoothGattDescriptor,
                status: Int,
            ) {
                val connection = cameraConnections[deviceAddress] ?: return
                if (connection.gatt !== gatt) return
                connection.gattQueue?.onDescriptorWrite(descriptor)
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    log(
                        "GATT descriptor callback failed status=${gattStatusName(status)} " +
                            "device=${getDeviceDisplayString(gatt.device)}"
                    )
                }
            }
            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray
            ) {
                if (characteristic.uuid == Constants.REMOTE_CONTROL_STATUS_UUID) {
                    val address = gatt.device.address
                    val cameraStatus = CameraStatus.fromBytes(value)

                    // Mark remote control as enabled as soon as any status change is received
                    if (!_isRemoteControlEnabled.value.getOrDefault(address, false) &&
                        cameraStatus != CameraStatus.REMOTE_CONTROL_DISABLED) {
                        updateStatusMap(_isRemoteControlEnabled, address, true)
                        notificationHelper.updateNotifications(cameraConnections.values, isRemoteControlEnabled.value, isForegroundServiceStarted) { log(it) }
                        log("Remote control is ENABLED for $address")
                    }

                    when (cameraStatus) {
                        // Focus Status
                        CameraStatus.FOCUS_ACQUIRED -> {
                            updateStatusMap(_isFocusAcquired, address, true)
                        }
                        CameraStatus.FOCUS_LOST -> {
                            updateStatusMap(_isFocusAcquired, address, false)
                        }

                        // Shutter Status
                        CameraStatus.SHUTTER_READY -> {
                            updateStatusMap(_isShutterReady, address, true)
                        }
                        CameraStatus.SHUTTER_ACTIVE -> {
                            updateStatusMap(_isShutterReady, address, false)
                        }

                        // Video Recording Status
                        CameraStatus.VIDEO_STARTED -> {
                            updateStatusMap(_isRecordingVideo, address, true)
                        }
                        CameraStatus.VIDEO_STOPPED -> {
                            updateStatusMap(_isRecordingVideo, address, false)
                        }

                        CameraStatus.REMOTE_CONTROL_DISABLED -> {
                            updateStatusMap(_isRemoteControlEnabled, address, false)
                            notificationHelper.updateNotifications(cameraConnections.values, isRemoteControlEnabled.value, isForegroundServiceStarted) { log(it) }
                            log("Remote control is DISABLED for $address")
                        }
                        else -> {
                            log("Received unknown status packet: ${value.toHexString()}")
                        }
                    }
                }
            }

        }
    }

    @SuppressLint("MissingPermission")
    private fun handleConnectionFailure(
        deviceAddress: String,
        failedGatt: BluetoothGatt?,
        status: Int,
        reason: String,
    ) {
        val connection = cameraConnections[deviceAddress] ?: return
        // Ignore stale callbacks from a GATT instance that has already been replaced.
        if (failedGatt != null && connection.gatt != null && connection.gatt !== failedGatt) return

        log(
            "Connection ended for ${getDeviceDisplayString(deviceAddress)}: " +
                "reason=$reason status=$status (${gattStatusName(status)})"
        )
        ReconnectionMetrics.event(
            this,
            "gatt_connection_ended",
            mapOf("address" to deviceAddress, "reason" to reason, "status" to status),
        )
        cancelConnectionTimers(connection)
        connection.gattQueue?.clear()
        connection.gattQueue = null
        runCatching { failedGatt?.close() }
        connection.gatt = null
        connection.isConnected = false
        connection.isConnecting = false
        connection.disconnectTimestamp = System.currentTimeMillis()
        AppSettingsManager.updateDisconnectTimestamp(this, deviceAddress, connection.disconnectTimestamp!!)
        stopLocationUpdates(connection)
        stopBackgroundLocationFetching()
        notificationHelper.clearShutterErrorNotification(deviceAddress)
        // FIRST_MATCH scans stay in the matched state while GATT is connected. Reset
        // that state after a disconnect so a still-on camera, or one switched off and
        // back on quickly, can generate a fresh wake event immediately.
        val presenceRearmed = SonyPresenceScanner.restart(this, deviceAddress)

        if (
            !presenceRearmed && connection.isPresent &&
            connection.connectAttempts < Constants.MAX_GATT_CONNECT_RETRIES
        ) {
            connection.connectAttempts++
            val delayMs = (1L shl connection.connectAttempts.coerceAtMost(5)) * 1_000L
            log("Retrying ${getDeviceDisplayString(deviceAddress)} in ${delayMs}ms")
            connection.reconnectRunnable = Runnable {
                connection.reconnectRunnable = null
                if (connection.isPresent && !connection.isConnected && !connection.isConnecting) {
                    connectToDevice(connection.device)
                }
            }.also { handler.postDelayed(it, delayMs) }
        } else {
            connection.connectAttempts = 0
            finishForegroundIfIdle()
        }
        updateConnectionsList()
    }

    fun probeRemoteControl(deviceAddress: String) {
        val connection = cameraConnections[deviceAddress] ?: return
        connection.isRcProbeErrorReceived = false

        probeRemoteControlInternal(deviceAddress, 5)
    }

    private fun probeRemoteControlInternal(deviceAddress: String, retryCount: Int) {
        var isRemoteControlDisabled = false
        val connection = cameraConnections[deviceAddress]
        if (connection!=null && connection.isRcProbeErrorReceived) {
            updateStatusMap(_isRemoteControlEnabled, deviceAddress, false)
            isRemoteControlDisabled = true
        }
        if (retryCount > 0 && !isRemoteControlDisabled) {
            sendRemoteCommand(deviceAddress, RemoteControlCommand.REMOTE_CONTROL_PROBE)
            handler.postDelayed(
                {
                    probeRemoteControlInternal(deviceAddress, retryCount - 1)
                }, 250
            )
        }
        else {
            if (!isRemoteControlDisabled) {
                updateStatusMap(_isRemoteControlEnabled, deviceAddress, true)
            }
            notificationHelper.updateNotifications(cameraConnections.values, isRemoteControlEnabled.value, isForegroundServiceStarted,) { log(it) }
        }
    }

    // Helper to update maps
    private fun updateStatusMap(flow: MutableStateFlow<Map<String, Boolean>>, address: String, newValue: Boolean) {
        val currentMap = flow.value.toMutableMap()
        if (currentMap[address] != newValue) {
            currentMap[address] = newValue
            flow.value = currentMap
        }
    }

    // --- Public Functions for UI to Call ---

    @SuppressLint("MissingPermission")
    fun forgetDevice(deviceAddress: String) {
        log("Forgetting device ${getDeviceDisplayString(deviceAddress)}...")

        cameraConnections[deviceAddress]?.let { connection ->
            // Stop all device related activities
            stopLocationUpdates(connection)
            cancelConnectionTimers(connection)
            connection.gattQueue?.clear()
            connection.gatt?.disconnect()
            connection.gatt?.close()
            CompanionCameraManager(this).removeAssociation(deviceAddress)

            notificationHelper.cancel(connection.device.address.hashCode())
            notificationHelper.clearShutterErrorNotification(deviceAddress)

            // Remove from connections map
            cameraConnections.remove(deviceAddress)

            // Remove all camera settings (Quick Connect + disconnect timestamp)
            AppSettingsManager.removeSavedCamera(this, deviceAddress)

            // Update UI
            updateConnectionsList()

            log("Device ${getDeviceDisplayString(deviceAddress)} forgotten successfully")

            // Stop service if no more cameras
            if (cameraConnections.isEmpty()) {
                log("No more cameras, stopping service")
                stopService()
            }
        } ?: log("Device ${getDeviceDisplayString(deviceAddress)} not found in connections")

        notificationHelper.updateNotifications(cameraConnections.values, isRemoteControlEnabled.value, isForegroundServiceStarted) { log(it) }
    }

    fun onSettingsChanged(deviceAddress: String) {
        // update the notifications in case the camera name has changed
        notificationHelper.updateNotifications(cameraConnections.values, isRemoteControlEnabled.value, isForegroundServiceStarted) { log(it) }
    }

    // --- Characteristic Writes ---
    @SuppressLint("MissingPermission")
    private fun lockLocationEndpoint(gatt: BluetoothGatt, deviceAddress: String) {
        val lockChar = gatt.getService(Constants.PICT_SERVICE_UUID)?.getCharacteristic(Constants.LOCK_LOCATION_ENDPOINT_UUID)
        if (lockChar == null) {
            log("Lock Location Endpoint characteristic (dd30) not found for ${getDeviceDisplayString(deviceAddress)}!")
            return
        }
        writeCharacteristic(gatt, lockChar, byteArrayOf(0x01), BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
    }

    @SuppressLint("MissingPermission")
    private fun enableLocationUpdates(gatt: BluetoothGatt, deviceAddress: String) {
        val enableChar = gatt.getService(Constants.PICT_SERVICE_UUID)?.getCharacteristic(Constants.ENABLE_LOCATION_UPDATES_UUID)
        if (enableChar == null) {
            log("Enable Location Updates characteristic (dd31) not found for ${getDeviceDisplayString(deviceAddress)}!")
            return
        }
        writeCharacteristic(gatt, enableChar, byteArrayOf(0x01), BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
    }

    @SuppressLint("MissingPermission")
    private fun synchronizeTime(gatt: BluetoothGatt, deviceAddress: String): Boolean {
        val timeChar = gatt.getService(Constants.TIME_SERVICE_UUID)?.getCharacteristic(Constants.TIME_CHARACTERISTIC_UUID)
        if (timeChar == null) {
            log("Time characteristic not found for ${getDeviceDisplayString(gatt.device)} -> Skipping sync.")
            return false
        }
        log("Syncing time for ${getDeviceDisplayString(gatt.device)} ...")

        val localTimezone = TimeZone.getDefault()
        val currentTimeMillis = System.currentTimeMillis()
        val cal = Calendar.getInstance().apply {
            timeInMillis = currentTimeMillis
        }
        logTimeState(currentTimeMillis, localTimezone)
        val dstActive = if (localTimezone.inDaylightTime(Date(currentTimeMillis))) {
            1.toShort()
        } else {
            0.toShort()
        }

        var (tzOffsetHours, tzOffsetMinutes) = getRawUtcOffset()
        if (tzOffsetHours < 0 ) {
            tzOffsetHours = 24 + tzOffsetHours
        }
        val payload = ByteBuffer.allocate(13).order(ByteOrder.BIG_ENDIAN)
            .put(byteArrayOf(0x0c, 0x00, 0x00.toByte()))
            .putShort(cal.get(Calendar.YEAR).toShort())
            .put((cal.get(Calendar.MONTH) + 1).toByte())
            .put(cal.get(Calendar.DAY_OF_MONTH).toByte())
            .put(cal.get(Calendar.HOUR_OF_DAY).toByte())
            .put(cal.get(Calendar.MINUTE).toByte())
            .put(cal.get(Calendar.SECOND).toByte())
            .put(dstActive.toByte())
            .put(tzOffsetHours.toByte())
            .put(tzOffsetMinutes.toByte())
            .array()

        return writeCharacteristic(gatt, timeChar, payload, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
    }

    private fun synchronizeTimeWithRetry(gatt: BluetoothGatt, deviceAddress: String) {
        if (cameraConnections[deviceAddress] == null) return
        if (synchronizeTime(gatt, deviceAddress)) return

        retryTimeSyncOrContinue(gatt, deviceAddress)
    }

    private fun retryTimeSyncOrContinue(gatt: BluetoothGatt, deviceAddress: String) {
        val connection = cameraConnections[deviceAddress] ?: return
        if (connection.timeSyncAttempts < Constants.MAX_BT_RETRIES) {
            connection.timeSyncAttempts++
            val delayMillis = 100L * connection.timeSyncAttempts
            log(
                "Time write was not completed for ${getDeviceDisplayString(gatt.device)}; " +
                    "retrying in ${delayMillis}ms (${connection.timeSyncAttempts}/${Constants.MAX_BT_RETRIES})"
            )
            handler.postDelayed(
                {
                    val currentConnection = cameraConnections[deviceAddress]
                    if (currentConnection?.isConnected == true && currentConnection.gatt === gatt) {
                        synchronizeTimeWithRetry(gatt, deviceAddress)
                    }
                },
                delayMillis
            )
        } else {
            log("Time sync retries exhausted for ${getDeviceDisplayString(gatt.device)}; continuing with location data")
            connection.timeSyncAttempts = 0
            sendInitialLocationData(deviceAddress)
        }
    }

    fun getRawUtcOffset(): Pair<Int, Int> {
        val timeZone: TimeZone = TimeZone.getDefault()
        val rawOffsetMillis: Int = timeZone.rawOffset

        val totalSeconds = (rawOffsetMillis / 1000)

        val sign = if (totalSeconds < 0) -1 else 1
        val absSeconds = abs(totalSeconds)

        val hours = (absSeconds / 3600) * sign
        val minutes = (absSeconds % 3600) / 60

        return Pair(hours, minutes)
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates(deviceAddress: String) {
        if (!hasRequiredPermissions()) {
            log("Cannot start location updates: Permissions not granted")
            return
        }

        val connection = cameraConnections[deviceAddress] ?: let {
            log("Cannot start location updates: Camera ${getDeviceDisplayString(deviceAddress)} not connected")
            return
        }

        if (!connection.isConnected || connection.gatt == null) {
            log("Cannot start location updates: Camera ${getDeviceDisplayString(deviceAddress)} not connected")
            return
        }

        if (connection.locationUpdateRunnable != null) {
            log("Location data stream already active for ${getDeviceDisplayString(deviceAddress)}")
            return
        }

        log("Starting to stream location data to ${getDeviceDisplayString(deviceAddress)} ...")

        val runnable = object : Runnable {
            override fun run() {
                sendLocationData(deviceAddress)
                handler.postDelayed(this, Constants.LOCATION_UPDATE_INTERVAL)
            }
        }

        connection.locationUpdateRunnable = runnable
        handler.post(runnable)
    }

    private fun sendInitialLocationData(deviceAddress: String) {
        val connection = cameraConnections[deviceAddress] ?: return
        connection.isInitialLocationWritePending = true
        if (!sendLocationData(deviceAddress)) {
            connection.isInitialLocationWritePending = false
            log("Initial location data was not queued for ${getDeviceDisplayString(deviceAddress)}; starting periodic stream")
            scheduleLocationStreaming(deviceAddress)
        }
    }

    private fun scheduleLocationStreaming(deviceAddress: String) {
        val connection = cameraConnections[deviceAddress] ?: return
        if (connection.locationUpdateRunnable != null || connection.isLocationStreamStartPending) return

        connection.isLocationStreamStartPending = true
        // The first location update is sometimes ignored, so begin the periodic stream
        // after the initial DD11 callback and send a second update after this short delay.
        handler.postDelayed(
            {
                val currentConnection = cameraConnections[deviceAddress] ?: return@postDelayed
                currentConnection.isLocationStreamStartPending = false
                if (currentConnection.isConnected) {
                    startLocationUpdates(deviceAddress)
                }
            },
            500
        )
    }

    @SuppressLint("MissingPermission")
    private fun sendLocationData(deviceAddress: String): Boolean {
        val  location = lastKnownLocation
        if (location == null) {
            log("No pre-fetched location data to send to ${getDeviceDisplayString(deviceAddress)} skipping location update.")
            return false
        }

        val connection = cameraConnections[deviceAddress] ?: let {
            log("Cannot send location data: Camera ${getDeviceDisplayString(deviceAddress)} not in connections list")
            return false
        }
        val gatt = connection.gatt ?: let {
            log("Cannot send location data: Camera ${getDeviceDisplayString(deviceAddress)} GATT not connected")
            return false
        }

        val locationChar = gatt.getService(Constants.PICT_SERVICE_UUID)?.getCharacteristic(Constants.LOCATION_CHARACTERISTIC_UUID)
        if (locationChar == null) {
            log("Location characteristic not found for ${getDeviceDisplayString(deviceAddress)}.")
            return false
        }

        val buffer = ByteBuffer.allocate(95).order(ByteOrder.BIG_ENDIAN)
        val utcCalendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        val localTimezone = TimeZone.getDefault()
        val currentTimeMillis = System.currentTimeMillis()

        buffer.putShort(0x005D)
        buffer.put(byteArrayOf(0x08, 0x02, 0xFC.toByte()))
        buffer.put(0x03.toByte())
        buffer.put(byteArrayOf(0x00, 0x00, 0x10, 0x10, 0x10))
        buffer.putInt((location.latitude * 10_000_000).toInt())
        buffer.putInt((location.longitude * 10_000_000).toInt())
        buffer.putShort(utcCalendar.get(Calendar.YEAR).toShort())
        buffer.put((utcCalendar.get(Calendar.MONTH) + 1).toByte())
        buffer.put(utcCalendar.get(Calendar.DAY_OF_MONTH).toByte())
        buffer.put(utcCalendar.get(Calendar.HOUR_OF_DAY).toByte())
        buffer.put(utcCalendar.get(Calendar.MINUTE).toByte())
        buffer.put(utcCalendar.get(Calendar.SECOND).toByte())
        buffer.position(91)
        buffer.putShort((localTimezone.rawOffset / 60000).toShort())
        val dstOffset = if (localTimezone.inDaylightTime(Date(currentTimeMillis))) {
            (localTimezone.dstSavings / 60000).toShort()
        } else {
            0.toShort()
        }
        buffer.putShort(dstOffset)

        val payload = buffer.array()

        val accepted = writeCharacteristic(gatt, locationChar, payload, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
        if (accepted) {
            log("Location data sent to ${getDeviceDisplayString(deviceAddress)}")
        } else {
            log("Location data write was not accepted for ${getDeviceDisplayString(deviceAddress)}")
        }
        return accepted
    }

    @SuppressLint("MissingPermission")
    fun triggerShutter(deviceAddress: String? = null, fromNotification: Boolean = false) {
        if (deviceAddress != null) {
            if (fromNotification) {
                _shutterTriggeredFromNotification.value = deviceAddress
            }

            log("Triggering shutter for ${getDeviceDisplayString(deviceAddress)}...")
            sendRemoteCommand(deviceAddress, RemoteControlCommand.FULL_SHUTTER_DOWN)
            handler.postDelayed({
                sendRemoteCommand(deviceAddress, RemoteControlCommand.FULL_SHUTTER_UP)
            }, 200)
        }
    }

    @SuppressLint("MissingPermission")
    fun sendRemoteCommand(deviceAddress: String, command: RemoteControlCommand) {
        val connection = cameraConnections[deviceAddress]
        if (connection == null || !connection.isConnected) {
            log("Cannot send remote command: Camera ${getDeviceDisplayString(deviceAddress)} not connected when sending command ${command.name}")
            return
        }

        val gatt = connection.gatt ?: return
        val remoteControlChar = gatt.getService(Constants.REMOTE_CONTROL_SERVICE_UUID)
            ?.getCharacteristic(Constants.REMOTE_CONTROL_CHARACTERISTIC_UUID)
        if (remoteControlChar == null) {
            log("Remote control characteristic not found for ${getDeviceDisplayString(deviceAddress)} when sending command  ${command.name}")
            connection.isRcProbeErrorReceived = true
            connection.isRemoteControlEnabled = false
            updateStatusMap(_isRemoteControlEnabled, deviceAddress, false)
            return
        }

        log("Sending remote command ${command.name} to ${getDeviceDisplayString(connection)}: ${command.bytes.joinToString(" ") { "0x%02x".format(it) }}")
        writeCharacteristic(gatt, remoteControlChar, command.bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
    }

    @SuppressLint("MissingPermission")
    private fun writeCharacteristic(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, payload: ByteArray, writeType: Int): Boolean {
        val connection = cameraConnections[gatt.device.address] ?: return false
        if (connection.gatt !== gatt) return false
        return connection.gattQueue?.enqueueCharacteristic(
            characteristic = characteristic,
            payload = payload,
            writeType = writeType,
            label = characteristicLabel(characteristic.uuid),
        ) == true
    }

    private fun logTimeState(now: Long, timeZone: TimeZone) {
        val rawOffsetMinutes = timeZone.rawOffset / 60_000
        val effectiveOffsetMinutes = timeZone.getOffset(now) / 60_000
        val dstOffsetMinutes = effectiveOffsetMinutes - rawOffsetMinutes
        val localFormatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).apply {
            this.timeZone = timeZone
        }
        val utcFormatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            this.timeZone = TimeZone.getTimeZone("UTC")
        }

        log(
            "Time state " +
                "zone=${timeZone.id} " +
                "local=${localFormatter.format(Date(now))} " +
                "utc=${utcFormatter.format(Date(now))} " +
                "rawOffsetMinutes=$rawOffsetMinutes " +
                "effectiveOffsetMinutes=$effectiveOffsetMinutes " +
                "dstOffsetMinutes=$dstOffsetMinutes " +
                "dstActive=${timeZone.inDaylightTime(Date(now))}"
        )
    }

    private fun characteristicLabel(uuid: UUID): String =
        when (uuid) {
            Constants.LOCK_LOCATION_ENDPOINT_UUID -> "DD30_LOCK_LOCATION_ENDPOINT"
            Constants.ENABLE_LOCATION_UPDATES_UUID -> "DD31_ENABLE_LOCATION_UPDATES"
            Constants.LOCATION_CHARACTERISTIC_UUID -> "DD11_LOCATION"
            Constants.TIME_CHARACTERISTIC_UUID -> "CC13_TIME"
            Constants.REMOTE_CONTROL_CHARACTERISTIC_UUID -> "FF01_REMOTE_CONTROL"
            else -> uuid.toString()
        }

    private fun gattStatusName(status: Int): String =
        when (status) {
            BluetoothGatt.GATT_SUCCESS -> "GATT_SUCCESS"
            BluetoothGatt.GATT_READ_NOT_PERMITTED -> "GATT_READ_NOT_PERMITTED"
            BluetoothGatt.GATT_WRITE_NOT_PERMITTED -> "GATT_WRITE_NOT_PERMITTED"
            BluetoothGatt.GATT_INSUFFICIENT_AUTHENTICATION -> "GATT_INSUFFICIENT_AUTHENTICATION"
            BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED -> "GATT_REQUEST_NOT_SUPPORTED"
            BluetoothGatt.GATT_INVALID_OFFSET -> "GATT_INVALID_OFFSET"
            BluetoothGatt.GATT_INSUFFICIENT_AUTHORIZATION -> "GATT_INSUFFICIENT_AUTHORIZATION"
            BluetoothGatt.GATT_INVALID_ATTRIBUTE_LENGTH -> "GATT_INVALID_ATTRIBUTE_LENGTH"
            BluetoothGatt.GATT_INSUFFICIENT_ENCRYPTION -> "GATT_INSUFFICIENT_ENCRYPTION"
            BluetoothGatt.GATT_CONNECTION_CONGESTED -> "GATT_CONNECTION_CONGESTED"
            BluetoothGatt.GATT_CONNECTION_TIMEOUT -> "GATT_CONNECTION_TIMEOUT"
            BluetoothGatt.GATT_FAILURE -> "GATT_FAILURE"
            133 -> "GATT_ERROR_133"
            else -> "UNKNOWN_$status"
        }


    private fun initializeBluetoothAndLocation() {
        bluetoothManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter
        bluetoothAdapter.bluetoothLeScanner?.let {
            bleScanner = it
        } ?: log("BLE scanner unavailable - Bluetooth may be disabled")

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        // Define the callback that will receive location updates
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                for (location in locationResult.locations) {
                    log("Pre-fetched current location: Lat=${"%.2f".format(location.latitude)}, Lon=${"%.2f".format(location.longitude)}")
                    lastKnownLocation = location
                }
            }
        }
    }
    @SuppressLint("MissingPermission")
    private fun startBackgroundLocationFetching() {
        if (!hasRequiredPermissions()) {
            log("Cannot start location updates: Permissions not granted")
            return
        }
        if (isFetchingLocation) {
            log("Not starting background location fetching - it's already running")
            return
        }
        // Fetch last known location so we have something immediately and then start fetching more accurate updates
        if (lastKnownLocation == null) {
            fusedLocationClient.getLastLocation().addOnSuccessListener { location ->
                if (location != null) {
                    lastKnownLocation = location
                    log("Pre-fetched last location: Lat=${"%.2f".format(location.latitude)}, Lon=${"%.2f".format(location.longitude)}")
                } else {
                    log("No last known location available yet")
                }
            }
        }
        log("Starting background location fetching")
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, Constants.LOCATION_UPDATE_INTERVAL)
            .build()
        fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper())
        isFetchingLocation = true
    }

    private fun stopBackgroundLocationFetching() {
        val anyCameraConnected = cameraConnections.values.any { it.isConnected }
        if (anyCameraConnected) {
            log("stopBackgroundLocationFetching() called, but active connections remain. Continuing location updates.")
            return
        }

        if (!isFetchingLocation) {
            return
        }

        log("Stopping background location fetching (no active cameras)")
        fusedLocationClient.removeLocationUpdates(locationCallback)
        lastKnownLocation = null
        isFetchingLocation = false
    }

    // --- Utility Methods ---
    fun clearShutterError() {
        _shutterErrorMessage.value = null
    }

    @SuppressLint("MissingPermission")
    private fun loadSavedCameras() {
        val savedAddresses = AppSettingsManager.getSavedCameras(this)
        if (savedAddresses.isNotEmpty()) {
            savedAddresses.forEach { address ->
                if (address.isNotBlank()) {
                    try {
                        if (!::bluetoothAdapter.isInitialized) {
                            log("Bluetooth adapter not initialized yet, deferring camera loading")
                            return@forEach
                        }
                        val device = bluetoothAdapter.getRemoteDevice(address)
                        val connection = cameraConnections.getOrPut(address) {
                            CameraConnection(device = device)
                        }

                        // Load saved camera settings
                        val cameraSettings = AppSettingsManager.getCameraSettings(this, address)
                        connection.disconnectTimestamp = cameraSettings.lastDisconnectTimestamp

                        log("Loaded saved camera: ${getDeviceDisplayString(device)}")

                    } catch (e: Exception) {
                        log("Failed to load saved camera $address: ${e.message}")
                    }
                }
            }

            // Add mock cameras for testing the UI
//            for (i in 55 until 55 ) {
//                val mockAddress = "00:11:22:33:44:$i"
//                val mockDevice = bluetoothAdapter.getRemoteDevice(mockAddress)
//                val mockConnection = CameraConnection(device = mockDevice)
//                cameraConnections[mockAddress] = mockConnection
//            }

            updateConnectionsList()
        }
    }

    @SuppressLint("MissingPermission")
    fun refreshSavedCameras() {
        loadSavedCameras()
        updateConnectionsList()
    }
    private fun updateConnectionsList() {
        val newList = cameraConnections.values.map { conn ->
            CameraConnection(
                device = conn.device,
                gatt = conn.gatt,
                isConnected = conn.isConnected,
                isConnecting = conn.isConnecting,
                isBonded = conn.isBonded,
                bondAttempts = conn.bondAttempts,
                retries = conn.retries,
                disconnectTimestamp = conn.disconnectTimestamp,
                locationUpdateRunnable = conn.locationUpdateRunnable,
                isPresent = conn.isPresent,
                connectAttempts = conn.connectAttempts,
                firstLocationWritten = conn.firstLocationWritten,
            )
        }
        _connectedCameras.value = newList
        DiagnosticsLog.setProcessState(this,
            "fgs=$isForegroundServiceStarted connected=${newList.count { it.isConnected }} " +
                "connecting=${newList.count { it.isConnecting }}")
        log("Updated connections list: ${cameraConnections.size} cameras, connected: ${newList.count { it.isConnected }}, details: ${newList.map { "${it.device.address}: connected=${it.isConnected}, connecting=${it.isConnecting}" }}")
    }


    private fun hasRequiredPermissions(): Boolean {
        val requiredPermissions = listOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_BACKGROUND_LOCATION,
        )
        return requiredPermissions.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
    }

    private fun isAdapterAndScannerReady(): Boolean {
        if (!::bluetoothAdapter.isInitialized || !bluetoothAdapter.isEnabled) {
            log("Bluetooth adapter not initialized or not enabled.")
            return false
        }
        if (!::bleScanner.isInitialized) {
            log("BLE scanner not initialized.")
            return false
        }
        return true
    }

    private fun stopLocationUpdates(connection: CameraConnection) {
        connection.locationUpdateRunnable?.let {
            log("Stopping location updates for ${getDeviceDisplayString(connection)}.")
            handler.removeCallbacks(it)
            connection.locationUpdateRunnable = null
        }
        connection.timeSyncAttempts = 0
        connection.isInitialLocationWritePending = false
        connection.isLocationStreamStartPending = false
    }

    @SuppressLint("MissingPermission")
    private fun getDeviceDisplayString(device: BluetoothDevice): String {
        val name = AppSettingsManager.getCameraName(baseContext, device.address, device.name, false)
        if (!name.isEmpty()) {
            return "$name [${device.address}]"
        }
        return "[${device.address}]"
    }
    private fun getDeviceDisplayString(address: String): String {
        val connection = cameraConnections[address] ?: return "[$address]"
        val device = connection.device
        return getDeviceDisplayString(device)
    }
    private fun getDeviceDisplayString(connection: CameraConnection): String {
        return getDeviceDisplayString(connection.device)
    }


    @SuppressLint("MissingPermission")
    fun log(message: String) {
        Log.d(TAG, message)
        val currentLog = _log.value.toMutableList()
        currentLog.add(0, "[${SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date())}] $message")
        if (currentLog.size > 200) {
            currentLog.removeAt(currentLog.size - 1)
        }
        _log.value = currentLog
    }

    fun clearLog() {
        _log.value = emptyList()
        log("Log cleared.")
    }

    fun getLogAsString(): String {
        return _log.value.joinToString("\n") + "\n\n" + ReconnectionMetrics.summary(this) +
            "\n\n--- Persistent diagnostics ---\n" + DiagnosticsLog.read(this)
    }
}
