package au.howardagent.download

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit

class ModelDownloader(private val context: Context) {

    companion object {
        private const val TAG = "ModelDownloader"
        // "GGUF" in ASCII — first 4 bytes of any valid GGUF file.
        private val GGUF_MAGIC = byteArrayOf(0x47, 0x47, 0x55, 0x46)
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .addInterceptor { chain ->
            chain.proceed(
                chain.request().newBuilder()
                    .header("User-Agent", "Howard-Agent/1.0 (Android)")
                    .header("Accept", "*/*")
                    .build()
            )
        }
        .build()

    private val modelsDir = File(context.filesDir, "models").also { it.mkdirs() }

    fun modelFile(model: ModelInfo): File = File(modelsDir, model.filename)

    fun isDownloaded(model: ModelInfo): Boolean {
        val f = modelFile(model)
        // Only treat a file as "downloaded" if it has plausible size and the
        // GGUF magic header. A 0-byte or HTML error page should not pass.
        if (!f.exists() || f.length() < 1024) return false
        return hasGgufMagic(f)
    }

    /**
     * Delete any downloaded or partial copies of [model]. Used by the UI to
     * recover from a corrupt download.
     */
    fun delete(model: ModelInfo) {
        modelFile(model).delete()
        File(modelsDir, "${model.filename}.tmp").delete()
    }

    fun download(model: ModelInfo): Flow<Float> = flow {
        val file = modelFile(model)
        val tmpFile = File(modelsDir, "${model.filename}.tmp")

        // If there's a finished file already, short-circuit.
        if (file.exists() && hasGgufMagic(file)) {
            emit(1f)
            return@flow
        }

        var downloaded = if (tmpFile.exists()) tmpFile.length() else 0L

        val reqBuilder = Request.Builder().url(model.downloadUrl)
        if (downloaded > 0) {
            reqBuilder.addHeader("Range", "bytes=$downloaded-")
        }

        val response = client.newCall(reqBuilder.build()).execute()
        if (!response.isSuccessful && response.code != 206) {
            response.close()
            throw Exception("Download failed: HTTP ${response.code} for ${model.downloadUrl}")
        }

        val contentType = response.header("Content-Type").orEmpty()
        if (contentType.startsWith("text/html")) {
            response.close()
            throw Exception("Download returned HTML (redirect/login page): $contentType")
        }

        val resuming = response.code == 206
        val totalBytes = if (resuming) {
            val contentRange = response.header("Content-Range")
            contentRange?.substringAfter("/")?.toLongOrNull()
                ?: (downloaded + (response.body?.contentLength() ?: 0))
        } else {
            // Server ignored Range — start fresh and truncate the tmp file.
            downloaded = 0L
            tmpFile.delete()
            response.body?.contentLength() ?: -1L
        }

        // Use RandomAccessFile so the resume case doesn't truncate existing bytes.
        val raf = RandomAccessFile(tmpFile, "rw")
        try {
            raf.seek(downloaded)
            response.body?.byteStream()?.use { input ->
                val buffer = ByteArray(64 * 1024)
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    raf.write(buffer, 0, read)
                    downloaded += read
                    if (totalBytes > 0) {
                        emit((downloaded.toFloat() / totalBytes).coerceIn(0f, 1f))
                    }
                }
            }
        } finally {
            raf.close()
            response.close()
        }

        // Validate what we actually got before renaming.
        if (totalBytes > 0 && downloaded != totalBytes) {
            tmpFile.delete()
            throw Exception(
                "Download truncated: got $downloaded of $totalBytes bytes. Try again."
            )
        }
        if (!hasGgufMagic(tmpFile)) {
            val size = tmpFile.length()
            tmpFile.delete()
            throw Exception(
                "Downloaded file is not a valid GGUF model (size=$size). " +
                    "The host may have returned an error page."
            )
        }

        if (!tmpFile.renameTo(file)) {
            // Fall back to copy if rename fails (e.g. cross-device).
            tmpFile.inputStream().use { i -> file.outputStream().use { o -> i.copyTo(o) } }
            tmpFile.delete()
        }
        Log.i(TAG, "Download complete: ${model.filename} (${file.length()} bytes)")
        emit(1f)
    }.flowOn(Dispatchers.IO)

    private fun hasGgufMagic(file: File): Boolean {
        if (!file.exists() || file.length() < 4) return false
        return try {
            file.inputStream().use { stream ->
                val head = ByteArray(4)
                val n = stream.read(head)
                n == 4 && head.contentEquals(GGUF_MAGIC)
            }
        } catch (_: Exception) {
            false
        }
    }
}
