package au.howardagent.download

data class ModelInfo(
    val id: String,
    val name: String,
    val category: String,
    val parameterSize: String,
    val fileSizeMb: Int,
    val ramRequiredGb: Float,
    val downloadUrl: String,
    val filename: String
)

object ModelRegistry {

    private val models = listOf(
        // Thinking models
        ModelInfo("qwen3-0.6b", "Qwen3 0.6B", "Thinking", "0.6B", 462, 1f,
            "https://huggingface.co/bartowski/Qwen_Qwen3-0.6B-GGUF/resolve/main/Qwen_Qwen3-0.6B-Q4_K_M.gguf",
            "qwen3-0.6b-q4km.gguf"),
        ModelInfo("qwen3-1.7b", "Qwen3 1.7B", "Thinking", "1.7B", 1223, 2f,
            "https://huggingface.co/bartowski/Qwen_Qwen3-1.7B-GGUF/resolve/main/Qwen_Qwen3-1.7B-Q4_K_M.gguf",
            "qwen3-1.7b-q4km.gguf"),
        ModelInfo("qwen3-4b", "Qwen3 4B", "Thinking", "4B", 2381, 3.5f,
            "https://huggingface.co/bartowski/Qwen_Qwen3-4B-GGUF/resolve/main/Qwen_Qwen3-4B-Q4_K_M.gguf",
            "qwen3-4b-q4km.gguf"),

        // Standard models
        ModelInfo("smollm3-3b", "SmolLM3 3B", "Standard", "3B", 1826, 3f,
            "https://huggingface.co/bartowski/HuggingFaceTB_SmolLM3-3B-GGUF/resolve/main/HuggingFaceTB_SmolLM3-3B-Q4_K_M.gguf",
            "smollm3-3b-q4km.gguf"),
        ModelInfo("gemma2-2b", "Gemma 2 2B", "Standard", "2B", 1629, 2.5f,
            "https://huggingface.co/bartowski/gemma-2-2b-it-GGUF/resolve/main/gemma-2-2b-it-Q4_K_M.gguf",
            "gemma2-2b-q4km.gguf"),

        // Agent models
        ModelInfo("llama31-8b", "Llama 3.1 8B", "Agent", "8B", 4692, 5.5f,
            "https://huggingface.co/bartowski/Meta-Llama-3.1-8B-Instruct-GGUF/resolve/main/Meta-Llama-3.1-8B-Instruct-Q4_K_M.gguf",
            "llama31-8b-q4km.gguf"),
        ModelInfo("mistral-7b", "Mistral 7B", "Agent", "7B", 4170, 5f,
            "https://huggingface.co/bartowski/Mistral-7B-Instruct-v0.3-GGUF/resolve/main/Mistral-7B-Instruct-v0.3-Q4_K_M.gguf",
            "mistral-7b-q4km.gguf"),
    )

    fun all(): List<ModelInfo> = models

    fun getById(id: String): ModelInfo? = models.find { it.id == id }

    fun forDevice(ramGb: Int): List<ModelInfo> {
        val maxGb = DeviceDetector.maxRecommendedModelGb(ramGb)
        return models.filter { it.ramRequiredGb <= maxGb }
    }

    fun categories(): List<String> = models.map { it.category }.distinct()
}
