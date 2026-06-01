package com.example.ghostmachine

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.ghostmachine.ui.theme.GHOSTMACHINETheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            GHOSTMACHINETheme {
                GhostMachineScreen(
                    onOpenAccessibility = {
                        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        startActivity(intent)
                    },
                    onExecuteAction = { actionJson ->
                        Toast.makeText(
                            this,
                            "Action will execute in 5 seconds. Switch to Calculator now.",
                            Toast.LENGTH_LONG
                        ).show()

                        Handler(Looper.getMainLooper()).postDelayed({
                            val success = GhostAccessibilityService.executeAction(actionJson)

                            if (success) {
                                Toast.makeText(this, "Action executed successfully", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(
                                    this,
                                    "Execution failed. Enable Accessibility Service first.",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }, 5000)
                    }
                )
            }
        }
    }
}

@Composable
fun GhostMachineScreen(
    onOpenAccessibility: () -> Unit,
    onExecuteAction: (String) -> Unit
) {
    var status by remember { mutableStateOf("Phase 2: Dynamic JSON actions") }
    var jsonText by remember { mutableStateOf("{\n  \"action\": \"tap\",\n  \"x\": 470,\n  \"y\": 2100\n}") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Ghost Machine",
            style = MaterialTheme.typography.headlineMedium
        )

        Text(
            text = status,
            modifier = Modifier.padding(top = 12.dp, bottom = 24.dp)
        )

        OutlinedTextField(
            value = jsonText,
            onValueChange = { jsonText = it },
            label = { Text("Action JSON") },
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp),
            maxLines = 6
        )

        Button(
            onClick = {
                status = "Open settings and enable Ghost Machine service"
                onOpenAccessibility()
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Open Accessibility Settings")
        }

        Button(
            modifier = Modifier
                .padding(top = 16.dp)
                .fillMaxWidth(),
            onClick = {
                status = "Executing action in 5 seconds..."
                onExecuteAction(jsonText)
            }
        ) {
            Text("Execute Action")
        }
    }
}