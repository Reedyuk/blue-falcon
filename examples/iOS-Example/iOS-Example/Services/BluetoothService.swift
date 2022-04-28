//
//  BluetoothService.swift
//  Blue-Falcon
//
//  Created by Andrew Reed on 04/09/2019.
//  Copyright © 2019 Andrew Reed. All rights reserved.
//

import Foundation
import BlueFalcon
import CoreBluetooth
import UIKit

class BluetoothService {

    private let blueFalcon = BlueFalcon(context: UIView(), serviceUUID:  nil)
    private var devices: [BluetoothPeripheral] = []
    var detectedDeviceDelegates: [BluetoothServiceDetectedDeviceDelegate] = []
    var connectedDeviceDelegates: [(UUID, BluetoothServiceConnectedDeviceDelegate)] = []
    var characteristicDelegates: [(CBUUID, BluetoothServiceCharacteristicDelegate)] = []

    //create some sort of notification queue which waits x seconds before refreshing.

    init() {
        blueFalcon.delegates.add(self)
    }

    func scan() throws {
        try blueFalcon.scan()
    }

    func connect(bluetoothPeripheral: BluetoothPeripheral) {
        blueFalcon.connect(bluetoothPeripheral: bluetoothPeripheral, autoConnect: true)
    }

    func notifyCharacteristic(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristic: CBCharacteristic,
        notify: Bool
    ) {
        blueFalcon.notifyCharacteristic(
            bluetoothPeripheral: bluetoothPeripheral,
            bluetoothCharacteristic: BluetoothCharacteristic(characteristic: bluetoothCharacteristic),
            notify: notify
        )
    }

    func readCharacteristic(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristic: CBCharacteristic
    ) {
        blueFalcon.readCharacteristic(
            bluetoothPeripheral: bluetoothPeripheral,
            bluetoothCharacteristic: BluetoothCharacteristic(characteristic: bluetoothCharacteristic)
        )
    }

    func writeCharacteristic(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristic: CBCharacteristic,
        value: String
    ) {
        blueFalcon.writeCharacteristic(
            bluetoothPeripheral: bluetoothPeripheral,
            bluetoothCharacteristic: BluetoothCharacteristic(characteristic: bluetoothCharacteristic),
            value: value,
            writeType_: 1
        )
    }
    
    func readDescriptor(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristic: CBCharacteristic,
        bluetoothDescriptor: CBDescriptor
    ) {
        blueFalcon.readDescriptor(
            bluetoothPeripheral: bluetoothPeripheral,
            bluetoothCharacteristic: BluetoothCharacteristic(characteristic: bluetoothCharacteristic),
            bluetoothCharacteristicDescriptor: bluetoothDescriptor
        )
    }

    func removeDetectedDeviceDelegate(delegate: BluetoothServiceDetectedDeviceDelegate) {
        for (index, storedDelegate) in AppDelegate.instance.bluetoothService.detectedDeviceDelegates.enumerated() {
            if delegate === storedDelegate {
                AppDelegate.instance.bluetoothService.detectedDeviceDelegates.remove(at: index)
            }
        }
    }

    func removeConnectedDeviceDelegate(delegate: BluetoothServiceConnectedDeviceDelegate) {
        for (index, storedDelegate) in AppDelegate.instance.bluetoothService.connectedDeviceDelegates.enumerated() {
            if delegate === storedDelegate.1 {
                AppDelegate.instance.bluetoothService.connectedDeviceDelegates.remove(at: index)
            }
        }
    }

    func removeCharacteristicDelegate(delegate: BluetoothServiceCharacteristicDelegate) {
        for (index, storedDelegate) in AppDelegate.instance.bluetoothService.characteristicDelegates.enumerated() {
            if delegate === storedDelegate.1 {
                AppDelegate.instance.bluetoothService.characteristicDelegates.remove(at: index)
            }
        }
    }

}

extension BluetoothService: BlueFalconDelegate {
    func didReadDescriptor(bluetoothPeripheral: BluetoothPeripheral, bluetoothCharacteristicDescriptor: CBDescriptor) {
        print("BT Service didReadDescriptor -> \(bluetoothCharacteristicDescriptor.value ?? "")")
    }
    
    func didRssiUpdate(bluetoothPeripheral: BluetoothPeripheral) {
    }

    func didDiscoverDevice(bluetoothPeripheral: BluetoothPeripheral, advertisementData: [AdvertisementDataRetrievalKeys : Any]) {
        guard (devices.first {
            $0.bluetoothDevice.identifier == bluetoothPeripheral.bluetoothDevice.identifier
        } == nil) else { return }
        
        print("Device advertised data: \(advertisementData)")
        
        
        devices.append(bluetoothPeripheral)
        detectedDeviceDelegates.forEach { delegate in
            delegate.discoveredDevice(devices: devices)
        }
    }

    func didConnect(bluetoothPeripheral: BluetoothPeripheral) {
        bluetoothServiceConnectedDeviceDelegates(bluetoothPeripheralId: bluetoothPeripheral.bluetoothDevice.identifier)
        .forEach { bluetoothServiceConnectedDeviceDelegate in
            bluetoothServiceConnectedDeviceDelegate.connectedDevice()
        }
    }

    func didDisconnect(bluetoothPeripheral: BluetoothPeripheral) {

    }

    func didDiscoverServices(bluetoothPeripheral: BluetoothPeripheral) {
        bluetoothServiceConnectedDeviceDelegates(bluetoothPeripheralId: bluetoothPeripheral.bluetoothDevice.identifier)
        .forEach { bluetoothServiceConnectedDeviceDelegate in
            bluetoothServiceConnectedDeviceDelegate.discoveredServices()
        }
    }

    func didDiscoverCharacteristics(bluetoothPeripheral: BluetoothPeripheral) {
    }

    func didCharacteristcValueChanged(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristic: BluetoothCharacteristic
    ) {
        bluetoothServiceCharacteristicDelegates(bluetoothCharacteristicId: bluetoothCharacteristic.characteristic.uuid)
        .forEach { bluetoothServiceCharacteristicDelegate in
            bluetoothServiceCharacteristicDelegate.characteristcValueChanged()
        }
    }

    func didUpdateMTU(bluetoothPeripheral: BluetoothPeripheral) {}

    private func bluetoothServiceConnectedDeviceDelegates(bluetoothPeripheralId: UUID) -> [BluetoothServiceConnectedDeviceDelegate] {
        return connectedDeviceDelegates.compactMap { connectedDeviceDelegateTuple -> BluetoothServiceConnectedDeviceDelegate? in
            connectedDeviceDelegateTuple.0 == bluetoothPeripheralId ? connectedDeviceDelegateTuple.1 : nil
        }
    }

    private func bluetoothServiceCharacteristicDelegates(bluetoothCharacteristicId: CBUUID) -> [BluetoothServiceCharacteristicDelegate] {
        return characteristicDelegates.compactMap { characteristicDelegateTuple -> BluetoothServiceCharacteristicDelegate? in
            characteristicDelegateTuple.0 == bluetoothCharacteristicId ? characteristicDelegateTuple.1 : nil
        }
    }
    
    func didWriteCharacteristic(bluetoothPeripheral: BluetoothPeripheral, bluetoothCharacteristic: BluetoothCharacteristic, success: Bool) {
        print("BT Service didWriteCharacteristic -> success: \(success)")
    }
}


protocol BluetoothServiceDetectedDeviceDelegate: AnyObject {
    func discoveredDevice(devices: [BluetoothPeripheral])
}

protocol BluetoothServiceConnectedDeviceDelegate: AnyObject {
    func connectedDevice()
    func discoveredServices()
}

protocol BluetoothServiceCharacteristicDelegate: AnyObject {
    func characteristcValueChanged()
}
