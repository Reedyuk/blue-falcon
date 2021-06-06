//
//  DevicesView.swift
//  Blue-Falcon
//
//  Created by Andrew Reed on 16/08/2019.
//  Copyright © 2019 Andrew Reed. All rights reserved.
//

import SwiftUI
import BlueFalcon
import Combine

struct DevicesView : View {

    @ObservedObject var viewModel = DevicesViewModel()

    var body: some View {
        NavigationView {
            VStack(alignment: .leading) {
                HStack {
                    Text("Bluetooth Status:")
                    Text(viewModel.status == .scanning ? "Scanning" : "Not Scanning")
                    if viewModel.status == .scanning {
                        ActivityIndicator(isAnimating: .constant(true), style: .medium)
                    }
                }.padding()
                List(viewModel.devicesViewModels) { deviceViewModel in
                    NavigationLink(
                        destination: DeviceView(
                            deviceViewModel: DeviceViewModel(
                                device: deviceViewModel.device
                            )
                        )
                    ) {
                        DevicesViewCell(
                            name: deviceViewModel.name,
                            deviceId: deviceViewModel.id
                        )
                    }
                }
                .navigationBarTitle(Text("Blue Falcon Devices"))
            }
            .onAppear {
                self.viewModel.onAppear()
                self.scan()
            }
            .onDisappear {
                self.viewModel.onDisapear()
            }
        }
        .colorScheme(.dark)
    }

    //the old way would be to perform this in the uiview controller.
    private func scan() {
        do {
            try viewModel.scan()
        } catch {
            let error = error as NSError
            switch error.userInfo["KotlinException"] {
            case is BluetoothPermissionException:
                showError(message: Strings.Errors.Bluetooth.permission)
            case is BluetoothNotEnabledException:
                showError(message: Strings.Errors.Bluetooth.notEnabled)
            case is BluetoothUnsupportedException:
                showError(message: Strings.Errors.Bluetooth.unsupported)
            case is BluetoothResettingException:
                showError(message: Strings.Errors.Bluetooth.resetting)
            default:
                DispatchQueue.main.asyncAfter(deadline: .now() + 1) {
                    self.scan()
                }
            }
        }
    }

    private func showError(message: String) {
        let alert = UIAlertController(
            title: "Bluetooth Error",
            message: message,
            preferredStyle: .alert
        )
        alert.addAction(UIAlertAction(title: "OK", style: .default))
        SceneDelegate.instance.window?.rootViewController?.present(alert, animated: true)
    }

}

#if DEBUG
struct DevicesView_Previews : PreviewProvider {
    static var previews: some View {
        DevicesView()
    }
}
#endif
