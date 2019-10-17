//
//  DevicesViewController.swift
//  iosApp
//
//  Created by Andrew Reed on 15/10/2019.
//

import UIKit
import app

class DevicesViewController: UIViewController {

    private var viewModel: DevicesViewModel?
    @IBOutlet weak var label: UILabel!

    override func viewDidLoad() {
        super.viewDidLoad()
        viewModel = DevicesViewModel(output: self)
    }

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        scan()
    }

    //this will get moved to view model
    private func scan() {
        do {
            try AppDelegate.instance.bluetoothService.scan()
        } catch {
            let error = error as NSError
            let errorMessage = error.userInfo[NSLocalizedDescriptionKey]
            showError(message: errorMessage.debugDescription)
        }
    }

    private func showError(message: String) {
        let alert = UIAlertController(
            title: "Bluetooth Error",
            message: message,
            preferredStyle: .alert
        )
        alert.addAction(UIAlertAction(title: "OK", style: .default))
        present(alert, animated: true)
    }
}

extension DevicesViewController: DevicesViewModelOutput {

    //called when we detect devices.
    func refresh() {
        //refresh tableview.
    }

}
