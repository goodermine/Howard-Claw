package au.howardagent.engine

import au.howardagent.data.SecurePrefs

class EngineRouter(private val prefs: SecurePrefs) {

    private var localEngine: LocalEngine? = null
    private var cloudEngine: CloudEngine? = null
    private var activeEngine: InferenceEngine? = null

    fun getEngine(localModelPath: String? = null): InferenceEngine {
        val provider = prefs.activeProvider

        return when (provider) {
            "local" -> {
                if (localEngine == null) {
                    localEngine = LocalEngine()
                }
                if (localModelPath != null) {
                    localEngine!!.load(localModelPath)
                }
                localEngine!!.also { activeEngine = it }
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
