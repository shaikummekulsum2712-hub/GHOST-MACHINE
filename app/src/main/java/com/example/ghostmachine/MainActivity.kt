package com.example.ghostmachine

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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

    private val callingPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val contactsGranted = result[Manifest.permission.READ_CONTACTS] == true
            val callGranted = result[Manifest.permission.CALL_PHONE] == true

            val message = when {
                contactsGranted && callGranted -> "Calling permissions granted"
                contactsGranted -> "Contacts granted. Direct calling permission is still needed."
                callGranted -> "Calling granted. Contact lookup permission is still needed."
                else -> "Calling permissions were not granted"
            }
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            var backendUrl by remember { mutableStateOf(BackendConfig.baseUrl(this)) }
            var backendStatus by remember { mutableStateOf("") }

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
                                    "2. Grant calling permissions if you want direct contact calls.\n" +
                                    "3. Enable Accessibility Service.\n" +
                                    "4. Go to any app/home screen.\n" +
                                    "5. Tap the floating 👻 button and speak your command.",
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
                                callingPermissionLauncher.launch(
                                    arrayOf(
                                        Manifest.permission.READ_CONTACTS,
                                        Manifest.permission.CALL_PHONE
                                    )
                                )
                            }
                        ) {
                            Text("Grant Calling Permissions")
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

                        Text("Backend address")
                        OutlinedTextField(
                            value = backendUrl,
                            onValueChange = { backendUrl = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text("http://laptop-ip:8000") }
                        )

                        Button(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                backendStatus = if (BackendConfig.saveBaseUrl(this@MainActivity, backendUrl)) {
                                    "Saved. Use this address while the backend is running."
                                } else {
                                    "Use a complete URL with http:// or https:// and a port."
                                }
                            }
                        ) {
                            Text("Save Backend Address")
                        }

                        if (backendStatus.isNotBlank()) {
                            Text(backendStatus)
                        }

                        Spacer(modifier = Modifier.height(16.dp))
                        Text("Backend command:")
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
