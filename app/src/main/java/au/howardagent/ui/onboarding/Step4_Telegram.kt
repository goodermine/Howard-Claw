package au.howardagent.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import au.howardagent.data.SecurePrefs
import au.howardagent.connectors.TelegramConnector
import kotlinx.coroutines.launch

@Composable
fun ConnectStep(
    prefs: SecurePrefs,
    onNext: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val connector = remember { TelegramConnector(prefs) }

    var botToken by remember { mutableStateOf(prefs.telegramBotToken) }
    var channelId by remember { mutableStateOf(prefs.telegramChannelId) }
    var pollingEnabled by remember { mutableStateOf(prefs.telegramEnabled) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var testSuccess by remember { mutableStateOf(false) }
    var isTesting by remember { mutableStateOf(false) }

    // Outer column fills the step area; content scrolls, buttons stay pinned.
    Column(modifier = Modifier.fillMaxSize()) {

        // ── Scrollable content ────────────────────────────────────────────
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            Text(
                text = "Telegram Integration",
                style = MaterialTheme.typography.headlineSmall
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Optional — connect Howard to Telegram for remote control and notifications. You can skip this step.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Instructions card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Setup Instructions",
                        style = MaterialTheme.typography.titleSmall
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "1. Open Telegram and search for @BotFather\n" +
                                "2. Send /newbot and follow the prompts\n" +
                                "3. Copy the bot token provided\n" +
                                "4. Create a channel and add your bot as admin\n" +
                                "5. Get the channel ID (e.g., @your_channel or numeric ID)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Bot token field
            OutlinedTextField(
                value = botToken,
                onValueChange = { value ->
                    botToken = value
                    prefs.telegramBotToken = value
                },
                label = { Text("Bot Token") },
                placeholder = { Text("123456789:ABCdefGhIJKlmNoPQRsTUVwxyZ") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Channel ID field
            OutlinedTextField(
                value = channelId,
                onValueChange = { value ->
                    channelId = value
                    prefs.telegramChannelId = value
                },
                label = { Text("Channel ID") },
                placeholder = { Text("@your_channel or -1001234567890") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Test connection button
            OutlinedButton(
                onClick = {
                    isTesting = true
                    testResult = null
                    scope.launch {
                        try {
                            val result = connector.testConnection()
                            testSuccess = result.isSuccess
                            testResult = result.getOrElse { e ->
                                e.message ?: "Connection failed"
                            }
                        } catch (e: Exception) {
                            testSuccess = false
                            testResult = e.message ?: "Connection failed"
                        } finally {
                            isTesting = false
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = botToken.isNotBlank() && !isTesting
            ) {
                Text(if (isTesting) "Testing..." else "Test Connection")
            }

            // Test result card
            if (testResult != null) {
                Spacer(modifier = Modifier.height(12.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (testSuccess)
                            Color(0xFF1B5E20).copy(alpha = 0.12f)
                        else
                            MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = if (testSuccess) "Connected!" else "Error",
                            style = MaterialTheme.typography.titleSmall,
                            color = if (testSuccess)
                                Color(0xFF4CAF50)
                            else
                                MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = testResult ?: "",
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace
                            )
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Polling toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Enable Inbound Polling",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "Receive commands via Telegram messages",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Switch(
                    checked = pollingEnabled,
                    onCheckedChange = { enabled ->
                        pollingEnabled = enabled
                        prefs.telegramEnabled = enabled
                    }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
        }

        // ── Fixed bottom bar (always visible, never scrolls) ──────────────
        HorizontalDivider()
        Surface(
            modifier = Modifier.fillMaxWidth(),
            tonalElevation = 3.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Button(
                    onClick = {
                        prefs.onboardingComplete = true
                        onNext()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Finish Setup → Go to Chat")
                }
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        prefs.onboardingComplete = true
                        onNext()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Skip Telegram")
                }
            }
        }
    }
}
