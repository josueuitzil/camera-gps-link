package org.kutner.cameragpslink

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothStatusCodes
import android.os.Handler
import java.util.ArrayDeque

/** Enforces Android's single-outstanding-GATT-operation rule per connection. */
class GattOperationQueue(
    private val gatt: BluetoothGatt,
    private val handler: Handler,
    private val log: (String) -> Unit,
    private val onTimeout: () -> Unit,
) {
    private sealed interface Operation {
        val label: String

        data class CharacteristicWrite(
            val characteristic: BluetoothGattCharacteristic,
            val payload: ByteArray,
            val writeType: Int,
            override val label: String,
        ) : Operation

        data class DescriptorWrite(
            val descriptor: BluetoothGattDescriptor,
            val payload: ByteArray,
            override val label: String,
        ) : Operation
    }

    private val pending = ArrayDeque<Operation>()
    private var current: Operation? = null
    private var timeout: Runnable? = null
    private var closed = false
    private var immediateFailures = 0

    fun enqueueCharacteristic(
        characteristic: BluetoothGattCharacteristic,
        payload: ByteArray,
        writeType: Int,
        label: String,
    ): Boolean {
        if (closed) return false
        if (label == "DD11_LOCATION") {
            // Location is state, not a transaction log. Keep only the newest pending fix.
            pending.removeIf { it.label == label }
        }
        pending.add(Operation.CharacteristicWrite(characteristic, payload.copyOf(), writeType, label))
        drain()
        return true
    }

    fun enqueueDescriptor(descriptor: BluetoothGattDescriptor, payload: ByteArray, label: String): Boolean {
        if (closed) return false
        pending.add(Operation.DescriptorWrite(descriptor, payload.copyOf(), label))
        drain()
        return true
    }

    fun onCharacteristicWrite(characteristic: BluetoothGattCharacteristic): Boolean {
        val operation = current as? Operation.CharacteristicWrite ?: return false
        if (operation.characteristic.uuid != characteristic.uuid) return false
        complete()
        return true
    }

    fun onDescriptorWrite(descriptor: BluetoothGattDescriptor): Boolean {
        val operation = current as? Operation.DescriptorWrite ?: return false
        if (operation.descriptor.uuid != descriptor.uuid) return false
        complete()
        return true
    }

    fun clear() {
        closed = true
        pending.clear()
        timeout?.let(handler::removeCallbacks)
        timeout = null
        current = null
        immediateFailures = 0
    }

    private fun complete() {
        timeout?.let(handler::removeCallbacks)
        timeout = null
        current = null
        // Let the Bluetooth callback enqueue its protocol-dependent successor first.
        handler.post(::drain)
    }

    @SuppressLint("MissingPermission")
    private fun drain() {
        if (closed || current != null) return
        val next = pending.poll() ?: return
        current = next
        val result = when (next) {
            is Operation.CharacteristicWrite ->
                gatt.writeCharacteristic(next.characteristic, next.payload, next.writeType)
            is Operation.DescriptorWrite -> gatt.writeDescriptor(next.descriptor, next.payload)
        }
        if (result != BluetoothStatusCodes.SUCCESS) {
            log("GATT queue rejected ${next.label}: status=$result")
            current = null
            immediateFailures++
            if (immediateFailures >= Constants.MAX_BT_RETRIES) {
                pending.clear()
                log("GATT queue exhausted immediate retries for ${next.label}")
                onTimeout()
            } else {
                // BUSY is normally transient; put the operation back and retry with a bound.
                pending.addFirst(next)
                handler.postDelayed(::drain, 150L * immediateFailures)
            }
            return
        }
        immediateFailures = 0
        timeout = Runnable {
            if (current === next) {
                log("GATT operation timed out: ${next.label}")
                current = null
                pending.clear()
                onTimeout()
            }
        }.also { handler.postDelayed(it, Constants.GATT_OPERATION_TIMEOUT) }
    }
}
