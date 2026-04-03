package au.howardagent.ui.onboarding

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import au.howardagent.data.SecurePrefs
import au.howardagent.download.DeviceDetector
import au.howardagent.download.ModelDownloader
import au.howardagent.download.ModelInfo
import au.howardagent.download.ModelRegistry
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

@Composable
fun Step1_ModelDownload(prefs: SecurePrefs, onNext: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val profile = remember { DeviceDetector.profile(context) }
    val models = remember { ModelRegistry.forDevice(profile.ramGb) }
    val downloader = remember { ModelDownloader(context) }
    var downloadingId by remember { mutableStateOf<String?>(null) }
    var downloadProgress by remember { mutableFloatStateOf(0f) }
    var downloadedIds by remember {
        mutableStateOf(models.filter { downloader.isDownloaded(it) }.map { it.id }.toSet())
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(
            text = "Download a Model",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )

        Text(
            text = "Device: ${profile.soc} · ${profile.ramGb}GB RAM · ${profile.suitability.label}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 8.dp)
        )

        Text(
            text = "Optional — you can skip this and use cloud providers instead.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(16.dp))

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(models, key = { it.id }) { model ->
                ModelCard(
                    model = model,
                    isDownloaded = model.id in downloadedIds,
                    isDownloading = downloadingId == model.id,
                    progress = if (downloadingId == model.id) downloadProgress else 0f,
                    onDownload = {
                        scope.launch {
                            downloadingId = model.id
                            downloadProgress = 0f
                            downloader.download(model).collect { progress ->
                                downloadProgress = progress
                            }
                            downloadedIds = downloadedIds + model.id
                            downloadingId = null
                        }
                    }
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        Button(
            onClick = onNext,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Continue")
        }
    }
}

@Composable
private fun ModelCard(
    model: ModelInfo,
    isDownloaded: Boolean,
    isDownloading: Boolean,
    progress: Float,
    onDownload: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = model.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "${model.parameterSize} · ${model.category} · ${model.fileSizeMb}MB",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                when {
                    isDownloaded -> Icon(
                        Icons.Default.Check,
                        contentDescription = "Downloaded",
                        tint = MaterialTheme.colorScheme.primary
                    )
                    isDownloading -> CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp
                    )
                    else -> IconButton(onClick = onDownload) {
                        Icon(Icons.Default.ArrowDropDown, contentDescription = "Download")
                    }
                }
            }

            if (isDownloading) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = "${(progress * 100).toInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}
