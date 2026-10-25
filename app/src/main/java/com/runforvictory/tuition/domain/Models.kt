package com.runforvictory.tuition.domain

import java.time.LocalDate
import java.time.ZonedDateTime

enum class UserRole {
    OWNER,
    TUTOR,
    PARENT;

    companion object {
        fun fromString(role: String?): UserRole {
            return when (role?.lowercase()?.trim()) {
                "owner" -> OWNER
                "tutor" -> TUTOR
                "parent" -> PARENT
                else -> PARENT
            }
        }
    }
}

data class UserProfile(
    val id: String,
    val role: UserRole,
    val fullName: String,
    val phone: String? = null,
    val avatarUrl: String? = null,
    val isActive: Boolean = true,
    val centreId: String = "10000000-0000-0000-0000-000000000001"
)

enum class StudentStatus {
    ACTIVE,
    ARCHIVED;

    companion object {
        fun fromString(status: String?): StudentStatus {
            return if (status?.equals("ARCHIVED", ignoreCase = true) == true) ARCHIVED else ACTIVE
        }
    }
}

data class Student(
    val id: String,
    val centreId: String = "10000000-0000-0000-0000-000000000001",
    val fullName: String,
    val grade: String,
    val guardianName: String,
    val guardianPhone: String? = null,
    val contactPhone: String? = null,
    val dateOfBirth: LocalDate? = null,
    val address: String? = null,
    val notes: String? = null,
    val status: StudentStatus = StudentStatus.ACTIVE,
    val createdAt: ZonedDateTime = ZonedDateTime.now(),
    val archivedAt: ZonedDateTime? = null,
    val archivedBy: String? = null,
    val archivedReason: String? = null
) {
    val isArchived: Boolean
        get() = (status == StudentStatus.ARCHIVED)
}

data class StudentHistory(
    val student: Student,
    val attendanceRecords: List<StudentAttendanceRecord>,
    val workLogs: List<DailyWorkReport>
)

enum class StudentAttendanceStatus {
    PRESENT,
    LATE,
    HALF_DAY,
    ABSENT
}

data class StudentAttendanceRecord(
    val id: String,
    val studentId: String,
    val studentName: String,
    val attendanceDate: LocalDate,
    val checkInTime: ZonedDateTime,
    val checkOutTime: ZonedDateTime? = null,
    val status: StudentAttendanceStatus = StudentAttendanceStatus.PRESENT,
    val notes: String? = null,
    val durationMinutes: Long? = null
)

enum class GpsVerificationStatus {
    VERIFIED,
    REJECTED,
    OVERRIDDEN
}

data class TutorAttendanceRecord(
    val id: String,
    val tutorId: String,
    val attendanceDate: LocalDate,
    val checkInTime: ZonedDateTime,
    val checkOutTime: ZonedDateTime? = null,
    val checkInDistanceMeters: Double,
    val checkInAccuracyMeters: Double,
    val verificationStatus: GpsVerificationStatus,
    val rejectionReason: String? = null,
    val isManualOverride: Boolean = false,
    val overrideReason: String? = null,
    val durationMinutes: Long? = null
)

data class DailyWorkReport(
    val id: String,
    val studentId: String,
    val studentName: String,
    val tutorId: String,
    val tutorName: String,
    val reportDate: LocalDate,
    val subject: String,
    val workCompleted: String,
    val homework: String? = null,
    val progressNotes: String? = null,
    val idempotencyKey: String
)

data class TuitionCenterConfig(
    val id: String,
    val name: String = "Run for Victory",
    val subtitle: String = "By Ravi Kumar",
    val latitude: Double = 17.385044,
    val longitude: Double = 78.486671,
    val radiusMeters: Int = 150,
    val timezone: String = "Asia/Kolkata",
    val maxAccuracyMeters: Double = 60.0
)
