package com.example.agent

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class ChatMsg(val fromUser: Boolean, val text: String)

object GroqApi {
    private const val MODEL = "llama3-70b-8192"

    fun ask(apiKey: String, prompt: String): String {
        val url = URL("https://api.groq.com/openai/v1/chat/completions")
        val message = JSONObject().put("role", "user").put("content", prompt)
        val body = JSONObject()
            .put("model", MODEL)
            .put("messages", JSONArray().put(message))

        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Authorization", "Bearer $apiKey")
        conn.doOutput = true
        conn.connectTimeout = 15000
        conn.readTimeout = 60000
        conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream.bufferedReader().use { it.readText() }
        if (code !in 200..299) return "Error $code: $text"

        return JSONObject(text)
            .getJSONArray("choices")
            .getJSONObject(0)
            .getJSONObject("message")
            .getString("content")
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("agent_prefs", Context.MODE_PRIVATE)
        setContent {
            MaterialTheme {
                AppScreen(prefs)
            }
        }
    }
}

@Composable
fun AppScreen(prefs: SharedPreferences) {
    var showSettings by remember {
        mutableStateOf(prefs.getString("gemini_key", "").isNullOrEmpty())
    }
    if (showSettings) {
        SettingsScreen(prefs) { showSettings = false }
    } else {
        ChatScreen(prefs) { showSettings = true }
    }
}

@Composable
fun SettingsScreen(prefs: SharedPreferences, onDone: () -> Unit) {
    var key by remember { mutableStateOf(prefs.getString("gemini_key", "") ?: "") }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall)
        OutlinedTextField(
            value = key,
            onValueChange = { key = it },
            label = { Text("Groq API Key") },
            modifier = Modifier.fillMaxWidth()
        )
        Button(
            onClick = {
                prefs.edit().putString("gemini_key", key.trim()).apply()
                onDone()
            },
            enabled = key.isNotBlank()
        ) {
            Text("सेव करो")
        }
    }
}

@Composable
fun ChatScreen(prefs: SharedPreferences, onOpenSettings: () -> Unit) {
    val scope = rememberCoroutineScope()
    val messages = remember { mutableStateListOf<ChatMsg>() }
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                "Agent Chat",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onOpenSettings) { Text("Settings") }
        }

        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(messages) { m ->
                Text(if (m.fromUser) "आप: ${m.text}" else "AI: ${m.text}")
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                enabled = !busy && input.isNotBlank(),
                onClick = {
                    val prompt = input.trim()
                    val key = prefs.getString("gemini_key", "") ?: ""
                    messages.add(ChatMsg(true, prompt))
                    input = ""
                    busy = true
                    scope.launch {
                        val reply = withContext(Dispatchers.IO) {
                            try {
                                GroqApi.ask(key, prompt)
                            } catch (e: Exception) {
                                "त्रुटि: ${e.message}"
                            }
                        }
                        messages.add(ChatMsg(false, reply))
                        busy = false
                    }
                }
            ) { Text("Groq") }

            Button(enabled = false, onClick = {}) { Text("Termux") }
            Button(enabled = false, onClick = {}) { Text("GitHub") }
        }

        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            label = { Text("कमांड लिखें") },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
        )
    }
}
