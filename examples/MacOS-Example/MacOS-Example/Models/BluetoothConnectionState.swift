//
//  BluetoothConnectionState.swift
//  Blue-Falcon
//
//  Created by Andrew Reed on 01/09/2019.
//  Copyright © 2019 Andrew Reed. All rights reserved.
//

import CoreBluetooth

extension CBPeripheralState {

    func displayText() -> String {
        switch self {
            case .connected: return "Connected"
            case .connecting: return "Connecting..."
            case .disconnected: return "Disconnected"
            case .disconnecting: return "Disconnecting"
            @unknown default: fatalError("Unexpected blueooth state")
        }
    }

}
