package dev.bluefalcon.viewModels

import android.app.AlertDialog
import java.nio.charset.Charset
import android.text.InputType
import android.widget.EditText
import dev.bluefalcon.*
import dev.bluefalcon.activities.DeviceServiceActivity
import dev.bluefalcon.services.BluetoothServiceCharacteristicDelegate
import java.util.*

class DeviceCharacteristicViewModel(
    private val deviceServiceActivity: DeviceServiceActivity,
    private val deviceServiceViewModel: DeviceServiceViewModel,
    private val device: BluetoothPeripheral,
    var characteristic: BluetoothCharacteristic): BluetoothServiceCharacteristicDelegate {

    var notify = false
    val id = characteristic.characteristic.uuid
    var descriptorValues: MutableMap<UUID, String> = mutableMapOf()
    val descriptors = characteristic.descriptors

    init {
        BlueFalconApplication.instance.bluetoothService.characteristicDelegates[characteristic.characteristic.uuid] = this
    }

    fun value(): String? = characteristic.value?.toString(Charset.defaultCharset())

    fun readDescriptorTapped() {
        log("readDescriptorTapped number of descriptors: ${characteristic.descriptors.size}")
        characteristic.descriptors.forEach { descriptor ->
            BlueFalconApplication.instance.bluetoothService.readDescriptor(
                device,
                characteristic,
                descriptor
            )
        }
    }

    fun readCharacteristicTapped() {
        BlueFalconApplication.instance.bluetoothService.readCharacteristic(
            device,
            characteristic
        )
    }

    fun notifyCharacteristicTapped() {
        notify = !notify
        BlueFalconApplication.instance.bluetoothService.notifyCharacteristic(
            device,
            characteristic,
            notify
        )
        deviceServiceViewModel.notifyValueChanged()
    }

    fun writeCharactersticTapped() {
        val builder = AlertDialog.Builder(deviceServiceActivity)
        builder.setTitle("Input value to send to characteristic")
        val input = EditText(deviceServiceActivity)
        input.inputType = InputType.TYPE_CLASS_TEXT
        builder.setView(input)

        builder.setPositiveButton("OK") { _, _ ->
            BlueFalconApplication.instance.bluetoothService.writeCharacteristic(
                device,
                characteristic,
                input.text.toString()
            )
        }
        builder.setNegativeButton("Cancel") { dialog, _ -> dialog.cancel() }
        builder.show()
    }

    override fun characteristcValueChanged(bluetoothCharacteristic: BluetoothCharacteristic) {
        characteristic = bluetoothCharacteristic
        deviceServiceViewModel.notifyValueChanged()
    }

    override fun descriptorValueChanged(bluetoothCharacteristicDescriptor: BluetoothCharacteristicDescriptor) {

        descriptorValues[bluetoothCharacteristicDescriptor.uuid] = String(bluetoothCharacteristicDescriptor.value)
        deviceServiceViewModel.notifyValueChanged()
    }

}