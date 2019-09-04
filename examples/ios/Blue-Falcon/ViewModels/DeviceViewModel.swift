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

class DeviceViewModel: ObservableObject {

    @Published var state: CBPeripheralState = .connecting
    @Published var deviceServiceCellViewModels: [DeviceServiceCellViewModel] = []

    let device: CBPeripheral
    let title: String

    init(device: CBPeripheral) {
        self.device = device
        title = "\(device.identifier.uuidString) \(device.name ?? "")"
        AppDelegate.instance.bluetoothService.connectedDeviceDelegate.append((device.identifier, self))
    }

    func onAppear() {
        connect()
    }

    func onDisapear() {

    }

    func connect() {
        AppDelegate.instance.bluetoothService.connect(bluetoothPeripheral: device)
    }

}

extension DeviceViewModel: BluetoothServiceConnectedDeviceDelegate {

    func connectedDevice() {
        state = .connected
    }

    func discoveredServices() {
        guard let services = device.services else { return }
        self.deviceServiceCellViewModels = services.map({ service -> DeviceServiceCellViewModel in
            DeviceServiceCellViewModel(id: service.uuid, service: service)
        })
    }

}
