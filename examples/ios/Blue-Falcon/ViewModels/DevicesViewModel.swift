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

    func didDiscoverDevice(bluetoothPeripheral: CBPeripheral) {
        guard !devices.contains(bluetoothPeripheral),
            let name = bluetoothPeripheral.name else { return }
        print("In-app did discover device. \(name)")
        devices.append(bluetoothPeripheral)
        devicesViewModels.append(
            DevicesCellViewModel(
                id: bluetoothPeripheral.identifier.uuidString,
                name: name,
                device: bluetoothPeripheral
            )
        )
    }

    func didConnect(bluetoothPeripheral: CBPeripheral) {}

    func didDisconnect(bluetoothPeripheral: CBPeripheral) {}

    func didDiscoverServices(bluetoothPeripheral: CBPeripheral) {}

}
