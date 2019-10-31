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
                output: deviceViewController,
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
