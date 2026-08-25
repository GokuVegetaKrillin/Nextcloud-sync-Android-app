package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.*
import com.example.ui.MainViewModel
import com.example.ui.screens.*
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.theme.NcPrimaryBlue
import com.example.ui.theme.NcWarningAmber

enum class AppScreen(
    val route: String,
    val title: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
) {
    DASHBOARD("dashboard", "Dashboard", Icons.Filled.Sync, Icons.Outlined.Sync),
    FOLDERS("folders", "Folders", Icons.Filled.FolderCopy, Icons.Outlined.FolderCopy),
    EXPLORER("explorer", "Local Files", Icons.Filled.Source, Icons.Outlined.Source),
    ACTIVITY("activity", "Activity", Icons.Filled.History, Icons.Outlined.History),
    CONFLICTS("conflicts", "Conflicts", Icons.Filled.Warning, Icons.Outlined.WarningAmber),
    SETTINGS("settings", "Settings", Icons.Filled.Settings, Icons.Outlined.Settings)
}

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            MyApplicationTheme {
                NextcloudAppRoot(viewModel = viewModel)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NextcloudAppRoot(viewModel: MainViewModel) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route ?: AppScreen.DASHBOARD.route

    val conflicts by viewModel.conflicts.collectAsState()
    val statusMessage by viewModel.statusMessage.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(statusMessage) {
        statusMessage?.let { msg ->
            snackbarHostState.showSnackbar(
                message = msg,
                duration = SnackbarDuration.Short
            )
            viewModel.clearStatusMessage()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Image(
                            painter = painterResource(id = R.drawable.app_logo),
                            contentDescription = "Nextcloud Logo",
                            modifier = Modifier.size(30.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "Nextcloud Sync",
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                ),
                actions = {
                    IconButton(
                        onClick = { viewModel.startSync() },
                        modifier = Modifier.testTag("topbar_sync_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Sync,
                            contentDescription = "Sync",
                            tint = NcPrimaryBlue
                        )
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 6.dp
            ) {
                AppScreen.entries.forEach { screen ->
                    val isSelected = currentRoute == screen.route
                    val isConflictTab = screen == AppScreen.CONFLICTS

                    NavigationBarItem(
                        selected = isSelected,
                        onClick = {
                            navController.navigate(screen.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = {
                            BadgedBox(
                                badge = {
                                    if (isConflictTab && conflicts.isNotEmpty()) {
                                        Badge(
                                            containerColor = NcWarningAmber,
                                            contentColor = Color.White
                                        ) {
                                            Text("${conflicts.size}")
                                        }
                                    }
                                }
                            ) {
                                Icon(
                                    imageVector = if (isSelected) screen.selectedIcon else screen.unselectedIcon,
                                    contentDescription = screen.title
                                )
                            }
                        },
                        label = {
                            Text(
                                text = screen.title,
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1
                            )
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = NcPrimaryBlue,
                            selectedTextColor = NcPrimaryBlue,
                            indicatorColor = NcPrimaryBlue.copy(alpha = 0.12f)
                        ),
                        modifier = Modifier.testTag("nav_item_${screen.route}")
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            NavHost(
                navController = navController,
                startDestination = AppScreen.DASHBOARD.route
            ) {
                composable(AppScreen.DASHBOARD.route) {
                    DashboardScreen(
                        viewModel = viewModel,
                        onNavigateToFolders = { navController.navigate(AppScreen.FOLDERS.route) },
                        onNavigateToConflicts = { navController.navigate(AppScreen.CONFLICTS.route) },
                        onNavigateToActivity = { navController.navigate(AppScreen.ACTIVITY.route) },
                        onNavigateToSettings = { navController.navigate(AppScreen.SETTINGS.route) }
                    )
                }

                composable(AppScreen.FOLDERS.route) {
                    FoldersScreen(viewModel = viewModel)
                }

                composable(AppScreen.EXPLORER.route) {
                    ExplorerScreen(
                        viewModel = viewModel,
                        onSyncNow = { viewModel.startSync() }
                    )
                }

                composable(AppScreen.ACTIVITY.route) {
                    ActivityScreen(viewModel = viewModel)
                }

                composable(AppScreen.CONFLICTS.route) {
                    ConflictsScreen(viewModel = viewModel)
                }

                composable(AppScreen.SETTINGS.route) {
                    SettingsScreen(viewModel = viewModel)
                }
            }
        }
    }
}
