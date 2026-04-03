package au.howardagent.ui.onboarding

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import au.howardagent.data.SecurePrefs

@Composable
fun Step2_CloudKeys(prefs: SecurePrefs, onNext: () -> Unit) {
    var openaiKey by remember { mutableStateOf(prefs.openaiKey) }
    var anthropicKey by remember { mutableStateOf(prefs.anthropicKey) }
    var geminiKey by remember { mutableStateOf(prefs.geminiKey) }
    var openrouterKey by remember { mutableStateOf(prefs.openrouterKey) }
    var ollamaUrl by remember { mutableStateOf(prefs.ollamaBaseUrl) }
    var githubToken by remember { mutableStateOf(prefs.githubToken) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            text = "Cloud Providers",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )

        Text(
            text = "Add API keys for cloud fallback. All keys are stored encrypted on-device. " +
                "OpenRouter free tier requires no payment.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 8.dp)
        )

        Spacer(Modifier.height(8.dp))

        ApiKeyField(
            label = "OpenAI API Key",
            value = openaiKey,
            onValueChange = { openaiKey = it; prefs.openaiKey = it }
        )

        ApiKeyField(
            label = "Anthropic API Key",
            value = anthropicKey,
            onValueChange = { anthropicKey = it; prefs.anthropicKey = it }
        )

        ApiKeyField(
            label = "Google Gemini API Key",
            value = geminiKey,
            onValueChange = { geminiKey = it; prefs.geminiKey = it }
        )

        ApiKeyField(
            label = "OpenRouter API Key",
            value = openrouterKey,
            onValueChange = { openrouterKey = it; prefs.openrouterKey = it },
            hint = "Free tier available — no payment needed"
        )

        Spacer(Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(Modifier.height(16.dp))

        Text(
            text = "Self-Hosted",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )

        Spacer(Modifier.height(8.dp))

        OutlinedTextField(
            value = ollamaUrl,
            onValueChange = { ollamaUrl = it; prefs.ollamaBaseUrl = it },
            label = { Text("Ollama Base URL") },
            placeholder = { Text("http://192.168.1.x:11434") },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
        )

        Spacer(Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(Modifier.height(16.dp))

        Text(
            text = "GitHub Integration",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )

        Spacer(Modifier.height(8.dp))

        ApiKeyField(
            label = "GitHub Personal Access Token",
            value = githubToken,
            onValueChange = { githubToken = it; prefs.githubToken = it },
            hint = "Required for github_sync tool"
        )

        Spacer(Modifier.height(24.dp))

        Button(
            onClick = onNext,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Continue")
        }

        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun ApiKeyField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    hint: String? = null
) {
    var visible by remember { mutableStateOf(false) }

    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            singleLine = true,
            visualTransformation = if (visible) VisualTransformation.None
                else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { visible = !visible }) {
                    Icon(
                        if (visible) Icons.Default.Lock else Icons.Default.Person,
                        contentDescription = if (visible) "Hide" else "Show"
                    )
                }
            },
            modifier = Modifier.fillMaxWidth()
        )
        if (hint != null) {
            Text(
                text = hint,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, top = 2.dp)
            )
        }
    }
}
