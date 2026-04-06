package dev.bluefalcon

import com.monkopedia.sdbus.Object
import com.monkopedia.sdbus.ObjectPath
import dev.bluefalcon.bluez.Agent1Adaptor

/**
 * A BlueZ Agent1 implementation that auto-accepts all pairing requests.
 * Uses NoInputNoOutput capability for Just Works pairing.
 */
class NoInputNoOutputAgent(obj: Object) : Agent1Adaptor(obj) {
    override suspend fun release() {}
    override suspend fun requestPinCode(device: ObjectPath): String = "0000"
    override suspend fun displayPinCode(device: ObjectPath, pincode: String) {}
    override suspend fun requestPasskey(device: ObjectPath): UInt = 0u
    override suspend fun displayPasskey(device: ObjectPath, passkey: UInt, entered: UShort) {}
    override suspend fun requestConfirmation(device: ObjectPath, passkey: UInt) {}
    override suspend fun requestAuthorization(device: ObjectPath) {}
    override suspend fun authorizeService(device: ObjectPath, uuid: String) {}
    override suspend fun cancel() {}
}
