package com.example.transcritorsemantico

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.transcritorsemantico.ui.MemoryWaveApp
import com.example.transcritorsemantico.ui.MainViewModel
import com.example.transcritorsemantico.ui.theme.TranscritorSemanticoTheme

class MainActivity : ComponentActivity() {

    private var micGranted by mutableStateOf(false)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        micGranted = granted
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        micGranted = checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

        enableEdgeToEdge()
        setContent {
            val viewModel: MainViewModel = viewModel()
            TranscritorSemanticoTheme {
                MemoryWaveApp(
                    viewModel = viewModel,
                    micGranted = micGranted,
                    requestMicPermission = {
                        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    },
                )
            }
        }
    }
}
