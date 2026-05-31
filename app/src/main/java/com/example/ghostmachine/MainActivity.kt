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
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
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
                    onTestTap = {
                        Toast.makeText(
                            this,
                            "Tap will happen in 5 seconds. Open Calculator now.",
                            Toast.LENGTH_LONG
                        ).show()

                        Handler(Looper.getMainLooper()).postDelayed({
                            val success = GhostAccessibilityService.tap(500f, 800f)

                            if (success) {
                                Toast.makeText(this, "Tap command sent", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(
                                    this,
                                    "Enable Accessibility Service first",
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
    onTestTap: () -> Unit
) {
    var status by remember { mutableStateOf("Phase 1: Fixed tap test") }

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

        Button(
            onClick = {
                status = "Open settings and enable Ghost Machine service"
                onOpenAccessibility()
            }
        ) {
            Text("Open Accessibility Settings")
        }

        Button(
            modifier = Modifier.padding(top = 16.dp),
            onClick = {
                status = "Tap will happen in 5 seconds"
                onTestTap()
            }
        ) {
            Text("Test Tap")
        }
    }
}