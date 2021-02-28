//
//  DeviceViewModel.swift
//  Blue-Falcon
//
//  Created by Andrew Reed on 24/08/2019.
//  Copyright © 2019 Andrew Reed. All rights reserved.
//

import Foundation
import BlueFalcon
import CoreBluetooth
import Combine

class DeviceViewModel: ObservableObject {

    @Published var state: CBPeripheralState = .connecting
    @Published var deviceServiceCellViewModels: [DeviceServiceCellViewModel] = []

    let device: BluetoothPeripheral
    let title: String

    init(device: BluetoothPeripheral) {
        self.device = device
        title = "\(device.bluetoothDevice.identifier.uuidString) \(device.name ?? "")"
    }

    func onAppear() {
        AppDelegate.instance.bluetoothService.connectedDeviceDelegates.append((device.bluetoothDevice.identifier, self))
        connect()
    }

    func onDisapear() {
        AppDelegate.instance.bluetoothService.removeConnectedDeviceDelegate(delegate: self)
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
        guard let services = device.bluetoothDevice.services else { return }
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.0) {
            self.deviceServiceCellViewModels = services.map({ service -> DeviceServiceCellViewModel in
                DeviceServiceCellViewModel(id: service.uuid, service: service)
            })
        }
    }

}
