package com.runforvictory.tuition.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

sealed interface LocationFetchResult {
    data class Success(val reading: LocationReading) : LocationFetchResult
    data object PermissionDenied : LocationFetchResult
    data object GpsDisabled : LocationFetchResult
    data object Timeout : LocationFetchResult
    data class Error(val message: String) : LocationFetchResult
}

interface FreshLocationProvider {
    fun hasLocationPermission(): Boolean
    fun isGpsEnabled(): Boolean
    suspend fun getFreshLocation(timeoutMs: Long = 15_000L): LocationFetchResult
}

/**
 * Deterministic test/fake provider for unit tests and headless preview.
 */
class FakeFreshLocationProvider(
    var hasPermission: Boolean = true,
    var isGpsOn: Boolean = true,
    var simulatedResult: LocationFetchResult = LocationFetchResult.Success(
        LocationReading(
            latitude = 17.385044,
            longitude = 78.486671,
            accuracyMeters = 15f,
            timestampMillis = System.currentTimeMillis()
        )
    )
) : FreshLocationProvider {
    override fun hasLocationPermission(): Boolean = hasPermission
    override fun isGpsEnabled(): Boolean = isGpsOn
    override suspend fun getFreshLocation(timeoutMs: Long): LocationFetchResult {
        if (!hasPermission) return LocationFetchResult.PermissionDenied
        if (!isGpsOn) return LocationFetchResult.GpsDisabled
        return simulatedResult
    }
}

/**
 * Production implementation of FreshLocationProvider for Run for Victory.
 * Uses high accuracy foreground single-shot location with timeout.
 * Strictly adheres to foreground-only privacy principles: no background tracking.
 */
class FreshLocationManager(
    private val context: Context
) : FreshLocationProvider {

    private val locationManager: LocationManager? =
        context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    private val fusedLocationClient by lazy {
        LocationServices.getFusedLocationProviderClient(context)
    }

    override fun hasLocationPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    override fun isGpsEnabled(): Boolean {
        val lm = locationManager ?: return false
        return try {
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                    lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        } catch (e: Exception) {
            false
        }
    }

    override suspend fun getFreshLocation(timeoutMs: Long): LocationFetchResult {
        if (!hasLocationPermission()) {
            return LocationFetchResult.PermissionDenied
        }

        if (!isGpsEnabled()) {
            return LocationFetchResult.GpsDisabled
        }

        return try {
            val cancellationTokenSource = CancellationTokenSource()
            val location: Location? = withTimeoutOrNull(timeoutMs) {
                fusedLocationClient.getCurrentLocation(
                    Priority.PRIORITY_HIGH_ACCURACY,
                    cancellationTokenSource.token
                ).awaitTask()
            }

            if (location == null) {
                LocationFetchResult.Timeout
            } else {
                val isMock = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    location.isMock
                } else {
                    @Suppress("DEPRECATION")
                    location.isFromMockProvider
                }

                LocationFetchResult.Success(
                    LocationReading(
                        latitude = location.latitude,
                        longitude = location.longitude,
                        accuracyMeters = location.accuracy,
                        timestampMillis = location.time,
                        isMock = isMock
                    )
                )
            }
        } catch (e: SecurityException) {
            LocationFetchResult.PermissionDenied
        } catch (e: Exception) {
            LocationFetchResult.Error(e.message ?: "Failed to acquire GPS location.")
        }
    }

    private suspend fun <T> Task<T>.awaitTask(): T =
        suspendCancellableCoroutine { continuation ->
            addOnSuccessListener { result ->
                continuation.resume(result)
            }
            addOnFailureListener { exception ->
                continuation.resumeWith(Result.failure(exception))
            }
            addOnCanceledListener {
                continuation.cancel()
            }
        }
}
