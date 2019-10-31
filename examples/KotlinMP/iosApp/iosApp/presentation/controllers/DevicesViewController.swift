//
//  DevicesViewController.swift
//  iosApp
//
//  Created by Andrew Reed on 15/10/2019.
//

import UIKit
import app

class DevicesViewController: UITableViewController {

    private var viewModel: DevicesViewModel!
    private var deviceViewModels: [DevicesItemViewModel] = []

    override func viewDidLoad() {
        super.viewDidLoad()
        viewModel = DevicesViewModel(
            output: self,
            bluetoothService: AppDelegate.instance.bluetoothService
        )
        deviceViewModels = viewModel.deviceViewModels()
        viewModel.scan()
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
        performSegue(
            withIdentifier: "deviceViewController",
            sender: viewModel.devices[indexPath.row]
        )
    }

    override func prepare(for segue: UIStoryboardSegue, sender: Any?) {
        if let deviceViewController = segue.destination as? DeviceViewController,
            let device = sender as? LibraryBluetoothPeripheral {
            deviceViewController.bluetoothDevice = device
            let viewModel = DeviceViewModel(
                output: deviceViewController,
                bluetoothDevice: device,
                bluetoothService: AppDelegate.instance.bluetoothService
            )
            deviceViewController.viewModel = viewModel
        }
    }

}

extension DevicesViewController: DevicesViewModelOutput {

    //called when we detect devices.
    func refresh() {
        deviceViewModels = viewModel.deviceViewModels()
        tableView.reloadData()
    }

    func requiresBluetoothPermission() {
        let alert = UIAlertController(
            title: "Bluetooth Error",
            message: Strings.Errors.Bluetooth.permission,
            preferredStyle: .alert
        )
        alert.addAction(UIAlertAction(title: "OK", style: .default))
        present(alert, animated: true)
    }

}
