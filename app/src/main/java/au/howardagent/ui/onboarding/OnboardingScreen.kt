package au.howardagent.ui.onboarding

import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import au.howardagent.data.SecurePrefs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingScreen(
    prefs: SecurePrefs,
    onComplete: () -> Unit
) {
    var step by remember { mutableIntStateOf(0) }
    val totalSteps = 5

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Howard Setup") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
        ) {
            // Progress indicator
            LinearProgressIndicator(
                progress = { (step + 1).toFloat() / totalSteps },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            )

            Text(
                text = "Step ${step + 1} of $totalSteps",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )

            // Step content
            AnimatedContent(
                targetState = step,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                label = "onboarding_step"
            ) { currentStep ->
                when (currentStep) {
                    0 -> NoticeStep(onNext = { step = 1 })
                    1 -> Step1_ModelDownload(prefs = prefs, onNext = { step = 2 })
                    2 -> Step2_CloudKeys(prefs = prefs, onNext = { step = 3 })
                    3 -> OpenClawStep(prefs = prefs, onNext = { step = 4 })
                    4 -> ConnectStep(prefs = prefs, onNext = {
                        prefs.onboardingComplete = true
                        onComplete()
                    })
                }
            }

            // Back button (except on first step) - compact so it doesn't
            // eat into the step's own bottom button bar
            if (step > 0) {
                TextButton(
                    onClick = { step-- },
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text("← Back")
                }
            }
        }
    }
}
