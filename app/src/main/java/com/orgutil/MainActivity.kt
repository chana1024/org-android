package com.orgutil

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.navigation.compose.rememberNavController
import com.orgutil.ui.navigation.OrgUtilNavigation
import com.orgutil.ui.theme.OrgUtilTheme
import com.orgutil.ui.theme.ThemeController
import com.orgutil.widget.AgendaWidgetOpenRequest
import com.orgutil.widget.toAgendaWidgetOpenRequest
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private var activeIntent by mutableStateOf<Intent?>(null)

    // Live theme choice: read INSIDE setContent below so a selection made in
    // Settings recomposes the whole tree under OrgUtilTheme on the same
    // frame — widget-launched goal/TODO/planning routes land here too, so
    // every deep link opens under the persisted palette.
    @Inject lateinit var themeController: ThemeController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        activeIntent = intent
        // The UI is forced light (both Classic and Kraft Ledger are light
        // palettes), so the system bars must be locked to light style too —
        // the auto-detecting overload follows the system dark setting and
        // would put light icons on the light bar for dark-system users.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(
                android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT
            ),
            navigationBarStyle = SystemBarStyle.light(
                android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT
            )
        )

        setContent {
            OrgUtilTheme(choice = themeController.current) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    OrgUtilApp(widgetIntent = activeIntent)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        activeIntent = intent
    }
}

@Composable
fun OrgUtilApp(widgetIntent: Intent? = null) {
    val navController = rememberNavController()
    val widgetRequest: AgendaWidgetOpenRequest? =
        androidx.compose.runtime.remember(widgetIntent) { widgetIntent?.toAgendaWidgetOpenRequest() }
    val navigateTo = widgetIntent?.getStringExtra("navigate_to")
    
    // Handle widget navigation
    LaunchedEffect(navigateTo, widgetRequest?.requestId) {
        if (widgetRequest != null) {
            navController.navigate("main") {
                popUpTo("main") { inclusive = false }
                launchSingleTop = true
            }
        } else if (navigateTo == "capture") {
            navController.navigate("capture")
        }
    }
    
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        // Pure shell: every inset is consumed exactly once further down
        // (MainScreen bottom bar / per-screen OrgTopBar). Padding here
        // would stack with them — the physical-device blank-space bug.
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { innerPadding ->
        OrgUtilNavigation(
            navController = navController,
            modifier = Modifier.padding(innerPadding),
            widgetRequest = widgetRequest
        )
    }
}

@Preview(showBackground = true)
@Composable
fun OrgUtilAppPreview() {
    OrgUtilTheme(choice = com.orgutil.data.datasource.ThemeChoice.CLASSIC) {
        OrgUtilApp()
    }
}
