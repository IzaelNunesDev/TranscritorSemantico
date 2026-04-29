package com.example.transcritorsemantico.whisper

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class WhisperModelManager(
    private val context: Context,
) {
    private val modelDir = File(context.filesDir, "memorywave/whisper-models")

    init {
        if (!modelDir.exists()) {
            modelDir.mkdirs()
        }
    }

    fun modelFile(model: WhisperModel): File = File(modelDir, model.fileName)

    fun installedModelIds(): List<String> {
        return WhisperModel.entries
            .filter { modelFile(it).exists() && modelFile(it).length() > 0L }
            .map { it.id }
    }

    suspend fun ensureModel(
        model: WhisperModel,
        onProgress: suspend (String) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        val target = modelFile(model)
        if (target.exists() && target.length() > 0L) {
            onProgress("Modelo ${model.title} pronto no aparelho.")
            return@withContext target
        }

        val temp = File(target.absolutePath + ".download")
        if (temp.exists()) temp.delete()

        onProgress("Baixando modelo ${model.title} ${model.sizeLabel}...")
        val connection = (URL(model.remoteUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 30_000
            instanceFollowRedirects = true
        }

        try {
            connection.connect()
            if (connection.responseCode !in 200..299) {
                error("Falha ao baixar modelo Whisper (${connection.responseCode}).")
            }
            val totalBytes = connection.contentLengthLong
            connection.inputStream.use { input ->
                temp.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var copied = 0L
                    var read = input.read(buffer)
                    var lastPercent = -1
                    while (read >= 0) {
                        output.write(buffer, 0, read)
                        copied += read
                        if (totalBytes > 0) {
                            val percent = ((copied * 100) / totalBytes).toInt()
                            if (percent != lastPercent && percent % 5 == 0) {
                                lastPercent = percent
                                onProgress("Baixando ${model.title}: $percent%")
                            }
                        }
                        read = input.read(buffer)
                    }
                }
            }
            if (target.exists()) target.delete()
            temp.renameTo(target)
            onProgress("Modelo ${model.title} concluído.")
            return@withContext target
        } finally {
            connection.disconnect()
            if (temp.exists() && !target.exists()) {
                temp.delete()
            }
        }
    }
}
