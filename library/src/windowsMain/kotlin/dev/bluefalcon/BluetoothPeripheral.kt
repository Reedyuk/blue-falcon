package dev.bluefalcon

/**
 * Native Windows Bluetooth device wrapper
 * This wraps a Windows Bluetooth LE device address (UINT64)
 */
actual class NativeBluetoothDevice(val address: Long) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is NativeBluetoothDevice) return false
        return address == other.address
    }

    override fun hashCode(): Int {
        return address.hashCode()
    }

    override fun toString(): String {
        // Format as MAC address
        return String.format(
            "%02X:%02X:%02X:%02X:%02X:%02X",
            (address shr 40) and 0xFF,
            (address shr 32) and 0xFF,
            (address shr 24) and 0xFF,
            (address shr 16) and 0xFF,
            (address shr 8) and 0xFF,
            address and 0xFF
        )
    }
}
