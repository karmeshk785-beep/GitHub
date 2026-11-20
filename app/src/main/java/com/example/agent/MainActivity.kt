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
import androidx.compose.ui.Alignment
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

enum class Screen { CHAT, SETTINGS, KEYS, CHATLIST, AGENTS, ABOUT, TERMUX }

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
}    fun pushFile(token: String, owner: String, repo: String, path: String, content: String): Int {
        val encoded = java.util.Base64.getEncoder().encodeToString(content.toByteArray(Charsets.UTF_8))
        val body = JSONObject()
            .put("message", "Add $path via agent")
            .put("content", encoded)
            .put("branch", "main")
            .toString()
        val conn = URL("https://api.github.com/repos/$owner/$repo/contents/$path")
            .openConnection() as HttpURLConnection
        conn.requestMethod = "PUT"
        conn.setRequestProperty("Authorization", "Bearer $token")
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.setRequestProperty("Content-Type", "application/json")
        conn.doOutput = true
        conn.connectTimeout = 15000
        conn.readTimeout = 60000
        conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        return conn.responseCode
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

        fun pushFile(token: String, owner: String, repo: String, path: String, content: String): Int {
        val encoded = java.util.Base64.getEncoder().encodeToString(content.toByteArray(Charsets.UTF_8))
        val body = JSONObject()
            .put("message", "Add $path via agent")
            .put("content", encoded)
            .put("branch", "main")
            .toString()
        val conn = URL("https://api.github.com/repos/$owner/$repo/contents/$path")
            .openConnection() as HttpURLConnection
        conn.requestMethod = "PUT"
        conn.setRequestProperty("Authorization", "Bearer $token")
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.setRequestProperty("Content-Type", "application/json")
        conn.doOutput = true
        conn.connectTimeout = 15000
        conn.readTimeout = 60000
        conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        return conn.responseCode
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
}object Installer {
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

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = Store.prefs(this)
        setContent {
            MaterialTheme {
                App(prefs)
            }
        }
    }
}

@Composable
fun App(prefs: SharedPreferences) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    val sessions = remember {
        val list = mutableStateListOf<ChatSession>()
        list.addAll(Store.load(prefs))
        if (list.isEmpty()) list.add(newSession())
        list
    }
    var currentId by remember { mutableStateOf(sessions.first().id) }
    var screen by remember {
        mutableStateOf(if (prefs.getString("groq_key", "").isNullOrEmpty()) Screen.KEYS else Screen.CHAT)
    }
    var busy by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }
    val current = sessions.firstOrNull { it.id == currentId } ?: sessions.first()

    fun persist() = Store.save(prefs, sessions)

    fun addMsg(m: ChatMsg) {
        current.messages.add(m)
        persist()
    }

    fun aiReply() {
        val key = prefs.getString("groq_key", "") ?: ""
        val target = current
        val history = target.messages.toList()
        busy = true
        scope.launch {
            val reply = withContext(Dispatchers.IO) {
                try {
                    Groq.ask(key, history)
                } catch (e: Exception) {
                    "त्रुटि: ${e.message}"
                }
            }
            target.messages.add(ChatMsg(false, reply))
            Store.save(prefs, sessions)
            busy = false
        }
    }

        fun buildApk() {
        val token = prefs.getString("gh_token", "") ?: ""
        val owner = prefs.getString("gh_owner", "") ?: ""
        val repo = prefs.getString("gh_repo", "") ?: ""
        val target = current
        if (token.isEmpty() || owner.isEmpty() || repo.isEmpty()) {
            target.messages.add(ChatMsg(false, "पहले चाबियाँ में GitHub token, owner और repo भरें।"))
            Store.save(prefs, sessions)
            return
        }
        busy = true
        scope.launch {
            target.messages.add(ChatMsg(false, "कोड लिखा जा रहा है..."))
            Store.save(prefs, sessions)

            val spec = target.messages.lastOrNull { it.fromUser }?.text ?: ""
            val code = withContext(Dispatchers.IO) {
                try {
                    Groq.ask(prefs.getString("groq_key", "") ?: "", listOf(ChatMsg(true, "इस डिज़ाइन से एक Android Kotlin Compose फ़ाइल लिखो, सिर्फ़ कोड दो: $spec")))
                } catch (e: Exception) {
                    "त्रुटि: ${e.message}"
                }
            }
            val fileCode = extractCode(code).ifEmpty { code }

            val before = withContext(Dispatchers.IO) {
                GitHubBuild.latestArtifactId(token, owner, repo)
            } ?: 0L

            val pushCode = withContext(Dispatchers.IO) {
                GitHubBuild.pushFile(token, owner, repo, "app/src/main/java/com/example/agent/Generated.kt", fileCode)
            }
            if (pushCode !in 200..299) {
                target.messages.add(ChatMsg(false, "GitHub पर फ़ाइल नहीं गई (code $pushCode)।"))
                Store.save(prefs, sessions)
                busy = false
                return@launch
            }
            target.messages.add(ChatMsg(false, "कोड GitHub पर भेज दिया। Build शुरू हो रहा है।"))
            Store.save(prefs, sessions)

            val code2 = withContext(Dispatchers.IO) {
                GitHubBuild.trigger(token, owner, repo)
            }
            if (code2 != 204) {
                target.messages.add(ChatMsg(false, "Build शुरू नहीं हुआ (code $code2)।"))
                Store.save(prefs, sessions)
                busy = false
                return@launch
            }

            var found: Long? = null
            var tries = 0
            while (found == null && tries < 40) {
                delay(15000)
                tries++
                val id = withContext(Dispatchers.IO) {
                    GitHubBuild.latestArtifactId(token, owner, repo)
                }
                if (id != null && id > before) found = id
            }
            val f = found
            if (f != null) {
                target.messages.add(ChatMsg(false, "APK तैयार है। नीचे 'इंस्टॉल करें' दबाइए।", f))
            } else {
                target.messages.add(ChatMsg(false, "Build अभी पूरा नहीं हुआ। GitHub Actions पेज पर देखें।"))
            }
            Store.save(prefs, sessions)
            busy = false
        }
        }

        fun install(id: Long) {
        val token = prefs.getString("gh_token", "") ?: ""
        val owner = prefs.getString("gh_owner", "") ?: ""
        val repo = prefs.getString("gh_repo", "") ?: ""
        busy = true
        scope.launch {
            val apk = withContext(Dispatchers.IO) {
                GitHubBuild.downloadApk(ctx, token, owner, repo, id)
            }
            busy = false
            if (apk == null) {
                addMsg(ChatMsg(false, "APK डाउनलोड नहीं हो पाई। token जाँचें।"))
            } else {
                val saved = withContext(Dispatchers.IO) {
                    val dir = android.os.Environment.getExternalStoragePublicDirectory(
                        android.os.Environment.DIRECTORY_DOWNLOADS
                    )
                    dir.mkdirs()
                    val dest = File(dir, "agent-app-${System.currentTimeMillis()}.apk")
                    apk.copyTo(dest, overwrite = true)
                    dest
                }
                addMsg(ChatMsg(false, "APK Downloads में सेव हो गई: ${saved.name}"))
                Installer.install(ctx, saved)
            }
        }
        }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                val name = withContext(Dispatchers.IO) { fileName(ctx, uri) }
                val entries = withContext(Dispatchers.IO) { zipEntries(ctx, uri) }
                addMsg(ChatMsg(true, "फ़ाइल भेजी: $name"))
                if (entries.isEmpty()) {
                    addMsg(ChatMsg(false, "यह ZIP नहीं लगती या इसमें फ़ाइलें नहीं मिलीं।"))
                } else {
                    addMsg(ChatMsg(true, "प्रोजेक्ट फ़ाइलें:\n" + entries.joinToString("\n")))
                    aiReply()
                }
            }
        }
    }

        fun send() {
        val text = input.trim()
        if (text.isEmpty() || busy) return
        input = ""
        if (current.messages.isEmpty()) current.title = text.take(25)
        addMsg(ChatMsg(true, text))
        if (isBuildCommand(text)) buildApk() else aiReply()
        }

    fun newChat() {
        val s = newSession()
        sessions.add(0, s)
        currentId = s.id
        persist()
        screen = Screen.CHAT
    }

    fun deleteChat(s: ChatSession) {
        sessions.remove(s)
        if (sessions.isEmpty()) sessions.add(newSession())
        if (currentId == s.id) currentId = sessions.first().id
        persist()
    }

    when (screen) {
        Screen.CHAT -> ChatScreen(
            session = current,
            busy = busy,
            input = input,
            onInput = { input = it },
            onSend = { send() },
            onAttach = { picker.launch("*/*") },
            onSettings = { screen = Screen.SETTINGS },
            onNewChat = { newChat() },
            onInstall = { install(it) },
            onChatList = { screen = Screen.CHATLIST }
        )
        Screen.SETTINGS -> SettingsScreen { screen = it }
        Screen.KEYS -> KeysScreen(prefs) { screen = Screen.CHAT }
        Screen.CHATLIST -> ChatListScreen(
            sessions = sessions,
            onOpen = {
                currentId = it.id
                screen = Screen.CHAT
            },
            onDelete = { deleteChat(it) },
            onBack = { screen = Screen.SETTINGS }
        )
        Screen.AGENTS -> AgentsScreen { screen = Screen.SETTINGS }
        Screen.ABOUT -> AboutScreen { screen = Screen.SETTINGS }
        Screen.TERMUX -> TermuxScreen { screen = Screen.CHAT }
    }
}@Composable
fun ChatScreen(
    session: ChatSession,
    busy: Boolean,
    input: String,
    onInput: (String) -> Unit,
    onSend: () -> Unit,
    onAttach: () -> Unit,
    onSettings: () -> Unit,
    onNewChat: () -> Unit,
    onInstall: (Long) -> Unit,
    onChatList: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onChatList) { Text("सूची") }
            Text(
                session.title,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f).padding(start = 4.dp)
            )
            TextButton(onClick = onNewChat) { Text("नई") }
            TextButton(onClick = onSettings) { Text("⚙") }
        }

        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
            items(session.messages) { m ->
                MessageItem(m, onInstall)
            }
        }

        if (busy) Text("सोच रहा हूँ...", modifier = Modifier.padding(4.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            TextButton(onClick = onAttach) { Text("फ़ाइल") }
            OutlinedTextField(
                value = input,
                onValueChange = onInput,
                label = { Text("लिखें या कमांड दें") },
                modifier = Modifier.weight(1f)
            )
            Button(onClick = onSend, enabled = !busy && input.isNotBlank()) {
                Text("भेजो")
            }
        }
    }
}

@Composable
fun MessageItem(m: ChatMsg, onInstall: (Long) -> Unit) {
    val clip = LocalClipboardManager.current
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        SelectionContainer {
            Text(if (m.fromUser) "आप: ${m.text}" else "AI: ${m.text}")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = { clip.setText(AnnotatedString(m.text)) }) { Text("कॉपी") }
            if (m.text.contains("```")) {
                TextButton(onClick = { clip.setText(AnnotatedString(extractCode(m.text))) }) {
                    Text("कोड कॉपी")
                }
            }
            val id = m.artifactId
            if (id != null) {
                Button(onClick = { onInstall(id) }) { Text("इंस्टॉल करें") }
            }
        }
    }
}

@Composable
fun SettingsScreen(onNav: (Screen) -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("सेटिंग्स", style = MaterialTheme.typography.headlineSmall)
        Button(onClick = { onNav(Screen.KEYS) }) { Text("चाबियाँ") }
        Button(onClick = { onNav(Screen.CHATLIST) }) { Text("चैट सूची") }
        Button(onClick = { onNav(Screen.AGENTS) }) { Text("एजेंट") }
        Button(onClick = { onNav(Screen.ABOUT) }) { Text("इस ऐप के बारे में") }
        TextButton(onClick = { onNav(Screen.CHAT) }) { Text("चैट पर वापस") }
    }
}

@Composable
fun KeysScreen(prefs: SharedPreferences, onDone: () -> Unit) {
    var groq by remember { mutableStateOf(prefs.getString("groq_key", "") ?: "") }
    var token by remember { mutableStateOf(prefs.getString("gh_token", "") ?: "") }
    var owner by remember { mutableStateOf(prefs.getString("gh_owner", "") ?: "") }
    var repo by remember { mutableStateOf(prefs.getString("gh_repo", "") ?: "") }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("चाबियाँ", style = MaterialTheme.typography.headlineSmall)
        OutlinedTextField(
            value = groq,
            onValueChange = { groq = it },
            label = { Text("Groq API Key") },
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = token,
            onValueChange = { token = it },
            label = { Text("GitHub Token") },
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = owner,
            onValueChange = { owner = it },
            label = { Text("GitHub Username (owner)") },
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = repo,
            onValueChange = { repo = it },
            label = { Text("GitHub Repo नाम") },
            modifier = Modifier.fillMaxWidth()
        )
        Button(onClick = {
            prefs.edit()
                .putString("groq_key", groq.trim())
                .putString("gh_token", token.trim())
                .putString("gh_owner", owner.trim())
                .putString("gh_repo", repo.trim())
                .apply()
            onDone()
        }) {
            Text("सेव करो")
        }
    }
}

@Composable
fun ChatListScreen(
    sessions: List<ChatSession>,
    onOpen: (ChatSession) -> Unit,
    onDelete: (ChatSession) -> Unit,
    onBack: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                "पुरानी चैट",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onBack) { Text("वापस") }
        }
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(sessions) { s ->
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    Text(
                        s.title,
                        modifier = Modifier.weight(1f).clickable { onOpen(s) }
                    )
                    TextButton(onClick = { onDelete(s) }) { Text("मिटाओ") }
                }
            }
        }
    }
}

@Composable
fun AgentsScreen(onBack: () -> Unit) {
    val agents = listOf(
        "मैनेजर" to "कमांड पढ़कर काम बाँटता है",
        "कोडर" to "Groq से कोड लिखता है",
        "रनर" to "Termux में कमांड चलाता है",
        "पुशर" to "GitHub पर कोड भेजता है",
        "चेकर" to "हर स्टेप की जाँच करता है",
        "Fixer" to "गलती होने पर सुधार की कोशिश करता है"
    )
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("एजेंट", style = MaterialTheme.typography.headlineSmall)
        Text("अभी यह ढाँचा है। Termux और पूरी पाइपलाइन बाद में जुड़ेगी।")
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(agents) { (name, desc) ->
                Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    Text(name, style = MaterialTheme.typography.titleMedium)
                    Text(desc)
                }
            }
        }
        TextButton(onClick = onBack) { Text("वापस") }
    }
}

@Composable
fun AboutScreen(onBack: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("इस ऐप के बारे में", style = MaterialTheme.typography.headlineSmall)
        Text("यह ऐप तिलक राम ने बनाया है।")
        TextButton(onClick = onBack) { Text("वापस") }
    }
}

@Composable
fun TermuxScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val lines = remember { mutableStateListOf<String>() }
    var cmd by remember { mutableStateOf("") }

    fun runTermux(command: String) {
        lines.add("\$ $command")
        val intent = Intent().apply {
            setClassName("com.termux", "com.termux.app.RunCommandService")
            action = "com.termux.RUN_COMMAND"
            putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash")
            putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-c", command))
            putExtra("com.termux.RUN_COMMAND_BACKGROUND", false)
        }
        try {
            ctx.startService(intent)
            lines.add("कमांड Termux को भेज दी गई। नतीजा Termux ऐप में देखें।")
        } catch (e: Exception) {
            lines.add("त्रुटि: ${e.message}")
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Termux",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onBack) { Text("वापस") }
        }

        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
            items(lines) { l ->
                SelectionContainer { Text(l) }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = cmd,
                onValueChange = { cmd = it },
                label = { Text("Termux कमांड लिखें") },
                modifier = Modifier.weight(1f)
            )
            Button(
                onClick = {
                    val c = cmd.trim()
                    if (c.isNotEmpty()) {
                        runTermux(c)
                        cmd = ""
                    }
                },
                enabled = cmd.isNotBlank()
            ) {
                Text("चलाओ")
            }
        }
    }
}
