package org.kutner.cameragpslink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BluetoothAddressTest {
    @Test
    fun `normalizes lowercase CDM address for Android scan filters`() {
        assertEquals("34:90:EA:F0:BC:E2", BluetoothAddress.normalize("34:90:ea:f0:bc:e2"))
    }

    @Test
    fun `trims a valid address`() {
        assertEquals("34:90:EA:F0:BC:E2", BluetoothAddress.normalize(" 34:90:EA:F0:BC:E2 "))
    }

    @Test
    fun `rejects malformed addresses`() {
        assertNull(BluetoothAddress.normalize("34:90:EA:F0:BC"))
        assertNull(BluetoothAddress.normalize("not-an-address"))
        assertNull(BluetoothAddress.normalize(null))
    }
}
