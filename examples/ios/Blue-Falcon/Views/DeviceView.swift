//
//  DeviceView.swift
//  Blue-Falcon
//
//  Created by Andrew Reed on 24/08/2019.
//  Copyright © 2019 Andrew Reed. All rights reserved.
//

import SwiftUI
import library
import Combine
import CoreBluetooth

struct DeviceView : View {

    @ObservedObject var deviceViewModel: DeviceViewModel

    var body: some View {
        VStack {
            Text(deviceViewModel.isConnected ? "Connected" : "Connecting...")
                .navigationBarTitle(Text(deviceViewModel.device.name!))
                .onAppear {
                    self.deviceViewModel.connect()
            }
        }
    }

}

#if DEBUG
/*struct DeviceView_Previews : PreviewProvider {
    static var previews: some View {
        //DeviceView()
    }
}*/
#endif
