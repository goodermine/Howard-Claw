package au.howardagent.engine

interface InferenceEngine {
    suspend fun infer(
        prompt: String,
        systemPrompt: String,
        onToken: (String) -> Unit,
        onComplete: () -> Unit,
        onError: (String) -> Unit
    )

    fun stop()
    fun release()
}
