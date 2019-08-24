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
        VStack(alignment: HorizontalAlignment.leading, spacing: 10) {
            Text(deviceViewModel.isConnected ? "Connected" : "Connecting...")
                .navigationBarTitle(Text(deviceViewModel.device.name!))
                .onAppear {
                    self.deviceViewModel.connect()
            }.padding(10)
            Text(deviceViewModel.deviceServiceCellViewModels.isEmpty ? "" : "Services")
                .bold()
                .padding(10)
            List(deviceViewModel.deviceServiceCellViewModels) { viewModel in
                NavigationLink(
                    destination: DeviceServiceView(
                        deviceServiceViewModel: DeviceServiceViewModel(
                            service: viewModel.service,
                            device: self.deviceViewModel.device
                        )
                    )
                ) {
                    Text(viewModel.id.uuidString)
                }
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
