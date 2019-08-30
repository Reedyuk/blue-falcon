package dev.bluefalcon.viewModels

import android.app.AlertDialog
import android.bluetooth.BluetoothGattCharacteristic
import dev.bluefalcon.BlueFalconApplication
import dev.bluefalcon.BluetoothPeripheral
import java.nio.charset.Charset
import android.text.InputType
import android.widget.EditText
import dev.bluefalcon.activities.DeviceServiceActivity


class DeviceCharacteristicViewModel(
    private val deviceServiceActivity: DeviceServiceActivity,
    private val deviceServiceViewModel: DeviceServiceViewModel,
    private val device: BluetoothPeripheral,
    var characteristic: BluetoothGattCharacteristic) {

    var notify = false
    val id = characteristic.uuid

    fun value(): String {
        characteristic.value?.let { data ->
            String(data, Charset.defaultCharset()).let {
                return it
            }
        }
        return ""
    }

    fun readCharacteristicTapped() {
        BlueFalconApplication.instance.blueFalcon.readCharacteristic(
            device,
            characteristic
        )
    }

    fun notifyCharacteristicTapped() {
        notify = !notify
        BlueFalconApplication.instance.blueFalcon.notifyCharacteristic(
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
            BlueFalconApplication.instance.blueFalcon.writeCharacteristic(
                device,
                characteristic,
                input.text.toString()
            )
        }
        builder.setNegativeButton("Cancel") { dialog, _ -> dialog.cancel() }
        builder.show()
    }

}