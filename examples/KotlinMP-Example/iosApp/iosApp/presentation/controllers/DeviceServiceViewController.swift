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
    private lazy var deviceViewModels: [DeviceCharacteristicViewModel] = viewModel.deviceCharacteristicViewModels(output: nil)
    var bluetoothDevice: LibraryBluetoothPeripheral!
    var service: LibraryBluetoothService!

    override func tableView(_ tableView: UITableView, numberOfRowsInSection section: Int) -> Int {
        return deviceViewModels.count
    }

    override func tableView(_ tableView: UITableView, cellForRowAt indexPath: IndexPath) -> UITableViewCell {
        let cell = tableView.dequeueReusableCell(withIdentifier: "CharacteristicCell", for: indexPath) as! CharacteristicViewCell
        cell.setup(viewModel: deviceViewModels[indexPath.row])
        return cell
    }

}
