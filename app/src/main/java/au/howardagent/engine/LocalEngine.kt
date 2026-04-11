package au.howardagent.engine

import android.util.Log

class LocalEngine : InferenceEngine {

    companion object {
        private const val TAG = "LocalEngine"
        init {
            System.loadLibrary("howard_jni")
        }
    }

    private var modelLoaded = false
    private var loadedPath: String? = null

    // JNI methods
    external fun loadModel(modelPath: String, nThreads: Int, nCtx: Int): Boolean
    external fun runInference(prompt: String, systemPrompt: String, callback: InferenceCallback)
    external fun stopInference()
    external fun freeModel()

    interface InferenceCallback {
        fun onToken(token: String)
        fun onComplete()
        fun onError(error: String)
    }

    fun isLoaded(): Boolean = modelLoaded
    fun loadedModelPath(): String? = loadedPath

    fun load(modelPath: String, threads: Int = 4, contextSize: Int = 2048): Boolean {
        if (modelLoaded && loadedPath == modelPath) return true
        if (modelLoaded) {
            freeModel()
            modelLoaded = false
            loadedPath = null
        }
        modelLoaded = loadModel(modelPath, threads, contextSize)
        loadedPath = if (modelLoaded) modelPath else null
        Log.i(TAG, "Model loaded: $modelLoaded ($modelPath)")
        return modelLoaded
    }

    override suspend fun infer(
        prompt: String,
        systemPrompt: String,
        onToken: (String) -> Unit,
        onComplete: () -> Unit,
        onError: (String) -> Unit
    ) {
        if (!modelLoaded) {
            onError("No model loaded")
            return
        }

        runInference(prompt, systemPrompt, object : InferenceCallback {
            override fun onToken(token: String) = onToken(token)
            override fun onComplete() = onComplete()
            override fun onError(error: String) = onError(error)
        })
    }

    override fun stop() {
        stopInference()
    }

    override fun release() {
        freeModel()
        modelLoaded = false
        loadedPath = null
    }
}
