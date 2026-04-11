package au.howardagent.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class SecurePrefs(context: Context) {

    companion object {
        private const val TAG = "SecurePrefs"
        private const val PREFS_NAME = "howard_secure_prefs"
    }

    private val prefs: SharedPreferences = try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        Log.e(TAG, "EncryptedSharedPreferences failed, falling back to plain prefs: ${e.message}")
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    var onboardingComplete: Boolean
        get() = prefs.getBoolean("onboarding_complete", false)
        set(value) = prefs.edit().putBoolean("onboarding_complete", value).apply()

    var activeProvider: String
        get() = prefs.getString("active_provider", "local") ?: "local"
        set(value) = prefs.edit().putString("active_provider", value).apply()

    var selectedModelId: String
        get() = prefs.getString("selected_model_id", "") ?: ""
        set(value) = prefs.edit().putString("selected_model_id", value).apply()

    // Cloud API keys
    var openaiKey: String
        get() = prefs.getString("openai_key", "") ?: ""
        set(value) = prefs.edit().putString("openai_key", value).apply()

    var anthropicKey: String
        get() = prefs.getString("anthropic_key", "") ?: ""
        set(value) = prefs.edit().putString("anthropic_key", value).apply()

    var geminiKey: String
        get() = prefs.getString("gemini_key", "") ?: ""
        set(value) = prefs.edit().putString("gemini_key", value).apply()

    var openrouterKey: String
        get() = prefs.getString("openrouter_key", "") ?: ""
        set(value) = prefs.edit().putString("openrouter_key", value).apply()

    // Ollama
    var ollamaBaseUrl: String
        get() = prefs.getString("ollama_base_url", "") ?: ""
        set(value) = prefs.edit().putString("ollama_base_url", value).apply()

    // GitHub
    var githubToken: String
        get() = prefs.getString("github_token", "") ?: ""
        set(value) = prefs.edit().putString("github_token", value).apply()

    // Telegram
    var telegramBotToken: String
        get() = prefs.getString("telegram_bot_token", "") ?: ""
        set(value) = prefs.edit().putString("telegram_bot_token", value).apply()

    var telegramChannelId: String
        get() = prefs.getString("telegram_channel_id", "") ?: ""
        set(value) = prefs.edit().putString("telegram_channel_id", value).apply()

    var telegramEnabled: Boolean
        get() = prefs.getBoolean("telegram_enabled", false)
        set(value) = prefs.edit().putBoolean("telegram_enabled", value).apply()

    fun getApiKey(provider: String): String = when (provider) {
        "openai"     -> openaiKey
        "anthropic"  -> anthropicKey
        "gemini"     -> geminiKey
        "openrouter" -> openrouterKey
        else         -> ""
    }
}
