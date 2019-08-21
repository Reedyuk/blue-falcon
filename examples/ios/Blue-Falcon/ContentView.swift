//
//  ContentView.swift
//  Blue-Falcon
//
//  Created by Andrew Reed on 16/08/2019.
//  Copyright © 2019 Andrew Reed. All rights reserved.
//

import SwiftUI
import library

struct ContentView : View {
    var body: some View {
        VStack {
            Text("Hello Blue Falcon")
                .padding(10)
            Text("Bluetooth Device Status")
                .padding(10)
                .onAppear {
                let blueFalcon = BlueFalcon()
                blueFalcon.scan()
            }
        }
    }
}

#if DEBUG
struct ContentView_Previews : PreviewProvider {
    static var previews: some View {
        ContentView()
    }
}
#endif
