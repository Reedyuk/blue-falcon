package com.example.bluefalconcomposemultiplatform.core.presentation

/**
 * `true` on platforms where a bottom tab bar is the conventional way to switch between top-level
 * destinations (Android, iOS); `false` on platforms that keep the existing top segmented control
 * (desktop JVM, native macOS). See ADR 0016.
 */
expect val useBottomNavigation: Boolean
