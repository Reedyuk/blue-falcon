import SwiftUI
import shared
import KMMViewModelSwiftUI
import KMPNativeCoroutinesAsync

struct DevicesView: View {
    
    @ObservedViewModel var viewModel: DevicesViewModel
    
	var body: some View {
        VStack {
            Text("Devices")
            Button {
                viewModel.scan()
            } label: {
                Text("Scan")
            }
            List {
                ForEach(viewModel.devices, id: \.name) { device in
                    Text(device.name ?? device.uuid)
                }
            }
            Spacer()
        }
	}
}
