//
//  HostingController.swift
//  Blue-Falcon
//
//  Created by Andrew Reed on 31/08/2019.
//  Copyright © 2019 Andrew Reed. All rights reserved.
//

import Foundation
import UIKit
import SwiftUI

class HostingController: UIHostingController<DevicesView> {
    override var preferredStatusBarStyle: UIStatusBarStyle {
        return .lightContent
    }
}
