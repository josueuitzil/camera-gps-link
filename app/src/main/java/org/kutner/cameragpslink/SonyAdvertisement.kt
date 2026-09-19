package org.kutner.cameragpslink

/** Parser and hardware-filter values for Sony camera BLE manufacturer data. */
object SonyAdvertisement {
    private const val FIRST_TAG_OFFSET = 6
    private const val TAG_SIZE = 3
    // Observed on a production a6700 advertisement. Tags before 0x21 can vary,
    // which is why the software parser above still walks all three-byte tags.
    private const val A6700_POWER_TAG_OFFSET = 12
    private const val POWER_TAG = 0x21
    private const val POWERED_ON_MASK = 0x0040
    private const val REMOTE_CONTROL_TAG = 0x22
    private const val REMOTE_CONTROL_MASK = 0x0004

    data class State(
        val protocolVersion: Int,
        val isPoweredOn: Boolean,
        val isRemoteControlEnabled: Boolean,
    )

    fun parse(data: ByteArray): State? {
        if (data.size < 3 || !data.copyOfRange(0, 2).contentEquals(Constants.SONY_DEVICE_TYPE_CAMERA)) {
            return null
        }
        return State(
            protocolVersion = data[2].toInt() and 0xff,
            isPoweredOn = feature(data, POWER_TAG, POWERED_ON_MASK, defaultValue = true),
            isRemoteControlEnabled = feature(data, REMOTE_CONTROL_TAG, REMOTE_CONTROL_MASK, defaultValue = false),
        )
    }

    fun feature(data: ByteArray, tag: Int, mask: Int, defaultValue: Boolean): Boolean {
        var offset = FIRST_TAG_OFFSET
        while (offset <= data.size - TAG_SIZE) {
            if ((data[offset].toInt() and 0xff) == tag) {
                val value = (data[offset + 1].toInt() and 0xff) or
                    ((data[offset + 2].toInt() and 0xff) shl 8)
                return value and mask != 0
            }
            offset += TAG_SIZE
        }
        return defaultValue
    }

    /**
     * Manufacturer filter for the observed a6700/Creators' App advertisement layout.
     * Zero mask bytes are wildcards. Matching the power bit prevents the system
     * PendingIntent scan from waking the app for the camera's power-off beacon.
     */
    val poweredOnFilterData = ByteArray(A6700_POWER_TAG_OFFSET + TAG_SIZE).apply {
        this[0] = 0x03
        this[1] = 0x00
        this[A6700_POWER_TAG_OFFSET] = POWER_TAG.toByte()
        this[A6700_POWER_TAG_OFFSET + 1] = POWERED_ON_MASK.toByte()
    }

    val poweredOnFilterMask = ByteArray(A6700_POWER_TAG_OFFSET + TAG_SIZE).apply {
        this[0] = 0xff.toByte()
        this[1] = 0xff.toByte()
        this[A6700_POWER_TAG_OFFSET] = 0xff.toByte()
        this[A6700_POWER_TAG_OFFSET + 1] = POWERED_ON_MASK.toByte()
    }
}
