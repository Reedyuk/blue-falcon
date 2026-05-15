package dev.bluefalcon.core

/**
 * Bluetooth permission exception
 */
class BluetoothPermissionException(
    message: String = "Permission required for bluetooth"
) : Exception(message)

/**
 * Bluetooth not enabled exception
 */
class BluetoothNotEnabledException(
    message: String = "Bluetooth is not enabled"
) : Exception(message)

/**
 * Bluetooth not supported exception
 */
class BluetoothUnsupportedException(
    message: String = "Bluetooth is not supported on this device"
) : Exception(message)

/**
 * Bluetooth resetting exception
 */
class BluetoothResettingException(
    message: String = "Bluetooth service is currently resetting"
) : Exception(message)

/**
 * Unknown Bluetooth exception
 */
class BluetoothUnknownException(
    message: String = "Unknown error happened"
) : Exception(message)

/**
 * L2CAP channel open/IO failure. Gives callers a typed failure instead of a
 * raw platform exception.
 */
class L2capException(
    message: String = "L2CAP channel error",
    cause: Throwable? = null
) : Exception(message, cause)
