//
//  DeviceViewModel.swift
//  Blue-Falcon
//
//  Created by Andrew Reed on 24/08/2019.
//  Copyright © 2019 Andrew Reed. All rights reserved.
//

import Foundation
import library
import CoreBluetooth

class DeviceViewModel: BlueFalconDelegate, ObservableObject {

    let device: CBPeripheral
    @Published var isConnected = false

    init(device: CBPeripheral) {
        self.device = device
        AppDelegate.instance.blueFalcon.delegates.add(self)
    }

    func connect() {
        AppDelegate.instance.blueFalcon.connect(bluetoothPeripheral: device)
    }

    //can we make this optional?
    func didDiscoverDevice(bluetoothPeripheral: CBPeripheral) {}

    func didConnect(bluetoothPeripheral: CBPeripheral) {
        guard isSameDevice(bluetoothPeripheral) else { return }
        //update view to show connected status
        print("connected to device \(bluetoothPeripheral.name)")
        isConnected = true
    }

    func didDisconnect(bluetoothPeripheral: CBPeripheral) {
        guard isSameDevice(bluetoothPeripheral) else { return }
        //update view to show disconnected
        print("disconnected to device \(bluetoothPeripheral.name)")
    }

    func didDiscoverServices(bluetoothPeripheral: CBPeripheral) {
        guard isSameDevice(bluetoothPeripheral) else { return }
        print("didDiscoverServices \(bluetoothPeripheral.services)")
    }

    private func isSameDevice(_ bluetoothPeripheral: CBPeripheral) -> Bool {
        return device.identifier == bluetoothPeripheral.identifier
    }
}
