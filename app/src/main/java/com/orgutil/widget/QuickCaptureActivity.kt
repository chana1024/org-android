package com.orgutil.widget

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import com.orgutil.ui.theme.LocalExtendedColors
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.orgutil.ui.screens.CaptureOptionsDraft
import com.orgutil.ui.screens.CaptureOptionsPanel
import com.orgutil.ui.screens.buildOptions
import com.orgutil.ui.screens.draftError
import com.orgutil.ui.theme.OrgUtilTheme
import com.orgutil.ui.theme.ThemeController
import com.orgutil.ui.viewmodel.CaptureViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import javax.inject.Inject

@AndroidEntryPoint
class QuickCaptureActivity : ComponentActivity() {

    // Widget-launched dialog follows the same persisted palette as the app
    // and the home-screen widgets.
    @Inject lateinit var themeController: ThemeController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            OrgUtilTheme(choice = themeController.current) {
                QuickCaptureDialog(
                    onDismiss = { finish() },
                    onCapture = { content ->
                        // Handle capture and finish
                        finish()
                    }
                )
            }
        }
    }
    
    companion object {
        fun createIntent(context: Context): PendingIntent {
            val intent = Intent(context, QuickCaptureActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
            return PendingIntent.getActivity(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickCaptureDialog(
    onDismiss: () -> Unit,
    onCapture: (String) -> Unit,
    viewModel: CaptureViewModel = hiltViewModel()
) {
    var inputText by remember { mutableStateOf("") }
    var optionsDraft by remember { mutableStateOf(CaptureOptionsDraft()) }
    val uiState by viewModel.uiState.collectAsState()
    val focusRequester = remember { FocusRequester() }

    // The draft defaults to the plain capture of old; the panel below owns
    // the optional TODO state / SCHEDULED / habit decorations.
    val builtOptions = optionsDraft.buildOptions()
    val optionsError = optionsDraft.draftError(inputText)
    val canSave = inputText.isNotBlank() && !uiState.isLoading &&
        builtOptions != null && optionsError == null

    LaunchedEffect(Unit) {
        delay(100)
        focusRequester.requestFocus()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "快速记录",
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            // Scrollable so the input + option rows + status all fit above the
            // IME and at large font scales, in both the classic and Kraft themes.
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                    placeholder = {
                        Text("输入您的想法或待办事项...")
                    },
                    minLines = 2,
                    maxLines = 5,
                    shape = RoundedCornerShape(12.dp)
                )

                CaptureOptionsPanel(
                    draft = optionsDraft,
                    onDraftChange = { optionsDraft = it },
                    inputText = inputText
                )

                uiState.error?.let { error ->
                    Text(
                        text = error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                uiState.successMessage?.let { message ->
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = LocalExtendedColors.current.success,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val options = builtOptions ?: return@Button
                    val content = inputText.trim()
                    if (content.isNotBlank()) {
                        // Close on the ACTUAL save outcome — never a timer or
                        // a stale snapshot; a failed write keeps the draft.
                        viewModel.addToCaptureFile(content, options) { saved ->
                            if (saved) onCapture(content)
                        }
                    }
                },
                enabled = canSave
            ) {
                if (uiState.isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    Text("记录")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}