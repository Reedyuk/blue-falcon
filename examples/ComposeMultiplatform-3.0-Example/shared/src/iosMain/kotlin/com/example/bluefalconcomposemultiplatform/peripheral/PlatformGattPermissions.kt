package com.example.bluefalconcomposemultiplatform.peripheral

import kotlinx.cinterop.ExperimentalForeignApi
import platform.CoreBluetooth.CBAttributePermissionsReadEncryptionRequired
import platform.CoreBluetooth.CBAttributePermissionsWriteEncryptionRequired

@OptIn(ExperimentalForeignApi::class)
actual object PlatformGattPermissions {
    actual val readEncrypted: Int = CBAttributePermissionsReadEncryptionRequired.toInt()
    actual val writeEncrypted: Int = CBAttributePermissionsWriteEncryptionRequired.toInt()
}
