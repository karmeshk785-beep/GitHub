package com.example.agent

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.zip.ZipInputStream

enum class Screen { CHAT, SETTINGS, KEYS, CHATLIST, AGENTS, ABOUT }

data class ChatMsg(val fromUser: Boolean, val text: String, val artifactId: Long? = null)

class ChatSession(val id: String, initialTitle: String, val messages: SnapshotStateList<ChatMsg>) {
    var title by mutableStateOf(initialTitle)
}

fun newSession(): ChatSession =
    ChatSession(UUID.randomUUID().toString(), "नई चैट", mutableStateListOf())

object Store {
    fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences("agent_prefs", Context.MODE_PRIVATE)

    fun load(p: SharedPreferences): MutableList<ChatSession> {
        val out = mutableListOf<ChatSession>()
        val arr = JSONArray(p.getString("sessions", "[]") ?: "[]")
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val ma = o.getJSONArray("messages")
            val msgs = mutableStateListOf<ChatMsg>()
            for (j in 0 until ma.length()) {
                val m = ma.getJSONObject(j)
                val a = if (m.isNull("a")) null else m.getLong("a")
                msgs.add(ChatMsg(m.getBoolean("u"), m.getString("t"), a))
            }
            out.add(ChatSession(o.getString("id"), o.getString("title"), msgs))
        }
        return out
    }

    fun save(p: SharedPreferences, list: List<ChatSession>) {
        val arr = JSONArray()
        for (s in list) {
            val ma = JSONArray()
            for (m in s.messages) {
                ma.put(
                    JSONObject()
                        .put("u", m.fromUser)
                        .put("t", m.text)
                        .put("a", m.artifactId ?: JSONObject.NULL)
                )
            }
            arr.put(JSONObject().put("id", s.id).put("title", s.title).put("messages", ma))
        }
        p.edit().putString("sessions", arr.toString()).apply()
    }
}

fun httpPost(url: String, body: String, token: String, headers: Map<String, String>): Pair<Int, String> {
    val conn = URL(url).openConnection() as HttpURLConnection
    conn.requestMethod = "POST"
    conn.setRequestProperty("Content-Type", "application/json")
    conn.setRequestProperty("Authorization", "Bearer $token")
    headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
    conn.doOutput = true
    conn.connectTimeout = 15000
    conn.readTimeout = 60000
    conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
    val code = conn.responseCode
    val stream = if (code in 200..299) conn.inputStream else conn.errorStream
    val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
    return code to text
}

fun httpGet(url: String, token: String): Pair<Int, String> {
    val conn = URL(url).openConnection() as HttpURLConnection
    conn.setRequestProperty("Authorization", "Bearer $token")
    conn.setRequestProperty("Accept", "application/vnd.github+json")
    conn.connectTimeout = 15000
    conn.readTimeout = 60000
    val code = conn.responseCode
    val stream = if (code in 200..299) conn.inputStream else conn.errorStream
    val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
    return code to text
}

object Groq {
    private const val MODEL = "openai/gpt-oss-20b"

    fun ask(key: String, history: List<ChatMsg>): String {
        val arr = JSONArray()
        arr.put(
            JSONObject().put("role", "system").put(
                "content",
                "तुम एक मददगार सहायक हो। हिंदी में साफ़ और छोटे जवाब दो। कोड हो तो ``` में दो।"
            )
        )
        for (m in history.takeLast(12)) {
            if (m.text.isBlank()) continue
            arr.put(
                JSONObject()
                    .put("role", if (m.fromUser) "user" else "assistant")
                    .put("content", m.text)
            )
        }
        val body = JSONObject().put("model", MODEL).put("messages", arr).toString()
        val (code, text) = httpPost(
            "https://api.groq.com/openai/v1/chat/completions", body, key, emptyMap()
        )
        if (code !in 200..299) return "Error $code: $text"
        return JSONObject(text)
            .getJSONArray("choices").getJSONObject(0)
            .getJSONObject("message").getString("content")
    }
}
object GitHubBuild {
    private const val GH_ACCEPT = "application/vnd.github+json"

    fun latestArtifactId(token: String, owner: String, repo: String): Long? {
        val (code, text) = httpGet(
            "https://api.github.com/repos/$owner/$repo/actions/artifacts?name=app-debug&per_page=1",
            token
        )
        if (code !in 200..299) return null
        val arr = JSONObject(text).getJSONArray("artifacts")
        return if (arr.length() == 0) null else arr.getJSONObject(0).getLong("id")
    }

    fun trigger(token: String, owner: String, repo: String): Int {
        val body = JSONObject().put("ref", "main").toString()
        val (code, _) = httpPost(
            "https://api.github.com/repos/$owner/$repo/actions/workflows/build.yml/dispatches",
            body,
            token,
            mapOf("Accept" to GH_ACCEPT)
        )
        return code
    }

    fun downloadApk(ctx: Context, token: String, owner: String, repo: String, artifactId: Long): File? {
        val (c, t) = httpGet(
            "https://api.github.com/repos/$owner/$repo/actions/artifacts/$artifactId", token
        )
        if (c !in 200..299) return null
        val dl = JSONObject(t).getString("archive_download_url")

        val first = URL(dl).openConnection() as HttpURLConnection
        first.setRequestProperty("Authorization", "Bearer $token")
        first.instanceFollowRedirects = false
        val code = first.responseCode
        val src: InputStream = if (code in 300..399) {
            val loc = first.getHeaderField("Location") ?: return null
            URL(loc).openStream()
        } else {
            first.inputStream
        }

        val zip = File(ctx.cacheDir, "artifact.zip")
        src.use { input -> FileOutputStream(zip).use { input.copyTo(it) } }

        val apk = File(ctx.cacheDir, "app-debug.apk")
        var found = false
        ZipInputStream(zip.inputStream()).use { z ->
            var e = z.nextEntry
            while (e != null) {
                if (e.name.endsWith("app-debug.apk")) {
                    FileOutputStream(apk).use { z.copyTo(it) }
                    found = true
                    break
                }
                e = z.nextEntry
            }
        }
        return if (found) apk else null
    }
}

object Installer {
    fun install(ctx: Context, apk: File) {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", apk)
        val i = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ctx.startActivity(i)
    }
}

fun isBuildCommand(t: String): Boolean {
    val s = t.lowercase()
    return s.contains("apk") &&
        (s.contains("बनाओ") || s.contains("banao") || s.contains("build"))
}

fun extractCode(s: String): String =
    Regex("""```[a-zA-Z]*\n?([\s\S]*?)```""")
        .findAll(s)
        .joinToString("\n\n") { it.groupValues[1].trim() }

fun fileName(ctx: Context, uri: Uri): String {
    val c = ctx.contentResolver.query(uri, null, null, null, null) ?: return "फ़ाइल"
    c.use {
        if (it.moveToFirst()) {
            val i = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (i >= 0) return it.getString(i)
        }
    }
    return "फ़ाइल"
}

fun zipEntries(ctx: Context, uri: Uri): List<String> {
    val out = mutableListOf<String>()
    try {
        ctx.contentResolver.openInputStream(uri)?.use { raw ->
            ZipInputStream(raw).use { z ->
                var e = z.nextEntry
                while (e != null && out.size < 300) {
                    if (!e.isDirectory) out.add(e.name)
                    e = z.nextEntry
                }
            }
        }
    } catch (e: Exception) {
        // फ़ाइल ZIP नहीं है, तो खाली लिस्ट लौटेगी
    }
    return out
}
