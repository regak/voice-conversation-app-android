package com.regak.voiceorb

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * Manages multiple named sessions (like ChatGPT's conversation list).
 * Each session has its own resumeHandle + transcript + metadata.
 * Persisted to SharedPreferences as a JSON array.
 */
object SessionManager {
    private const val PREFS = "voice_orb_sessions"
    private const val KEY_SESSIONS = "sessions"
    private const val KEY_CURRENT_ID = "current_session_id"

    /** One saved conversation. */
    data class Session(
        val id: String,              // unique id (timestamp-based)
        val title: String,           // first user message or "New chat"
        val createdAt: Long,         // epoch ms
        val updatedAt: Long,         // epoch ms
        val resumeHandle: String?,   // Gemini session-resumption handle
        val transcript: List<Pair<Boolean, String>>, // (isModel, text)
    )

    /** All saved sessions, newest first. */
    fun loadAll(context: Context): List<Session> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_SESSIONS, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Session(
                    id = o.getString("id"),
                    title = o.getString("title"),
                    createdAt = o.getLong("createdAt"),
                    updatedAt = o.getLong("updatedAt"),
                    resumeHandle = if (o.has("resumeHandle") && !o.isNull("resumeHandle"))
                        o.getString("resumeHandle") else null,
                    transcript = if (o.has("transcript")) {
                        val tArr = o.getJSONArray("transcript")
                        (0 until tArr.length()).map { j ->
                            val t = tArr.getJSONObject(j)
                            t.getBoolean("isModel") to t.getString("text")
                        }
                    } else emptyList(),
                )
            }
        }.getOrElse { emptyList() }
    }

    fun saveAll(context: Context, sessions: List<Session>) {
        val arr = JSONArray()
        sessions.forEach { s ->
            val tArr = JSONArray()
            s.transcript.forEach { (isModel, text) ->
                tArr.put(JSONObject().apply { put("isModel", isModel); put("text", text) })
            }
            arr.put(JSONObject().apply {
                put("id", s.id)
                put("title", s.title)
                put("createdAt", s.createdAt)
                put("updatedAt", s.updatedAt)
                put("resumeHandle", s.resumeHandle)
                put("transcript", tArr)
            })
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_SESSIONS, arr.toString()).apply()
    }

    /** Creates a brand-new empty session and makes it current. */
    fun newSession(context: Context): Session {
        val now = System.currentTimeMillis()
        val session = Session(
            id = now.toString(),
            title = "New chat",
            createdAt = now,
            updatedAt = now,
            resumeHandle = null,
            transcript = emptyList(),
        )
        val all = loadAll(context)
        val updated = listOf(session) + all
        val finalList = updated.take(50) // cap at 50
        saveAll(context, finalList)
        setCurrentId(context, session.id)
        return session
    }

    fun getCurrentId(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_CURRENT_ID, null)

    fun setCurrentId(context: Context, id: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_CURRENT_ID, id).apply()
    }

    fun getCurrent(context: Context): Session? {
        val id = getCurrentId(context) ?: return null
        return loadAll(context).firstOrNull { it.id == id }
    }

    /** Updates or inserts a session (e.g. after a turn, new handle, new title). */
    fun updateSession(context: Context, updated: Session) {
        val all = loadAll(context)
        val idx = all.indexOfFirst { it.id == updated.id }
        val newList = if (idx >= 0) {
            val copy = all.toMutableList()
            copy[idx] = updated.copy(updatedAt = System.currentTimeMillis())
            copy
        } else {
            (listOf(updated) + all).take(50)
        }
        saveAll(context, newList)
    }

    fun deleteSession(context: Context, id: String) {
        val all = loadAll(context).filter { it.id != id }
        saveAll(context, all)
        if (getCurrentId(context) == id) {
            val next = all.firstOrNull()?.id
            if (next != null) setCurrentId(context, next)
            else context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .remove(KEY_CURRENT_ID).apply()
        }
    }

    /** Groups sessions for UI: Today / Yesterday / Previous 7 days / Older. */
    fun groupedForDisplay(sessions: List<Session>): List<SessionGroup> {
        val now = System.currentTimeMillis()
        val cal = java.util.Calendar.getInstance()
        cal.timeInMillis = now
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0); cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0); cal.set(java.util.Calendar.MILLISECOND, 0)
        val todayStart = cal.timeInMillis
        val yesterdayStart = todayStart - 24L * 60 * 60 * 1000
        val weekAgoStart = todayStart - 7 * 24L * 60 * 60 * 1000

        val today = mutableListOf<Session>()
        val yesterday = mutableListOf<Session>()
        val thisWeek = mutableListOf<Session>()
        val older = mutableListOf<Session>()

        sessions.forEach { s ->
            when {
                s.updatedAt >= todayStart -> today.add(s)
                s.updatedAt >= yesterdayStart -> yesterday.add(s)
                s.updatedAt >= weekAgoStart -> thisWeek.add(s)
                else -> older.add(s)
            }
        }

        return listOf(
            if (today.isNotEmpty()) SessionGroup("Today", today) else null,
            if (yesterday.isNotEmpty()) SessionGroup("Yesterday", yesterday) else null,
            if (thisWeek.isNotEmpty()) SessionGroup("Previous 7 days", thisWeek) else null,
            if (older.isNotEmpty()) SessionGroup("Older", older) else null,
        ).filterNotNull()
    }

    data class SessionGroup(val label: String, val sessions: List<Session>)
}