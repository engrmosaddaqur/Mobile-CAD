package com.example

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.cad.engine.CadWorkspaceViewModel
import com.example.cad.engine.HomeViewModel
import com.example.data.UserPreferencesRepository
import com.example.ui.home.HomeScreen
import com.example.ui.screens.HelpScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.theme.MobileCadTheme
import com.example.ui.workspace.CadWorkspaceScreen

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val incomingUri: Uri? = if (intent?.action == Intent.ACTION_VIEW) intent.data else null

        setContent {
            val homeViewModel: HomeViewModel = viewModel()
            val prefs by homeViewModel.preferences.collectAsStateWithLifecycle()
            val recentDrawings by homeViewModel.recentDrawings.collectAsStateWithLifecycle()
            val pendingRecovery by homeViewModel.pendingRecovery.collectAsStateWithLifecycle()
            val importError by homeViewModel.importError.collectAsStateWithLifecycle()

            val navController = rememberNavController()
            val prefsRepo = UserPreferencesRepository(applicationContext)

            val openFileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                uri?.let {
                    homeViewModel.importFile(it) { importedId ->
                        navController.navigate("workspace/$importedId?recover=false")
                    }
                }
            }

            LaunchedEffect(incomingUri) {
                incomingUri?.let { uri ->
                    homeViewModel.importFile(uri) { importedId ->
                        navController.navigate("workspace/$importedId?recover=false")
                    }
                }
            }

            MobileCadTheme(themeMode = prefs.themeMode) {
                NavHost(navController = navController, startDestination = ROUTE_HOME) {
                    composable(ROUTE_HOME) {
                        LaunchedEffect(Unit) {
                            homeViewModel.refreshRecoveryStatus()
                        }
                        HomeScreen(
                            recentDrawings = recentDrawings,
                            pendingRecovery = pendingRecovery,
                            importError = importError,
                            defaultUnits = prefs.defaultUnits,
                            onNewDrawing = {
                                homeViewModel.createNewDrawing(
                                    name = "Drawing_${System.currentTimeMillis() % 10000}",
                                    projectGroup = "Default Project",
                                    units = prefs.defaultUnits
                                ) { id ->
                                    navController.navigate("workspace/$id?recover=false")
                                }
                            },
                            onOpenDrawingPicker = {
                                openFileLauncher.launch(arrayOf("*/*"))
                            },
                            onCreateProject = { name, group, units ->
                                homeViewModel.createNewDrawing(name, group, units) { id ->
                                    navController.navigate("workspace/$id?recover=false")
                                }
                            },
                            onOpenRecent = { id, recover ->
                                navController.navigate("workspace/$id?recover=$recover")
                            },
                            onDiscardRecovery = homeViewModel::discardRecovery,
                            onRenameDrawing = homeViewModel::renameDrawing,
                            onDeleteDrawing = homeViewModel::deleteDrawing,
                            onNavigateSettings = { navController.navigate(ROUTE_SETTINGS) },
                            onNavigateHelp = { navController.navigate(ROUTE_HELP) },
                            onDismissImportError = homeViewModel::dismissImportError
                        )
                    }

                    composable(
                        route = "workspace/{drawingId}?recover={recover}",
                        arguments = listOf(
                            navArgument("drawingId") { type = NavType.StringType },
                            navArgument("recover") { type = NavType.BoolType; defaultValue = false }
                        )
                    ) { backStackEntry ->
                        val drawingId = backStackEntry.arguments?.getString("drawingId") ?: "new_blank"
                        val recover = backStackEntry.arguments?.getBoolean("recover") ?: false
                        val workspaceVm: CadWorkspaceViewModel = viewModel()

                        LaunchedEffect(drawingId, recover) {
                            workspaceVm.loadDrawing(drawingId, recoverAutosave = recover)
                        }

                        CadWorkspaceScreen(
                            viewModel = workspaceVm,
                            onNavigateBack = { navController.popBackStack() }
                        )
                    }

                    composable(ROUTE_SETTINGS) {
                        SettingsScreen(
                            preferences = prefs,
                            prefsRepo = prefsRepo,
                            onNavigateBack = { navController.popBackStack() }
                        )
                    }

                    composable(ROUTE_HELP) {
                        HelpScreen(onNavigateBack = { navController.popBackStack() })
                    }
                }
            }
        }
    }

    companion object {
        const val ROUTE_HOME = "home"
        const val ROUTE_SETTINGS = "settings"
        const val ROUTE_HELP = "help"
    }
}
