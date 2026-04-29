package com.example.transcritorsemantico.whisper

import android.content.Context
import com.whispercpp.whisper.WhisperContext
import com.whispercpp.whisper.WhisperSegment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class WhisperFileTranscriber(
    private val context: Context,
) {
    data class Result(
        val segments: List<WhisperSegment>,
        val speechWindowCount: Int,
        val speechSeconds: Float,
        val totalSeconds: Float,
    )

    private val mutex = Mutex()
    private var loadedModelPath: String? = null
    private var whisperContext: WhisperContext? = null

    suspend fun transcribeFile(
        filePath: String,
        modelPath: String,
        language: String = "auto",
        onProgress: suspend (String) -> Unit,
    ): Result = mutex.withLock {
        onProgress("Lendo e convertendo áudio para 16 kHz mono...")
        val samples = withContext(Dispatchers.IO) {
            AudioDecodeUtil.decodeToWhisperInput(filePath)
        }
        val totalSeconds = samples.size / 16000f
        onProgress("Áudio pronto. ${"%.1f".format(totalSeconds)} segundos detectados em 16 kHz mono.")

        ensureContext(modelPath, onProgress)

        onProgress("Rodando VAD local para isolar trechos com fala...")
        val windows = withContext(Dispatchers.Default) {
            WhisperVad.detectSpeechWindows(samples)
        }
        if (windows.isEmpty()) {
            error("Nenhuma fala audível foi detectada no arquivo.")
        }
        val speechSeconds = windows.sumOf { it.lengthSamples.toLong() }.toFloat() / 16000f
        onProgress(
            "VAD encontrou ${windows.size} trecho(s) com fala " +
                "em ${"%.1f".format(speechSeconds)}s de áudio útil."
        )

        val segments = mutableListOf<WhisperSegment>()
        windows.forEachIndexed { index, window ->
            val slice = samples.copyOfRange(window.startSample, window.endSample)
            val chunkDurationSeconds = slice.size / 16000f
            onProgress(
                "Transcrevendo trecho ${index + 1}/${windows.size} " +
                "(${String.format("%.1f", chunkDurationSeconds)}s)..."
            )
            val chunkSegments = whisperContext?.transcribeSegments(slice, language).orEmpty()
            segments += chunkSegments.map {
                it.copy(
                    startMs = it.startMs + ((window.startSample / 16_000f) * 1000).toLong(),
                    endMs = it.endMs + ((window.startSample / 16_000f) * 1000).toLong(),
                )
            }
        }

        onProgress("Transcrição concluída com ${segments.size} segmentos.")
        return Result(
            segments = segments,
            speechWindowCount = windows.size,
            speechSeconds = speechSeconds,
            totalSeconds = totalSeconds,
        )
    }

    private suspend fun ensureContext(
        modelPath: String,
        onProgress: suspend (String) -> Unit,
    ) {
        if (loadedModelPath == modelPath && whisperContext != null) {
            return
        }
        whisperContext?.release()
        onProgress("Carregando modelo Whisper...")
        whisperContext = withContext(Dispatchers.IO) {
            WhisperContext.createContextFromFile(modelPath)
        }
        loadedModelPath = modelPath
    }
}
