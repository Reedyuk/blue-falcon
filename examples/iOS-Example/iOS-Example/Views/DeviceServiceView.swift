//
//  DeviceServiceView.swift
//  Blue-Falcon
//
//  Created by Andrew Reed on 24/08/2019.
//  Copyright © 2019 Andrew Reed. All rights reserved.
//

import SwiftUI
import BlueFalcon
import Combine
import CoreBluetooth

struct DeviceServiceView : View {

    @ObservedObject var deviceServiceViewModel: DeviceServiceViewModel

    var body: some View {
        VStack(alignment: .leading) {
            Text(deviceServiceViewModel.characteristics.isEmpty ? "" : "Characteristics")
                .bold()
                .padding(10)
                .navigationBarTitle(Text(deviceServiceViewModel.service.uuid.uuidString))
            List(deviceServiceViewModel.deviceCharacteristicCellViewModels) { viewModel in
                DeviceCharacteristicCell(viewModel: viewModel)
            }
        }
    }

}

#if DEBUG
/*struct DeviceServiceView_Previews : PreviewProvider {
    static var previews: some View {
        //DeviceServiceView()
    }
}*/
#endif
