package com.runforvictory.tuition.location

data class LocationReading(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float,
    val timestampMillis: Long,
    val isMock: Boolean = false
)

sealed interface LocationValidationResult {
    data class Valid(
        val reading: LocationReading,
        val distanceToCenterMeters: Double
    ) : LocationValidationResult

    data class Inaccurate(
        val reading: LocationReading,
        val accuracyMeters: Float,
        val maxAllowedMeters: Double
    ) : LocationValidationResult

    data class Stale(
        val ageSeconds: Long,
        val maxAgeSeconds: Long
    ) : LocationValidationResult

    data class MockDetected(
        val reading: LocationReading
    ) : LocationValidationResult

    data class OutOfBounds(
        val reading: LocationReading,
        val distanceMeters: Double,
        val permittedRadiusMeters: Int
    ) : LocationValidationResult
}

object LocationValidator {
    private const val MAX_STALENESS_SECONDS = 120L // 2 minutes

    fun validateReading(
        reading: LocationReading,
        centerLat: Double,
        centerLng: Double,
        permittedRadiusMeters: Int,
        maxAccuracyMeters: Double,
        currentTimeMillis: Long = System.currentTimeMillis()
    ): LocationValidationResult {
        // 1. Mock location rejection
        if (reading.isMock) {
            return LocationValidationResult.MockDetected(reading)
        }

        // 2. Staleness check
        val ageSeconds = (currentTimeMillis - reading.timestampMillis) / 1000L
        if (ageSeconds > MAX_STALENESS_SECONDS) {
            return LocationValidationResult.Stale(ageSeconds, MAX_STALENESS_SECONDS)
        }

        // 3. Accuracy check
        if (reading.accuracyMeters > maxAccuracyMeters) {
            return LocationValidationResult.Inaccurate(
                reading,
                reading.accuracyMeters,
                maxAccuracyMeters
            )
        }

        // 4. Geofence distance calculation
        val distance = HaversineUtil.calculateDistanceMeters(
            reading.latitude,
            reading.longitude,
            centerLat,
            centerLng
        )

        if (distance > permittedRadiusMeters) {
            return LocationValidationResult.OutOfBounds(
                reading,
                distance,
                permittedRadiusMeters
            )
        }

        return LocationValidationResult.Valid(reading, distance)
    }
}
