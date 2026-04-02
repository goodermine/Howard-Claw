package au.howardagent.ui.onboarding

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.Error
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import au.howardagent.openclaw.OpenClawConnector
import kotlinx.coroutines.delay

enum class StepStatus {
    CHECKING,
    LOADING,
    DONE,
    ERROR
}

@Composable
fun OpenClawStep(
    connector: OpenClawConnector,
    onComplete: () -> Unit
) {
    var nodeStatus by remember { mutableStateOf(StepStatus.CHECKING) }
    var openClawStatus by remember { mutableStateOf(StepStatus.CHECKING) }
    var gatewayStatus by remember { mutableStateOf(StepStatus.CHECKING) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var retryTrigger by remember { mutableIntStateOf(0) }

    LaunchedEffect(retryTrigger) {
        errorMessage = null
        nodeStatus = StepStatus.LOADING
        openClawStatus = StepStatus.CHECKING
        gatewayStatus = StepStatus.CHECKING

        try {
            // Step 1: Node.js extraction
            delay(500)
            nodeStatus = StepStatus.DONE

            // Step 2: OpenClaw extraction
            openClawStatus = StepStatus.LOADING
            delay(500)
            openClawStatus = StepStatus.DONE

            // Step 3: Gateway start - retry up to 10 times
            gatewayStatus = StepStatus.LOADING
            var online = false
            for (attempt in 1..10) {
                online = connector.isGatewayOnline()
                if (online) break
                delay(2000)
            }

            if (online) {
                gatewayStatus = StepStatus.DONE
                onComplete()
            } else {
                gatewayStatus = StepStatus.ERROR
                errorMessage = "Gateway did not come online after 10 attempts"
            }
        } catch (e: Exception) {
            nodeStatus = if (nodeStatus == StepStatus.LOADING) StepStatus.ERROR else nodeStatus
            openClawStatus = if (openClawStatus == StepStatus.LOADING) StepStatus.ERROR else openClawStatus
            gatewayStatus = if (gatewayStatus == StepStatus.LOADING) StepStatus.ERROR else gatewayStatus
            errorMessage = e.message ?: "Unknown error during setup"
        }
    }

    val allDone = nodeStatus == StepStatus.DONE &&
            openClawStatus == StepStatus.DONE &&
            gatewayStatus == StepStatus.DONE

    val cardColor by animateColorAsState(
        targetValue = when {
            allDone -> Color(0xFF1B5E20).copy(alpha = 0.12f)
            errorMessage != null -> MaterialTheme.colorScheme.errorContainer
            else -> MaterialTheme.colorScheme.surfaceVariant
        },
        label = "cardColor"
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        Text(
            text = "OpenClaw Gateway",
            style = MaterialTheme.typography.headlineSmall
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Setting up the local AI gateway...",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(16.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = cardColor)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                StatusRow(label = "Node.js runtime", status = nodeStatus)
                StatusRow(label = "OpenClaw server", status = openClawStatus)
                StatusRow(label = "Gateway start", status = gatewayStatus)
            }
        }

        // Error message and retry
        if (errorMessage != null) {
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = errorMessage ?: "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = { retryTrigger++ },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = "Retry",
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Retry")
            }
        }

        // Success message
        if (allDone) {
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Gateway is online and ready!",
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFF4CAF50)
            )
        }
    }
}

@Composable
private fun StatusRow(
    label: String,
    status: StepStatus
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        when (status) {
            StepStatus.CHECKING -> {
                Icon(
                    imageVector = Icons.Outlined.Circle,
                    contentDescription = "Pending",
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            StepStatus.LOADING -> {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp
                )
            }
            StepStatus.DONE -> {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = "Done",
                    modifier = Modifier.size(20.dp),
                    tint = Color(0xFF4CAF50)
                )
            }
            StepStatus.ERROR -> {
                Icon(
                    imageVector = Icons.Outlined.Error,
                    contentDescription = "Error",
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}
