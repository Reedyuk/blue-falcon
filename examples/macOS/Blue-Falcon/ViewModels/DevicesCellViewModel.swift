//
//  DevicesCellViewModel.swift
//  Blue-Falcon
//
//  Created by Andrew Reed on 24/08/2019.
//  Copyright © 2019 Andrew Reed. All rights reserved.
//

import Foundation
import CoreBluetooth
import library

struct DevicesCellViewModel: Identifiable {
    var id: String
    let name: String
    let device: BluetoothPeripheral
}
