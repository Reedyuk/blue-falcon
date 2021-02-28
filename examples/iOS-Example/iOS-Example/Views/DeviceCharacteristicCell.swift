//
//  DeviceCharacteristicCell.swift
//  Blue-Falcon
//
//  Created by Andrew Reed on 28/08/2019.
//  Copyright © 2019 Andrew Reed. All rights reserved.
//

import Foundation
import SwiftUI

struct DeviceCharacteristicCell: View {

    @ObservedObject private var viewModel: DeviceCharacteristicCellViewModel

    init(viewModel: DeviceCharacteristicCellViewModel) {
        self.viewModel = viewModel
    }

    var body: some View {
        VStack(alignment: HorizontalAlignment.leading, spacing: 10) {
            VStack(alignment: HorizontalAlignment.leading, spacing: 10) {
                HStack {
                    Text("ID: ").bold()
                    Text(viewModel.name)
                }
                if viewModel.reading {
                    HStack {
                        Text("Reading...")
                        ActivityIndicator(isAnimating: .constant(true), style: .medium)
                    }
                }
                if viewModel.characterisicValue != nil {
                    HStack {
                        Text("Value: ").bold()
                        Text(viewModel.characterisicValue!)
                    }
                }
            }
            HStack(alignment: .center) {
                Spacer()
                Group {
                    Button(action: {}) {
                        Text("Read").padding(5)
                    }.onTapGesture {
                        self.viewModel.readCharacteristicTapped(self.viewModel.characteristic)
                    }
                    .foregroundColor(Color.white)
                    .padding()
                    .background(Color.blue)
                    .cornerRadius(5)
                }
                Group {
                    Button(action: {}) {
                        Text("Notify \(viewModel.notify.description)").padding(5)
                    }.onTapGesture {
                        self.viewModel.notifyCharacteristicTapped(self.viewModel.characteristic)
                    }
                    .foregroundColor(Color.white)
                    .padding()
                    .background(Color.purple)
                    .cornerRadius(5)
                }
                Group {
                    Button(action: {}) {
                        Text("Write").padding(5)
                    }.onTapGesture {
                        self.viewModel.writeCharacteristicTapped(self.viewModel.characteristic)
                    }
                    .foregroundColor(Color.white)
                    .padding()
                    .background(Color.yellow)
                    .cornerRadius(5)
                }
                Spacer()
            }.padding()
            if !self.viewModel.descriptors.isEmpty {
                VStack(alignment: HorizontalAlignment.leading, spacing: 10) {
                    HStack {
                        Text("Descriptors: ").bold()
                        ForEach(self.viewModel.descriptorData, id: \.id) { descriptor in
                            Text("\(descriptor.id) \(descriptor.data)")
                        }
                    }
                }.padding()
            }
        }
        .onAppear {
            self.viewModel.onAppear()
        }
        .onDisappear {
            self.viewModel.onDisapear()
        }
    }
}
