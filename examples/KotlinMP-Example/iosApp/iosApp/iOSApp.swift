import SwiftUI
import shared

@main
struct iOSApp: App {
    let blueFalconApp = BlueFalconApplication(context: UIView())
	var body: some Scene {
		WindowGroup {
            DevicesView(viewModel: blueFalconApp.createDevicesViewModel())
		}
	}
}
