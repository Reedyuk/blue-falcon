package dev.bluefalcon.viewModels

import android.app.AlertDialog
import android.bluetooth.BluetoothGattCharacteristic
import dev.bluefalcon.BlueFalconApplication
import dev.bluefalcon.BluetoothPeripheral
import java.nio.charset.Charset
import android.text.InputType
import android.widget.EditText
import dev.bluefalcon.BluetoothCharacteristic
import dev.bluefalcon.activities.DeviceServiceActivity
import dev.bluefalcon.services.BluetoothServiceCharacteristicDelegate


class DeviceCharacteristicViewModel(
    private val deviceServiceActivity: DeviceServiceActivity,
    private val deviceServiceViewModel: DeviceServiceViewModel,
    private val device: BluetoothPeripheral,
    var characteristic: BluetoothGattCharacteristic): BluetoothServiceCharacteristicDelegate {

    var notify = false
    val id = characteristic.uuid

    init {
        BlueFalconApplication.instance.bluetoothService.characteristicDelegates[characteristic.uuid] = this
    }

    fun value(): String {
        characteristic.value?.let { data ->
            String(data, Charset.defaultCharset()).let {
                return it
            }
        }
        return ""
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

}