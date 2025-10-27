package com.example.shieldshare

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Monitor
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.shieldshare.data.prefs.AppPrefs
import com.example.shieldshare.ui.home.HomeScreen
import com.example.shieldshare.ui.monitoring.MonitoringDashboardScreen
import com.example.shieldshare.ui.settings.SettingsScreen
import com.example.shieldshare.ui.theme.ShieldShareTheme
import com.example.shieldshare.ui.theme.ThemeMode
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        setContent {
            MainAppContent()
        }
    }
}

@Composable
fun MainAppContent() {
    val context = LocalContext.current
    val appPrefs = remember { AppPrefs(context) }
    
    // Create a mutable state for theme mode that can be updated
    val themeModeState = remember { mutableStateOf(loadThemeMode(appPrefs)) }
    
    // Remember the reload function to avoid recreating it
    val reloadTheme = remember {
        {
            themeModeState.value = loadThemeMode(appPrefs)
        }
    }
    
    ShieldShareTheme(themeMode = themeModeState.value) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            // Pass reloadTheme to ShieldShareAppContent so settings can trigger it
            ShieldShareAppContent(reloadTheme)
        }
    }
}

fun loadThemeMode(appPrefs: AppPrefs): ThemeMode {
    val themeModeString = appPrefs.getString("theme_mode", "SYSTEM") ?: "SYSTEM"
    return try {
        ThemeMode.valueOf(themeModeString)
    } catch (e: Exception) {
        ThemeMode.SYSTEM
    }
}

@Composable
fun ShieldShareAppContent(onThemeChange: () -> Unit = {}) {
    val navController = rememberNavController()
    
    Scaffold(
        bottomBar = {
            NavigationBar {
                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentDestination = navBackStackEntry?.destination
                
                listOf(
                    TabItem("home", "Home", Icons.Default.Home),
                    TabItem("monitoring", "Monitoring", Icons.Default.Monitor),
                    TabItem("settings", "Settings", Icons.Default.Settings)
                ).forEach { tab ->
                    NavigationBarItem(
                        icon = { Icon(tab.icon, contentDescription = tab.title) },
                        label = { Text(tab.title) },
                        selected = currentDestination?.hierarchy?.any { it.route == tab.route } == true,
                        onClick = {
                            navController.navigate(tab.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    )
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = "home",
            modifier = Modifier.padding(innerPadding)
        ) {
            composable("home") {
                HomeScreen()
            }
            composable("monitoring") {
                MonitoringDashboardScreen()
            }
            composable("settings") {
                SettingsScreen(onThemeChanged = onThemeChange)
            }
        }
    }
}

data class TabItem(
    val route: String,
    val title: String,
    val icon: ImageVector
)
