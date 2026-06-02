package id.nearyou.app.location

/**
 * Thrown by a real `LocationProvider` actual when permission is granted but no device coordinate can
 * be acquired (GPS off / timeout / null fix). The `LocationProvider.current(): LatLng` signature is
 * non-nullable and unchanged (per the `mobile-nearby-timeline` delta), so "no fix" is signalled by an
 * exception rather than a sentinel coordinate (a fake coordinate would silently mislocate the user).
 *
 * The Nearby gate catches this off the granted fetch path and renders the **existing** retryable
 * error state (`signin_error_network` + retry) — introducing NO new `NearbyTimelineOutcome` member
 * (`openspec/specs/mobile-nearby-timeline` § "Granted but no coordinate obtainable maps to the
 * existing retryable error state"). The message MUST NOT carry the coordinate (there is none to
 * carry; never add one).
 */
class LocationUnavailableException(
    message: String = "No location fix available",
) : Exception(message)
