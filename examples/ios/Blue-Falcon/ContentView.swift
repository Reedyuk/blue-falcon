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
import Combine

struct ContentView : View {

    @ObservedObject var delegate = BluetoothDelegate()

    var body: some View {
        NavigationView {
            List(delegate.devices) { device in
                Text(device.identifier.uuidString)
            }
            .navigationBarTitle(Text("Blue Falcon Devices"))
        }.onAppear {
                DispatchQueue.main.asyncAfter(deadline: .now() + 2.0) {
                    AppDelegate.instance.blueFalcon.scan()
                }
        }
    }

    class BluetoothDelegate: BlueFalconDelegate, ObservableObject {

        @Published var devices: [CBPeripheral] = []

        init() {
            AppDelegate.instance.blueFalcon.delegates.add(self)
        }

        func didDiscoverDevice(bluetoothPeripheral: CBPeripheral) {
            guard !devices.contains(bluetoothPeripheral),
                let name = bluetoothPeripheral.name else { return }
            print("In-app did discover device. \(name)")
            devices.append(bluetoothPeripheral)
        }

        func didConnect(bluetoothPeripheral: CBPeripheral) {
            //update view to show connected status
        }

        func didDisconnect(bluetoothPeripheral: CBPeripheral) {
            //update view to show disconnected
        }

    }

}

extension CBPeripheral: Identifiable {
}

#if DEBUG
struct ContentView_Previews : PreviewProvider {
    static var previews: some View {
        ContentView()
    }
}
#endif
