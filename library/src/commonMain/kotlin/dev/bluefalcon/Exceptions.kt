package dev.bluefalcon

class PermissionException: Exception()

class BluetoothNotEnabledException: Exception()
// handle different states:
// bluetooth off
// device does not support bluetooth
// permission for bluetooth