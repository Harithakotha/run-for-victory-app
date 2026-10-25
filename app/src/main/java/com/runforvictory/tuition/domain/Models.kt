package com.runforvictory.tuition.domain

import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
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
    ABSENT,
    EXCUSED;

    companion object {
        fun fromString(status: String?): StudentAttendanceStatus {
            return when (status?.uppercase()?.trim()) {
                "PRESENT" -> PRESENT
                "LATE" -> LATE
                "HALF_DAY" -> HALF_DAY
                "ABSENT" -> ABSENT
                "EXCUSED" -> EXCUSED
                else -> PRESENT
            }
        }
    }
}

data class StudentAttendanceRecord(
    val id: String,
    val centreId: String = "10000000-0000-0000-0000-000000000001",
    val studentId: String,
    val studentName: String,
    val studentGrade: String = "",
    val attendanceDate: LocalDate,
    val checkInTime: ZonedDateTime,
    val checkOutTime: ZonedDateTime? = null,
    val status: StudentAttendanceStatus = StudentAttendanceStatus.PRESENT,
    val notes: String? = null,
    val durationMinutes: Long? = null,
    val recordedBy: String? = null,
    val recordedByName: String? = null,
    val correctedBy: String? = null,
    val correctedByName: String? = null,
    val correctionReason: String? = null,
    val correctedAt: ZonedDateTime? = null,
    val idempotencyKey: String? = null
) {
    fun calculateCurrentDuration(now: ZonedDateTime = ZonedDateTime.now(ZoneId.of("Asia/Kolkata"))): Long {
        return if (checkOutTime != null) {
            durationMinutes ?: Duration.between(checkInTime, checkOutTime).toMinutes()
        } else {
            Duration.between(checkInTime, now).toMinutes().coerceAtLeast(0)
        }
    }
}

data class AttendanceDailySummary(
    val date: LocalDate,
    val totalActiveStudents: Int,
    val presentCount: Int,
    val lateCount: Int,
    val halfDayCount: Int = 0,
    val absentCount: Int,
    val excusedCount: Int,
    val notRecordedCount: Int,
    val attendancePercentage: Double
)

data class AttendanceMonthlySummary(
    val year: Int,
    val month: Int,
    val totalSessions: Int,
    val presentCount: Int,
    val lateCount: Int,
    val halfDayCount: Int = 0,
    val absentCount: Int,
    val excusedCount: Int,
    val averageAttendancePercentage: Double
)

data class StudentAttendanceItem(
    val student: Student,
    val attendance: StudentAttendanceRecord? = null,
    val isProcessing: Boolean = false
) {
    val isCheckedIn: Boolean get() = attendance != null
    val isCheckedOut: Boolean get() = attendance?.checkOutTime != null
    val canCheckIn: Boolean get() = attendance == null
    val canCheckOut: Boolean get() = (attendance != null && attendance.checkOutTime == null)
}

enum class GpsVerificationStatus {
    VERIFIED,
    REJECTED,
    OVERRIDDEN
}

data class TutorAttendanceRecord(
    val id: String,
    val tutorId: String,
    val tutorName: String = "",
    val centreId: String = "10000000-0000-0000-0000-000000000001",
    val attendanceDate: LocalDate,
    val checkInTime: ZonedDateTime,
    val checkOutTime: ZonedDateTime? = null,
    val checkInLat: Double = 17.385044,
    val checkInLng: Double = 78.486671,
    val checkInDistanceMeters: Double,
    val checkInAccuracyMeters: Double,
    val verificationStatus: GpsVerificationStatus,
    val rejectionReason: String? = null,
    val checkOutLat: Double? = null,
    val checkOutLng: Double? = null,
    val checkOutAccuracyMeters: Double? = null,
    val checkOutDistanceMeters: Double? = null,
    val checkOutVerificationStatus: GpsVerificationStatus? = null,
    val checkOutRejectionReason: String? = null,
    val isManualOverride: Boolean = false,
    val overrideBy: String? = null,
    val overrideByName: String? = null,
    val overrideReason: String? = null,
    val overrideAt: ZonedDateTime? = null,
    val durationMinutes: Long? = null
) {
    fun calculateCurrentDuration(now: ZonedDateTime = ZonedDateTime.now(ZoneId.of("Asia/Kolkata"))): Long {
        return if (checkOutTime != null) {
            durationMinutes ?: Duration.between(checkInTime, checkOutTime).toMinutes()
        } else {
            Duration.between(checkInTime, now).toMinutes().coerceAtLeast(0)
        }
    }
}

data class TutorMonthlySummary(
    val tutorId: String,
    val year: Int,
    val month: Int,
    val totalDaysWorked: Int,
    val totalHoursWorked: Double,
    val verifiedSessionsCount: Int,
    val rejectedSessionsCount: Int,
    val overriddenSessionsCount: Int
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
    val maxAccuracyMeters: Double = 60.0,
    val maxLocationAgeSeconds: Long = 120L,
    val ownerOverridePolicy: String = "Owner can override GPS failures with documented audit reason"
)
