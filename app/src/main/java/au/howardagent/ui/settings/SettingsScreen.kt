package au.howardagent.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import au.howardagent.HowardApplication
import au.howardagent.BuildConfig
import au.howardagent.connectors.OpenClawConnector
import au.howardagent.data.SecurePrefs
import au.howardagent.download.DeviceDetector
import au.howardagent.download.ModelDownloader
import au.howardagent.download.ModelInfo
import au.howardagent.download.ModelRegistry
import au.howardagent.service.GatewayService
import android.content.Intent
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.items
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs   = HowardApplication.instance.securePrefs
    val device  = remember { DeviceDetector.profile(context) }
    var subScreen by remember { mutableStateOf<String?>(null) }
    var gatewayOnline by remember { mutableStateOf(false) }
    val gatewayDetail by GatewayService.status.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val connector = remember { OpenClawConnector() }

    LaunchedEffect(Unit) {
        while (true) {
            gatewayOnline = connector.isOnline()
            delay(5_000)
        }
    }

    when (subScreen) {
        "models"   -> ModelsSubscreen(prefs) { subScreen = null }
        "apikeys"  -> ApiKeysSubscreen(prefs) { subScreen = null }
        "telegram" -> TelegramSubscreen(prefs) { subScreen = null }
        else -> Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Settings", fontWeight = FontWeight.Bold) },
                    navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") } }
                )
            }
        ) { padding ->
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("Device", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(6.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                                InfoChip("RAM", "${device.ramGb} GB")
                                InfoChip("CPU", "${device.cpuCores} cores")
                                InfoChip("AI", device.suitability.label)
                            }
                        }
                    }
                }

                item {
                    val isStarting = gatewayDetail.startsWith("extracting")
                            || gatewayDetail.startsWith("installing")
                            || gatewayDetail == "starting_server"
                            || gatewayDetail == "restarting"
                    val isStopped = gatewayDetail == "stopped"
                    val hasError = gatewayDetail.startsWith("error")

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = when {
                                gatewayOnline -> MaterialTheme.colorScheme.surfaceVariant
                                hasError -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                                else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                            }
                        )
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                when {
                                    isStarting -> CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp), strokeWidth = 2.dp
                                    )
                                    gatewayOnline -> Icon(
                                        Icons.Default.CheckCircle, null,
                                        tint = Color(0xFF2E7D32)
                                    )
                                    else -> Icon(
                                        Icons.Default.Warning, null,
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        "OpenClaw Gateway",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Text(
                                        when {
                                            gatewayOnline -> "Online — port ${GatewayService.GATEWAY_PORT}"
                                            isStarting -> gatewayDetail.replace("_", " ")
                                                .replaceFirstChar { it.uppercaseChar() } + "..."
                                            hasError -> gatewayDetail.removePrefix("error: ")
                                            else -> "Offline"
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            Spacer(Modifier.height(12.dp))

                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (gatewayOnline || isStarting) {
                                    OutlinedButton(
                                        onClick = {
                                            context.stopService(
                                                Intent(context, GatewayService::class.java)
                                            )
                                        },
                                        modifier = Modifier.weight(1f)
                                    ) { Text("Stop") }
                                } else {
                                    Button(
                                        onClick = {
                                            context.startForegroundService(
                                                Intent(context, GatewayService::class.java)
                                                    .apply { action = GatewayService.ACTION_START }
                                            )
                                        },
                                        modifier = Modifier.weight(1f)
                                    ) { Text("Start Gateway") }
                                }

                                OutlinedButton(
                                    onClick = {
                                        context.stopService(
                                            Intent(context, GatewayService::class.java)
                                        )
                                        scope.launch {
                                            delay(1_500)
                                            context.startForegroundService(
                                                Intent(context, GatewayService::class.java)
                                                    .apply { action = GatewayService.ACTION_START }
                                            )
                                        }
                                    },
                                    modifier = Modifier.weight(1f)
                                ) { Text("Restart") }
                            }
                        }
                    }
                }

                item { Spacer(Modifier.height(4.dp)) }
                item { Text("Configuration", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant) }

                item { SettingsRow(Icons.Default.Build, "Models", "Manage local models") { subScreen = "models" } }
                item { SettingsRow(Icons.Default.Lock, "API Keys", "Cloud provider keys") { subScreen = "apikeys" } }
                item { SettingsRow(Icons.Default.Send, "Telegram", "Bot token & channel") { subScreen = "telegram" } }

                item { Spacer(Modifier.height(4.dp)) }
                item { Text("About", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant) }

                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            InfoRow("Version", BuildConfig.VERSION_NAME)
                            InfoRow("OpenClaw", if (gatewayOnline) "Online" else "Offline")
                            InfoRow("Local LLM", if (prefs.activeProvider == "local") "Active" else "Standby")
                            InfoRow("Telegram", if (prefs.telegramEnabled) "Enabled" else "Disabled")
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsRow(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), onClick = onClick) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.AutoMirrored.Filled.ArrowForward, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun InfoChip(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
    }
}

/* ── Models subscreen ──────────────────────────────────────────────── */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelsSubscreen(prefs: SecurePrefs, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val downloader = remember { ModelDownloader(context) }
    val device = remember { DeviceDetector.profile(context) }
    val models = remember { ModelRegistry.forDevice(device.ramGb) }

    var downloadedIds by remember {
        mutableStateOf(models.filter { downloader.isDownloaded(it) }.map { it.id }.toSet())
    }
    var selectedId by remember { mutableStateOf(prefs.selectedModelId) }
    var downloadingId by remember { mutableStateOf<String?>(null) }
    var downloadProgress by remember { mutableFloatStateOf(0f) }
    var errorText by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Models", fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") } }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                Text(
                    "Tap a downloaded model to make it the active local model. " +
                        "The active model is used whenever you select \"Local GGUF\" in chat.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            errorText?.let {
                item {
                    Text(it, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error)
                }
            }
            items(models, key = { it.id }) { model ->
                val isDownloaded = model.id in downloadedIds
                val isSelected = model.id == selectedId
                val isDownloading = downloadingId == model.id

                fun startDownload() {
                    scope.launch {
                        downloadingId = model.id
                        downloadProgress = 0f
                        errorText = null
                        try {
                            downloader.download(model).collect { p -> downloadProgress = p }
                            downloadedIds = downloadedIds + model.id
                            selectedId = model.id
                            prefs.selectedModelId = model.id
                            prefs.activeProvider = "local"
                        } catch (e: Exception) {
                            errorText = e.message ?: "Download failed"
                        } finally {
                            downloadingId = null
                        }
                    }
                }

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        if (isDownloaded) {
                            selectedId = model.id
                            prefs.selectedModelId = model.id
                            prefs.activeProvider = "local"
                        }
                    },
                    colors = if (isSelected)
                        CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                    else CardDefaults.cardColors()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    model.name + if (isSelected) "  (active)" else "",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    "${model.parameterSize} · ${model.category} · ${model.fileSizeMb} MB",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            when {
                                isDownloading -> CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp), strokeWidth = 2.dp
                                )
                                isDownloaded -> Icon(
                                    Icons.Default.CheckCircle, null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                else -> TextButton(onClick = { startDownload() }) {
                                    Text("Download")
                                }
                            }
                        }
                        if (isDownloading) {
                            Spacer(Modifier.height(8.dp))
                            LinearProgressIndicator(
                                progress = { downloadProgress },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Text(
                                "${(downloadProgress * 100).toInt()}%",
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                        if (isDownloaded && !isDownloading) {
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = {
                                    // Delete corrupt / unwanted copy and forget selection.
                                    downloader.delete(model)
                                    downloadedIds = downloadedIds - model.id
                                    if (selectedId == model.id) {
                                        selectedId = ""
                                        prefs.selectedModelId = ""
                                    }
                                }) { Text("Delete") }
                                TextButton(onClick = {
                                    downloader.delete(model)
                                    downloadedIds = downloadedIds - model.id
                                    startDownload()
                                }) { Text("Redownload") }
                            }
                        }
                    }
                }
            }
        }
    }
}

/* ── API Keys subscreen ────────────────────────────────────────────── */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApiKeysSubscreen(prefs: SecurePrefs, onBack: () -> Unit) {
    var openai by remember { mutableStateOf(prefs.openaiKey) }
    var anthropic by remember { mutableStateOf(prefs.anthropicKey) }
    var gemini by remember { mutableStateOf(prefs.geminiKey) }
    var openrouter by remember { mutableStateOf(prefs.openrouterKey) }
    var ollama by remember { mutableStateOf(prefs.ollamaBaseUrl) }
    var github by remember { mutableStateOf(prefs.githubToken) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("API Keys", fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") } }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "Keys are stored in EncryptedSharedPreferences on this device.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedTextField(
                value = openai,
                onValueChange = { openai = it; prefs.openaiKey = it },
                label = { Text("OpenAI (ChatGPT)") },
                placeholder = { Text("sk-...") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            OutlinedTextField(
                value = anthropic,
                onValueChange = { anthropic = it; prefs.anthropicKey = it },
                label = { Text("Anthropic (Claude)") },
                placeholder = { Text("sk-ant-...") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            OutlinedTextField(
                value = gemini,
                onValueChange = { gemini = it; prefs.geminiKey = it },
                label = { Text("Google Gemini") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            OutlinedTextField(
                value = openrouter,
                onValueChange = { openrouter = it; prefs.openrouterKey = it },
                label = { Text("OpenRouter") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            OutlinedTextField(
                value = ollama,
                onValueChange = { ollama = it; prefs.ollamaBaseUrl = it },
                label = { Text("Ollama base URL") },
                placeholder = { Text("http://192.168.1.10:11434/v1") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            OutlinedTextField(
                value = github,
                onValueChange = { github = it; prefs.githubToken = it },
                label = { Text("GitHub token (optional)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
        }
    }
}

/* ── Telegram subscreen ────────────────────────────────────────────── */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TelegramSubscreen(prefs: SecurePrefs, onBack: () -> Unit) {
    var botToken by remember { mutableStateOf(prefs.telegramBotToken) }
    var channelId by remember { mutableStateOf(prefs.telegramChannelId) }
    var enabled by remember { mutableStateOf(prefs.telegramEnabled) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Telegram", fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") } }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "Connect Howard to a Telegram bot for remote control and notifications.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedTextField(
                value = botToken,
                onValueChange = { botToken = it; prefs.telegramBotToken = it },
                label = { Text("Bot Token") },
                placeholder = { Text("123456789:ABCdef...") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            OutlinedTextField(
                value = channelId,
                onValueChange = { channelId = it; prefs.telegramChannelId = it },
                label = { Text("Channel ID") },
                placeholder = { Text("@your_channel or -100123...") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Enable inbound polling",
                        style = MaterialTheme.typography.bodyMedium)
                    Text("Receive commands sent to the bot",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = { enabled = it; prefs.telegramEnabled = it }
                )
            }
        }
    }
}
