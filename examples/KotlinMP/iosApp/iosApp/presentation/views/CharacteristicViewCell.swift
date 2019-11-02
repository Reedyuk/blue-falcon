//
//  CharacteristicViewCell.swift
//  iosApp
//
//  Created by Andrew Reed on 01/11/2019.
//

import UIKit
import app

class CharacteristicViewCell: UITableViewCell {
    @IBOutlet weak var nameLabel: UILabel!
    @IBOutlet weak var readValueLabel: UILabel!
    @IBOutlet weak var notifyButton: UIButton!
    
    var viewModel: DeviceCharacteristicViewModel!

    func setup(viewModel: DeviceCharacteristicViewModel) {
        viewModel.output = self
        self.viewModel = viewModel
        redraw()
    }

    private func redraw() {
        nameLabel.text = viewModel.displayName
        readValueLabel.text = viewModel.value
        notifyButton.titleLabel?.text = "Notify \(viewModel.notify.description)"
    }

    @IBAction func readPressed() {
        viewModel.readCharacteristicTapped()
    }

    @IBAction func notifyPressed() {
        viewModel.notifyCharacteristicTapped()
    }

    @IBAction func writePressed() {
        let alert = UIAlertController(
            title: "Characteristic Write",
            message: "Please enter a value to write to the characteristic",
            preferredStyle: .alert
        )
        alert.addTextField { textfield in
            textfield.placeholder = "Enter value"
        }
        alert.addAction(UIAlertAction(title: "OK", style: .default, handler: { _ in
            guard let input = alert.textFields?.first?.text else { return }
            self.viewModel.writeCharactersticTapped(value: input)
        }))
        alert.addAction(UIAlertAction(title: "Cancel", style: .cancel))
        AppDelegate.instance.window?.rootViewController?.present(alert, animated: true)
    }

}

extension CharacteristicViewCell: DeviceCharacteristicViewModelOutput {
    func refresh() {
        redraw()
    }
}
