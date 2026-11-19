@Composable
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
