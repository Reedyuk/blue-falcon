//
//  DeviceView.swift
//  Blue-Falcon
//
//  Created by Andrew Reed on 24/08/2019.
//  Copyright © 2019 Andrew Reed. All rights reserved.
//

import SwiftUI
import BlueFalcon
import Combine
import CoreBluetooth

struct DeviceView : View {

    @ObservedObject var deviceViewModel: DeviceViewModel

    var body: some View {
        VStack(alignment: HorizontalAlignment.leading, spacing: 10) {
            HStack {
                Text(deviceViewModel.state.displayText())
                if deviceViewModel.state == CBPeripheralState.connecting {
                    ActivityIndicator(isAnimating: .constant(true), style: .medium)
                }
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
        .navigationBarTitle(deviceViewModel.title)
        .onAppear {
            self.deviceViewModel.onAppear()
        }
        .onDisappear {
            self.deviceViewModel.onDisapear()
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
