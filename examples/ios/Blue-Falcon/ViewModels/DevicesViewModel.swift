//
//  DevicesViewModel.swift
//  Blue-Falcon
//
//  Created by Andrew Reed on 24/08/2019.
//  Copyright © 2019 Andrew Reed. All rights reserved.
//

import Foundation
import library
import CoreBluetooth
import Combine

class DevicesViewModel: BlueFalconDelegate, ObservableObject {

    @Published var devicesViewModels: [DevicesCellViewModel] = []
    @Published var status = "Not Scanning"
    var devices: [CBPeripheral] = []

    func scan() throws {
        AppDelegate.instance.blueFalcon.delegates.add(self)
        try AppDelegate.instance.blueFalcon.scan()
        status = "Scanning"
        disconnectAllDevices()
    }

    func stopScanning() {
        AppDelegate.instance.blueFalcon.stopScanning()
        AppDelegate.instance.blueFalcon.delegates.remove(self)
        status = "Not Scanning"
    }

    func disconnectAllDevices() {
        AppDelegate.instance.connectedDevices.forEach { device in
            AppDelegate.instance.blueFalcon.disconnect(bluetoothPeripheral: device)
        }
    }

    func didDiscoverDevice(bluetoothPeripheral: CBPeripheral) {
        guard !devices.contains(bluetoothPeripheral) else { return }
        devices.append(bluetoothPeripheral)
        var deviceName = ""
        if let name = bluetoothPeripheral.name {
            deviceName += " \(name)"
        }
        devicesViewModels.append(
            DevicesCellViewModel(
                id: bluetoothPeripheral.identifier.uuidString,
                name: deviceName,
                device: bluetoothPeripheral
            )
        )
    }

    func didConnect(bluetoothPeripheral: CBPeripheral) {}

    func didDisconnect(bluetoothPeripheral: CBPeripheral) {
        AppDelegate.instance.connectedDevices.removeAll { device -> Bool in
            device == bluetoothPeripheral
        }
        guard let name = bluetoothPeripheral.name else { return }
        print("disconnected to device \(name)")
    }

    func didDiscoverServices(bluetoothPeripheral: CBPeripheral) {}

    func didDiscoverCharacteristics(bluetoothPeripheral: CBPeripheral) {}

    func didCharacteristcValueChanged(bluetoothPeripheral: CBPeripheral, bluetoothCharacteristic: CBCharacteristic) {}

}
