//
//  DeviceServiceViewController.swift
//  iosApp
//
//  Created by Andrew Reed on 31/10/2019.
//

import UIKit
import app

class DeviceServiceViewController: UITableViewController {

    var viewModel: DeviceCharacteristicsViewModel!
    private var deviceViewModels: [DeviceCharacteristicViewModel] = []
    var bluetoothDevice: LibraryBluetoothPeripheral!
    var service: LibraryBluetoothService!

    override func viewDidLoad() {
        super.viewDidLoad()
        deviceViewModels = viewModel.deviceCharacteristicViewModels()
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
        //let service = viewModel.services[indexPath.row]

    }

}

extension DeviceServiceViewController: DeviceCharacteristicsViewModelOutput {
    func refresh() {
        deviceViewModels = viewModel.deviceCharacteristicViewModels()
        tableView.reloadData()
    }
}
