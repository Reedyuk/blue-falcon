package dev.bluefalcon

class BluetoothPermissionException(message: String = "Permission required for bluetooth"): Exception(message)

class BluetoothNotEnabledException(message: String = "Bluetooth is not enabled"): Exception(message)

class BluetoothUnsupportedException(message: String = "Bluetooth is not supported on this device"): Exception(message)

class BluetoothResettingException(message: String = "Bluetooth service is currently resetting"): Exception(message)

class BluetoothUnknownException(message: String = "Unknown error happened"): Exception(message)
