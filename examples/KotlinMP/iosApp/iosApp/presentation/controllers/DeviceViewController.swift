//
//  DeviceViewController.swift
//  iosApp
//
//  Created by Andrew Reed on 31/10/2019.
//

import UIKit
import app

class DeviceViewController: UITableViewController {

    var viewModel: DeviceViewModel!
    private var deviceViewModels: [DeviceServiceViewModel] = []
    var bluetoothDevice: LibraryBluetoothPeripheral!

    override func viewDidLoad() {
        super.viewDidLoad()
        tableView.tableHeaderView = createHeaderView()
    }

    private func createHeaderView() -> UIView {
        let deviceName = viewModel.displayName
        let rssi = viewModel.rssi
        let headerView = UIView(
            frame: CGRect(x: 0, y: 0, width: tableView.frame.width, height: 100)
        )
        let titleLabel = UILabel(
            frame: CGRect(x: 20, y: 10, width: tableView.frame.width-40, height: 44)
        )
        titleLabel.text = deviceName
        headerView.addSubview(titleLabel)
        let rssiLabel = UILabel(
            frame: CGRect(x: 20, y: 40, width: tableView.frame.width-40, height: 44)
        )
        if let rssiForced = rssi {
            rssiLabel.text = "rssi: \(rssiForced)"
        }
        headerView.addSubview(rssiLabel)
        return headerView
    }

    override func tableView(_ tableView: UITableView, numberOfRowsInSection section: Int) -> Int {
        return deviceViewModels.count
    }

    override func tableView(_ tableView: UITableView, cellForRowAt indexPath: IndexPath) -> UITableViewCell {
        let cell = tableView.dequeueReusableCell(withIdentifier: "DevicesCell", for: indexPath)
        cell.textLabel?.text = deviceViewModels[indexPath.row].displayName
        return cell
    }

    override func tableView(_ tableView: UITableView, didSelectRowAt indexPath: IndexPath) {
        let service = viewModel.services[indexPath.row]
        performSegue(withIdentifier: "DeviceServiceViewController", sender: service)
    }

    override func prepare(for segue: UIStoryboardSegue, sender: Any?) {
        if let deviceViewController = segue.destination as? DeviceServiceViewController,
            let service = sender as? LibraryBluetoothService {
            deviceViewController.service = service
            let viewModel = DeviceCharacteristicsViewModel(
                bluetoothService: AppDelegate.instance.bluetoothService,
                bluetoothDevice: bluetoothDevice,
                service: service,
                characteristics: service.characteristics
            )
            deviceViewController.viewModel = viewModel
        }
    }

}

extension DeviceViewController: DeviceViewModelOutput {
    func refresh() {
        deviceViewModels = viewModel.deviceServiceViewModels()
        tableView.reloadData()
    }
}
