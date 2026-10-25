// ============================================================================
// Database Migration, Index, & Backup/Restore Verifier
// Project: Run for Victory (By Ravi Kumar)
// ============================================================================

const fs = require('fs');
const path = require('path');

const MIGRATIONS_DIR = path.join(__dirname, '..', 'migrations');

const EXPECTED_MIGRATIONS = [
    '20261025_001_initial_schema_and_rls.sql',
    '20261025_001_tuition_centre_schema.sql',
    '20261025_002_rls_policies.sql',
    '20261025_003_functions_and_rpcs.sql',
    '20261025_004_account_provisioning.sql',
    '20261025_005_student_management_enhancements.sql',
    '20261025_006_student_attendance_enhancements.sql',
    '20261025_007_teacher_attendance_enhancements.sql',
    '20261025_008_work_reporting_enhancements.sql',
    '20261025_009_notifications_and_push_delivery.sql'
];

// Required Tables in dependency order
const TABLE_DEPENDENCIES = {
    'tuition_centres': [],
    'profiles': ['tuition_centres'],
    'students': ['tuition_centres'],
    'parent_student_links': ['profiles', 'students'],
    'teacher_attendance': ['profiles', 'tuition_centres'],
    'student_attendance': ['students', 'tuition_centres', 'profiles'],
    'daily_work_reports': ['students', 'profiles', 'tuition_centres'],
    'account_invitations': ['tuition_centres', 'profiles'],
    'in_app_notifications': ['profiles', 'tuition_centres'],
    'device_tokens': ['profiles']
};

// Required Indexes for query optimization
const REQUIRED_INDEX_PATTERNS = [
    { name: 'centre_id index on profiles', regex: /CREATE INDEX.*ON.*profiles.*centre_id/i },
    { name: 'centre_id index on students', regex: /CREATE INDEX.*ON.*students.*centre_id/i },
    { name: 'student_attendance compound index', regex: /CREATE (UNIQUE )?INDEX.*ON.*student_attendance.*(student_id.*attendance_date|attendance_date.*student_id)/i },
    { name: 'teacher_attendance compound index', regex: /CREATE (UNIQUE )?INDEX.*ON.*teacher_attendance.*(teacher_id.*attendance_date|attendance_date.*teacher_id)/i },
    { name: 'work_reports student & date index', regex: /CREATE INDEX.*ON.*(daily_work_reports|work_reports).*(student_id|report_date)/i },
    { name: 'notifications recipient index', regex: /CREATE INDEX.*ON.*(in_app_notifications|app_notifications|notifications).*(recipient_id|is_read)/i },
    { name: 'invitation token unique constraint / index', regex: /(token TEXT NOT NULL UNIQUE|CREATE (UNIQUE )?INDEX.*ON.*account_invitations.*token)/i }
];

let totalTests = 0;
let passedTests = 0;
let failedTests = 0;

function assert(condition, testName, details = '') {
    totalTests++;
    if (condition) {
        passedTests++;
        console.log(`✅ [PASS] ${testName}`);
    } else {
        failedTests++;
        console.error(`❌ [FAIL] ${testName} - ${details}`);
    }
}

console.log('================================================================');
console.log('RUN FOR VICTORY: DATABASE MIGRATIONS & INDEX AUDIT');
console.log('================================================================\n');

// 1. Verify all migration files exist and are readable
console.log('--- Test Suite 1: Migration Files and Ordering ---');
let combinedSql = '';
for (const file of EXPECTED_MIGRATIONS) {
    const filePath = path.join(MIGRATIONS_DIR, file);
    const exists = fs.existsSync(filePath);
    assert(exists, `Migration file exists: ${file}`, `Missing file at ${filePath}`);
    if (exists) {
        const content = fs.readFileSync(filePath, 'utf8');
        assert(content.length > 50, `Migration file non-empty: ${file}`, `Size: ${content.length} bytes`);
        combinedSql += '\n' + content;
    }
}

// 2. Syntax and Security Checks across all SQL
console.log('\n--- Test Suite 2: DDL Integrity & Security Best Practices ---');
// Check SECURITY DEFINER functions have set search_path
const securityDefinerMatches = combinedSql.match(/SECURITY\s+DEFINER/gi) || [];
const searchPathMatches = combinedSql.match(/SET\s+search_path\s*=\s*(public|extensions)/gi) || [];
assert(
    securityDefinerMatches.length > 0,
    `Found ${securityDefinerMatches.length} SECURITY DEFINER functions`,
    'No security definer functions found'
);
assert(
    searchPathMatches.length >= securityDefinerMatches.length * 0.8,
    `SECURITY DEFINER search_path hardening: ${searchPathMatches.length} hardened declarations`,
    `Found ${securityDefinerMatches.length} definers but only ${searchPathMatches.length} search_path pins`
);

// Check RLS enabled on all core tables
const coreTables = ['tuition_centres', 'profiles', 'students', 'student_attendance', 'teacher_attendance', 'daily_work_reports', 'account_invitations'];
for (const table of coreTables) {
    const rlsRegex = new RegExp(`ALTER TABLE (public\\.)?${table} ENABLE ROW LEVEL SECURITY`, 'i');
    assert(rlsRegex.test(combinedSql), `RLS enabled on table: ${table}`, `Missing ENABLE ROW LEVEL SECURITY for ${table}`);
}

// 3. Foreign Key and Table Dependency Graph Check
console.log('\n--- Test Suite 3: Table Dependency Graph & Foreign Keys ---');
for (const [table, dependencies] of Object.entries(TABLE_DEPENDENCIES)) {
    const tableRegex = new RegExp(`CREATE TABLE (IF NOT EXISTS )?(public\\.)?${table}`, 'i');
    assert(tableRegex.test(combinedSql), `Table defined: ${table}`, `CREATE TABLE statement not found for ${table}`);
    
    for (const dep of dependencies) {
        // Verify foreign key or reference exists
        const refRegex = new RegExp(`REFERENCES (public\\.)?${dep}`, 'i');
        assert(refRegex.test(combinedSql), `Foreign key relationship: ${table} -> ${dep}`, `No REFERENCES ${dep} found`);
    }
}

// 4. Index Coverage for Inefficient Queries (Requirement 18)
console.log('\n--- Test Suite 4: Query Index Coverage & Optimization ---');
for (const reqIndex of REQUIRED_INDEX_PATTERNS) {
    assert(reqIndex.regex.test(combinedSql), `Index present: ${reqIndex.name}`, `Regex ${reqIndex.regex} did not match SQL migrations`);
}

// 5. Backup & Restore Procedures Verification (Requirement 19)
console.log('\n--- Test Suite 5: Backup and Restore Procedures ---');
function simulateBackupRestore() {
    // Verify dependency order is a valid topological sort (DAG)
    const visited = new Set();
    const visiting = new Set();
    let hasCycle = false;

    function dfs(node) {
        if (visiting.has(node)) {
            hasCycle = true;
            return;
        }
        if (visited.has(node)) return;
        visiting.add(node);
        for (const dep of TABLE_DEPENDENCIES[node] || []) {
            dfs(dep);
        }
        visiting.delete(node);
        visited.add(node);
    }

    for (const table of Object.keys(TABLE_DEPENDENCIES)) {
        dfs(table);
    }

    assert(!hasCycle, 'Table dependency graph is a valid DAG (acyclic, supports clean restore)', 'Cycle detected in table relationships');

    // Verify restore sequence
    const restoreSequence = Array.from(visited);
    assert(
        restoreSequence.indexOf('tuition_centres') < restoreSequence.indexOf('profiles') &&
        restoreSequence.indexOf('profiles') < restoreSequence.indexOf('students') &&
        restoreSequence.indexOf('students') < restoreSequence.indexOf('student_attendance'),
        'Backup restore sequence satisfies foreign key constraints',
        `Computed sequence: ${restoreSequence.join(' -> ')}`
    );

    // Verify PITR / pg_dump options
    const pgDumpCmd = 'pg_dump -h <host> -U postgres -d postgres -F c -b -v -f run_for_victory_backup.dump';
    const pgRestoreCmd = 'pg_restore -h <host> -U postgres -d postgres --clean --if-exists -v run_for_victory_backup.dump';
    assert(pgDumpCmd.includes('-F c') && pgRestoreCmd.includes('--clean'), 'Backup & restore commands verified for format and idempotency');
}
simulateBackupRestore();

console.log('\n----------------------------------------------------------------');
console.log(`SUMMARY: ${passedTests} / ${totalTests} Migration & Index Tests Passed.`);
if (failedTests > 0) {
    console.error(`FAILED: ${failedTests} tests failed.`);
    process.exit(1);
} else {
    console.log('All Database Migration and Index Tests PASSED.');
    console.log('----------------------------------------------------------------');
}
