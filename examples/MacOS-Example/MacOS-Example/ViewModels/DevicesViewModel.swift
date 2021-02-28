//
//  DevicesViewModel.swift
//  Blue-Falcon
//
//  Created by Andrew Reed on 24/08/2019.
//  Copyright © 2019 Andrew Reed. All rights reserved.
//

import Foundation
import CoreBluetooth
import Combine
import BlueFalcon

class DevicesViewModel: ObservableObject {

    @Published var devicesViewModels: [DevicesCellViewModel] = []
    @Published var status: BluetoothScanningState = .notScanning
    
    init() {
        scan()
    }

    func onAppear() {
        AppDelegate.instance.bluetoothService.detectedDeviceDelegates.append(self)
    }

    func onDisapear() {
        AppDelegate.instance.bluetoothService.removeDetectedDeviceDelegate(delegate: self)
    }
    
    private func scan() {
        do {
            try AppDelegate.instance.bluetoothService.scan()
            status = .scanning
        } catch {
            let error = error as NSError
            switch error.userInfo["KotlinException"] {
            case is BluetoothPermissionException:
                showError(message: Strings.Errors.Bluetooth.permission)
            case is BluetoothNotEnabledException:
                showError(message: Strings.Errors.Bluetooth.notEnabled)
            case is BluetoothUnsupportedException:
                showError(message: Strings.Errors.Bluetooth.unsupported)
            case is BluetoothResettingException:
                showError(message: Strings.Errors.Bluetooth.resetting)
            default:
                DispatchQueue.main.asyncAfter(deadline: .now() + 1) {
                    self.scan()
                }
            }
        }
    }
    
    private func showError(message: String) {
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
