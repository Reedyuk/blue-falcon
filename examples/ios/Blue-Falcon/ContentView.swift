//
//  ContentView.swift
//  Blue-Falcon
//
//  Created by Andrew Reed on 16/08/2019.
//  Copyright © 2019 Andrew Reed. All rights reserved.
//

import SwiftUI
import library
import CoreBluetooth

struct ContentView : View {
    let blueFalcon = BlueFalcon()

    var body: some View {
        VStack {
            Text("Hello Blue Falcon")
            Text("Bluetooth Device Status")
                .onAppear {
                    self.blueFalcon.delegates.add(BluetoothDelegate())
                    DispatchQueue.main.asyncAfter(deadline: .now() + 2.0) {
                        self.blueFalcon.scan()
                    }
            }
        }
    }
}

class BluetoothDelegate: BlueFalconDelegate {

    func didDiscoverDevice(bluetoothPeripheral: CBPeripheral) {
        print("In-app did discover device. \(bluetoothPeripheral.name)")
    }

    func didConnect(bluetoothPeripheral: CBPeripheral) {
    }

    func didDisconnect(bluetoothPeripheral: CBPeripheral) {
    }

}

#if DEBUG
struct ContentView_Previews : PreviewProvider {
    static var previews: some View {
        ContentView()
    }
}
#endif
