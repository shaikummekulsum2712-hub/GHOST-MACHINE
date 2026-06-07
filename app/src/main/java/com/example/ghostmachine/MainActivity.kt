package com.example.ghostmachine

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {

    private val micPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                Toast.makeText(this, "Mic permission granted", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Mic permission is needed for voice commands", Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(start = 20.dp, end = 20.dp, top = 60.dp, bottom = 20.dp)
                    ) {
                        Text(
                            text = "👻 Ghost Machine",
                            style = MaterialTheme.typography.headlineMedium
                        )

                        Spacer(modifier = Modifier.height(24.dp))

                        Text(
                            text = "1. Grant mic permission.\n" +
                                    "2. Enable Accessibility Service.\n" +
                                    "3. Go to any app/home screen.\n" +
                                    "4. Tap floating 👻 button and speak your command.",
                            style = MaterialTheme.typography.bodyLarge
                        )

                        Spacer(modifier = Modifier.height(28.dp))

                        Button(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        ) {
                            Text("Grant Mic Permission")
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Button(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                            }
                        ) {
                            Text("Open Accessibility Settings")
                        }

                        Spacer(modifier = Modifier.height(28.dp))

                        Text("Backend:")
                        Text("python -m uvicorn main:app --host 0.0.0.0 --port 8000 --reload")

                        Spacer(modifier = Modifier.height(12.dp))

                        Text("ADB reverse:")
                        Text("adb reverse tcp:8000 tcp:8000")
                    }
                }
            }
        }
    }
}