#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>
#include "llama.h"

#define TAG "HowardJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// Inline helper (replaces removed llama_batch_add from common)
static void howard_batch_add(struct llama_batch & batch, llama_token id, llama_pos pos, llama_seq_id seq_id, bool logits) {
    batch.token   [batch.n_tokens] = id;
    batch.pos     [batch.n_tokens] = pos;
    batch.n_seq_id[batch.n_tokens] = 1;
    batch.seq_id  [batch.n_tokens][0] = seq_id;
    batch.logits  [batch.n_tokens] = logits ? 1 : 0;
    batch.n_tokens++;
}

static llama_model  *g_model   = nullptr;
static llama_context *g_ctx    = nullptr;
static bool           g_abort  = false;

extern "C" {

JNIEXPORT jboolean JNICALL
Java_au_howardagent_engine_LocalEngine_loadModel(
    JNIEnv *env, jobject thiz,
    jstring modelPath, jint nThreads, jint nCtx
) {
    if (g_model) {
        llama_free(g_ctx);
        llama_model_free(g_model);
        g_ctx = nullptr;
        g_model = nullptr;
    }

    const char *path = env->GetStringUTFChars(modelPath, nullptr);
    LOGI("Loading model: %s (threads=%d, ctx=%d)", path, nThreads, nCtx);

    llama_model_params model_params = llama_model_default_params();
    model_params.n_gpu_layers = 0; // CPU-only on Android

    g_model = llama_model_load_from_file(path, model_params);
    env->ReleaseStringUTFChars(modelPath, path);

    if (!g_model) {
        LOGE("Failed to load model");
        return JNI_FALSE;
    }

    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx     = nCtx;
    ctx_params.n_threads = nThreads;
    ctx_params.n_threads_batch = nThreads;

    g_ctx = llama_init_from_model(g_model, ctx_params);
    if (!g_ctx) {
        LOGE("Failed to create context");
        llama_model_free(g_model);
        g_model = nullptr;
        return JNI_FALSE;
    }

    g_abort = false;
    LOGI("Model loaded successfully");
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_au_howardagent_engine_LocalEngine_runInference(
    JNIEnv *env, jobject thiz,
    jstring prompt, jstring systemPrompt, jobject callback
) {
    if (!g_model || !g_ctx) {
        LOGE("Model not loaded");
        // Call onError
        jclass cbClass = env->GetObjectClass(callback);
        jmethodID onError = env->GetMethodID(cbClass, "onError", "(Ljava/lang/String;)V");
        jstring err = env->NewStringUTF("Model not loaded");
        env->CallVoidMethod(callback, onError, err);
        return;
    }

    g_abort = false;

    const char *promptStr = env->GetStringUTFChars(prompt, nullptr);
    const char *sysStr    = env->GetStringUTFChars(systemPrompt, nullptr);

    // Build the full prompt
    std::string fullPrompt = std::string(sysStr) + "\n\nUser: " + promptStr + "\n\nHoward:";

    env->ReleaseStringUTFChars(prompt, promptStr);
    env->ReleaseStringUTFChars(systemPrompt, sysStr);

    // Tokenize
    const llama_vocab *vocab = llama_model_get_vocab(g_model);
    int n_prompt_tokens = fullPrompt.length() + 32;
    std::vector<llama_token> tokens(n_prompt_tokens);
    n_prompt_tokens = llama_tokenize(vocab, fullPrompt.c_str(), fullPrompt.length(),
                                     tokens.data(), tokens.size(), true, true);
    if (n_prompt_tokens < 0) {
        tokens.resize(-n_prompt_tokens);
        n_prompt_tokens = llama_tokenize(vocab, fullPrompt.c_str(), fullPrompt.length(),
                                         tokens.data(), tokens.size(), true, true);
    }
    tokens.resize(n_prompt_tokens);

    // Clear KV cache
    llama_memory_clear(llama_get_memory(g_ctx), true);

    // Process prompt in batch
    llama_batch batch = llama_batch_init(n_prompt_tokens, 0, 1);
    for (int i = 0; i < n_prompt_tokens; i++) {
        howard_batch_add(batch, tokens[i], i, 0, false);
    }
    batch.logits[batch.n_tokens - 1] = true;

    if (llama_decode(g_ctx, batch) != 0) {
        jclass cbClass = env->GetObjectClass(callback);
        jmethodID onError = env->GetMethodID(cbClass, "onError", "(Ljava/lang/String;)V");
        jstring err = env->NewStringUTF("Failed to decode prompt");
        env->CallVoidMethod(callback, onError, err);
        llama_batch_free(batch);
        return;
    }
    llama_batch_free(batch);

    // Get callback methods
    jclass cbClass = env->GetObjectClass(callback);
    jmethodID onToken    = env->GetMethodID(cbClass, "onToken", "(Ljava/lang/String;)V");
    jmethodID onComplete = env->GetMethodID(cbClass, "onComplete", "()V");
    jmethodID onError    = env->GetMethodID(cbClass, "onError", "(Ljava/lang/String;)V");

    // Generate tokens
    int n_cur = n_prompt_tokens;
    int n_max = llama_n_ctx(g_ctx);

    llama_sampler *sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(sampler, llama_sampler_init_temp(0.7f));
    llama_sampler_chain_add(sampler, llama_sampler_init_dist(42));

    while (n_cur < n_max && !g_abort) {
        llama_token new_token = llama_sampler_sample(sampler, g_ctx, -1);

        if (llama_vocab_is_eog(vocab, new_token)) {
            break;
        }

        // Convert token to string
        char buf[256];
        int n = llama_token_to_piece(vocab, new_token, buf, sizeof(buf), 0, true);
        if (n > 0) {
            std::string piece(buf, n);
            jstring jPiece = env->NewStringUTF(piece.c_str());
            env->CallVoidMethod(callback, onToken, jPiece);
            env->DeleteLocalRef(jPiece);
        }

        // Decode next
        llama_batch next_batch = llama_batch_init(1, 0, 1);
        howard_batch_add(next_batch, new_token, n_cur, 0, true);
        if (llama_decode(g_ctx, next_batch) != 0) {
            llama_batch_free(next_batch);
            jstring err = env->NewStringUTF("Decode failed during generation");
            env->CallVoidMethod(callback, onError, err);
            llama_sampler_free(sampler);
            return;
        }
        llama_batch_free(next_batch);
        n_cur++;
    }

    llama_sampler_free(sampler);
    env->CallVoidMethod(callback, onComplete);
}

JNIEXPORT void JNICALL
Java_au_howardagent_engine_LocalEngine_stopInference(
    JNIEnv *env, jobject thiz
) {
    g_abort = true;
}

JNIEXPORT void JNICALL
Java_au_howardagent_engine_LocalEngine_freeModel(
    JNIEnv *env, jobject thiz
) {
    if (g_ctx) {
        llama_free(g_ctx);
        g_ctx = nullptr;
    }
    if (g_model) {
        llama_model_free(g_model);
        g_model = nullptr;
    }
    LOGI("Model freed");
}

} // extern "C"
