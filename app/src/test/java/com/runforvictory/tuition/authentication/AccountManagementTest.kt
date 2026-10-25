package com.runforvictory.tuition.authentication

import com.runforvictory.tuition.core.AuthSession
import com.runforvictory.tuition.core.InMemorySessionStorage
import com.runforvictory.tuition.core.Resource
import com.runforvictory.tuition.core.di.DefaultAuthRepository
import com.runforvictory.tuition.domain.UserProfile
import com.runforvictory.tuition.domain.UserRole
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant

class AccountManagementTest {

    private lateinit var sessionStorage: InMemorySessionStorage
    private lateinit var authRepository: DefaultAuthRepository

    @Before
    fun setUp() {
        sessionStorage = InMemorySessionStorage()
        authRepository = DefaultAuthRepository(sessionStorage)
    }

    // 1. Session Expiry Test
    @Test
    fun sessionExpiry_clearsSessionAndReturnsNull() = runTest {
        // Arrange: Save a session that expired 1 hour ago
        val expiredSession = AuthSession(
            accessToken = "expired-token",
            refreshToken = "expired-refresh",
            expiresAt = Instant.now().minusSeconds(3600),
            userProfile = UserProfile(
                id = "user-1",
                role = UserRole.TUTOR,
                fullName = "Anand Rao",
                isActive = true
            )
        )
        sessionStorage.saveSession(expiredSession)

        // Act: Attempt to restore expired session
        val result = authRepository.restoreSession()

        // Assert: Expired session pruned, returns null
        assertTrue(result is Resource.Success)
        assertNull((result as Resource.Success).data)
        assertNull(sessionStorage.getSession())
    }

    // 2. Valid Session Restoration Test
    @Test
    fun sessionValid_restoresSessionSuccessfully() = runTest {
        // Arrange: Save a session valid for 24 hours
        val validSession = AuthSession(
            accessToken = "valid-token",
            refreshToken = "valid-refresh",
            expiresAt = Instant.now().plusSeconds(86400),
            userProfile = UserProfile(
                id = "11111111-1111-1111-1111-111111111111",
                role = UserRole.OWNER,
                fullName = "Ravi Kumar (Owner)",
                isActive = true
            )
        )
        sessionStorage.saveSession(validSession)

        // Act: Restore session
        val result = authRepository.restoreSession()

        // Assert
        assertTrue(result is Resource.Success)
        val restored = (result as Resource.Success).data
        assertNotNull(restored)
        assertEquals("11111111-1111-1111-1111-111111111111", restored?.id)
        assertEquals(UserRole.OWNER, restored?.role)
    }

    // 3. Invalid Credentials Test
    @Test
    fun invalidCredentials_rejectedWithErrorMessage() = runTest {
        val result = authRepository.signIn("owner@runforvictory.com", "wrongpassword")
        assertTrue(result is Resource.Error)
        assertEquals("Invalid email or password.", (result as Resource.Error).message)
    }

    // 4. Role Enforcement: Non-Owner cannot invite users
    @Test
    fun roleEnforcement_tutorCannotInviteUsers() = runTest {
        // Sign in as tutor
        authRepository.signIn("tutor.anand@runforvictory.com", "validPass123")

        // Act: Tutor attempts to invite
        val inviteResult = authRepository.inviteUser(
            email = "newtutor@example.com",
            role = UserRole.TUTOR
        )

        // Assert: Rejected
        assertTrue(inviteResult is Resource.Error)
        assertTrue((inviteResult as Resource.Error).message.contains("Access Denied"))
    }

    // 5. Invitation Misuse: Reused token is rejected
    @Test
    fun invitationMisuse_reusedTokenIsRejected() = runTest {
        // Sign in as owner
        authRepository.signIn("owner@runforvictory.com", "validPass123")

        // Owner generates invite
        val inviteResult = authRepository.inviteUser(
            email = "newtutor@example.com",
            role = UserRole.TUTOR
        )
        assertTrue(inviteResult is Resource.Success)
        val token = (inviteResult as Resource.Success).data

        // First acceptance succeeds
        val accept1 = authRepository.acceptInvitation(token, "Prakash Teacher", "9876543210")
        assertTrue(accept1 is Resource.Success)

        // Second acceptance with same token MUST be rejected
        val accept2 = authRepository.acceptInvitation(token, "Imposter Teacher", "9876543210")
        assertTrue(accept2 is Resource.Error)
        assertTrue((accept2 as Resource.Error).message.contains("already been used"))
    }

    // 6. Revoked / Deactivated Access: Deactivated user cannot sign in
    @Test
    fun revokedAccess_deactivatedUserCannotSignIn() = runTest {
        val result = authRepository.signIn("deactivated@example.com", "validPass123")
        assertTrue(result is Resource.Error)
        assertTrue((result as Resource.Error).message.contains("deactivated"))
    }

    // 7. Revoked Access: Active session terminated upon account deactivation
    @Test
    fun revokedAccess_activeSessionTerminatedUponDeactivation() = runTest {
        // Sign in as owner
        authRepository.signIn("owner@runforvictory.com", "validPass123")

        // Deactivate tutor Anand
        val deactivateResult = authRepository.deactivateUser(
            "22222222-2222-2222-2222-222222222222",
            "Tutor resigned"
        )
        assertTrue(deactivateResult is Resource.Success)

        // Simulate tutor previously had a saved session
        val tutorSession = AuthSession(
            accessToken = "token",
            refreshToken = "refresh",
            expiresAt = Instant.now().plusSeconds(3600),
            userProfile = UserProfile(
                id = "22222222-2222-2222-2222-222222222222",
                role = UserRole.TUTOR,
                fullName = "Anand Rao (Tutor)",
                isActive = true
            )
        )
        sessionStorage.saveSession(tutorSession)

        // Act: Attempt to restore session for deactivated user
        val restoreResult = authRepository.restoreSession()

        // Assert: Session revoked, access terminated
        assertTrue(restoreResult is Resource.Error)
        assertTrue((restoreResult as Resource.Error).message.contains("Session revoked"))
        assertNull(sessionStorage.getSession())
    }
}
