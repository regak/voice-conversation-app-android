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
import androidx.compose.material.icons.filled.CheckCircle
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.core.content.ContextCompat
import com.regak.voiceorb.ui.theme.VoiceOrbTheme
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput

class MainActivity : ComponentActivity() {

    companion object {
        const val ACTION_OPEN_YOUTUBE = "com.regak.voiceorb.action.OPEN_YOUTUBE"
        const val EXTRA_VIDEO_URL = "video_url"
    }

    private val viewModel: VoiceSessionViewModel by viewModels()

    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val micGranted = results[Manifest.permission.RECORD_AUDIO] == true
        if (micGranted) {
            viewModel.start(BuildConfig.GEMINI_API_KEY)
        } else {
            viewModel.lastError.value = "Microphone permission denied"
        }
        // POST_NOTIFICATIONS denial is non-fatal -- the foreground service
        // (and its mic access) still works, the status notification just
        // won't be visible.
    }

    /** Driving mode's SMS side needs its own runtime permissions (a normal
     *  dialog, unlike WhatsApp's manual Settings toggle). Only flips the
     *  toggle on if the two that matter -- receive + send -- are granted;
     *  contacts is nice-to-have for name resolution, not required. */
    private val requestSmsPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val canReceive = results[Manifest.permission.RECEIVE_SMS] == true
        val canSend = results[Manifest.permission.SEND_SMS] == true
        if (canReceive && canSend) {
            VoiceSessionService.drivingMode.value = true
        } else {
            viewModel.lastError.value = "SMS permissions denied -- Driving mode needs them to read/reply to texts."
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installCrashHandler()
        val previousCrash = readAndClearLastCrash()

        // Restore the visible conversation immediately on cold app open --
        // VoiceSessionService may not even be running yet (it only starts
        // on "Start talking" or a Driving-mode notice), so loading here
        // too means history shows up right away instead of only after the
        // service happens to be created. Guarded by isEmpty() so this is a
        // harmless no-op if the service already loaded it first.
        if (VoiceSessionService.transcript.isEmpty()) {
            ConversationPersistence.loadTranscript(applicationContext).forEach { (isModel, text) ->
                VoiceSessionService.transcript.add(TranscriptLine(isModel).apply { this.text = text })
            }
        }

        setContent {
            VoiceOrbTheme {
                VoiceOrbScreen(
                    viewModel = viewModel,
                    onStartRequested = { startWithPermissionCheck() },
                    onStopRequested = { viewModel.stop() },
                    onRequestSmsPermissions = {
                        requestSmsPermissions.launch(
                            arrayOf(
                                Manifest.permission.RECEIVE_SMS,
                                Manifest.permission.SEND_SMS,
                                Manifest.permission.READ_SMS,
                                Manifest.permission.READ_CONTACTS,
                            ),
                        )
                    },
                )
            }
        }

        if (previousCrash != null) {
            // There is no ADB/logcat access to this device, so this is the
            // only way to see what the LAST run actually crashed with.
            viewModel.lastError.value = "Previous run crashed:\n$previousCrash"
            viewModel.orbState.value = OrbState.ERROR
        }

        handleIncomingIntent(intent)
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    /** Opens YouTube when asked to via [ACTION_OPEN_YOUTUBE] (sent by
     *  VoiceSessionService). This is a deliberate two-hop relay instead of
     *  the Service calling startActivity() directly on the YouTube intent:
     *  Android's Background Activity Launch restrictions (tightened on
     *  Android 10+, further on 12+) can silently block a background
     *  service from popping open another app's UI, which is exactly what
     *  caused "sometimes opens, sometimes doesn't" in testing. Launching
     *  your OWN app's activity from a service is always allowed, and once
     *  THIS activity is actively starting/resuming, it is itself a
     *  foreground context -- so the YouTube launch from here is reliably
     *  exempt from BAL restrictions. */
    private fun handleIncomingIntent(intent: android.content.Intent?) {
        if (intent?.action != ACTION_OPEN_YOUTUBE) return
        val videoUrl = intent.getStringExtra(EXTRA_VIDEO_URL) ?: return
        VoiceSessionService.debugLog.add("MainActivity.handleIncomingIntent: videoUrl=$videoUrl")
        try {
            val youtubeIntent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(videoUrl)).apply {
                setPackage("com.google.android.youtube")
            }
            val resolved = youtubeIntent.resolveActivity(packageManager)
            VoiceSessionService.debugLog.add("resolveActivity(youtube-package)=${resolved?.className ?: "null"}")
            if (resolved != null) {
                startActivity(youtubeIntent)
                VoiceSessionService.debugLog.add("startActivity(youtube-package intent) called")
            } else {
                startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(videoUrl)))
                VoiceSessionService.debugLog.add("startActivity(generic ACTION_VIEW, no package) called")
            }
        } catch (t: Throwable) {
            VoiceSessionService.debugLog.add("handleIncomingIntent FAILED: ${t.javaClass.simpleName}: ${t.message}")
            viewModel.lastError.value = "Failed to open YouTube: ${t.message}"
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
        val micGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        val notificationsGranted = android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

        if (micGranted && notificationsGranted) {
            viewModel.start(BuildConfig.GEMINI_API_KEY)
        } else {
            val toRequest = mutableListOf<String>()
            if (!micGranted) toRequest.add(Manifest.permission.RECORD_AUDIO)
            if (!notificationsGranted) toRequest.add(Manifest.permission.POST_NOTIFICATIONS)
            requestPermissions.launch(toRequest.toTypedArray())
        }
    }

    override fun onDestroy() {
        // Deliberately NOT calling viewModel.stop() here. The whole point of
        // the foreground service is that the voice session survives the
        // Activity being destroyed (e.g. Android reclaiming it under memory
        // pressure while the user is in another app) -- only an explicit
        // end action (the black button, or the notification's "End
        // conversation") should stop the session.
        super.onDestroy()
    }
}

@Composable
fun VoiceOrbScreen(
    viewModel: VoiceSessionViewModel,
    onStartRequested: () -> Unit,
    onStopRequested: () -> Unit,
    onRequestSmsPermissions: () -> Unit,
) {
    val orbState by viewModel.orbState
    val error by viewModel.lastError
    var showDebug by remember { mutableStateOf(false) }
    var isMuted by remember { mutableStateOf(false) }
    var showInfoBanner by remember { mutableStateOf(true) }
    var drawerOpen by remember { mutableStateOf(false) }

    val sessionActive = orbState != OrbState.IDLE && orbState != OrbState.ERROR
    val context = LocalContext.current
    val sessions = SessionManager.groupedForDisplay(SessionManager.loadAll(context))
    val currentSessionId = SessionManager.getCurrentId(context)

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val drawerScope = rememberCoroutineScope()

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                modifier = Modifier.width(300.dp),
                drawerContainerColor = Color.White,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = 60.dp, start = 16.dp, end = 16.dp, bottom = 16.dp),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("Conversations", color = Color.Black, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        IconButton(onClick = { drawerScope.launch { drawerState.close() } }) {
                            Icon(Icons.Filled.Close, contentDescription = "Close", tint = Color.Gray)
                        }
                    }
                    Divider(color = Color.LightGray)
                    LazyColumn {
                        sessions.forEach { group ->
                            item {
                                Text(group.label, color = Color.Gray, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(top = 12.dp, bottom = 8.dp))
                            }
                            group.sessions.forEach { session ->
                                item {
                                    SessionListItem(
                                        session = session,
                                        isCurrent = SessionManager.getCurrentId(context) == session.id,
                                        onClick = {
                                            switchToSession(context, session.id)
                                            drawerScope.launch { drawerState.close() }
                                        },
                                        onLongClick = {
                                            deleteSession(context, session.id)
                                        },
                                    )
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.padding(bottom = 24.dp))
                    // New chat button at bottom of drawer
                    OutlinedButton(
                        onClick = {
                            newSession(context)
                            drawerScope.launch { drawerState.close() }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        border = BorderStroke(1.dp, Color.LightGray),
                    ) {
                        Row(
                            modifier = Modifier.padding(vertical = 12.dp),
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            Icon(Icons.Filled.Add, contentDescription = null, tint = Color.Gray)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("New chat", color = Color.Gray, fontSize = 14.sp)
                        }
                    }
                }
            }
        },
        gesturesEnabled = true,
    ) {
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
                RoundIconButton(icon = Icons.Filled.Menu, onClick = { drawerScope.launch { drawerState.open() } })
                RoundIconButton(icon = Icons.Filled.Tune, onClick = { showDebug = !showDebug })
            }

            // Visible build marker -- lets us positively confirm which APK
            // is actually installed/running, after a prior bug where the
            // same versionCode across releases let a sideloaded update
            // silently fail to replace the old app on some devices.
            Text(
                text = "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                color = Color(0xFFC0C4CC),
                fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 20.dp),
            )

            // Driving mode: while on, new WhatsApp messages are announced
            // into the live conversation and can be replied to by voice.
            // Requires "Notification access" (a special, manually-granted
            // permission -- there is no runtime dialog for it), so the
            // toggle deep-links to system Settings the first time if it
            // isn't granted yet rather than silently doing nothing.
            val drivingMode by VoiceSessionService.drivingMode
            val context = androidx.compose.ui.platform.LocalContext.current
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text("Driving mode", color = Color.White, fontSize = 13.sp)
                    Text(
                        "Reads WhatsApp + SMS aloud, reply by voice",
                        color = Color(0xFF9AA0A6),
                        fontSize = 10.sp,
                    )
                }
                Switch(
                    checked = drivingMode,
                    onCheckedChange = { wantsOn ->
                        val smsGranted = ContextCompat.checkSelfPermission(
                            context, Manifest.permission.RECEIVE_SMS,
                        ) == PackageManager.PERMISSION_GRANTED && ContextCompat.checkSelfPermission(
                            context, Manifest.permission.SEND_SMS,
                        ) == PackageManager.PERMISSION_GRANTED
                        if (wantsOn && !WhatsAppNotificationListener.isEnabled(context)) {
                            viewModel.lastError.value =
                                "Grant 'Notification access' for Voice Orb in the screen " +
                                    "that just opened, then turn Driving mode on again."
                            context.startActivity(
                                android.content.Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS),
                            )
                        } else if (wantsOn && !smsGranted) {
                            onRequestSmsPermissions()
                        } else {
                            VoiceSessionService.drivingMode.value = wantsOn
                        }
                    },
                )
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

@Composable
private fun SessionListItem(
    session: SessionManager.Session,
    isCurrent: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .combinedClickableCompat(onClick = onClick, onLongClick = onLongClick),
        color = if (isCurrent) Color(0xFFE8F0FE) else Color.Transparent,
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = session.title,
                    color = Color.Black,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = if (isCurrent) FontWeight.Medium else FontWeight.Normal,
                    modifier = Modifier.weight(1f),
                )
                if (isCurrent) {
                    Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = Color(0xFF1A73E8), modifier = Modifier.size(18.dp))
                }
            }
            Text(
                text = formatRelativeTime(session.updatedAt),
                color = Color.Gray,
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/** Plain clickable + long-press without pulling in the separate
 *  `foundation` combinedClickable experimental API opt-in. */
private fun Modifier.combinedClickableCompat(onClick: () -> Unit, onLongClick: () -> Unit): Modifier =
    this.then(
        Modifier.pointerInput(Unit) {
            detectTapGestures(
                onTap = { onClick() },
                onLongPress = { onLongClick() },
            )
        },
    )

private fun formatRelativeTime(timestamp: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - timestamp
    val minutes = diff / (60 * 1000)
    val hours = diff / (60 * 60 * 1000)
    val days = diff / (24 * 60 * 60 * 1000)
    return when {
        minutes < 1 -> "Just now"
        minutes < 60 -> "$minutes min ago"
        hours < 24 -> "$hours hr ago"
        days < 7 -> "$days day${if (days > 1) "s" else ""} ago"
        else -> {
            val cal = java.util.Calendar.getInstance()
            cal.timeInMillis = timestamp
            "${cal.get(java.util.Calendar.MONTH) + 1}/${cal.get(java.util.Calendar.DAY_OF_MONTH)}"
        }
    }
}

private fun switchToSession(context: android.content.Context, sessionId: String) {
    SessionManager.setCurrentId(context, sessionId)
    val switchIntent = android.content.Intent(context, VoiceSessionService::class.java).apply {
        action = VoiceSessionService.ACTION_SWITCH_SESSION
        putExtra(VoiceSessionService.EXTRA_SESSION_ID, sessionId)
        putExtra(VoiceSessionService.EXTRA_API_KEY, BuildConfig.GEMINI_API_KEY)
    }
    androidx.core.content.ContextCompat.startForegroundService(context, switchIntent)
}

private fun newSession(context: android.content.Context) {
    val session = SessionManager.newSession(context)
    SessionManager.setCurrentId(context, session.id)
    val switchIntent = android.content.Intent(context, VoiceSessionService::class.java).apply {
        action = VoiceSessionService.ACTION_SWITCH_SESSION
        putExtra(VoiceSessionService.EXTRA_SESSION_ID, session.id)
        putExtra(VoiceSessionService.EXTRA_API_KEY, BuildConfig.GEMINI_API_KEY)
    }
    androidx.core.content.ContextCompat.startForegroundService(context, switchIntent)
}

private fun deleteSession(context: android.content.Context, sessionId: String) {
    val wasCurrent = SessionManager.getCurrentId(context) == sessionId
    SessionManager.deleteSession(context, sessionId)

    if (wasCurrent) {
        val nextId = SessionManager.getCurrentId(context)
        val switchIntent = android.content.Intent(context, VoiceSessionService::class.java).apply {
            action = VoiceSessionService.ACTION_SWITCH_SESSION
            putExtra(VoiceSessionService.EXTRA_SESSION_ID, nextId)
            putExtra(VoiceSessionService.EXTRA_API_KEY, BuildConfig.GEMINI_API_KEY)
        }
        androidx.core.content.ContextCompat.startForegroundService(context, switchIntent)
    }
}
