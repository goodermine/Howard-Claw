package au.howardagent

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import au.howardagent.ui.chat.ChatScreen
import au.howardagent.ui.onboarding.OnboardingScreen
import au.howardagent.ui.settings.SettingsScreen
import au.howardagent.ui.tools.ToolsScreen
import au.howardagent.ui.theme.HowardTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val prefs = HowardApplication.instance.securePrefs
        val startDest = if (prefs.onboardingComplete) "chat" else "onboarding"

        setContent {
            HowardTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val navController = rememberNavController()
                    NavHost(navController = navController, startDestination = startDest) {
                        composable("onboarding") {
                            OnboardingScreen(
                                prefs = prefs,
                                onComplete = {
                                    navController.navigate("chat") {
                                        popUpTo("onboarding") { inclusive = true }
                                    }
                                }
                            )
                        }
                        composable("chat") {
                            ChatScreen(
                                onNavigateToTools = { navController.navigate("tools") },
                                onNavigateToSettings = { navController.navigate("settings") }
                            )
                        }
                        composable("tools") {
                            ToolsScreen(onBack = { navController.popBackStack() })
                        }
                        composable("settings") {
                            SettingsScreen(onBack = { navController.popBackStack() })
                        }
                    }
                }
            }
        }
    }
}
