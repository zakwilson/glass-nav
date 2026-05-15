package dev.glass.phone.routing

/**
 * Travel mode for routing. Each value maps to a BRouter profile plus the matching `v` (vehicle)
 * hint expected by the BRouter AIDL.
 *
 * The named profiles must be present in the user's BRouter installation. `trekking`, `car-fast`,
 * and `hiking-mountain` ship with stock BRouter; if a user has stripped their profile set, routing
 * for that mode will fail with a clear BRouter error.
 */
enum class NavigationMode(val profile: String, val vehicle: String) {
    CYCLING(profile = "trekking", vehicle = "bicycle"),
    WALKING(profile = "hiking-mountain", vehicle = "foot"),
    DRIVING(profile = "car-fast", vehicle = "motorcar"),
}
