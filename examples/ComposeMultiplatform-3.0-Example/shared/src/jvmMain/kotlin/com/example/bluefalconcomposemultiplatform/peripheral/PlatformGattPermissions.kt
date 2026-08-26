package com.example.bluefalconcomposemultiplatform.peripheral

/**
 * Desktop JVM engines (Windows/Linux/macOS-JNI) do not support the peripheral/advertising
 * role (see `AppModule.desktop.kt`'s `NoOpBluetoothAdvertiser`), so these values are never
 * applied to a real GATT server. They exist only to satisfy the `expect` declaration.
 */
actual object PlatformGattPermissions {
    actual val readEncrypted: Int = 0
    actual val writeEncrypted: Int = 0
}
