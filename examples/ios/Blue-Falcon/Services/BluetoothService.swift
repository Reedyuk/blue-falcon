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

    private let blueFalcon = BlueFalcon()
    private var devices: [CBPeripheral] = []
    var detectedDeviceDelegates: [BluetoothServiceDetectedDeviceDelegate] = []
    var connectedDeviceDelegate: [(UUID, BluetoothServiceConnectedDeviceDelegate)] = []

    init() {
        blueFalcon.delegates.add(self)
    }

    func scan() throws {
        try blueFalcon.scan()
    }

    func connect(bluetoothPeripheral: CBPeripheral) {
        blueFalcon.connect(bluetoothPeripheral: bluetoothPeripheral)
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
        bluetoothServiceConnectedDeviceDelegates(bluetoothPeripheralId: bluetoothPeripheral.identifier)
        .forEach { bluetoothServiceConnectedDeviceDelegate in
            bluetoothServiceConnectedDeviceDelegate.connectedDevice()
        }
    }

    func didDisconnect(bluetoothPeripheral: CBPeripheral) {

    }

    func didDiscoverServices(bluetoothPeripheral: CBPeripheral) {
        bluetoothServiceConnectedDeviceDelegates(bluetoothPeripheralId: bluetoothPeripheral.identifier)
        .forEach { bluetoothServiceConnectedDeviceDelegate in
            bluetoothServiceConnectedDeviceDelegate.discoveredServices()
        }
    }

    func didDiscoverCharacteristics(bluetoothPeripheral: CBPeripheral) {

    }

    func didCharacteristcValueChanged(
        bluetoothPeripheral: CBPeripheral,
        bluetoothCharacteristic: CBCharacteristic
    ) {

    }

    private func bluetoothServiceConnectedDeviceDelegates(bluetoothPeripheralId: UUID) -> [BluetoothServiceConnectedDeviceDelegate] {
        return connectedDeviceDelegate.compactMap { connectedDeviceDelegateTuple -> BluetoothServiceConnectedDeviceDelegate? in
            connectedDeviceDelegateTuple.0 == bluetoothPeripheralId ? connectedDeviceDelegateTuple.1 : nil
        }
    }

}

protocol BluetoothServiceDetectedDeviceDelegate {
    func discoveredDevice(devices: [CBPeripheral])
}

protocol BluetoothServiceConnectedDeviceDelegate {
    func connectedDevice()
    func discoveredServices()
}
