package com.runforvictory.tuition.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.runforvictory.tuition.authentication.AuthViewModel
import com.runforvictory.tuition.authentication.LoginScreen
import com.runforvictory.tuition.core.di.AppContainer
import com.runforvictory.tuition.domain.UserRole
import com.runforvictory.tuition.features.owner.OwnerDashboardScreen
import com.runforvictory.tuition.features.owner.StudentManagementScreen
import com.runforvictory.tuition.features.owner.StudentManagementViewModel
import com.runforvictory.tuition.features.parent.ParentDashboardScreen
import com.runforvictory.tuition.features.tutor.TutorDashboardScreen

@Composable
fun AppNavHost(
    authViewModel: AuthViewModel,
    appContainer: AppContainer,
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController()
) {
    NavHost(
        navController = navController,
        startDestination = Screen.Login.route,
        modifier = modifier
    ) {
        composable(Screen.Login.route) {
            LoginScreen(
                viewModel = authViewModel,
                onLoginSuccess = { role ->
                    val destination = when (role) {
                        UserRole.OWNER -> Screen.OwnerDashboard.route
                        UserRole.TUTOR -> Screen.TutorDashboard.route
                        UserRole.PARENT -> Screen.ParentDashboard.route
                    }
                    navController.navigate(destination) {
                        popUpTo(Screen.Login.route) { inclusive = true }
                    }
                }
            )
        }

        composable(Screen.OwnerDashboard.route) {
            OwnerDashboardScreen(
                onNavigateToStudents = {
                    navController.navigate(Screen.StudentManagement.route)
                },
                onSignOut = {
                    authViewModel.signOut()
                    navController.navigate(Screen.Login.route) {
                        popUpTo(0) { inclusive = true }
                    }
                }
            )
        }

        composable(Screen.StudentManagement.route) {
            val studentVm: StudentManagementViewModel = viewModel(
                factory = object : androidx.lifecycle.ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                        return StudentManagementViewModel(appContainer.studentRepository) as T
                    }
                }
            )
            StudentManagementScreen(
                viewModel = studentVm,
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }

        composable(Screen.TutorDashboard.route) {
            TutorDashboardScreen(
                onSignOut = {
                    authViewModel.signOut()
                    navController.navigate(Screen.Login.route) {
                        popUpTo(0) { inclusive = true }
                    }
                }
            )
        }

        composable(Screen.ParentDashboard.route) {
            ParentDashboardScreen(
                onSignOut = {
                    authViewModel.signOut()
                    navController.navigate(Screen.Login.route) {
                        popUpTo(0) { inclusive = true }
                    }
                }
            )
        }
    }
}
