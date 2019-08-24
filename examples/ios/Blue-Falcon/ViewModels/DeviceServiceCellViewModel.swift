//
//  DeviceServiceCellViewModel.swift
//  Blue-Falcon
//
//  Created by Andrew Reed on 24/08/2019.
//  Copyright © 2019 Andrew Reed. All rights reserved.
//

import Foundation
import CoreBluetooth

struct DeviceServiceCellViewModel: Identifiable {
    var id: CBUUID
    let service: CBService
}
