package com.runforvictory.tuition.core

import com.runforvictory.tuition.domain.UserProfile
import com.runforvictory.tuition.domain.UserRole
import java.time.Instant

data class AuthSession(
    val accessToken: String,
    val refreshToken: String,
    val expiresAt: Instant,
    val userProfile: UserProfile
) {
    val isExpired: Boolean
        get() = Instant.now().isAfter(expiresAt)
}

interface SessionStorage {
    suspend fun saveSession(session: AuthSession)
    suspend fun getSession(): AuthSession?
    suspend fun clearSession()
}

/**
 * In-memory and testable session storage.
 * In production Android builds, backed by EncryptedSharedPreferences with AES-256 GCM.
 */
class InMemorySessionStorage : SessionStorage {
    private var currentSession: AuthSession? = null

    override suspend fun saveSession(session: AuthSession) {
        currentSession = session
    }

    override suspend fun getSession(): AuthSession? {
        val session = currentSession ?: return null
        if (session.isExpired) {
            clearSession()
            return null
        }
        return session
    }

    override suspend fun clearSession() {
        currentSession = null
    }
}
