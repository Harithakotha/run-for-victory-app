package com.runforvictory.tuition.navigation

sealed class Screen(val route: String) {
    data object Login : Screen("login")
    data object OwnerDashboard : Screen("owner_dashboard")
    data object StudentManagement : Screen("student_management")
    data object TutorDashboard : Screen("tutor_dashboard")
    data object ParentDashboard : Screen("parent_dashboard")
}
