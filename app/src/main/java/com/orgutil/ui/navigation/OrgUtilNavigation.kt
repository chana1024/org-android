package com.orgutil.ui.navigation

import android.util.Base64
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.orgutil.ui.screens.CaptureScreen
import com.orgutil.ui.screens.FileEditorScreen
import com.orgutil.ui.screens.FileListScreen
import java.net.URLDecoder
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
                    Log.d("OrgUtilNavigation", "File URI: $fileUri")
                    // 使用Base64编码，避免URL编码问题
                    val encodedFileUri = Base64.encodeToString(
                        fileUri.toString().toByteArray(StandardCharsets.UTF_8), 
                        Base64.NO_WRAP
                    )
                    Log.d("OrgUtilNavigation", "Base64 Encoded URI: $encodedFileUri")
                    navController.navigate("file_editor/$encodedFileUri")
                },
                onNavigateToCapture = {
                    navController.navigate("capture")
                }
            )
        }
        
        composable(route="file_editor/{fileUri}",
        arguments=listOf(navArgument("fileUri"){type= NavType.StringType})) { backStackEntry ->
            val encodedFileUriString = backStackEntry.arguments?.getString("fileUri")
            val fileUriString = encodedFileUriString?.let{
                String(Base64.decode(it, Base64.NO_WRAP), StandardCharsets.UTF_8)
            }
            Log.d("OrgUtilNavigation", "Base64 Decoded URI: $fileUriString")
            FileEditorScreen(
                fileUriString = fileUriString,
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }
        
        composable("capture") {
            CaptureScreen(
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }
    }
}