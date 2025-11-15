package com.example.shieldshare

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Monitor
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.core.view.WindowCompat
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
    var themeMode by remember { mutableStateOf(loadThemeMode(appPrefs)) }
    
    // Track reload requests with a key
    var reloadKey by remember { mutableIntStateOf(0) }
    
    // Reload theme when key changes
    LaunchedEffect(reloadKey) {
        if (reloadKey > 0) {
            themeMode = loadThemeMode(appPrefs)
        }
    }
    
    ShieldShareTheme(themeMode = themeMode) {
        // Configure status bar appearance based on theme
        val useDarkTheme = when (themeMode) {
            ThemeMode.SYSTEM -> isSystemInDarkTheme()
            ThemeMode.LIGHT -> false
            ThemeMode.DARK -> true
        }
        
        // Set status bar appearance
        val view = LocalView.current
        LaunchedEffect(useDarkTheme) {
            val activity = view.context as? ComponentActivity
            activity?.window?.let { window ->
                WindowCompat.getInsetsController(window, view).apply {
                    // isAppearanceLightStatusBars = true means dark icons (for light backgrounds)
                    // isAppearanceLightStatusBars = false means light icons (for dark backgrounds)
                    isAppearanceLightStatusBars = !useDarkTheme
                }
            }
        }
        
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            // Pass a callback that increments the reload key
            ShieldShareAppContent(onThemeChange = { reloadKey++ })
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
