//
//  DevicesViewModel.swift
//  Blue-Falcon
//
//  Created by Andrew Reed on 24/08/2019.
//  Copyright © 2019 Andrew Reed. All rights reserved.
//

import Foundation
import BlueFalcon
import CoreBluetooth
import Combine

class DevicesViewModel: ObservableObject {

    @Published var devicesViewModels: [DevicesCellViewModel] = []
    @Published var status: BluetoothScanningState = .notScanning

    func onAppear() {
        AppDelegate.instance.bluetoothService.detectedDeviceDelegates.append(self)
    }

    func onDisapear() {
        AppDelegate.instance.bluetoothService.removeDetectedDeviceDelegate(delegate: self)
    }

    func scan() throws {
        try AppDelegate.instance.bluetoothService.scan()
        status = .scanning
    }

}

extension DevicesViewModel: BluetoothServiceDetectedDeviceDelegate {

    func discoveredDevice(devices: [BluetoothPeripheral]) {
        devicesViewModels = devices.map { device -> DevicesCellViewModel in
            var deviceName = ""
            if let name = device.name {
                deviceName += " \(name)"
            }
            return DevicesCellViewModel(
                id: device.bluetoothDevice.identifier.uuidString,
                name: deviceName,
                device: device
            )
        }
    }

}
