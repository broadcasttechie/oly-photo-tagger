package com.olyphototagger.app.ui

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.olyphototagger.app.ui.settings.GpsSourcesScreen
import com.olyphototagger.app.ui.settings.GpsSourcesViewModel
import com.olyphototagger.app.ui.settings.SettingsScreen
import com.olyphototagger.app.ui.settings.SettingsViewModel
import com.olyphototagger.app.ui.workflow.DryRunScreen
import com.olyphototagger.app.ui.workflow.GeotagWorkflowViewModel
import com.olyphototagger.app.ui.workflow.HomeScreen
import com.olyphototagger.app.ui.workflow.ProgressScreen
import com.olyphototagger.app.ui.workflow.SummaryScreen

@Composable
fun AppNavigation(
    navController: NavHostController = rememberNavController(),
    pendingShareUri: Uri? = null,
    onPendingShareConsumed: () -> Unit = {}
) {
    // Obtained once here, above any per-route backstack entry, so it's effectively
    // Activity-scoped — state (selected folder, offset, scan result) survives
    // navigating back and forth across Home/DryRun/Progress/Summary, since it's really
    // one continuous workflow rather than four independent screens.
    val workflowViewModel: GeotagWorkflowViewModel = viewModel()

    // A GPX file shared in from another app takes the user straight to where they can
    // confirm importing it, regardless of where they were in the app when it arrived.
    LaunchedEffect(pendingShareUri) {
        if (pendingShareUri != null) {
            navController.navigate(AppRoute.GPS_SOURCES)
        }
    }

    NavHost(navController = navController, startDestination = AppRoute.HOME) {
        composable(AppRoute.HOME) {
            HomeScreen(
                viewModel = workflowViewModel,
                onNavigateToDryRun = { navController.navigate(AppRoute.DRY_RUN) },
                onNavigateToSettings = { navController.navigate(AppRoute.SETTINGS) },
                onNavigateToGpsSources = { navController.navigate(AppRoute.GPS_SOURCES) }
            )
        }
        composable(AppRoute.DRY_RUN) {
            DryRunScreen(
                viewModel = workflowViewModel,
                onBack = { navController.popBackStack() },
                onConfirmRun = {
                    // Drop DryRun from the backstack: once a write has started, back
                    // from Progress should land on Home, not a re-confirmable preview.
                    navController.navigate(AppRoute.PROGRESS) { popUpTo(AppRoute.HOME) }
                }
            )
        }
        composable(AppRoute.PROGRESS) {
            ProgressScreen(
                viewModel = workflowViewModel,
                onFinished = {
                    navController.navigate(AppRoute.SUMMARY) { popUpTo(AppRoute.HOME) }
                }
            )
        }
        composable(AppRoute.SUMMARY) {
            SummaryScreen(
                viewModel = workflowViewModel,
                onDone = {
                    workflowViewModel.resetForNextRun()
                    navController.popBackStack(AppRoute.HOME, inclusive = false)
                }
            )
        }
        composable(AppRoute.SETTINGS) {
            val settingsViewModel: SettingsViewModel = viewModel()
            SettingsScreen(
                viewModel = settingsViewModel,
                onBack = { navController.popBackStack() },
                onNavigateToGpsSources = { navController.navigate(AppRoute.GPS_SOURCES) }
            )
        }
        composable(AppRoute.GPS_SOURCES) {
            val gpsSourcesViewModel: GpsSourcesViewModel = viewModel()
            LaunchedEffect(pendingShareUri) {
                pendingShareUri?.let {
                    gpsSourcesViewModel.onShareIntentReceived(it)
                    onPendingShareConsumed()
                }
            }
            GpsSourcesScreen(
                viewModel = gpsSourcesViewModel,
                onBack = { navController.popBackStack() }
            )
        }
    }
}
