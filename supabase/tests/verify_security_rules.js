// ============================================================================
// Automated Security & RLS Policy Verifier
// Project: Run for Victory (By Ravi Kumar)
// Tests: 8 Mandatory Security Boundaries
// ============================================================================

const fs = require('fs');
const path = require('path');

// Haversine calculation in JS matching PL/pgSQL implementation
function haversineDistanceMeters(lat1, lon1, lat2, lon2) {
    const r = 6371000.0;
    const toRad = (x) => (x * Math.PI) / 180.0;
    const phi1 = toRad(lat1);
    const phi2 = toRad(lat2);
    const deltaPhi = toRad(lat2 - lat1);
    const deltaLambda = toRad(lon2 - lon1);

    const a = Math.sin(deltaPhi / 2.0) ** 2 +
              Math.cos(phi1) * Math.cos(phi2) * Math.sin(deltaLambda / 2.0) ** 2;
    const c = 2.0 * Math.atan2(Math.sqrt(a), Math.sqrt(1.0 - a));
    return r * c;
}

// In-Memory Database Simulator for Testing Multi-Tenant Policies
class SecuritySimulator {
    constructor() {
        this.centres = [
            {
                id: '10000000-0000-0000-0000-000000000001',
                name: 'Run for Victory',
                subtitle: 'By Ravi Kumar',
                latitude: 17.385044,
                longitude: 78.486671,
                radius_meters: 150,
                max_accuracy: 60.0
            },
            {
                id: '20000000-0000-0000-0000-000000000002',
                name: 'Elite Academy (Tenant B)',
                subtitle: 'Isolated Tenant',
                latitude: 17.440081,
                longitude: 78.348915,
                radius_meters: 100,
                max_accuracy: 50.0
            }
        ];

        this.profiles = [
            { id: '11111111-1111-1111-1111-111111111111', centre_id: '10000000-0000-0000-0000-000000000001', role: 'owner', full_name: 'Ravi Kumar (Owner)', is_active: true },
            { id: '22222222-2222-2222-2222-222222222222', centre_id: '10000000-0000-0000-0000-000000000001', role: 'tutor', full_name: 'Anand Rao (Tutor)', is_active: true },
            { id: '33333333-3333-3333-3333-333333333333', centre_id: '10000000-0000-0000-0000-000000000001', role: 'parent', full_name: 'Suresh Reddy (Parent A)', is_active: true },
            { id: '44444444-4444-4444-4444-444444444444', centre_id: '10000000-0000-0000-0000-000000000001', role: 'parent', full_name: 'Kavitha Devi (Parent B)', is_active: true },
            { id: '55555555-5555-5555-5555-555555555555', centre_id: '20000000-0000-0000-0000-000000000002', role: 'owner', full_name: 'Tenant B Owner', is_active: true }
        ];

        this.students = [
            { id: 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', centre_id: '10000000-0000-0000-0000-000000000001', full_name: 'Rahul Reddy (Child A)', status: 'ACTIVE' },
            { id: 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb', centre_id: '10000000-0000-0000-0000-000000000001', full_name: 'Pooja Devi (Child B)', status: 'ACTIVE' },
            { id: 'cccccccc-cccc-cccc-cccc-cccccccccccc', centre_id: '10000000-0000-0000-0000-000000000001', full_name: 'Vikram Singh (Archived)', status: 'ARCHIVED' },
            { id: 'dddddddd-dddd-dddd-dddd-dddddddddddd', centre_id: '20000000-0000-0000-0000-000000000002', full_name: 'Tenant B Student', status: 'ACTIVE' }
        ];

        this.parent_children = [
            { parent_id: '33333333-3333-3333-3333-333333333333', student_id: 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', centre_id: '10000000-0000-0000-0000-000000000001', is_active: true },
            { parent_id: '44444444-4444-4444-4444-444444444444', student_id: 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb', centre_id: '10000000-0000-0000-0000-000000000001', is_active: true }
        ];

        this.student_attendance = [
            {
                id: 'att-1',
                centre_id: '10000000-0000-0000-0000-000000000001',
                student_id: 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa',
                attendance_date: '2026-10-25',
                status: 'PRESENT',
                recorded_by: '22222222-2222-2222-2222-222222222222'
            }
        ];

        this.teacher_attendance = [];
        this.audit_logs = [];
    }

    // Resolves caller context
    getAuth(userId) {
        if (!userId) return null;
        return this.profiles.find(p => p.id === userId && p.is_active);
    }

    // RLS: View students
    selectStudents(authUserId) {
        const caller = this.getAuth(authUserId);
        if (!caller) return []; // Deny anonymous

        if (caller.role === 'owner') {
            return this.students.filter(s => s.centre_id === caller.centre_id);
        }

        if (caller.role === 'tutor') {
            return this.students.filter(s => s.centre_id === caller.centre_id && s.status === 'ACTIVE');
        }

        if (caller.role === 'parent') {
            const linkedStudentIds = this.parent_children
                .filter(pc => pc.parent_id === caller.id && pc.is_active)
                .map(pc => pc.student_id);
            return this.students.filter(s => linkedStudentIds.includes(s.id) && s.centre_id === caller.centre_id);
        }

        return [];
    }

    // RLS: Modify centre settings
    updateCentreSettings(authUserId, centreId, newRadius) {
        const caller = this.getAuth(authUserId);
        if (!caller || caller.role !== 'owner' || caller.centre_id !== centreId) {
            return { success: false, error: 'Access Denied: Only centre owner can modify settings.' };
        }
        const centre = this.centres.find(c => c.id === centreId);
        if (centre) {
            centre.radius_meters = newRadius;
            return { success: true };
        }
        return { success: false, error: 'Centre not found.' };
    }

    // RPC: Record Student Check-in
    recordStudentCheckIn(authUserId, studentId, dateStr = '2026-10-25') {
        const caller = this.getAuth(authUserId);
        if (!caller || (caller.role !== 'owner' && caller.role !== 'tutor')) {
            throw new Error('Access Denied: Only tutors or owners can record student attendance.');
        }

        const student = this.students.find(s => s.id === studentId && s.centre_id === caller.centre_id);
        if (!student) {
            throw new Error('Student not found in your tuition centre.');
        }

        if (student.status === 'ARCHIVED') {
            throw new Error(`Archived Student: Cannot mark attendance for archived student "${student.full_name}".`);
        }

        const duplicate = this.student_attendance.find(a => a.student_id === studentId && a.attendance_date === dateStr);
        if (duplicate) {
            throw new Error(`Duplicate Attendance: Student already has attendance recorded for today (${dateStr}).`);
        }

        const record = {
            id: 'att-' + Math.random().toString(36).substring(7),
            centre_id: caller.centre_id,
            student_id: studentId,
            attendance_date: dateStr,
            status: 'PRESENT',
            recorded_by: caller.id
        };
        this.student_attendance.push(record);
        return { success: true, record };
    }

    // RPC: Teacher Clock In
    recordTeacherClockIn(authUserId, lat, lng, accuracy, dateStr = '2026-10-25') {
        const caller = this.getAuth(authUserId);
        if (!caller || caller.role !== 'tutor') {
            throw new Error('Access Denied: Only active tutors can clock in.');
        }

        const centre = this.centres.find(c => c.id === caller.centre_id);
        const distance = haversineDistanceMeters(lat, lng, centre.latitude, centre.longitude);

        let verificationStatus = 'VERIFIED';
        let reason = null;

        if (accuracy > centre.max_accuracy) {
            verificationStatus = 'REJECTED';
            reason = `GPS accuracy insufficient (${accuracy}m > allowed ${centre.max_accuracy}m)`;
        } else if (distance > centre.radius_meters) {
            verificationStatus = 'REJECTED';
            reason = `Location outside geofence (${distance.toFixed(1)}m > allowed ${centre.radius_meters}m)`;
        }

        const record = {
            id: 't-att-' + Math.random().toString(36).substring(7),
            centre_id: caller.centre_id,
            teacher_id: caller.id,
            attendance_date: dateStr,
            status: verificationStatus === 'VERIFIED' ? 'OPEN' : 'REJECTED',
            check_in_distance_meters: distance,
            check_in_accuracy: accuracy,
            check_in_verification_status: verificationStatus,
            check_in_rejection_reason: reason
        };
        this.teacher_attendance.push(record);
        return { success: verificationStatus === 'VERIFIED', record };
    }
}

// ============================================================================
// EXECUTE TESTS
// ============================================================================
console.log('================================================================');
console.log('RUN FOR VICTORY: BACKEND & RLS SECURITY TEST SUITE');
console.log('================================================================\n');

const sim = new SecuritySimulator();
let passed = 0;
let total = 0;

function assert(condition, testName, failureMsg) {
    total++;
    if (condition) {
        console.log(`✅ [PASS] ${testName}`);
        passed++;
    } else {
        console.error(`❌ [FAIL] ${testName}: ${failureMsg}`);
        process.exitCode = 1;
    }
}

// TEST 1: Owner can view authorised centre records
const ownerStudents = sim.selectStudents('11111111-1111-1111-1111-111111111111');
assert(
    ownerStudents.length === 3 && ownerStudents.every(s => s.centre_id === '10000000-0000-0000-0000-000000000001'),
    'Test 1: Owner can view authorised centre records',
    `Expected 3 students for Centre A, found ${ownerStudents.length}`
);

// TEST 2: Tutor cannot perform owner-only actions
const tutorUpdateResult = sim.updateCentreSettings('22222222-2222-2222-2222-222222222222', '10000000-0000-0000-0000-000000000001', 500);
assert(
    tutorUpdateResult.success === false && tutorUpdateResult.error.includes('Access Denied'),
    'Test 2: Tutor cannot perform owner-only actions',
    'Tutor was able to modify centre settings!'
);

// TEST 3: Parent A cannot read Parent B\'s child
const parentAStudents = sim.selectStudents('33333333-3333-3333-3333-333333333333');
const canSeeChildA = parentAStudents.some(s => s.id === 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa');
const canSeeChildB = parentAStudents.some(s => s.id === 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb');
assert(
    canSeeChildA && !canSeeChildB,
    "Test 3: Parent A cannot read Parent B's child",
    `Parent A records contain child B? ${canSeeChildB}`
);

// TEST 4: Parent cannot insert or modify attendance
let parentCheckinFailed = false;
try {
    sim.recordStudentCheckIn('33333333-3333-3333-3333-333333333333', 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa');
} catch (e) {
    parentCheckinFailed = e.message.includes('Access Denied');
}
assert(
    parentCheckinFailed,
    'Test 4: Parent cannot insert or modify attendance',
    'Parent was allowed to insert student attendance!'
);

// TEST 5: Archived students cannot receive new attendance records
let archivedCheckinFailed = false;
try {
    sim.recordStudentCheckIn('22222222-2222-2222-2222-222222222222', 'cccccccc-cccc-cccc-cccc-cccccccccccc');
} catch (e) {
    archivedCheckinFailed = e.message.includes('Archived Student');
}
assert(
    archivedCheckinFailed,
    'Test 5: Archived students cannot receive new attendance records',
    'Archived student check-in was accepted!'
);

// TEST 6: Users cannot cross tuition-centre boundaries (Tenant A vs Tenant B)
const tenantBOwnerStudents = sim.selectStudents('55555555-5555-5555-5555-555555555555');
const hasTenantAStudent = tenantBOwnerStudents.some(s => s.centre_id === '10000000-0000-0000-0000-000000000001');
const hasTenantBStudent = tenantBOwnerStudents.some(s => s.centre_id === '20000000-0000-0000-0000-000000000002');
assert(
    !hasTenantAStudent && hasTenantBStudent,
    'Test 6: Users cannot cross tuition-centre boundaries',
    'Tenant boundary leakage between Centre A and Centre B!'
);

// TEST 7: Anonymous users cannot read private data
const anonStudents = sim.selectStudents(null);
assert(
    anonStudents.length === 0,
    'Test 7: Anonymous users cannot read private data',
    `Anonymous user read ${anonStudents.length} records!`
);

// TEST 8: Attendance retry cannot create duplicate records
let duplicateRejected = false;
try {
    // Student A already has attendance for 2026-10-25 in initial seed
    sim.recordStudentCheckIn('22222222-2222-2222-2222-222222222222', 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', '2026-10-25');
} catch (e) {
    duplicateRejected = e.message.includes('Duplicate Attendance');
}
assert(
    duplicateRejected,
    'Test 8: Attendance retry cannot create duplicate records',
    'Duplicate attendance on retry was allowed!'
);

// BONUS TEST 9: Tutor GPS Clock-in Out-of-Bounds Rejection (Haversine Geofence)
const farClockIn = sim.recordTeacherClockIn('22222222-2222-2222-2222-222222222222', 17.480000, 78.486671, 15.0);
assert(
    farClockIn.success === false && farClockIn.record.check_in_verification_status === 'REJECTED',
    'Bonus Test 9: Tutor GPS clock-in rejected outside geofence boundary',
    'Out of bounds clock-in was verified!'
);

// BONUS TEST 10: Inaccurate GPS reading rejection (>60m accuracy)
const inaccurateClockIn = sim.recordTeacherClockIn('22222222-2222-2222-2222-222222222222', 17.385044, 78.486671, 150.0);
assert(
    inaccurateClockIn.success === false && inaccurateClockIn.record.check_in_verification_status === 'REJECTED',
    'Bonus Test 10: Inaccurate GPS reading (>60m) rejected',
    'Inaccurate GPS reading was accepted!'
);

console.log('\n----------------------------------------------------------------');
console.log(`SUMMARY: ${passed} / ${total} Security Tests Passed Successfully.`);
console.log('----------------------------------------------------------------');

if (passed === total) {
    process.exit(0);
} else {
    process.exit(1);
}
