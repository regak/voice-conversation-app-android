package com.regak.voiceorb

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.regak.voiceorb.ui.theme.VoiceOrbTheme

class MainActivity : ComponentActivity() {

    private val viewModel: VoiceSessionViewModel by viewModels()

    private val requestMicPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) viewModel.start(BuildConfig.GEMINI_API_KEY)
        else viewModel.lastError.value = "Microphone permission denied"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installCrashHandler()
        val previousCrash = readAndClearLastCrash()

        setContent {
            VoiceOrbTheme {
                VoiceOrbScreen(
                    viewModel = viewModel,
                    onStartRequested = { startWithPermissionCheck() },
                    onStopRequested = { viewModel.stop() },
                )
            }
        }

        if (previousCrash != null) {
            // There is no ADB/logcat access to this device, so this is the
            // only way to see what the LAST run actually crashed with.
            viewModel.lastError.value = "Previous run crashed:\n$previousCrash"
            viewModel.orbState.value = OrbState.ERROR
        }
    }

    /** Writes any uncaught exception to a file so it survives the process death
     *  and can be shown on the NEXT app launch -- there is no ADB/logcat
     *  access to this test device. */
    private fun installCrashHandler() {
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sw = java.io.StringWriter()
                throwable.printStackTrace(java.io.PrintWriter(sw))
                java.io.File(filesDir, "last_crash.txt").writeText(sw.toString())
            } catch (_: Throwable) {
                // best effort -- never let the crash handler itself crash
            }
            previousHandler?.uncaughtException(thread, throwable)
        }
    }

    private fun readAndClearLastCrash(): String? {
        val f = java.io.File(filesDir, "last_crash.txt")
        if (!f.exists()) return null
        val text = runCatching { f.readText() }.getOrNull()
        f.delete()
        return text
    }

    private fun startWithPermissionCheck() {
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            viewModel.start(BuildConfig.GEMINI_API_KEY)
        } else {
            requestMicPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    override fun onDestroy() {
        viewModel.stop()
        super.onDestroy()
    }
}

@Composable
fun VoiceOrbScreen(
    viewModel: VoiceSessionViewModel,
    onStartRequested: () -> Unit,
    onStopRequested: () -> Unit,
) {
    val orbState by viewModel.orbState
    val error by viewModel.lastError
    var showDebug by remember { mutableStateOf(false) }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "Voice Orb",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
            )

            Orb(state = orbState, modifier = Modifier.size(160.dp))

            Spacer(Modifier.height(16.dp))
            Text(text = orbState.name, fontWeight = FontWeight.Medium)

            error?.let {
                Spacer(Modifier.height(8.dp))
                Text(text = it, color = MaterialTheme.colorScheme.error)
            }

            Spacer(Modifier.height(24.dp))

            Row {
                Button(onClick = {
                    if (orbState == OrbState.IDLE || orbState == OrbState.ERROR) {
                        onStartRequested()
                    } else {
                        onStopRequested()
                    }
                }) {
                    Icon(
                        imageVector = if (orbState == OrbState.IDLE || orbState == OrbState.ERROR)
                            Icons.Filled.Mic else Icons.Filled.MicOff,
                        contentDescription = null,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (orbState == OrbState.IDLE || orbState == OrbState.ERROR)
                            "Start talking" else "Stop"
                    )
                }
                Spacer(Modifier.width(12.dp))
                OutlinedButton(onClick = { showDebug = !showDebug }) {
                    Text(if (showDebug) "Hide debug" else "Debug log")
                }
            }

            Spacer(Modifier.height(16.dp))
            Text("Transcript", fontWeight = FontWeight.Medium)
            LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                items(viewModel.transcript) { line ->
                    Text(
                        text = (if (line.isModel) "Model: " else "You: ") + line.text,
                        color = if (line.isModel) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(vertical = 2.dp),
                    )
                }
            }

            if (showDebug) {
                Divider(Modifier.padding(vertical = 8.dp))
                Text("Debug (last server/audio events)", fontWeight = FontWeight.Medium)
                LazyColumn(modifier = Modifier.heightIn(max = 180.dp).fillMaxWidth()) {
                    items(viewModel.debugLog) { line ->
                        Text(text = line, fontSize = 11.sp, modifier = Modifier.padding(vertical = 1.dp))
                    }
                }
            }
        }
    }
}

@Composable
fun Orb(state: OrbState, modifier: Modifier = Modifier) {
    val infinite = rememberInfiniteTransition(label = "orb")
    val pulse by infinite.animateFloat(
        initialValue = 0.9f,
        targetValue = if (state == OrbState.SPEAKING) 1.15f
        else if (state == OrbState.LISTENING) 1.05f else 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 700),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse",
    )
    val color = when (state) {
        OrbState.IDLE -> Color(0xFF7C4DFF)
        OrbState.CONNECTING -> Color(0xFFFFC107)
        OrbState.LISTENING -> Color(0xFF00E5FF)
        OrbState.SPEAKING -> Color(0xFF00E676)
        OrbState.ERROR -> Color(0xFFFF5252)
    }
    Box(
        modifier = modifier
            .scale(pulse)
            .background(color = color, shape = CircleShape),
    )
}
