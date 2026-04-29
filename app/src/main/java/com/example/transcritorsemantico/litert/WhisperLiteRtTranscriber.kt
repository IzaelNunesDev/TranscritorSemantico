package com.example.transcritorsemantico.litert

import android.content.Context
import com.example.transcritorsemantico.transcription.BatchTranscriber
import com.example.transcritorsemantico.transcription.BatchTranscriptionResult
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.Environment

class WhisperLiteRtTranscriber(
    context: Context,
) : BatchTranscriber {
    private val modelManager = LiteRtModelManager(context)
    private var environment: Environment? = null
    private var compiledModel: CompiledModel? = null

    override val engineId: String = "litert_whisper"

    override suspend fun transcribeFile(
        filePath: String,
        language: String,
        onProgress: suspend (String) -> Unit,
    ): BatchTranscriptionResult {
        if (!modelManager.hasQuickTranscriptionModel()) {
            error(
                "LiteRT ainda não tem ${LiteRtModelManager.QUICK_WHISPER_MODEL_NAME}. " +
                    "Coloque o modelo em ${modelManager.quickTranscriptionModel.parentFile?.absolutePath}."
            )
        }

        val model = ensureCompiledModel(onProgress)
        val inputBuffers = model.createInputBuffers(SIGNATURE_TRANSCRIBE)
        val outputBuffers = model.createOutputBuffers(SIGNATURE_TRANSCRIBE)
        onProgress(
            "LiteRT compilou ${LiteRtModelManager.QUICK_WHISPER_MODEL_NAME} " +
                "com ${inputBuffers.size} entrada(s) e ${outputBuffers.size} saída(s)."
        )
        error("LiteRT Whisper está compilando, mas o pré/pós-processamento do contrato serving_transcribe ainda precisa ser ligado.")
    }

    private suspend fun ensureCompiledModel(onProgress: suspend (String) -> Unit): CompiledModel {
        compiledModel?.let { return it }

        onProgress("Inicializando LiteRT e escolhendo acelerador NPU > GPU > CPU...")
        val env = environment ?: Environment.create().also { environment = it }
        val options = CompiledModel.Options(Accelerator.NPU, Accelerator.GPU, Accelerator.CPU)
        return CompiledModel.create(modelManager.quickTranscriptionModel.absolutePath, options, env)
            .also { compiledModel = it }
    }

    fun close() {
        compiledModel?.close()
        compiledModel = null
        environment?.close()
        environment = null
    }

    companion object {
        private const val SIGNATURE_TRANSCRIBE = "serving_transcribe"
    }
}
