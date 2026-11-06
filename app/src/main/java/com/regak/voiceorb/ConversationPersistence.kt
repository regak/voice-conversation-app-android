package com.regak.voiceorb

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persists just enough to survive the app being fully closed/killed (not
 * just a network drop, which GeminiLiveClient's sessionResumption already
 * handles in-memory) -- a SharedPreferences-backed resumable session
 * handle, plus the visible transcript, so reopening the app looks like
 * ChatGPT's "continue where you left off" instead of starting blank.
 *
 * Caveat, stated plainly rather than guessed at: Google doesn't publish an
 * exact validity window for a Live API resumption handle -- it's a warm
 * session-continuation feature, not an indefinite server-side chat
 * history. It reliably survives the kind of gap this was built for (app
 * closed for a few minutes/hours), but after a very long gap the server
 * may no longer accept the old handle; the app should fall back to a
 * fresh session rather than get stuck if that happens.
 */
object ConversationPersistence {
    private const val PREFS = "voice_orb_conversation"
    private const val KEY_RESUME_HANDLE = "resume_handle"
    private const val KEY_TRANSCRIPT = "transcript"

    fun saveResumeHandle(context: Context, handle: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_RESUME_HANDLE, handle).apply()
    }

    fun loadResumeHandle(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_RESUME_HANDLE, null)

    fun clearResumeHandle(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_RESUME_HANDLE).apply()
    }

    /** [lines] as (isModel, text) pairs, oldest first. */
    fun saveTranscript(context: Context, lines: List<Pair<Boolean, String>>) {
        val arr = JSONArray()
        lines.forEach { (isModel, text) ->
            arr.put(JSONObject().apply { put("isModel", isModel); put("text", text) })
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_TRANSCRIPT, arr.toString()).apply()
    }

    fun loadTranscript(context: Context): List<Pair<Boolean, String>> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_TRANSCRIPT, null)
            ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                o.getBoolean("isModel") to o.getString("text")
            }
        }.getOrElse { emptyList() }
    }

    fun clearAll(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }
}
