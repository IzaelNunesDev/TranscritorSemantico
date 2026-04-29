package com.example.transcritorsemantico.whisper

import android.content.Context
import com.example.transcritorsemantico.transcription.BatchTranscriber
import com.example.transcritorsemantico.transcription.BatchTranscriptionResult
import com.whispercpp.whisper.WhisperContext
import com.whispercpp.whisper.WhisperSegment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class WhisperFileTranscriber(
    private val context: Context,
) : BatchTranscriber {
    override val engineId: String = "whisper_cpp"

    private val mutex = Mutex()
    private var loadedModelPath: String? = null
    private var whisperContext: WhisperContext? = null

    suspend fun transcribeFile(
        filePath: String,
        modelPath: String,
        language: String = "auto",
        onProgress: suspend (String) -> Unit,
    ): BatchTranscriptionResult = mutex.withLock {
        ensureContext(modelPath, onProgress)

        onProgress("Decodificando mídia em lotes de 30s para evitar OOM...")
        val segments = mutableListOf<WhisperSegment>()
        var speechWindowCount = 0
        var speechSeconds = 0f
        var decodedChunkCount = 0
        val totalDurationMs = withContext(Dispatchers.IO) {
            AudioDecodeUtil.durationMs(filePath)
        }.takeIf { it > 0L } ?: Long.MAX_VALUE

        var startMs = 0L
        var processedEndMs = 0L
        while (startMs < totalDurationMs) {
            val endMs = minOf(startMs + DECODE_CHUNK_MS, totalDurationMs)
            val decoded = withContext(Dispatchers.IO) {
                AudioDecodeUtil.decodeToWhisperChunk(filePath, startMs, endMs)
            }
            if (decoded.samples.isEmpty()) break

            decodedChunkCount += 1
            val samples = decoded.samples
            val windows = withContext(Dispatchers.Default) {
                WhisperVad.detectSpeechWindows(samples)
            }
            speechWindowCount += windows.size
            speechSeconds += windows.sumOf { it.lengthSamples.toLong() }.toFloat() / AudioDecodeUtil.WHISPER_SAMPLE_RATE

            windows.forEachIndexed { index, window ->
                val chunkDurationSeconds = window.lengthSamples / AudioDecodeUtil.WHISPER_SAMPLE_RATE.toFloat()
                onProgress(
                    "Lote $decodedChunkCount: transcrevendo fala ${index + 1}/${windows.size} " +
                        "(${String.format("%.1f", chunkDurationSeconds)}s)..."
                )
                val chunkSegments = whisperContext?.transcribeSegments(
                    data = samples,
                    language = language,
                    startSample = window.startSample,
                    sampleCount = window.lengthSamples,
                ).orEmpty()
                val windowStartMs = ((window.startSample * 1000L) / AudioDecodeUtil.WHISPER_SAMPLE_RATE)
                segments += chunkSegments.map {
                    it.copy(
                        startMs = it.startMs + decoded.startMs + windowStartMs,
                        endMs = it.endMs + decoded.startMs + windowStartMs,
                    )
                }
            }

            processedEndMs = endMs
            if (totalDurationMs == Long.MAX_VALUE && decoded.samples.size < AudioDecodeUtil.WHISPER_SAMPLE_RATE) {
                break
            }
            startMs = endMs
        }

        if (segments.isEmpty()) {
            error("Nenhuma fala audível foi detectada no arquivo.")
        }

        onProgress("Transcrição concluída com ${segments.size} segmentos.")
        return BatchTranscriptionResult(
            segments = segments,
            speechWindowCount = speechWindowCount,
            speechSeconds = speechSeconds,
            totalSeconds = ((if (totalDurationMs == Long.MAX_VALUE) processedEndMs else totalDurationMs) / 1000f),
            engineId = engineId,
        )
    }

    companion object {
        private const val DECODE_CHUNK_MS = 30_000L
    }

    override suspend fun transcribeFile(
        filePath: String,
        language: String,
        onProgress: suspend (String) -> Unit,
    ): BatchTranscriptionResult {
        error("Whisper.cpp legacy transcription needs an explicit GGML model path.")
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
