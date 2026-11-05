package com.regak.voiceorb

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * In-app YouTube player (Option C): a WebView running the YouTube IFrame
 * Player API, instead of handing off to the external YouTube app. Because
 * this lives entirely inside our own UI, there is no cross-app screen
 * switch involved -- Android's background-activity-launch restriction
 * (the thing that caused the external-app approach to sometimes silently
 * fail to switch screens) simply does not apply here, and pause/resume/
 * close/seek are real, reliable JS calls into our own WebView instead of
 * app-launch guesswork.
 *
 * NOTE on embedding errors: YouTube lets each video's owner disable
 * embedding entirely (common for official label/festival/VEVO content).
 * That restriction is enforced by YouTube itself and cannot legitimately
 * be bypassed by any app. searchYoutube() in VoiceSessionService already
 * filters these out up front via the Data API's status.embeddable field,
 * but as defense-in-depth this player also listens for the IFrame API's
 * own onError event via a JS bridge, and shows a friendly fallback with a
 * button to open the real YouTube app for that one video instead.
 */
@Composable
fun EmbeddedYoutubePlayer(
    video: YoutubeVideo,
    command: PlayerCommand?,
    error: String?,
    onCommandConsumed: () -> Unit,
    onError: (String?) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    Box(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Color.Black),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = video.title,
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "Close player",
                    tint = Color.White,
                    modifier = Modifier
                        .size(20.dp)
                        .clickable { onClose() },
                )
            }

            if (error != null) {
                // Fallback when this specific video can't be embedded (or
                // any other player-side error) -- the one case where we
                // honestly can't give in-app control, so offer the real
                // YouTube app instead rather than showing a dead black box.
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                        .padding(16.dp),
                ) {
                    Text(
                        text = "This video can't be played in-app ($error). " +
                            "Its owner has disabled embedding.",
                        color = Color.White,
                        fontSize = 13.sp,
                    )
                    Button(
                        onClick = {
                            val url = "https://www.youtube.com/watch?v=${video.videoId}"
                            val ytIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                                setPackage("com.google.android.youtube")
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            runCatching { context.startActivity(ytIntent) }.onFailure {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    },
                                )
                            }
                        },
                        modifier = Modifier.padding(top = 12.dp),
                    ) {
                        Icon(Icons.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text(" Open in YouTube app", modifier = Modifier.padding(start = 6.dp))
                    }
                }
            } else {
                // Keyed on videoId so switching to a new video gets a fresh
                // WebView/player instance instead of trying to reuse JS
                // state across unrelated videos.
                key(video.videoId) {
                    var webViewRef: WebView? by remember { mutableStateOf<WebView?>(null) }

                    AndroidView(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(16f / 9f),
                        factory = { ctx ->
                            WebView(ctx).apply {
                                layoutParams = ViewGroup.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                )
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                settings.mediaPlaybackRequiresUserGesture = false
                                webChromeClient = WebChromeClient()
                                addJavascriptInterface(
                                    object {
                                        @JavascriptInterface
                                        fun onPlayerError(code: Int) {
                                            val message = when (code) {
                                                2 -> "invalid video id"
                                                5 -> "HTML5 player error"
                                                100 -> "video not found or private"
                                                101, 150 -> "embedding disabled by video owner"
                                                else -> "error code $code"
                                            }
                                            onError(message)
                                        }
                                    },
                                    "AndroidPlayerBridge",
                                )
                                loadDataWithBaseURL(
                                    "https://www.youtube.com",
                                    buildPlayerHtml(video.videoId),
                                    "text/html",
                                    "utf-8",
                                    null,
                                )
                                webViewRef = this
                            }
                        },
                    )

                    DisposableEffect(video.videoId) {
                        onDispose {
                            webViewRef?.apply {
                                stopLoading()
                                loadUrl("about:blank")
                                destroy()
                            }
                            webViewRef = null
                        }
                    }

                    LaunchedEffect(command) {
                        when (command) {
                            PlayerCommand.Play ->
                                webViewRef?.evaluateJavascript("if (window.player) { player.playVideo(); }", null)
                            PlayerCommand.Pause ->
                                webViewRef?.evaluateJavascript("if (window.player) { player.pauseVideo(); }", null)
                            PlayerCommand.Close -> onClose()
                            is PlayerCommand.Seek ->
                                webViewRef?.evaluateJavascript(
                                    "if (window.player) { player.seekTo(player.getCurrentTime() + (${command.deltaSeconds}), true); }",
                                    null,
                                )
                            null -> {}
                        }
                        if (command != null) onCommandConsumed()
                    }

                    // Playback controls: rewind 10s / play-pause / forward 10s.
                    var isPlaying by remember { mutableStateOf(true) }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        IconButton(onClick = {
                            webViewRef?.evaluateJavascript(
                                "if (window.player) { player.seekTo(player.getCurrentTime() - 10, true); }",
                                null,
                            )
                        }) {
                            Icon(Icons.Filled.Replay10, contentDescription = "Rewind 10s", tint = Color.White)
                        }
                        IconButton(onClick = {
                            if (isPlaying) {
                                webViewRef?.evaluateJavascript("if (window.player) { player.pauseVideo(); }", null)
                            } else {
                                webViewRef?.evaluateJavascript("if (window.player) { player.playVideo(); }", null)
                            }
                            isPlaying = !isPlaying
                        }) {
                            Icon(
                                if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                contentDescription = if (isPlaying) "Pause" else "Play",
                                tint = Color.White,
                            )
                        }
                        IconButton(onClick = {
                            webViewRef?.evaluateJavascript(
                                "if (window.player) { player.seekTo(player.getCurrentTime() + 10, true); }",
                                null,
                            )
                        }) {
                            Icon(Icons.Filled.Forward10, contentDescription = "Forward 10s", tint = Color.White)
                        }
                    }
                }
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
private fun buildPlayerHtml(videoId: String): String = """
    <!DOCTYPE html>
    <html>
    <body style="margin:0;padding:0;background:#000;">
    <div id="player"></div>
    <script src="https://www.youtube.com/iframe_api"></script>
    <script>
      var player;
      function onYouTubeIframeAPIReady() {
        player = new YT.Player('player', {
          height: '100%',
          width: '100%',
          videoId: '$videoId',
          playerVars: { playsinline: 1, autoplay: 1 },
          events: {
            'onReady': function(event) { event.target.playVideo(); },
            'onError': function(event) {
              if (window.AndroidPlayerBridge) {
                AndroidPlayerBridge.onPlayerError(event.data);
              }
            }
          }
        });
        window.player = player;
      }
    </script>
    </body>
    </html>
""".trimIndent()
