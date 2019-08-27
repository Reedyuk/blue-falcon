//
//  DeviceServiceView.swift
//  Blue-Falcon
//
//  Created by Andrew Reed on 24/08/2019.
//  Copyright © 2019 Andrew Reed. All rights reserved.
//

import SwiftUI
import library
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
                .onDisappear {
                self.deviceServiceViewModel.removeDelegate()
            }
            List(deviceServiceViewModel.deviceCharacteristicCellViewModels) { viewModel in
                HStack {
                    VStack(alignment: HorizontalAlignment.leading, spacing: 10) {
                        Text(viewModel.id.uuidString)
                        if viewModel.characteristic.value != nil {
                            HStack {
                                Text("Value: ")
                                    .bold()
                                Text(String(decoding: viewModel.characteristic.value ?? Data(), as: UTF8.self))
                            }
                        }
                    }
                    Spacer()
                    Text("Read")
                        .onTapGesture {
                            self.deviceServiceViewModel.readCharacteristicTapped(viewModel.characteristic)
                        }
                        .padding()
                        .background(Color.blue)
                        .cornerRadius(5)
                    Text("Write")
                        .onTapGesture {
                            self.deviceServiceViewModel.writeCharacteristicTapped(viewModel.characteristic)
                        }
                        .padding()
                        .background(Color.yellow)
                        .cornerRadius(5)
                }
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
