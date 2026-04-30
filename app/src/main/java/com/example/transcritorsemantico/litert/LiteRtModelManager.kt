package com.example.transcritorsemantico.litert

import android.content.Context
import android.net.Uri
import android.content.ContentResolver
import android.util.Log
import java.io.File

class LiteRtModelManager(private val context: Context) {
    private val modelDir = File(context.filesDir, "memorywave/litert-models")

    init {
        if (!modelDir.exists()) {
            modelDir.mkdirs()
        }
        ensureModelInFilesDir()
        Log.i(LOG_TAG, "LiteRT model manager initialized: ${describeQuickModel()}")
    }

    val quickTranscriptionModel: File
        get() = File(modelDir, QUICK_WHISPER_MODEL_NAME)

    val gemmaAudioModel: File
        get() = File(modelDir, GEMMA_AUDIO_MODEL_NAME)

    fun hasQuickTranscriptionModel(): Boolean {
        return transcriptionModelCandidates().isNotEmpty()
    }

    fun transcriptionModelCandidates(): List<File> {
        return BUNDLED_MODEL_NAMES
            .map { File(modelDir, it) }
            .filter { it.exists() && it.length() > 0L }
    }

    fun preferredTranscriptionModel(): File? {
        return transcriptionModelCandidates().firstOrNull { it.extension.equals("tflite", ignoreCase = true) }
            ?: transcriptionModelCandidates().firstOrNull()
    }

    fun hasGemmaAudioModel(): Boolean {
        return transcriptionModelCandidates().any { it.extension.equals("litertlm", ignoreCase = true) }
    }

    fun describeQuickModel(): String {
        val candidates = transcriptionModelCandidates().joinToString { file ->
            "${file.name}:${file.length()}"
        }
        return "installed=${hasQuickTranscriptionModel()} candidates=[$candidates] dir=${modelDir.absolutePath}"
    }

    private fun ensureModelInFilesDir() {
        BUNDLED_MODEL_NAMES.forEach { assetName ->
            val target = File(modelDir, assetName)
            if (!target.exists() || target.length() <= 0L) {
                runCatching {
                    context.assets.open("models/$assetName").use { input ->
                        target.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    Log.i(LOG_TAG, "LiteRT model copied from assets/$assetName to ${target.absolutePath}")
                }.onFailure {
                    Log.d(LOG_TAG, "LiteRT model assets/$assetName was not found during bootstrap.", it)
                }
            }
        }
    }

    fun importQuickTranscriptionModel(
        uri: Uri,
        resolver: ContentResolver,
        displayName: String? = null,
    ): File {
        val target = when {
            displayName?.endsWith(".litertlm", ignoreCase = true) == true -> gemmaAudioModel
            displayName?.endsWith(".task", ignoreCase = true) == true -> gemmaAudioModel
            else -> quickTranscriptionModel
        }
        val temp = File(target.absolutePath + ".import")
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

        if (target.exists()) target.delete()
        if (!temp.renameTo(target)) {
            temp.copyTo(target, overwrite = true)
            temp.delete()
        }
        Log.i(LOG_TAG, "LiteRT model imported by user: ${describeQuickModel()}")
        return target
    }

    companion object {
        private const val LOG_TAG = "LiteRtModelManager"
        const val QUICK_WHISPER_MODEL_NAME = "whisper-base-transcribe-translate.tflite"
        const val GEMMA_AUDIO_MODEL_NAME = "gemma-audio.litertlm"
        private val BUNDLED_MODEL_NAMES = listOf(
            GEMMA_AUDIO_MODEL_NAME,
            QUICK_WHISPER_MODEL_NAME,
            "whisper-base.pt.tflite",
        )
    }
}
