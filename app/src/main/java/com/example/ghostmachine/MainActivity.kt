package com.example.ghostmachine

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.json.JSONObject
import java.util.Locale

class MainActivity : ComponentActivity() {

    private var commandText by mutableStateOf("")
    private var statusText by mutableStateOf("Ready")
    private var lastJson by mutableStateOf("")
    private var isLoading by mutableStateOf(false)

    private val speechLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val spokenText = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()

            if (!spokenText.isNullOrBlank()) {
                commandText = spokenText
            }
        }

    private val audioPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startVoiceInput()
            } else {
                Toast.makeText(this, "Mic permission denied", Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    GhostMachineScreen()
                }
            }
        }
    }

    @Composable
    private fun GhostMachineScreen() {
        val scrollState = rememberScrollState()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(18.dp)
                .verticalScroll(scrollState)
        ) {
            Text(
                text = "👻 Ghost Machine",
                style = MaterialTheme.typography.headlineMedium
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "Enter a command or use voice. Backend will analyze current screen and return one action.",
                style = MaterialTheme.typography.bodyMedium
            )

            Spacer(modifier = Modifier.height(18.dp))

            OutlinedTextField(
                value = commandText,
                onValueChange = { commandText = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Command") },
                placeholder = { Text("Example: check previous conversation") },
                minLines = 2
            )

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    modifier = Modifier.weight(1f),
                    enabled = !isLoading,
                    onClick = {
                        if (commandText.isBlank()) {
                            Toast.makeText(this@MainActivity, "Enter command first", Toast.LENGTH_SHORT).show()
                        } else {
                            runGhostCommand(commandText.trim())
                        }
                    }
                ) {
                    Text(if (isLoading) "Running..." else "Run Ghost")
                }

                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    enabled = !isLoading,
                    onClick = {
                        audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                ) {
                    Text("Voice")
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            OutlinedButton(
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    startActivity(intent)
                }
            ) {
                Text("Open Accessibility Settings")
            }

            Spacer(modifier = Modifier.height(22.dp))

            Text(
                text = "Status",
                style = MaterialTheme.typography.titleMedium
            )

            Spacer(modifier = Modifier.height(6.dp))

            Text(statusText)

            Spacer(modifier = Modifier.height(22.dp))

            Text(
                text = "Last Backend JSON",
                style = MaterialTheme.typography.titleMedium
            )

            Spacer(modifier = Modifier.height(6.dp))

            Text(lastJson.ifBlank { "-" })
        }
    }

    private fun startVoiceInput() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Tell Ghost what to do")
        }

        speechLauncher.launch(intent)
    }

    private fun runGhostCommand(command: String) {
        isLoading = true
        statusText = "Checking accessibility service..."
        lastJson = ""

        Thread {
            val serviceEnabled = GhostAccessibilityService.instance != null

            if (!serviceEnabled) {
                updateUi(
                    loading = false,
                    status = "Accessibility service is not enabled. Open settings and enable Ghost Machine.",
                    json = ""
                )
                return@Thread
            }

            updateUi(
                loading = true,
                status = "Capturing screenshot...",
                json = ""
            )

            val screenshotBytes = GhostAccessibilityService.captureScreenJpegBytes()

            if (screenshotBytes == null) {
                updateUi(
                    loading = false,
                    status = "Screenshot capture failed. Need Android 11+ and Accessibility permission.",
                    json = ""
                )
                return@Thread
            }

            updateUi(
                loading = true,
                status = "Sending screenshot to backend...",
                json = ""
            )

            val responseJson = ApiClient.analyzeScreen(command, screenshotBytes)

            if (responseJson == null) {
                updateUi(
                    loading = false,
                    status = "Backend failed. Check backend server and ADB reverse.",
                    json = ""
                )
                return@Thread
            }

            updateUi(
                loading = true,
                status = "Backend responded. Executing action...",
                json = responseJson
            )

            try {
                val obj = JSONObject(responseJson)
                val action = obj.optString("action", "unknown")
                val reason = obj.optString("reason", "No reason")
                val confidence = obj.optDouble("confidence", 0.0)

                if (action == "ask_user") {
                    updateUi(
                        loading = false,
                        status = "Need user help: $reason",
                        json = responseJson
                    )
                    return@Thread
                }

                if (action == "done") {
                    updateUi(
                        loading = false,
                        status = "Done: $reason",
                        json = responseJson
                    )
                    return@Thread
                }

                Thread.sleep(1200)

                val success = GhostAccessibilityService.executeAction(responseJson)

                updateUi(
                    loading = false,
                    status = if (success) {
                        "Executed: $action | confidence: $confidence | reason: $reason"
                    } else {
                        "Action failed: $action | reason: $reason"
                    },
                    json = responseJson
                )

            } catch (e: Exception) {
                updateUi(
                    loading = false,
                    status = "Invalid backend response: ${e.message}",
                    json = responseJson
                )
            }
        }.start()
    }

    private fun updateUi(
        loading: Boolean,
        status: String,
        json: String
    ) {
        Handler(Looper.getMainLooper()).post {
            isLoading = loading
            statusText = status
            lastJson = json
        }
    }
}