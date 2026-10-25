package com.runforvictory.tuition.location

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

object HaversineUtil {
    private const val EARTH_RADIUS_METERS = 6371000.0

    /**
     * Calculates the great-circle distance between two geographic coordinates
     * using the Haversine formula, returning the distance in meters.
     */
    fun calculateDistanceMeters(
        lat1: Double,
        lon1: Double,
        lat2: Double,
        lon2: Double
    ): Double {
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val deltaPhi = Math.toRadians(lat2 - lat1)
        val deltaLambda = Math.toRadians(lon2 - lon1)

        val sinDeltaPhi = sin(deltaPhi / 2.0)
        val sinDeltaLambda = sin(deltaLambda / 2.0)

        val a = sinDeltaPhi * sinDeltaPhi +
                cos(phi1) * cos(phi2) * sinDeltaLambda * sinDeltaLambda
        val c = 2.0 * atan2(sqrt(a), sqrt(1.0 - a))

        return EARTH_RADIUS_METERS * c
    }

    /**
     * Checks if coordinates fall within permitted geofence radius.
     */
    fun isWithinGeofence(
        currentLat: Double,
        currentLng: Double,
        centerLat: Double,
        centerLng: Double,
        radiusMeters: Int
    ): Boolean {
        val distance = calculateDistanceMeters(currentLat, currentLng, centerLat, centerLng)
        return distance <= radiusMeters
    }
}
