//
//  DeviceServiceViewModel.swift
//  Blue-Falcon
//
//  Created by Andrew Reed on 24/08/2019.
//  Copyright © 2019 Andrew Reed. All rights reserved.
//

import SwiftUI
import library
import Combine
import CoreBluetooth

class DeviceServiceViewModel: BlueFalconDelegate, ObservableObject {

    let service: CBService
    let device: CBPeripheral
    var characteristics: [CBCharacteristic] = []
    @Published var deviceCharacteristicCellViewModels: [DeviceCharacteristicCellViewModel] = []

    init(service: CBService, device: CBPeripheral) {
        self.service = service
        self.device = device
        self.characteristics = service.characteristics ?? []
        createViewModelsFromCharacteristics()
        addDelegate()
    }

    func addDelegate() {
        AppDelegate.instance.blueFalcon.delegates.add(self)
    }

    func removeDelegate() {
        AppDelegate.instance.blueFalcon.delegates.remove(self)
    }

    //consider moving to the characteristic cell view model.
    func readCharacteristicTapped(_ characteristic: CBCharacteristic) {
        AppDelegate.instance.blueFalcon.readCharacteristic(
            bluetoothPeripheral: device,
            bluetoothCharacteristic: characteristic
        )
    }

    func writeCharacteristicTapped(_ characteristic: CBCharacteristic) {
        print("writeCharacteristicTapped")
        let alert = UIAlertController(
            title: "Characteristic Write",
            message: "Please enter a value to write to the characteristic",
            preferredStyle: .alert
        )
        alert.addTextField { _ in
        }
        alert.addAction(UIAlertAction(title: "OK", style: .default, handler: { _ in
            guard let input = alert.textFields?.first?.text else { return }
            AppDelegate.instance.blueFalcon.writeCharacteristic(
                bluetoothPeripheral: self.device,
                bluetoothCharacteristic: characteristic,
                value: input
            )
        }))
        alert.addAction(UIAlertAction(title: "Cancel", style: .cancel))
        SceneDelegate.instance.window?.rootViewController?.present(alert, animated: true)
    }

    func didDiscoverDevice(bluetoothPeripheral: CBPeripheral) {}

    func didConnect(bluetoothPeripheral: CBPeripheral) {}

    func didDisconnect(bluetoothPeripheral: CBPeripheral) {}

    func didDiscoverServices(bluetoothPeripheral: CBPeripheral) {}

    func didDiscoverCharacteristics(bluetoothPeripheral: CBPeripheral) {
        guard isSameDevice(bluetoothPeripheral),
            let characteristics = service.characteristics else { return }
        print("didDiscoverCharacteristics \(characteristics)")
        self.characteristics = characteristics
        createViewModelsFromCharacteristics()
    }

    func didCharacteristcValueChanged(bluetoothPeripheral: CBPeripheral, bluetoothCharacteristic: CBCharacteristic) {
        print("didCharacteristcValueChanged - \(String(describing: bluetoothCharacteristic.value))")
        createViewModelsFromCharacteristics()
    }

    private func createViewModelsFromCharacteristics() {
        deviceCharacteristicCellViewModels = characteristics.map { characteristic -> DeviceCharacteristicCellViewModel in
            DeviceCharacteristicCellViewModel(
                id: characteristic.uuid,
                characteristic: characteristic,
                device: device
            )
        }
    }

    private func isSameDevice(_ bluetoothPeripheral: CBPeripheral) -> Bool {
        return device.identifier == bluetoothPeripheral.identifier
    }

}
