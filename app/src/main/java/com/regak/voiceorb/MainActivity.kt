package com.regak.voiceorb

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
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
    var isMuted by remember { mutableStateOf(false) }
    var showInfoBanner by remember { mutableStateOf(true) }

    val sessionActive = orbState != OrbState.IDLE && orbState != OrbState.ERROR

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFF3F4F6)),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Top bar: two circular icon buttons, ChatGPT voice-mode style.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                RoundIconButton(icon = Icons.Filled.Menu, onClick = { /* no-op: no nav drawer yet */ })
                RoundIconButton(icon = Icons.Filled.Tune, onClick = { showDebug = !showDebug })
            }

            // Full scrollable conversation history, ChatGPT-conversation-mode
            // style (both sides retained, not just the last exchange).
            val listState = rememberLazyListState()
            LaunchedEffect(viewModel.transcript.size) {
                if (viewModel.transcript.isNotEmpty()) {
                    listState.animateScrollToItem(viewModel.transcript.size - 1)
                }
            }
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 20.dp),
            ) {
                items(viewModel.transcript) { line ->
                    val isLastModelLine = line.isModel &&
                        viewModel.transcript.lastOrNull { it.isModel } === line
                    if (line.isModel) {
                        Column(modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)) {
                            Text(text = line.text, color = Color(0xFF1A1A1A), fontSize = 16.sp)
                            if (isLastModelLine) {
                                Row(
                                    modifier = Modifier.padding(top = 8.dp),
                                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                                ) {
                                    Icon(Icons.Filled.ContentCopy, contentDescription = "Copy", tint = Color(0xFF8A8F98), modifier = Modifier.size(18.dp))
                                    Icon(Icons.Filled.ThumbUp, contentDescription = "Good response", tint = Color(0xFF8A8F98), modifier = Modifier.size(18.dp))
                                    Icon(Icons.Filled.ThumbDown, contentDescription = "Bad response", tint = Color(0xFF8A8F98), modifier = Modifier.size(18.dp))
                                    Icon(Icons.Filled.Share, contentDescription = "Share", tint = Color(0xFF8A8F98), modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp, bottom = 4.dp),
                            contentAlignment = Alignment.CenterEnd,
                        ) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(18.dp))
                                    .background(Color(0xFFDCE7FB))
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                            ) {
                                Text(text = line.text, color = Color(0xFF1A1A1A), fontSize = 16.sp)
                            }
                        }
                    }
                }
            }

            // Centerpiece orb + status text.
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Orb(
                    state = orbState,
                    modifier = Modifier
                        .size(180.dp)
                        .clickable {
                            if (!sessionActive) onStartRequested()
                        },
                )
            }

            Spacer(Modifier.height(12.dp))
            Text(
                text = statusText(orbState, error),
                color = Color(0xFF8A8F98),
                fontSize = 15.sp,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
            Spacer(Modifier.height(16.dp))

            // Dismissible info banner -- shows the real error (via the crash
            // capture / debug plumbing) instead of ChatGPT's static copy.
            AnimatedVisibility(visible = error != null && showInfoBanner, enter = fadeIn(), exit = fadeOut()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.White)
                        .padding(16.dp),
                ) {
                    Icon(Icons.Filled.Info, contentDescription = null, tint = Color(0xFF8A8F98))
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Something went wrong", fontWeight = FontWeight.Medium, fontSize = 14.sp)
                        Text(
                            text = error.orEmpty(),
                            color = Color(0xFF6B7280),
                            fontSize = 13.sp,
                            maxLines = 6,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "Dismiss",
                        tint = Color(0xFF8A8F98),
                        modifier = Modifier
                            .size(18.dp)
                            .clickable { showInfoBanner = false },
                    )
                }
            }

            // Bottom pill input bar (decorative text field -- this is a
            // voice-only app, there's no text chat backend) + mute + end/start.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(28.dp))
                        .background(Color.White)
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null, tint = Color(0xFF8A8F98))
                    Spacer(Modifier.width(12.dp))
                    Text("Ask Voice Orb", color = Color(0xFFADB1B8), fontSize = 16.sp)
                }

                Spacer(Modifier.width(10.dp))

                RoundIconButton(
                    icon = if (isMuted) Icons.Filled.MicOff else Icons.Filled.Mic,
                    onClick = { isMuted = !isMuted; viewModel.setMuted(isMuted) },
                    background = Color.White,
                )

                Spacer(Modifier.width(10.dp))

                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(Color.Black)
                        .clickable {
                            if (sessionActive) onStopRequested() else onStartRequested()
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = if (sessionActive) Icons.Filled.Close else Icons.Filled.Mic,
                        contentDescription = if (sessionActive) "End conversation" else "Start conversation",
                        tint = Color.White,
                    )
                }
            }
        }

        // Debug panel as a slide-up overlay, toggled by the top-right sliders
        // icon -- kept for troubleshooting, hidden by default to match the
        // clean ChatGPT-style surface.
        AnimatedVisibility(
            visible = showDebug,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 260.dp)
                    .background(Color(0xFF1C1C1E))
                    .padding(12.dp),
            ) {
                Text("Debug log", color = Color.White, fontWeight = FontWeight.Medium, fontSize = 13.sp)
                Spacer(Modifier.height(6.dp))
                LazyColumn {
                    items(viewModel.debugLog) { line ->
                        Text(
                            text = line,
                            color = Color(0xFF9FE870),
                            fontSize = 11.sp,
                            modifier = Modifier.padding(vertical = 1.dp),
                        )
                    }
                }
            }
        }
    }
}

private fun statusText(state: OrbState, error: String?): String = when (state) {
    OrbState.IDLE -> "Tap the orb to start talking"
    OrbState.CONNECTING -> "Connecting..."
    OrbState.LISTENING -> "Listening..."
    OrbState.SPEAKING -> "Speaking..."
    OrbState.ERROR -> if (error != null) "Error" else "Something went wrong"
}

@Composable
private fun RoundIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    background: Color = Color.White,
) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(background)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = Color(0xFF1A1A1A))
    }
}

/** ChatGPT-voice-mode-inspired gradient orb: a soft cloud-like blue gradient
 *  that breathes gently when idle/listening and pulses more when speaking. */
@Composable
fun Orb(state: OrbState, modifier: Modifier = Modifier) {
    val infinite = rememberInfiniteTransition(label = "orb")
    val pulse by infinite.animateFloat(
        initialValue = 0.94f,
        targetValue = when (state) {
            OrbState.SPEAKING -> 1.12f
            OrbState.LISTENING -> 1.04f
            OrbState.CONNECTING -> 1.02f
            else -> 1.0f
        },
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = if (state == OrbState.SPEAKING) 500 else 1400, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse",
    )
    val gradientColors = when (state) {
        OrbState.ERROR -> listOf(Color(0xFFE53935), Color(0xFFFFCDD2), Color(0xFFFFFFFF))
        OrbState.CONNECTING -> listOf(Color(0xFF90A4AE), Color(0xFFCFD8DC), Color(0xFFFFFFFF))
        OrbState.SPEAKING -> listOf(Color(0xFF2962FF), Color(0xFF82B1FF), Color(0xFFE3F2FD))
        OrbState.LISTENING -> listOf(Color(0xFF1E88E5), Color(0xFF64B5F6), Color(0xFFE8F0FE))
        OrbState.IDLE -> listOf(Color(0xFF3B5FE0), Color(0xFF9DB9F5), Color(0xFFEFF3FD))
    }
    Box(
        modifier = modifier
            .scale(pulse)
            .clip(CircleShape)
            .background(
                brush = Brush.verticalGradient(gradientColors),
            ),
    )
}
