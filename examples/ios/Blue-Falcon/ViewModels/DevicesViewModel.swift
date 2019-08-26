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
    var devices: [CBPeripheral] = []

    init() {
        AppDelegate.instance.blueFalcon.delegates.add(self)
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
                name: bluetoothPeripheral.identifier.uuidString + deviceName,
                device: bluetoothPeripheral
            )
        )
    }

    func didConnect(bluetoothPeripheral: CBPeripheral) {}

    func didDisconnect(bluetoothPeripheral: CBPeripheral) {
        AppDelegate.instance.connectedDevices.removeAll { device -> Bool in
            device == bluetoothPeripheral
        }
        print("disconnected to device \(bluetoothPeripheral.name)")
    }

    func didDiscoverServices(bluetoothPeripheral: CBPeripheral) {}

    func didDiscoverCharacteristics(bluetoothPeripheral: CBPeripheral) {}

}
