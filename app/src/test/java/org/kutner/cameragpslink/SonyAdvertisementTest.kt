package org.kutner.cameragpslink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SonyAdvertisementTest {
    @Test
    fun parsesPoweredOnA6700Layout() {
        val data = byteArrayOf(
            0x03, 0x00, 0x65, 0x00, 0x55, 0x31,
            0x22, 0xbb.toByte(), 0xc0.toByte(),
            0x23, 0xb7.toByte(), 0xac.toByte(),
            0x21, 0x60, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00,
        )

        val state = SonyAdvertisement.parse(data)

        assertNotNull(state)
        assertEquals(0x65, state?.protocolVersion)
        assertTrue(state?.isPoweredOn == true)
        assertFalse(state?.isRemoteControlEnabled ?: true)
        assertTrue(matchesHardwarePowerFilter(data))
    }

    @Test
    fun powerOffBeaconDoesNotMatchWakeFilter() {
        val data = byteArrayOf(
            0x03, 0x00, 0x65, 0x00, 0x55, 0x31,
            0x22, 0xbb.toByte(), 0xc0.toByte(),
            0x23, 0xb7.toByte(), 0xac.toByte(),
            0x21, 0x20, 0x00,
        )

        val state = SonyAdvertisement.parse(data)

        assertFalse(state?.isPoweredOn ?: true)
        assertFalse(matchesHardwarePowerFilter(data))
    }

    @Test
    fun rejectsNonCameraManufacturerData() {
        assertNull(SonyAdvertisement.parse(byteArrayOf(0x02, 0x00, 0x65)))
    }

    @Test
    fun unknownPowerTagDefaultsToConnectForCompatibility() {
        val data = byteArrayOf(0x03, 0x00, 0x64, 0x45, 0x31, 0x00)
        assertTrue(SonyAdvertisement.parse(data)?.isPoweredOn == true)
    }

    private fun matchesHardwarePowerFilter(data: ByteArray): Boolean {
        if (data.size < SonyAdvertisement.poweredOnFilterData.size) return false
        return SonyAdvertisement.poweredOnFilterData.indices.all { index ->
            val mask = SonyAdvertisement.poweredOnFilterMask[index].toInt() and 0xff
            val expected = SonyAdvertisement.poweredOnFilterData[index].toInt() and mask
            val actual = data[index].toInt() and mask
            expected == actual
        }
    }
}
