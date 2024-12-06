import SwiftUI
import shared

@main
struct iOSApp: App {
    let blueFalconApp = BlueFalconApplication(context: UIApplication.shared)
	var body: some Scene {
		WindowGroup {
            DevicesView(viewModel: blueFalconApp.createDevicesViewModel())
		}
	}
}
