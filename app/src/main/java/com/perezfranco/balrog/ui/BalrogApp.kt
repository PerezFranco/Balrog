package com.perezfranco.balrog.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.navigation.NavType
import com.perezfranco.balrog.ui.screens.GeneratorScreen
import com.perezfranco.balrog.ui.screens.NoteEditorScreen
import com.perezfranco.balrog.ui.screens.PasswordEditorScreen
import com.perezfranco.balrog.ui.screens.SettingsScreen
import com.perezfranco.balrog.ui.screens.SetupScreen
import com.perezfranco.balrog.ui.screens.UnlockScreen
import com.perezfranco.balrog.ui.screens.VaultScreen

object Routes {
    const val SETUP = "setup"
    const val UNLOCK = "unlock"
    const val VAULT = "vault"
    const val PASSWORD_EDITOR = "password/{id}"
    const val NOTE_EDITOR = "note/{id}"
    const val GENERATOR = "generator"
    const val SETTINGS = "settings"

    fun passwordEditor(id: Long) = "password/$id"
    fun noteEditor(id: Long) = "note/$id"
}

@Composable
fun BalrogApp(activity: FragmentActivity) {
    val navController = rememberNavController()
    val viewModel: VaultViewModel = viewModel()
    val isUnlocked by viewModel.isUnlocked.collectAsState()

    // Whenever the session locks (auto-lock, manual lock), snap back to the
    // unlock screen and clear the back stack so nothing sensitive remains.
    LaunchedEffect(isUnlocked) {
        if (!isUnlocked && viewModel.vaultExists()) {
            navController.navigate(Routes.UNLOCK) {
                popUpTo(0) { inclusive = true }
            }
        }
    }

    val startDestination = when {
        !viewModel.vaultExists() -> Routes.SETUP
        isUnlocked -> Routes.VAULT
        else -> Routes.UNLOCK
    }

    NavHost(navController = navController, startDestination = startDestination) {
        composable(Routes.SETUP) {
            SetupScreen(viewModel = viewModel) {
                navController.navigate(Routes.VAULT) { popUpTo(0) { inclusive = true } }
            }
        }
        composable(Routes.UNLOCK) {
            UnlockScreen(viewModel = viewModel, activity = activity) {
                navController.navigate(Routes.VAULT) { popUpTo(0) { inclusive = true } }
            }
        }
        composable(Routes.VAULT) {
            VaultScreen(
                viewModel = viewModel,
                onOpenPassword = { id -> navController.navigate(Routes.passwordEditor(id)) },
                onOpenNote = { id -> navController.navigate(Routes.noteEditor(id)) },
                onOpenGenerator = { navController.navigate(Routes.GENERATOR) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
            )
        }
        composable(
            Routes.PASSWORD_EDITOR,
            arguments = listOf(navArgument("id") { type = NavType.LongType })
        ) { entry ->
            PasswordEditorScreen(
                viewModel = viewModel,
                itemId = entry.arguments?.getLong("id") ?: 0L,
                onBack = { navController.popBackStack() },
            )
        }
        composable(
            Routes.NOTE_EDITOR,
            arguments = listOf(navArgument("id") { type = NavType.LongType })
        ) { entry ->
            NoteEditorScreen(
                viewModel = viewModel,
                itemId = entry.arguments?.getLong("id") ?: 0L,
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.GENERATOR) {
            GeneratorScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                viewModel = viewModel,
                activity = activity,
                onBack = { navController.popBackStack() },
                onVaultDestroyed = {
                    navController.navigate(Routes.SETUP) { popUpTo(0) { inclusive = true } }
                },
            )
        }
    }
}
