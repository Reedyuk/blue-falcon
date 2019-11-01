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
        readValueLabel.text = viewModel.value()
        notifyButton.titleLabel?.text = "Notify \(viewModel.notify.description)"
    }

    @IBAction func readPressed() {
        viewModel.readCharacteristicTapped()
    }

    @IBAction func notifyPressed() {
        viewModel.notifyCharacteristicTapped()
    }

    @IBAction func writePressed() {
        viewModel.writeCharactersticTapped()
    }

}

extension CharacteristicViewCell: DeviceCharacteristicViewModelOutput {
    func refresh() {
        redraw()
    }
}
