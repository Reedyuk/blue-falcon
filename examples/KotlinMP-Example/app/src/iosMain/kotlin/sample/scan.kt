package sample

actual fun BluetoothService.scan() {
    performScan()
}