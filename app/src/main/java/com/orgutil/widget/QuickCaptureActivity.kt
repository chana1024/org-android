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
import com.orgutil.ui.theme.OrgUtilTheme
import com.orgutil.ui.viewmodel.CaptureViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@AndroidEntryPoint
class QuickCaptureActivity : ComponentActivity() {
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        setContent {
            OrgUtilTheme {
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
    val uiState by viewModel.uiState.collectAsState()
    val scope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }

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
            Column {
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                    placeholder = {
                        Text("输入您的想法或待办事项...")
                    },
                    minLines = 3,
                    maxLines = 6,
                    shape = RoundedCornerShape(12.dp)
                )

                uiState.error?.let { error ->
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                uiState.successMessage?.let { message ->
                    Spacer(modifier = Modifier.height(8.dp))
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
                    if (inputText.isNotBlank()) {
                        scope.launch {
                            viewModel.addToCaptureFile(inputText.trim())
                            // 等待操作完成后再关闭
                            delay(1500) // 给用户看到成功消息的时间
                            if (uiState.successMessage != null) {
                                onCapture(inputText.trim())
                            }
                        }
                    }
                },
                enabled = inputText.isNotBlank() && !uiState.isLoading
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