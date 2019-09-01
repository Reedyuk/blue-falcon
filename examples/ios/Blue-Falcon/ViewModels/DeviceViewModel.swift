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
    @Published var state: CBPeripheralState = .connecting
    @Published var deviceServiceCellViewModels: [DeviceServiceCellViewModel] = []
    var services: [CBService] = []
    var title: String {
        "\(device.identifier.uuidString) \(device.name ?? "")"
    }

    init(device: CBPeripheral) {
        self.device = device
        refreshViewModel()
    }

    func addDelegate() {
        AppDelegate.instance.blueFalcon.delegates.add(self)
    }

    func removeDelegate() {
        AppDelegate.instance.blueFalcon.delegates.remove(self)
    }

    func refreshViewModel() {
        services = device.services ?? []
        state = device.state
    }

    func connect() {
        AppDelegate.instance.blueFalcon.connect(bluetoothPeripheral: device)
    }

    private func setupServices(bluetoothPeripheral: CBPeripheral) {
        guard isSameDevice(bluetoothPeripheral),
            let services = bluetoothPeripheral.services,
            !services.isEmpty
            else { return }
        print("didDiscoverServices \(services)")
        self.services = services
        self.deviceServiceCellViewModels = services.map { service -> DeviceServiceCellViewModel in
            DeviceServiceCellViewModel(id: service.uuid, service: service)
        }
    }

    //can we make this optional?
    func didDiscoverDevice(bluetoothPeripheral: CBPeripheral) {}

    func didConnect(bluetoothPeripheral: CBPeripheral) {
        guard isSameDevice(bluetoothPeripheral) else { return }
        state = bluetoothPeripheral.state
        AppDelegate.instance.connectedDevices.append(bluetoothPeripheral)
    }

    func didDisconnect(bluetoothPeripheral: CBPeripheral) {}

    func didDiscoverServices(bluetoothPeripheral: CBPeripheral) {
        setupServices(bluetoothPeripheral: bluetoothPeripheral)
    }

    func didDiscoverCharacteristics(bluetoothPeripheral: CBPeripheral) {}

    func didCharacteristcValueChanged(bluetoothPeripheral: CBPeripheral, bluetoothCharacteristic: CBCharacteristic) {}

    private func isSameDevice(_ bluetoothPeripheral: CBPeripheral) -> Bool {
        return device.identifier == bluetoothPeripheral.identifier
    }
}

