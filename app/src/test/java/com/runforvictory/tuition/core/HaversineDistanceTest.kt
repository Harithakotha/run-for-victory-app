package com.runforvictory.tuition.core

import com.runforvictory.tuition.location.HaversineUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HaversineDistanceTest {

    // Center coordinates for Run for Victory Tuition Centre (Hyderabad default)
    private val centerLat = 17.385044
    private val centerLng = 78.486671
    private val permittedRadius = 150 // meters

    @Test
    fun distanceToSelf_isZero() {
        val distance = HaversineUtil.calculateDistanceMeters(
            centerLat, centerLng,
            centerLat, centerLng
        )
        assertEquals(0.0, distance, 0.001)
    }

    @Test
    fun locationInsideGeofence_isWithinGeofenceReturnsTrue() {
        // Point approximately 40 meters north
        val nearbyLat = 17.385400
        val nearbyLng = 78.486671

        val distance = HaversineUtil.calculateDistanceMeters(
            centerLat, centerLng,
            nearbyLat, nearbyLng
        )

        assertTrue("Expected distance < 150m, was $distance", distance < permittedRadius)
        assertTrue(HaversineUtil.isWithinGeofence(nearbyLat, nearbyLng, centerLat, centerLng, permittedRadius))
    }

    @Test
    fun locationOutsideGeofence_isWithinGeofenceReturnsFalse() {
        // Point approximately 800 meters away
        val farLat = 17.392000
        val farLng = 78.486671

        val distance = HaversineUtil.calculateDistanceMeters(
            centerLat, centerLng,
            farLat, farLng
        )

        assertTrue("Expected distance > 150m, was $distance", distance > permittedRadius)
        assertFalse(HaversineUtil.isWithinGeofence(farLat, farLng, centerLat, centerLng, permittedRadius))
    }
}
