//
//  DevicesViewCell.swift
//  Blue-Falcon
//
//  Created by Andrew Reed on 31/08/2019.
//  Copyright © 2019 Andrew Reed. All rights reserved.
//

import SwiftUI
import BlueFalcon
import Combine

struct DevicesViewCell : View {

    let name: String
    let deviceId: String

    var body: some View {
        VStack(alignment: .leading) {
            if !name.isEmpty {
                HStack {
                    Text("Name:")
                        .bold()
                    Text(name)
                        .italic()
                }.padding(EdgeInsets(top: 5, leading: 0, bottom: 0, trailing: 0))
            }
            Text(deviceId)
        }
    }
}
