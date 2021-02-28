//
//  DeviceServiceViewModel.swift
//  Blue-Falcon
//
//  Created by Andrew Reed on 24/08/2019.
//  Copyright © 2019 Andrew Reed. All rights reserved.
//

import SwiftUI
import BlueFalcon
import Combine
import CoreBluetooth

class DeviceServiceViewModel: ObservableObject {

    let service: CBService
    let device: BluetoothPeripheral
    var characteristics: [CBCharacteristic] = []
    @Published var deviceCharacteristicCellViewModels: [DeviceCharacteristicCellViewModel] = []

    init(service: CBService, device: BluetoothPeripheral) {
        self.service = service
        self.device = device
        self.characteristics = service.characteristics ?? []
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

}
