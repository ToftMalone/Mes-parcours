package com.example.data.model

/**
 * State of the GPS signal as tracked by [com.example.data.repository.TrackRepository].
 *
 * This used to be a French string compared character for character (“Signal trouvé”…) in
 * six places: translating the interface would have silently broken the logic. The
 * label to display is chosen on screen; here we only hold the state.
 */
enum class GpsStatus {
    /** No position received recently: searching, or signal lost. */
    SEARCHING,
    FOUND,
    /** No location provider active on the device. */
    DISABLED,
    /** Positions invented by the simulation (debug only). */
    SIMULATION
}
