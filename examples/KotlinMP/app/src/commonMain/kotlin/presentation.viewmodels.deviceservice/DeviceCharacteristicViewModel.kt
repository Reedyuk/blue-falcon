package presentation.viewmodels.deviceservice

import dev.bluefalcon.BluetoothCharacteristic

class DeviceCharacteristicViewModel(private val characteristic: BluetoothCharacteristic) {

    val displayName = characteristic.name
    fun value(): String {
        characteristic.value?.let { data ->
            String(data, Charset.defaultCharset()).let {
                return it
            }
        }
        return ""
    }

    fun readCharacteristicTapped() {
//        BlueFalconApplication.instance.bluetoothService.readCharacteristic(
//            device,
//            characteristic
//        )
    }

    fun notifyCharacteristicTapped() {
//        notify = !notify
//        BlueFalconApplication.instance.bluetoothService.notifyCharacteristic(
//            device,
//            characteristic,
//            notify
//        )
//        deviceServiceViewModel.notifyValueChanged()
    }

    fun writeCharactersticTapped() {
//        val builder = AlertDialog.Builder(deviceServiceActivity)
//        builder.setTitle("Input value to send to characteristic")
//        val input = EditText(deviceServiceActivity)
//        input.inputType = InputType.TYPE_CLASS_TEXT
//        builder.setView(input)
//
//        builder.setPositiveButton("OK") { _, _ ->
//            BlueFalconApplication.instance.bluetoothService.writeCharacteristic(
//                device,
//                characteristic,
//                input.text.toString()
//            )
//        }
//        builder.setNegativeButton("Cancel") { dialog, _ -> dialog.cancel() }
//        builder.show()
    }
}