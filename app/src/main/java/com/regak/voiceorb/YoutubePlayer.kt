package com.regak.voiceorb

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
 * close are real, reliable JS calls into our own WebView instead of
 * app-launch guesswork.
 */
@Composable
fun EmbeddedYoutubePlayer(
    video: YoutubeVideo,
    command: PlayerCommand?,
    onCommandConsumed: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
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

            // Keyed on videoId so switching to a new video gets a fresh
            // WebView/player instance instead of trying to reuse JS state
            // across unrelated videos.
            key(video.videoId) {
                var webViewRef: WebView? by remember { mutableStateOf<WebView?>(null) }

                AndroidView(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f),
                    factory = { context ->
                        WebView(context).apply {
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT,
                            )
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.mediaPlaybackRequiresUserGesture = false
                            webChromeClient = WebChromeClient()
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
                        null -> {}
                    }
                    if (command != null) onCommandConsumed()
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
            'onReady': function(event) { event.target.playVideo(); }
          }
        });
        window.player = player;
      }
    </script>
    </body>
    </html>
""".trimIndent()
