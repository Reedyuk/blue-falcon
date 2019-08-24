//
//  DeviceCharacteristicCellViewModel.swift
//  Blue-Falcon
//
//  Created by Andrew Reed on 24/08/2019.
//  Copyright © 2019 Andrew Reed. All rights reserved.
//

import Foundation
import CoreBluetooth

struct DeviceCharacteristicCellViewModel: Identifiable {
    var id: CBUUID
    let characteristic: CBCharacteristic
}
