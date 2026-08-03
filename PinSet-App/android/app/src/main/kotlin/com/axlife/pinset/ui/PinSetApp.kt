package com.axlife.pinset.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.axlife.pinset.InspectionAccessMode
import com.axlife.pinset.PinSetApplication
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.axlife.pinset.R
import com.axlife.pinset.ui.ai.AiInspectionScreen
import com.axlife.pinset.ui.camera.CameraScreen
import com.axlife.pinset.ui.detail.PinDetailScreen
import com.axlife.pinset.ui.history.HistoryScreen
import com.axlife.pinset.ui.history.ReferenceScreen
import com.axlife.pinset.ui.history.ReportScreen
import com.axlife.pinset.ui.home.HomeScreen
import com.axlife.pinset.ui.intro.HouseholdIntroScreen
import com.axlife.pinset.ui.pinset.PinPlacementScreen
import com.axlife.pinset.ui.gallery.ServerGalleryScreen

object Routes {
    const val INTRO = "intro"
    const val HOME = "home"
    const val HISTORY = "history"
    const val REPORT = "report"
    const val CAMERA = "camera"
    const val CAMERA_ANCHOR = "camera-anchor"   // entrance-anchor 2-shot
    const val PIN_PLACEMENT = "pin-placement"
    const val PIN_DETAIL = "pin-detail/{defectId}"
    const val REFERENCE = "reference"
    const val AI_INSPECTION = "ai-inspection"
    const val SERVER_GALLERY = "server-gallery"
    const val DEVICE_SIMULATOR = "device-simulator"

    fun pinDetail(defectId: Long) = "pin-detail/$defectId"
}

private data class BottomItem(val route: String, val labelRes: Int, val icon: androidx.compose.ui.graphics.vector.ImageVector)

@Composable
fun PinSetApp() {
    val nav = rememberNavController()
    val app = LocalContext.current.applicationContext as PinSetApplication
    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route

    val bottomItems = listOf(
        BottomItem(Routes.HOME, R.string.tab_home, Icons.Filled.Home),
        BottomItem(Routes.HISTORY, R.string.tab_list, Icons.Filled.List),
        BottomItem(Routes.REPORT, R.string.tab_report, Icons.Filled.Description),
        BottomItem(Routes.SERVER_GALLERY, R.string.tab_defect_db, Icons.Filled.PhotoLibrary)
    )
    val topLevelRoutes = bottomItems.map { it.route }.toSet()

    Scaffold(
        topBar = {
            Column {
                if (app.inspectionAccessMode == InspectionAccessMode.DEMO && currentRoute != Routes.INTRO) {
                    Text(
                        "DEMO 모드 · 서버 저장 안 됨",
                        color = Color.White,
                        fontWeight = FontWeight.ExtraBold,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFFB42318))
                            .padding(horizontal = 16.dp, vertical = 7.dp)
                    )
                }
                ServerConnectionStatus()
            }
        },
        bottomBar = {
            if (currentRoute in topLevelRoutes) {
                NavigationBar {
                    bottomItems.forEach { item ->
                        val selected = backStack?.destination?.hierarchy?.any { it.route == item.route } == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                nav.navigate(item.route) {
                                    popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(item.icon, contentDescription = null) },
                            label = { Text(stringResource(item.labelRes)) }
                        )
                    }
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = nav,
            startDestination = Routes.INTRO,
            modifier = Modifier.padding(padding)
        ) {
            composable(Routes.INTRO) { HouseholdIntroScreen(nav) }
            composable(Routes.HOME) { HomeScreen(nav) }
            composable(Routes.HISTORY) { HistoryScreen(nav) }
            composable(Routes.REPORT) { ReportScreen(nav) }
            composable(Routes.CAMERA) { CameraScreen(nav, anchorMode = false) }
            composable(Routes.CAMERA_ANCHOR) { CameraScreen(nav, anchorMode = true) }
            composable(Routes.PIN_PLACEMENT) { PinPlacementScreen(nav) }
            composable(Routes.REFERENCE) { ReferenceScreen(nav) }
            composable(Routes.AI_INSPECTION) { AiInspectionScreen(nav) }
            composable(Routes.SERVER_GALLERY) { ServerGalleryScreen(nav) }
            composable(Routes.DEVICE_SIMULATOR) { DeviceSimulatorScreen(nav) }
            composable(Routes.PIN_DETAIL) { entry ->
                val id = entry.arguments?.getString("defectId")?.toLongOrNull() ?: 0L
                PinDetailScreen(nav, id)
            }
        }
    }
}
