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
            val before = withContext(Dispatchers.IO) {
                GitHubBuild.latestArtifactId(token, owner, repo)
            } ?: 0L
            val code = withContext(Dispatchers.IO) {
                GitHubBuild.trigger(token, owner, repo)
            }
            if (code != 204) {
                target.messages.add(
                    ChatMsg(false, "GitHub build शुरू नहीं हुआ (code $code)। token और repo जाँचें।")
                )
                Store.save(prefs, sessions)
                busy = false
                return@launch
            }
            target.messages.add(
                ChatMsg(false, "Build शुरू हो गया है। 2-3 मिनट लगेंगे, मैं इंतज़ार कर रहा हूँ।")
            )
            Store.save(prefs, sessions)

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
                target.messages.add(
                    ChatMsg(false, "Build अभी पूरा नहीं हुआ। GitHub Actions पेज पर देखें।")
                )
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
                Installer.install(ctx, apk)
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
    }
}
