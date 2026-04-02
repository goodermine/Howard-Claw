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
import java.util.concurrent.TimeUnit

class ModelDownloader(private val context: Context) {

    companion object {
        private const val TAG = "ModelDownloader"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .build()

    private val modelsDir = File(context.filesDir, "models").also { it.mkdirs() }

    fun modelFile(model: ModelInfo): File = File(modelsDir, model.filename)

    fun isDownloaded(model: ModelInfo): Boolean = modelFile(model).exists()

    fun download(model: ModelInfo): Flow<Float> = flow {
        val file = modelFile(model)
        val tmpFile = File(modelsDir, "${model.filename}.tmp")

        var downloaded = if (tmpFile.exists()) tmpFile.length() else 0L

        val reqBuilder = Request.Builder().url(model.downloadUrl)
        if (downloaded > 0) {
            reqBuilder.addHeader("Range", "bytes=$downloaded-")
        }

        val response = client.newCall(reqBuilder.build()).execute()
        if (!response.isSuccessful && response.code != 206) {
            throw Exception("Download failed: HTTP ${response.code}")
        }

        val totalBytes = if (response.code == 206) {
            val contentRange = response.header("Content-Range")
            contentRange?.substringAfter("/")?.toLongOrNull() ?: (downloaded + (response.body?.contentLength() ?: 0))
        } else {
            downloaded = 0
            response.body?.contentLength() ?: 0
        }

        val outputStream = if (downloaded > 0) {
            tmpFile.outputStream().apply { channel.position(downloaded) }
        } else {
            tmpFile.outputStream()
        }

        response.body?.byteStream()?.use { input ->
            outputStream.use { output ->
                val buffer = ByteArray(8192)
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    output.write(buffer, 0, read)
                    downloaded += read
                    if (totalBytes > 0) {
                        emit(downloaded.toFloat() / totalBytes)
                    }
                }
            }
        }

        tmpFile.renameTo(file)
        Log.i(TAG, "Download complete: ${model.filename}")
        emit(1f)
    }.flowOn(Dispatchers.IO)
}
