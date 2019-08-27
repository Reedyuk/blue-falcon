//
//  DevicesView.swift
//  Blue-Falcon
//
//  Created by Andrew Reed on 16/08/2019.
//  Copyright © 2019 Andrew Reed. All rights reserved.
//

import SwiftUI
import library
import Combine

struct DevicesView : View {

    @ObservedObject var viewModel = DevicesViewModel()

    var body: some View {
        NavigationView {
            List(viewModel.devicesViewModels) { deviceViewModel in
                NavigationLink(
                    destination: DeviceView(
                        deviceViewModel: DeviceViewModel(
                            device: deviceViewModel.device
                        )
                    )
                ) {
                    Text(deviceViewModel.name)
                }
            }
            .navigationBarTitle(Text("Blue Falcon Devices"))
        }.onAppear {
            self.viewModel.addDelegate()
            //current hack due to waiting for powered on state, maybe throw an exception?
            DispatchQueue.main.asyncAfter(deadline: .now() + 1.0) {
                AppDelegate.instance.blueFalcon.scan()
                self.viewModel.disconnectAllDevices()
            }
        }.onDisappear {
            self.viewModel.removeDelegate()
        }
    }

}

#if DEBUG
struct DevicesView_Previews : PreviewProvider {
    static var previews: some View {
        DevicesView()
    }
}
#endif
