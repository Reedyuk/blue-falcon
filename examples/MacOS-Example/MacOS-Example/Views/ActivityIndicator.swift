//
//  ActivityIndicator.swift
//  Blue-Falcon
//
//  Created by Andrew Reed on 01/09/2019.
//  Copyright © 2019 Andrew Reed. All rights reserved.
//
import SwiftUI
import Cocoa

struct ActivityIndicator: NSViewRepresentable {
    func makeNSView(context: Context) -> NSProgressIndicator {
        let indicator = NSProgressIndicator()
        indicator.style = .spinning
        return indicator
    }
    
    func updateNSView(_ nsView: NSProgressIndicator, context: Context) {
        isAnimating ? nsView.startAnimation(nil) : nsView.stopAnimation(nil)
    }
    
    typealias NSViewType = NSProgressIndicator

    @Binding var isAnimating: Bool
}
