package au.howardagent.engine

import android.content.Context
import au.howardagent.data.SecurePrefs
import au.howardagent.download.ModelDownloader
import au.howardagent.download.ModelRegistry

class EngineRouter(
    private val context: Context,
    private val prefs: SecurePrefs
) {

    private var localEngine: LocalEngine? = null
    private var cloudEngine: CloudEngine? = null
    private var activeEngine: InferenceEngine? = null

    /**
     * Returns an inference engine for the active provider.
     *
     * For "local", resolves [SecurePrefs.selectedModelId] via [ModelRegistry] +
     * [ModelDownloader] to a concrete file path and ensures the LocalEngine is
     * loaded with it. Throws if no model is selected or the file does not exist
     * — callers should surface that as a user-visible error so the user knows to
     * download/select a model first.
     */
    fun getEngine(): InferenceEngine {
        val provider = prefs.activeProvider

        return when (provider) {
            "local" -> {
                val engine = localEngine ?: LocalEngine().also { localEngine = it }

                val modelId = prefs.selectedModelId
                if (modelId.isBlank()) {
                    throw IllegalStateException(
                        "No local model selected. Open Settings → Models, or download a model in onboarding."
                    )
                }
                val modelInfo = ModelRegistry.getById(modelId)
                    ?: throw IllegalStateException("Selected model '$modelId' not found in registry.")
                val file = ModelDownloader(context).modelFile(modelInfo)
                if (!file.exists()) {
                    throw IllegalStateException(
                        "Model file missing: ${file.name}. Re-download from Settings → Models."
                    )
                }

                if (!engine.isLoaded() || engine.loadedModelPath() != file.absolutePath) {
                    val ok = engine.load(file.absolutePath)
                    if (!ok) {
                        throw IllegalStateException("Failed to load model: ${file.name}")
                    }
                }
                engine.also { activeEngine = it }
            }
            else -> {
                val cloudProvider = try {
                    CloudProvider.valueOf(provider.uppercase())
                } catch (_: Exception) {
                    CloudProvider.OPENROUTER
                }
                CloudEngine(cloudProvider, prefs).also {
                    cloudEngine = it
                    activeEngine = it
                }
            }
        }
    }

    fun stopCurrent() {
        activeEngine?.stop()
    }

    fun releaseAll() {
        localEngine?.release()
        cloudEngine?.release()
        localEngine = null
        cloudEngine = null
        activeEngine = null
    }
}
