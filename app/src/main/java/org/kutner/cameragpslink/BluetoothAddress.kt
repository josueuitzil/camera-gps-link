package org.kutner.cameragpslink

import java.util.Locale

/** Canonicalizes Bluetooth addresses at Android/system boundaries. */
object BluetoothAddress {
    private val addressPattern = Regex("^(?:[0-9A-F]{2}:){5}[0-9A-F]{2}$")

    fun normalize(value: String?): String? {
        val normalized = value?.trim()?.uppercase(Locale.ROOT) ?: return null
        return normalized.takeIf(addressPattern::matches)
    }
}
