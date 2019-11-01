package presentation.viewmodels.deviceservice

import dev.bluefalcon.BluetoothCharacteristic
import dev.bluefalcon.BluetoothPeripheral
import sample.BluetoothService
import sample.DeviceCharacteristicDelegate

class DeviceCharacteristicViewModel(
    private val bluetoothService: BluetoothService,
    private var bluetoothDevice: BluetoothPeripheral,
    private val characteristic: BluetoothCharacteristic
): DeviceCharacteristicDelegate {
    var output: DeviceCharacteristicViewModelOutput? = null
    val displayName = characteristic.name
    var notify: Boolean = false
    fun value(): String = characteristic.value ?: ""

    init {
        bluetoothService.addDeviceCharacteristicDelegate(this)
    }

    fun readCharacteristicTapped() {
        bluetoothService.readCharacteristic(
            bluetoothDevice,
            characteristic
        )
    }

    fun notifyCharacteristicTapped() {
        notify = !notify
        bluetoothService.notifyCharacteristic(
            bluetoothDevice,
            characteristic,
            notify
        )
        output?.refresh()
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

    override fun didCharacteristcValueChanged(value: String) {
        output?.refresh()
    }
}

interface DeviceCharacteristicViewModelOutput {
    fun refresh()
}