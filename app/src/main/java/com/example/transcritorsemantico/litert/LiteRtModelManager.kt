package com.example.transcritorsemantico.litert

import android.content.Context
import android.net.Uri
import android.content.ContentResolver
import java.io.File

class LiteRtModelManager(private val context: Context) {
    private val modelDir = File(context.filesDir, "memorywave/litert-models")

    init {
        if (!modelDir.exists()) {
            modelDir.mkdirs()
        }
        ensureModelInFilesDir()
    }

    val quickTranscriptionModel: File
        get() = File(modelDir, QUICK_WHISPER_MODEL_NAME)

    fun hasQuickTranscriptionModel(): Boolean {
        return quickTranscriptionModel.exists() && quickTranscriptionModel.length() > 0L
    }

    private fun ensureModelInFilesDir() {
        if (!hasQuickTranscriptionModel()) {
            // Tenta copiar dos assets se existir
            runCatching {
                context.assets.open("models/$QUICK_WHISPER_MODEL_NAME").use { input ->
                    quickTranscriptionModel.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            }
        }
    }

    fun importQuickTranscriptionModel(uri: Uri, resolver: ContentResolver): File {
        val temp = File(quickTranscriptionModel.absolutePath + ".import")
        if (temp.exists()) temp.delete()

        resolver.openInputStream(uri)?.use { input ->
            temp.outputStream().use { output ->
                input.copyTo(output)
            }
        } ?: error("Nao foi possivel ler o modelo LiteRT selecionado.")

        if (temp.length() <= 0L) {
            temp.delete()
            error("O modelo LiteRT selecionado esta vazio.")
        }

        if (quickTranscriptionModel.exists()) quickTranscriptionModel.delete()
        if (!temp.renameTo(quickTranscriptionModel)) {
            temp.copyTo(quickTranscriptionModel, overwrite = true)
            temp.delete()
        }
        return quickTranscriptionModel
    }

    companion object {
        const val QUICK_WHISPER_MODEL_NAME = "whisper-base-transcribe-translate.tflite"
    }
}
