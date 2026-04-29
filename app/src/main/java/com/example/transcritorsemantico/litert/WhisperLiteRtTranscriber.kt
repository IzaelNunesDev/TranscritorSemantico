package com.example.transcritorsemantico.litert

import android.content.Context
import com.example.transcritorsemantico.transcription.BatchTranscriber
import com.example.transcritorsemantico.transcription.BatchTranscriptionResult
import com.example.transcritorsemantico.whisper.AudioDecodeUtil
import com.example.transcritorsemantico.whisper.WhisperVad
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.TensorBuffer
import com.google.ai.edge.litert.Environment
import com.google.gson.JsonParser
import com.whispercpp.whisper.WhisperSegment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

class WhisperLiteRtTranscriber(
    private val context: Context,
) : BatchTranscriber {
    private val modelManager = LiteRtModelManager(context)
    private val mutex = Mutex()
    private var environment: Environment? = null
    private var compiledModel: CompiledModel? = null
    private var tokenDecoder: LiteRtTokenDecoder? = null

    override val engineId: String = "litert_whisper"

    override suspend fun transcribeFile(
        filePath: String,
        language: String,
        onProgress: suspend (String) -> Unit,
    ): BatchTranscriptionResult = mutex.withLock {
        if (!modelManager.hasQuickTranscriptionModel()) {
            error(
                "LiteRT ainda não tem ${LiteRtModelManager.QUICK_WHISPER_MODEL_NAME}. " +
                    "Coloque o modelo em ${modelManager.quickTranscriptionModel.parentFile?.absolutePath}."
            )
        }

        val model = ensureCompiledModel(onProgress)
        val decoder = ensureTokenDecoder()
        onProgress(
            "LiteRT compilou ${LiteRtModelManager.QUICK_WHISPER_MODEL_NAME}. " +
                "Decodificando mídia em lotes de 30s..."
        )

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
            val windows = withContext(Dispatchers.Default) {
                WhisperVad.detectSpeechWindows(decoded.samples)
            }
            speechWindowCount += windows.size
            speechSeconds += windows.sumOf { it.lengthSamples.toLong() }.toFloat() / AudioDecodeUtil.WHISPER_SAMPLE_RATE

            windows.forEachIndexed { index, window ->
                val chunkDurationSeconds = window.lengthSamples / AudioDecodeUtil.WHISPER_SAMPLE_RATE.toFloat()
                onProgress(
                    "LiteRT lote $decodedChunkCount: transcrevendo fala ${index + 1}/${windows.size} " +
                        "(${String.format("%.1f", chunkDurationSeconds)}s)..."
                )
                val samples = decoded.samples.copyOfRange(window.startSample, window.endSample)
                val inputFeatures = model.createInputBuffer(SIGNATURE_TRANSCRIBE, INPUT_FEATURES)
                val sequences = model.createOutputBuffer(SIGNATURE_TRANSCRIBE, OUTPUT_SEQUENCES)
                withContext(Dispatchers.Default) {
                    inputFeatures.writeFloat(WhisperLogMel.compute(samples, melBins = 80, frames = 3000))
                }
                model.run(
                    mapOf(INPUT_FEATURES to inputFeatures),
                    mapOf(OUTPUT_SEQUENCES to sequences),
                    SIGNATURE_TRANSCRIBE,
                )
                val text = decoder.decode(listOf(sequences)).trim()
                if (text.isNotBlank()) {
                    val windowStartMs = ((window.startSample * 1000L) / AudioDecodeUtil.WHISPER_SAMPLE_RATE)
                    segments += WhisperSegment(
                        startMs = decoded.startMs + windowStartMs,
                        endMs = decoded.startMs + windowStartMs + ((samples.size * 1000L) / AudioDecodeUtil.WHISPER_SAMPLE_RATE),
                        text = text,
                    )
                }
                onProgress("LiteRT executou serving_transcribe com input_features 1x80x3000.")
            }

            processedEndMs = endMs
            if (totalDurationMs == Long.MAX_VALUE && decoded.samples.size < AudioDecodeUtil.WHISPER_SAMPLE_RATE) {
                break
            }
            startMs = endMs
        }

        if (segments.isEmpty()) {
            error("Nenhuma fala audível foi detectada ou decodificada pelo LiteRT.")
        }

        onProgress("Transcrição LiteRT concluída com ${segments.size} segmentos.")
        return BatchTranscriptionResult(
            segments = segments,
            speechWindowCount = speechWindowCount,
            speechSeconds = speechSeconds,
            totalSeconds = ((if (totalDurationMs == Long.MAX_VALUE) processedEndMs else totalDurationMs) / 1000f),
            engineId = engineId,
        )
    }

    private suspend fun ensureCompiledModel(onProgress: suspend (String) -> Unit): CompiledModel {
        compiledModel?.let { return it }

        onProgress("Inicializando LiteRT e escolhendo acelerador NPU > GPU > CPU...")
        val env = environment ?: Environment.create().also { environment = it }
        val options = CompiledModel.Options(Accelerator.NPU, Accelerator.GPU, Accelerator.CPU)
        return CompiledModel.create(modelManager.quickTranscriptionModel.absolutePath, options, env)
            .also { compiledModel = it }
    }

    private fun ensureTokenDecoder(): LiteRtTokenDecoder {
        tokenDecoder?.let { return it }
        return LiteRtTokenDecoder(
            filesDir = modelManager.quickTranscriptionModel.parentFile ?: context.filesDir,
        ).also { tokenDecoder = it }
    }

    fun close() {
        compiledModel?.close()
        compiledModel = null
        environment?.close()
        environment = null
    }

    companion object {
        private const val SIGNATURE_TRANSCRIBE = "serving_transcribe"
        private const val INPUT_FEATURES = "input_features"
        private const val OUTPUT_SEQUENCES = "sequences"
        private const val DECODE_CHUNK_MS = 30_000L
    }
}

private object WhisperLogMel {
    private const val SAMPLE_RATE = AudioDecodeUtil.WHISPER_SAMPLE_RATE
    private const val FFT_SIZE = 400
    private const val HOP_LENGTH = 160
    private const val MEL_MIN_HZ = 0f
    private const val MEL_MAX_HZ = 8000f

    fun compute(samples: FloatArray, melBins: Int, frames: Int): FloatArray {
        val padded = FloatArray(30 * SAMPLE_RATE) { index -> samples.getOrElse(index) { 0f } }
        val window = FloatArray(FFT_SIZE) { index ->
            (0.5 - 0.5 * cos((2.0 * PI * index) / FFT_SIZE)).toFloat()
        }
        val filters = melFilters(melBins)
        val raw = FloatArray(melBins * frames)
        val power = FloatArray((FFT_SIZE / 2) + 1)

        for (frame in 0 until frames) {
            val offset = frame * HOP_LENGTH
            powerSpectrum(padded, offset, window, power)
            for (mel in 0 until melBins) {
                var energy = 0f
                val filter = filters[mel]
                for (bin in power.indices) {
                    energy += power[bin] * filter[bin]
                }
                raw[(mel * frames) + frame] = (ln(max(energy, 1e-10f)) / ln(10f))
            }
        }

        val topDbFloor = (raw.maxOrNull() ?: 0f) - 8f
        return FloatArray(raw.size) { index ->
            ((max(raw[index], topDbFloor) + 4f) / 4f)
        }
    }

    private fun powerSpectrum(samples: FloatArray, offset: Int, window: FloatArray, out: FloatArray) {
        for (bin in out.indices) {
            var real = 0.0
            var imag = 0.0
            for (n in 0 until FFT_SIZE) {
                val sample = samples.getOrElse(offset + n) { 0f } * window[n]
                val angle = (2.0 * PI * bin * n) / FFT_SIZE
                real += sample * cos(angle)
                imag -= sample * kotlin.math.sin(angle)
            }
            out[bin] = ((real * real) + (imag * imag)).toFloat()
        }
    }

    private fun melFilters(melBins: Int): Array<FloatArray> {
        val fftBins = (FFT_SIZE / 2) + 1
        val melMin = hzToMel(MEL_MIN_HZ)
        val melMax = hzToMel(MEL_MAX_HZ)
        val melPoints = FloatArray(melBins + 2) { index ->
            melToHz(melMin + (melMax - melMin) * index / (melBins + 1))
        }
        val binPoints = melPoints.map { hz ->
            (((FFT_SIZE + 1) * hz) / SAMPLE_RATE).roundToInt().coerceIn(0, fftBins - 1)
        }

        return Array(melBins) { mel ->
            FloatArray(fftBins) { bin ->
                val left = binPoints[mel]
                val center = binPoints[mel + 1]
                val right = binPoints[mel + 2]
                when {
                    bin in left until center -> (bin - left).toFloat() / max(center - left, 1)
                    bin in center until right -> (right - bin).toFloat() / max(right - center, 1)
                    else -> 0f
                }
            }
        }
    }

    private fun hzToMel(hz: Float): Float = 2595f * (ln(1f + hz / 700f) / ln(10f))

    private fun melToHz(mel: Float): Float = 700f * (10f.pow(mel / 2595f) - 1f)
}

private class LiteRtTokenDecoder(
    filesDir: File,
) {
    private val idToToken: Map<Int, String> = loadVocabulary(filesDir)
    private val byteDecoder: Map<Char, Int> = whisperByteDecoder()

    fun decode(outputBuffers: List<TensorBuffer>): String {
        outputBuffers.forEach { buffer ->
            readUtf8(buffer)?.let { return it }
            readIntTokens(buffer)?.let { return it }
            readLongTokens(buffer)?.let { return it }
            readFloatTokens(buffer)?.let { return it }
        }
        error(
            "LiteRT executou serving_transcribe, mas não consegui decodificar as saídas. " +
                "Se o modelo retorna IDs/logits, coloque whisper-vocab.txt, vocab.txt ou vocab.json junto do modelo."
        )
    }

    private fun readUtf8(buffer: TensorBuffer): String? {
        val bytes = runCatching { buffer.readInt8() }.getOrNull() ?: return null
        val text = bytes.takeWhile { it != 0.toByte() }.toByteArray().toString(Charsets.UTF_8).trim()
        return text.takeIf { it.isNotBlank() && it.any(Char::isLetterOrDigit) }
    }

    private fun readIntTokens(buffer: TensorBuffer): String? {
        val values = runCatching { buffer.readInt() }.getOrNull() ?: return null
        return decodeIds(values.asIterable())
    }

    private fun readLongTokens(buffer: TensorBuffer): String? {
        val values = runCatching { buffer.readLong() }.getOrNull() ?: return null
        return decodeIds(values.map { it.toInt() })
    }

    private fun readFloatTokens(buffer: TensorBuffer): String? {
        val values = runCatching { buffer.readFloat() }.getOrNull() ?: return null
        if (values.isEmpty()) return null
        val directIds = values
            .filter { it.isFinite() }
            .map { it.roundToInt() }
        decodeIds(directIds)?.let { return it }

        val vocabSize = listOf(51865, 51864, 50258, 50257).firstOrNull { values.size % it == 0 } ?: return null
        val ids = values.asList().chunked(vocabSize).map { row ->
            row.indices.maxBy { row[it] }
        }
        return decodeIds(ids)
    }

    private fun decodeIds(ids: Iterable<Int>): String? {
        if (idToToken.isEmpty()) return null
        val tokenText = ids
            .takeWhile { it != 50256 }
            .filter { it >= 0 }
            .mapNotNull { idToToken[it] }
            .filterNot { it.startsWith("<|") && it.endsWith("|>") }
            .joinToString("")
        val text = decodeWhisperTokenText(tokenText).trim()
        return text.takeIf { it.isNotBlank() }
    }

    private fun loadVocabulary(filesDir: File): Map<Int, String> {
        val file = listOf(
            "whisper-vocab.txt",
            "vocab.txt",
            "tokens.txt",
            "whisper-vocab.json",
            "vocab.json",
        ).map { File(filesDir, it) }.firstOrNull { it.exists() && it.length() > 0L } ?: return emptyMap()

        return if (file.extension.equals("json", ignoreCase = true)) {
            parseJsonVocabulary(file.readText())
        } else {
            parseTextVocabulary(file.readLines())
        }
    }

    private fun parseTextVocabulary(lines: List<String>): Map<Int, String> {
        return lines.mapIndexedNotNull { index, line ->
            val trimmed = line.trim()
            if (trimmed.isBlank()) {
                null
            } else {
                val parts = trimmed.split(Regex("\\s+"), limit = 2)
                val explicitId = parts.firstOrNull()?.toIntOrNull()
                if (explicitId != null && parts.size == 2) explicitId to parts[1] else index to trimmed
            }
        }.toMap()
    }

    private fun parseJsonVocabulary(json: String): Map<Int, String> {
        val root = JsonParser.parseString(json)
        if (!root.isJsonObject) return emptyMap()
        return root.asJsonObject.entrySet().mapNotNull { (key, value) ->
            val idFromKey = key.toIntOrNull()
            if (idFromKey != null) {
                when {
                    value.isJsonPrimitive && value.asJsonPrimitive.isString -> idFromKey to value.asString
                    else -> null
                }
            } else if (value.isJsonPrimitive && value.asJsonPrimitive.isNumber) {
                value.asInt to key
            } else {
                null
            }
        }.toMap()
    }

    private fun decodeWhisperTokenText(tokenText: String): String {
        val bytes = ByteArrayOutputStream(tokenText.length)
        tokenText.forEach { char ->
            val byte = byteDecoder[char]
            if (byte != null) {
                bytes.write(byte)
            } else {
                bytes.write(char.toString().toByteArray(Charsets.UTF_8))
            }
        }
        return bytes.toByteArray().toString(Charsets.UTF_8)
    }

    private fun whisperByteDecoder(): Map<Char, Int> {
        val bytes = mutableListOf<Int>()
        bytes += 33..126
        bytes += 161..172
        bytes += 174..255

        val chars = bytes.toMutableList()
        var extra = 0
        for (byte in 0..255) {
            if (byte !in bytes) {
                bytes += byte
                chars += 256 + extra
                extra += 1
            }
        }
        return chars.map { it.toChar() }.zip(bytes).toMap()
    }
}
