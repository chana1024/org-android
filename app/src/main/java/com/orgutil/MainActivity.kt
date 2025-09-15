package com.orgutil

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.navigation.compose.rememberNavController
import com.orgutil.ui.navigation.OrgUtilNavigation
import com.orgutil.ui.theme.OrgUtilTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        val navigateTo = intent.getStringExtra("navigate_to")
        
        setContent {
            OrgUtilTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    OrgUtilApp(navigateTo = navigateTo)
                }
            }
        }
    }
}

@Composable
fun OrgUtilApp(navigateTo: String? = null) {
    val navController = rememberNavController()
    
    // Handle widget navigation
    LaunchedEffect(navigateTo) {
        if (navigateTo == "capture") {
            navController.navigate("capture")
        }
    }
    
    Scaffold(
        modifier = Modifier.fillMaxSize()
    ) { innerPadding ->
        OrgUtilNavigation(
            navController = navController,
            modifier = Modifier.padding(innerPadding)
        )
    }
}

@Preview(showBackground = true)
@Composable
fun OrgUtilAppPreview() {
    OrgUtilTheme {
        OrgUtilApp()
    }
}