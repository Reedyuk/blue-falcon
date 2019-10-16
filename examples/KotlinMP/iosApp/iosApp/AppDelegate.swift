import UIKit
import app

@UIApplicationMain
class AppDelegate: UIResponder, UIApplicationDelegate {

    lazy var bluetoothService = BluetoothService(
        blueFalcon: LibraryBlueFalcon(context: window!.rootViewController!.view, serviceUUID: nil)
    )
    var window: UIWindow?

    func application(_ application: UIApplication, didFinishLaunchingWithOptions launchOptions: [UIApplicationLaunchOptionsKey: Any]?) -> Bool {
        return true
    }

    func applicationWillResignActive(_ application: UIApplication) {}

    func applicationDidEnterBackground(_ application: UIApplication) {}

    func applicationWillEnterForeground(_ application: UIApplication) {}

    func applicationDidBecomeActive(_ application: UIApplication) {}

    func applicationWillTerminate(_ application: UIApplication) {}
}
