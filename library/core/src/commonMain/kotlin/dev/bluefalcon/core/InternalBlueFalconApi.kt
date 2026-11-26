package dev.bluefalcon.core

/**
 * Marks declarations that are public only so that other Blue Falcon modules (engines,
 * `blue-falcon-peripheral`) can share them. They are not part of the supported public API and can
 * change or disappear in any release without a deprecation cycle.
 */
@RequiresOptIn(
    level = RequiresOptIn.Level.ERROR,
    message = "This is an internal Blue Falcon API shared between Blue Falcon modules. Do not use it from application code.",
)
@Retention(AnnotationRetention.BINARY)
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.CONSTRUCTOR,
    AnnotationTarget.TYPEALIAS,
)
annotation class InternalBlueFalconApi
