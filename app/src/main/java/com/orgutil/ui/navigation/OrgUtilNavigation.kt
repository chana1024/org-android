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
import com.orgutil.ui.screens.FavoritesScreen
import com.orgutil.ui.screens.FileEditorScreen
import com.orgutil.ui.screens.MainScreen
import java.nio.charset.StandardCharsets

@Composable
fun OrgUtilNavigation(
    navController: NavHostController,
    modifier: Modifier = Modifier
) {
    NavHost(
        navController = navController,
        startDestination = "main",
        modifier = modifier
    ) {
        composable("main") {
            MainScreen(
                onFileSelected = { fileUri, highlightOffset, highlightLength, highlightQuery ->
                    Log.d("OrgUtilNavigation", "File URI: $fileUri")
                    // 使用Base64编码，避免URL编码问题
                    val encodedFileUri = Base64.encodeToString(
                        fileUri.toString().toByteArray(StandardCharsets.UTF_8),
                        Base64.NO_WRAP
                    )
                    val encodedHighlightQuery = highlightQuery?.let {
                        Base64.encodeToString(
                            it.toByteArray(StandardCharsets.UTF_8),
                            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
                        )
                    } ?: "-"
                    Log.d("OrgUtilNavigation", "Base64 Encoded URI: $encodedFileUri")
                    navController.navigate(
                        "file_editor/$encodedFileUri?highlightOffset=${highlightOffset ?: -1}&highlightLength=${highlightLength ?: -1}&highlightQuery=$encodedHighlightQuery"
                    )
                },
                onNavigateToCapture = {
                    navController.navigate("capture")
                }
            )
        }
        
        composable(
            route = "file_editor/{fileUri}?highlightOffset={highlightOffset}&highlightLength={highlightLength}&highlightQuery={highlightQuery}",
            arguments = listOf(
                navArgument("fileUri") { type = NavType.StringType },
                navArgument("highlightOffset") {
                    type = NavType.IntType
                    defaultValue = -1
                },
                navArgument("highlightLength") {
                    type = NavType.IntType
                    defaultValue = -1
                },
                navArgument("highlightQuery") {
                    type = NavType.StringType
                    defaultValue = "-"
                }
            )
        ) { backStackEntry ->
            val encodedFileUriString = backStackEntry.arguments?.getString("fileUri")
            val fileUriString = encodedFileUriString?.let{
                String(Base64.decode(it, Base64.NO_WRAP), StandardCharsets.UTF_8)
            }
            val highlightOffset = backStackEntry.arguments?.getInt("highlightOffset")
                ?.takeIf { it >= 0 }
            val highlightLength = backStackEntry.arguments?.getInt("highlightLength")
                ?.takeIf { it > 0 }
            val highlightQuery = backStackEntry.arguments?.getString("highlightQuery")
                ?.takeIf { it != "-" }
                ?.let { encoded ->
                    String(Base64.decode(encoded, Base64.URL_SAFE or Base64.NO_WRAP), StandardCharsets.UTF_8)
                }
            Log.d("OrgUtilNavigation", "Base64 Decoded URI: $fileUriString")
            FileEditorScreen(
                fileUriString = fileUriString,
                highlightOffset = highlightOffset,
                highlightLength = highlightLength,
                highlightQuery = highlightQuery,
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
