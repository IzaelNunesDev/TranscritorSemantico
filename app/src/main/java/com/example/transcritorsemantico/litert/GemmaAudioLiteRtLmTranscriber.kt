package com.example.transcritorsemantico.litert

import android.content.Context
import android.util.Log
import com.example.transcritorsemantico.transcription.BatchTranscriber
import com.example.transcritorsemantico.transcription.BatchTranscriptionResult
import com.example.transcritorsemantico.whisper.AudioDecodeUtil
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.SamplerConfig
import com.whispercpp.whisper.WhisperSegment
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@OptIn(ExperimentalApi::class)
class GemmaAudioLiteRtLmTranscriber(
    private val context: Context,
    private val modelManager: LiteRtModelManager = LiteRtModelManager(context),
) : BatchTranscriber {
    override val engineId: String = "litert_lm_gemma_audio"

    private val mutex = Mutex()
    private var loadedModelPath: String? = null
    private var engine: Engine? = null

    override suspend fun transcribeFile(
        filePath: String,
        language: String,
        onProgress: suspend (String) -> Unit,
    ): BatchTranscriptionResult = mutex.withLock {
        val modelFile = modelManager.preferredTranscriptionModel()
            ?.takeIf { it.extension.equals("litertlm", ignoreCase = true) }
            ?: error(
                "Importe um modelo .litertlm multimodal, como Gemma-4-E2B-it.litertlm ou " +
                    "Gemma-3n-E2B-it-int4.litertlm, para usar o Audio Scribe do LiteRT-LM."
            )

        onProgress("Inicializando LiteRT-LM Gemma Audio (${modelFile.name})...")
        val activeEngine = ensureEngine(modelFile.absolutePath)

        val totalDurationMs = withContext(Dispatchers.IO) {
            AudioDecodeUtil.durationMs(filePath)
        }.takeIf { it > 0L } ?: Long.MAX_VALUE

        val segments = mutableListOf<WhisperSegment>()
        var decodedChunkCount = 0
        var speechSeconds = 0f
        var startMs = 0L
        var processedEndMs = 0L

        while (startMs < totalDurationMs) {
            val endMs = minOf(startMs + MAX_AUDIO_CHUNK_MS, totalDurationMs)
            val decoded = withContext(Dispatchers.IO) {
                AudioDecodeUtil.decodeToWhisperChunk(filePath, startMs, endMs)
            }
            if (decoded.samples.isEmpty()) break

            decodedChunkCount += 1
            speechSeconds += decoded.samples.size.toFloat() / AudioDecodeUtil.WHISPER_SAMPLE_RATE
            onProgress("Gemma Audio lote $decodedChunkCount: enviando ate 30s como WAV 16 kHz...")

            val wavBytes = withContext(Dispatchers.Default) {
                WavPcm16.encodeMono16k(decoded.samples)
            }
            val inferenceStartedAt = System.currentTimeMillis()
            val text = runAudioConversation(
                engine = activeEngine,
                wavBytes = wavBytes,
                language = language,
            ).trim()
            val inferenceMs = System.currentTimeMillis() - inferenceStartedAt

            if (text.isNotBlank()) {
                Log.i(
                    LOG_TAG,
                    "Gemma Audio accepted chunk=$decodedChunkCount textLength=${text.length} inferenceMs=$inferenceMs"
                )
                segments += WhisperSegment(
                    startMs = decoded.startMs,
                    endMs = endMs,
                    text = text,
                )
            } else {
                Log.w(LOG_TAG, "Gemma Audio returned blank text chunk=$decodedChunkCount inferenceMs=$inferenceMs")
            }
            onProgress("Gemma Audio concluiu lote $decodedChunkCount em ${inferenceMs}ms.")

            processedEndMs = endMs
            if (totalDurationMs == Long.MAX_VALUE && decoded.samples.size < AudioDecodeUtil.WHISPER_SAMPLE_RATE) {
                break
            }
            startMs = endMs
        }

        if (segments.isEmpty()) {
            error("Gemma Audio nao retornou texto para este arquivo.")
        }

        BatchTranscriptionResult(
            segments = segments,
            speechWindowCount = decodedChunkCount,
            speechSeconds = speechSeconds,
            totalSeconds = ((if (totalDurationMs == Long.MAX_VALUE) processedEndMs else totalDurationMs) / 1000f),
            engineId = engineId,
        )
    }

    private fun ensureEngine(modelPath: String): Engine {
        if (loadedModelPath == modelPath) {
            engine?.let { return it }
        }
        close()
        val config = EngineConfig(
            modelPath = modelPath,
            backend = Backend.GPU(),
            audioBackend = Backend.CPU(),
            maxNumTokens = DEFAULT_MAX_OUTPUT_TOKENS,
            cacheDir = context.getExternalFilesDir(null)?.absolutePath,
        )
        return Engine(config).also { newEngine ->
            newEngine.initialize()
            engine = newEngine
            loadedModelPath = modelPath
            Log.i(LOG_TAG, "LiteRT-LM engine initialized for model=$modelPath")
        }
    }

    private suspend fun runAudioConversation(
        engine: Engine,
        wavBytes: ByteArray,
        language: String,
    ): String {
        val conversation: Conversation = engine.createConversation(
            ConversationConfig(
                samplerConfig = SamplerConfig(
                    topK = 1,
                    topP = 0.0,
                    temperature = 0.0,
                )
            )
        )
        val result = CompletableDeferred<String>()
        val partialText = StringBuilder()
        val prompt = transcriptionPrompt(language)
        conversation.sendMessageAsync(
            Contents.of(
                listOf(
                    Content.AudioBytes(wavBytes),
                    Content.Text(prompt),
                )
            ),
            object : MessageCallback {
                override fun onMessage(message: Message) {
                    partialText.append(message.toString())
                }

                override fun onDone() {
                    if (!result.isCompleted) result.complete(partialText.toString())
                }

                override fun onError(throwable: Throwable) {
                    if (!result.isCompleted) result.completeExceptionally(throwable)
                }
            },
            emptyMap(),
        )
        return try {
            result.await()
        } finally {
            runCatching { conversation.close() }
        }
    }

    private fun transcriptionPrompt(language: String): String {
        val target = when (language.lowercase()) {
            "pt", "pt-br" -> "Portuguese"
            "en" -> "English"
            "es" -> "Spanish"
            else -> "the original spoken language"
        }
        return "Transcribe the audio verbatim in $target. Return only the transcript text, without summary or commentary."
    }

    fun close() {
        engine?.close()
        engine = null
        loadedModelPath = null
    }

    companion object {
        private const val LOG_TAG = "GemmaAudioLiteRtLm"
        private const val MAX_AUDIO_CHUNK_MS = 30_000L
        private const val DEFAULT_MAX_OUTPUT_TOKENS = 1024
    }
}

private object WavPcm16 {
    fun encodeMono16k(samples: FloatArray): ByteArray {
        val pcm = ByteArray(samples.size * 2)
        val pcmBuffer = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach { sample ->
            val shortValue = (sample.coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort()
            pcmBuffer.putShort(shortValue)
        }

        val header = ByteArray(44)
        val dataSize = pcm.size
        val riffSize = dataSize + 36
        val byteRate = AudioDecodeUtil.WHISPER_SAMPLE_RATE * 2
        ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(byteArrayOf('R'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), 'F'.code.toByte()))
            putInt(riffSize)
            put(byteArrayOf('W'.code.toByte(), 'A'.code.toByte(), 'V'.code.toByte(), 'E'.code.toByte()))
            put(byteArrayOf('f'.code.toByte(), 'm'.code.toByte(), 't'.code.toByte(), ' '.code.toByte()))
            putInt(16)
            putShort(1)
            putShort(1)
            putInt(AudioDecodeUtil.WHISPER_SAMPLE_RATE)
            putInt(byteRate)
            putShort(2)
            putShort(16)
            put(byteArrayOf('d'.code.toByte(), 'a'.code.toByte(), 't'.code.toByte(), 'a'.code.toByte()))
            putInt(dataSize)
        }

        return ByteArrayOutputStream(header.size + pcm.size).apply {
            write(header)
            write(pcm)
        }.toByteArray()
    }
}
