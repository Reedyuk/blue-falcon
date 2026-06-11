package dev.bluefalcon.engine.js

import dev.bluefalcon.engine.js.external.Bluetooth
import dev.bluefalcon.engine.js.external.BluetoothDevice
import dev.bluefalcon.engine.js.external.BluetoothOptions
import dev.bluefalcon.engine.js.external.BluetoothRemoteGATTCharacteristic
import dev.bluefalcon.engine.js.external.BluetoothRemoteGATTServer
import dev.bluefalcon.engine.js.external.BluetoothRemoteGATTService
import kotlinx.browser.window
import kotlinx.coroutines.await
import kotlin.js.Promise
import org.khronos.webgl.DataView
import org.khronos.webgl.Int8Array
import org.w3c.dom.Navigator
import org.w3c.dom.events.Event
import org.w3c.dom.events.EventListener

private const val CHARACTERISTIC_VALUE_CHANGED = "characteristicvaluechanged"

internal actual typealias WebBluetoothApi = JsWebBluetoothApi
internal actual typealias WebDevice = JsWebDevice
internal actual typealias WebService = JsWebService
internal actual typealias WebCharacteristic = JsWebCharacteristic

private inline val Navigator.bluetooth: Bluetooth get() = asDynamic().bluetooth as Bluetooth

internal class JsWebBluetoothApi {
    suspend fun requestDevice(optionalServiceUuids: List<String>): JsWebDevice? {
        // Build the options without a `filters` field — accept any device and declare
        // the requested services as optional so they can be accessed after connecting.
        val options: dynamic = js("({})")
        options.acceptAllDevices = true
        options.optionalServices = optionalServiceUuids.toTypedArray()
        return try {
            val device = window.navigator.bluetooth
                .requestDevice(options.unsafeCast<BluetoothOptions>())
                .await()
            JsWebDevice(device)
        } catch (error: Throwable) {
            // A dismissed chooser (or no matching device) rejects with NotFoundError;
            // report that as "nothing selected" rather than surfacing it as a failure.
            if (error.isNotFoundError()) null else throw error
        }
    }
}

// Web Bluetooth raises a NotFoundError whenever a requested GATT entry is absent: a
// dismissed/empty device chooser, or a service/characteristic the device doesn't expose.
private fun Throwable.isNotFoundError(): Boolean =
    asDynamic().name == "NotFoundError"

// Awaits a discovery promise, returning the entries that exist. A requested UUID the
// device doesn't expose rejects with NotFoundError; treat that as "none" so a single
// missing UUID doesn't discard the services/characteristics that were found.
private suspend fun <T> Promise<Array<T>>.awaitOrEmptyIfNotFound(): List<T> =
    try {
        await().toList()
    } catch (error: Throwable) {
        if (error.isNotFoundError()) emptyList() else throw error
    }

internal class JsWebDevice(val device: BluetoothDevice) {
    val id: String get() = device.id
    val name: String? get() = device.name
    val isConnected: Boolean get() = device.gatt?.connected == true

    suspend fun connect() {
        device.gatt?.connect()?.await()
    }

    fun disconnect() {
        device.gatt?.disconnect()
    }

    suspend fun getPrimaryServices(serviceUuids: List<String>): List<JsWebService> {
        val gatt = device.gatt ?: return emptyList()
        val services = if (serviceUuids.isEmpty()) {
            // No argument → all primary services. An explicit null is read as a service name.
            gatt.getAllPrimaryServices().await().toList()
        } else {
            serviceUuids.flatMap { gatt.getPrimaryServices(it).awaitOrEmptyIfNotFound() }
        }
        return services.map { JsWebService(it) }
    }
}

internal class JsWebService(val service: BluetoothRemoteGATTService) {
    val uuid: String get() = service.uuid

    suspend fun getCharacteristics(characteristicUuids: List<String>): List<JsWebCharacteristic> {
        val characteristics = if (characteristicUuids.isEmpty()) {
            service.getAllCharacteristics().await().toList()
        } else {
            characteristicUuids.flatMap { service.getCharacteristics(it).awaitOrEmptyIfNotFound() }
        }
        return characteristics.map { JsWebCharacteristic(it) }
    }
}

internal class JsWebCharacteristic(val characteristic: BluetoothRemoteGATTCharacteristic) {
    private var valueChangedListener: EventListener? = null

    val uuid: String get() = characteristic.uuid
    val value: ByteArray? get() = characteristic.value?.toByteArray()

    suspend fun readValue(): ByteArray = characteristic.readValue().await().toByteArray()

    suspend fun writeValue(value: ByteArray) {
        characteristic.writeValue(value).await()
    }

    suspend fun startNotifications(onValueChanged: (ByteArray) -> Unit) {
        // Replace any previous listener so repeated enables don't stack handlers.
        valueChangedListener?.let { characteristic.removeEventListener(CHARACTERISTIC_VALUE_CHANGED, it) }
        val listener = object : EventListener {
            override fun handleEvent(event: Event) {
                characteristic.value?.toByteArray()?.let(onValueChanged)
            }
        }
        valueChangedListener = listener
        characteristic.addEventListener(CHARACTERISTIC_VALUE_CHANGED, listener)
        try {
            characteristic.startNotifications().await()
        } catch (error: Throwable) {
            // The native enable rejected (device disconnected, notifications unsupported,
            // …) — detach the listener we just attached so it and its closure don't leak,
            // then surface the failure.
            characteristic.removeEventListener(CHARACTERISTIC_VALUE_CHANGED, listener)
            valueChangedListener = null
            throw error
        }
    }

    suspend fun stopNotifications() {
        // Always detach the listener, even if the native stop rejects (e.g. the device
        // disconnected) — otherwise the handler and its closure leak.
        try {
            characteristic.stopNotifications().await()
        } finally {
            valueChangedListener?.let {
                characteristic.removeEventListener(CHARACTERISTIC_VALUE_CHANGED, it)
                valueChangedListener = null
            }
        }
    }
}

// copyOf() detaches the result from the DataView's backing ArrayBuffer. Without it the
// returned array is a live view: the browser reuses the buffer on the next notification,
// so a previously-emitted value would mutate underneath any collector still holding it.
// (The wasmJs actual copies element-by-element for the same reason.)
private fun DataView.toByteArray(): ByteArray =
    Int8Array(buffer, byteOffset, byteLength).unsafeCast<ByteArray>().copyOf()

// "All services/characteristics" must call the method with NO argument — passing an
// explicit null makes Web Bluetooth read it as a (invalid) service/characteristic name.
private fun BluetoothRemoteGATTServer.getAllPrimaryServices(): Promise<Array<BluetoothRemoteGATTService>> =
    asDynamic().getPrimaryServices().unsafeCast<Promise<Array<BluetoothRemoteGATTService>>>()

private fun BluetoothRemoteGATTService.getAllCharacteristics(): Promise<Array<BluetoothRemoteGATTCharacteristic>> =
    asDynamic().getCharacteristics().unsafeCast<Promise<Array<BluetoothRemoteGATTCharacteristic>>>()
