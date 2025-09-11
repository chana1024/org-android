package com.orgutil.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.orgutil.ui.screens.FileEditorScreen
import com.orgutil.ui.screens.FileListScreen
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

@Composable
fun OrgUtilNavigation(
    navController: NavHostController,
    modifier: Modifier = Modifier
) {
    NavHost(
        navController = navController,
        startDestination = "file_list",
        modifier = modifier
    ) {
        composable("file_list") {
            FileListScreen(
                onFileSelected = { fileUri ->
                    val encodedUri = URLEncoder.encode(fileUri.toString(), StandardCharsets.UTF_8.toString())
                    navController.navigate("file_editor/$encodedUri")
                }
            )
        }
        
        composable("file_editor/{fileUri}") { backStackEntry ->
            val fileUriString = backStackEntry.arguments?.getString("fileUri")
            FileEditorScreen(
                fileUriString = fileUriString,
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }
    }
}