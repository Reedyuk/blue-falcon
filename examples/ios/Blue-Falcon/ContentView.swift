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
        Text("Hello World").onAppear {
            let blueFalcon = BlueFalcon(bluetooth: PlatformBluetooth())
            print("Test")
            blueFalcon.scan()
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
