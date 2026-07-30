import SwiftUI

@main
struct iOSApp: App {
	@UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate

	var body: some Scene {
		WindowGroup {
			ContentView(appModule: appDelegate.appModule)
		}
	}
}
