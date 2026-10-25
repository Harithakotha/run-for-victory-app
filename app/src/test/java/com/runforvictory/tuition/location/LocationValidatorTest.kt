package com.runforvictory.tuition.location

import org.junit.Assert.assertTrue
import org.junit.Test

class LocationValidatorTest {

    private val centerLat = 17.385044
    private val centerLng = 78.486671
    private val permittedRadiusMeters = 150
    private val maxAccuracyMeters = 60.0

    @Test
    fun validReadingInsideGeofence_returnsValid() {
        val reading = LocationReading(
            latitude = 17.385100,
            longitude = 78.486700,
            accuracyMeters = 15.0f,
            timestampMillis = System.currentTimeMillis()
        )

        val result = LocationValidator.validateReading(
            reading = reading,
            centerLat = centerLat,
            centerLng = centerLng,
            permittedRadiusMeters = permittedRadiusMeters,
            maxAccuracyMeters = maxAccuracyMeters
        )

        assertTrue(result is LocationValidationResult.Valid)
    }

    @Test
    fun mockLocationReading_returnsMockDetected() {
        val reading = LocationReading(
            latitude = centerLat,
            longitude = centerLng,
            accuracyMeters = 10.0f,
            timestampMillis = System.currentTimeMillis(),
            isMock = true
        )

        val result = LocationValidator.validateReading(
            reading = reading,
            centerLat = centerLat,
            centerLng = centerLng,
            permittedRadiusMeters = permittedRadiusMeters,
            maxAccuracyMeters = maxAccuracyMeters
        )

        assertTrue(result is LocationValidationResult.MockDetected)
    }

    @Test
    fun inaccurateReading_returnsInaccurate() {
        val reading = LocationReading(
            latitude = centerLat,
            longitude = centerLng,
            accuracyMeters = 120.0f, // > 60.0m max allowed
            timestampMillis = System.currentTimeMillis()
        )

        val result = LocationValidator.validateReading(
            reading = reading,
            centerLat = centerLat,
            centerLng = centerLng,
            permittedRadiusMeters = permittedRadiusMeters,
            maxAccuracyMeters = maxAccuracyMeters
        )

        assertTrue(result is LocationValidationResult.Inaccurate)
    }

    @Test
    fun staleReading_returnsStale() {
        val fiveMinutesAgo = System.currentTimeMillis() - (300 * 1000L)
        val reading = LocationReading(
            latitude = centerLat,
            longitude = centerLng,
            accuracyMeters = 15.0f,
            timestampMillis = fiveMinutesAgo
        )

        val result = LocationValidator.validateReading(
            reading = reading,
            centerLat = centerLat,
            centerLng = centerLng,
            permittedRadiusMeters = permittedRadiusMeters,
            maxAccuracyMeters = maxAccuracyMeters
        )

        assertTrue(result is LocationValidationResult.Stale)
    }

    @Test
    fun outOfBoundsReading_returnsOutOfBounds() {
        val reading = LocationReading(
            latitude = 17.395000, // ~1.1km away
            longitude = 78.486671,
            accuracyMeters = 15.0f,
            timestampMillis = System.currentTimeMillis()
        )

        val result = LocationValidator.validateReading(
            reading = reading,
            centerLat = centerLat,
            centerLng = centerLng,
            permittedRadiusMeters = permittedRadiusMeters,
            maxAccuracyMeters = maxAccuracyMeters
        )

        assertTrue(result is LocationValidationResult.OutOfBounds)
    }
}
