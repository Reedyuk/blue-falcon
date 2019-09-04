//
//  BluetoothService.swift
//  Blue-Falcon
//
//  Created by Andrew Reed on 04/09/2019.
//  Copyright © 2019 Andrew Reed. All rights reserved.
//

import Foundation
import library
import CoreBluetooth

class BluetoothService {

    let blueFalcon = BlueFalcon()
    private var devices: [CBPeripheral] = []
    var detectedDeviceDelegates: [BluetoothServiceDetectedDeviceDelegate] = []

    init() {
        blueFalcon.delegates.add(self)
    }

    func scan() throws {
        try blueFalcon.scan()
    }

}

extension BluetoothService: BlueFalconDelegate {

    func didDiscoverDevice(bluetoothPeripheral: CBPeripheral) {
        guard !devices.contains(bluetoothPeripheral) else { return }
        devices.append(bluetoothPeripheral)
        detectedDeviceDelegates.forEach { delegate in
            delegate.discoveredDevice(devices: devices)
        }
    }

    func didConnect(bluetoothPeripheral: CBPeripheral) {

    }

    func didDisconnect(bluetoothPeripheral: CBPeripheral) {

    }

    func didDiscoverServices(bluetoothPeripheral: CBPeripheral) {

    }

    func didDiscoverCharacteristics(bluetoothPeripheral: CBPeripheral) {

    }

    func didCharacteristcValueChanged(
        bluetoothPeripheral: CBPeripheral,
        bluetoothCharacteristic: CBCharacteristic
    ) {

    }

}

protocol BluetoothServiceDetectedDeviceDelegate {

    func discoveredDevice(devices: [CBPeripheral])

}
