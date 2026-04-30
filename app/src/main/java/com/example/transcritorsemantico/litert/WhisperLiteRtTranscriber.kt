package com.example.transcritorsemantico.litert

import android.content.Context
import android.util.Log
import com.example.transcritorsemantico.transcription.BatchTranscriber
import com.example.transcritorsemantico.transcription.BatchTranscriptionResult
import com.example.transcritorsemantico.whisper.AudioDecodeUtil
import com.example.transcritorsemantico.whisper.WhisperVad
import com.google.gson.JsonParser
import com.whispercpp.whisper.WhisperSegment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter

class WhisperLiteRtTranscriber(
    private val context: Context,
) : BatchTranscriber {
    private val modelManager = LiteRtModelManager(context)
    private val mutex = Mutex()
    private var interpreter: Interpreter? = null
    private var interpreterModelFile: File? = null
    private var tokenDecoder: LiteRtTokenDecoder? = null
    private var gemmaAudioTranscriber: GemmaAudioLiteRtLmTranscriber? = null
    private val docWolleMelFilters: LiteRtMelFilters? by lazy {
        loadDocWolleMelFilters(context)
    }

    override val engineId: String = "litert_whisper"

    override suspend fun transcribeFile(
        filePath: String,
        language: String,
        onProgress: suspend (String) -> Unit,
    ): BatchTranscriptionResult = mutex.withLock {
        Log.i(LOG_TAG, "Starting LiteRT transcription for file=$filePath language=$language ${modelManager.describeQuickModel()}")
        if (!modelManager.hasQuickTranscriptionModel()) {
            Log.w(LOG_TAG, "LiteRT requested without installed model. ${modelManager.describeQuickModel()}")
            error(
                "LiteRT ainda não tem modelo de transcrição. " +
                    "Coloque o modelo em ${modelManager.quickTranscriptionModel.parentFile?.absolutePath}."
            )
        }

        val preferredModel = modelManager.preferredTranscriptionModel()
        if (preferredModel?.extension.equals("litertlm", ignoreCase = true)) {
            Log.i(LOG_TAG, "Delegating LiteRT transcription to LiteRT-LM audio model=${preferredModel?.name}")
            return (gemmaAudioTranscriber ?: GemmaAudioLiteRtLmTranscriber(context, modelManager).also {
                gemmaAudioTranscriber = it
            }).transcribeFile(filePath, language, onProgress)
        }

        val model = ensureInterpreter(onProgress)
        val decoder = ensureTokenDecoder(context)
        val modelName = interpreterModelFile?.name ?: LiteRtModelManager.QUICK_WHISPER_MODEL_NAME
        onProgress(
            "LiteRT carregou $modelName. " +
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
            Log.d(
                LOG_TAG,
                "LiteRT decoded chunk=$decodedChunkCount startMs=$startMs endMs=$endMs sampleCount=${decoded.samples.size}"
            )

            decodedChunkCount += 1
            val windows = withContext(Dispatchers.Default) {
                WhisperVad.detectSpeechWindows(decoded.samples)
            }
            Log.d(LOG_TAG, "LiteRT VAD windows=${windows.size} for chunk=$decodedChunkCount")
            speechWindowCount += windows.size
            speechSeconds += windows.sumOf { it.lengthSamples.toLong() }.toFloat() / AudioDecodeUtil.WHISPER_SAMPLE_RATE

            windows.forEachIndexed { index, window ->
                val chunkDurationSeconds = window.lengthSamples / AudioDecodeUtil.WHISPER_SAMPLE_RATE.toFloat()
                onProgress(
                    "LiteRT lote $decodedChunkCount: transcrevendo fala ${index + 1}/${windows.size} " +
                        "(${String.format("%.1f", chunkDurationSeconds)}s)..."
                )
                val samples = decoded.samples.copyOfRange(window.startSample, window.endSample)
                val melStartedAt = System.currentTimeMillis()
                val inputFeatureValues = withContext(Dispatchers.Default) {
                    WhisperLogMel.compute(samples, filters = docWolleMelFilters, frames = 3000)
                }
                val melElapsedMs = System.currentTimeMillis() - melStartedAt
                val inferenceStartedAt = System.currentTimeMillis()
                val inference = withContext(Dispatchers.Default) {
                    runWhisperSignature(model, inputFeatureValues)
                }
                val inferenceElapsedMs = System.currentTimeMillis() - inferenceStartedAt
                val text = decoder.decode(inference.signature, listOf(inference.output)).trim()
                if (text.isNotBlank()) {
                    Log.d(
                        LOG_TAG,
                        "LiteRT segment accepted length=${text.length} chunk=$decodedChunkCount " +
                            "window=${index + 1} melMs=$melElapsedMs inferenceMs=$inferenceElapsedMs"
                    )
                    val windowStartMs = ((window.startSample * 1000L) / AudioDecodeUtil.WHISPER_SAMPLE_RATE)
                    segments += WhisperSegment(
                        startMs = decoded.startMs + windowStartMs,
                        endMs = decoded.startMs + windowStartMs + ((samples.size * 1000L) / AudioDecodeUtil.WHISPER_SAMPLE_RATE),
                        text = text,
                    )
                } else {
                    Log.w(
                        LOG_TAG,
                        "LiteRT returned blank text for chunk=$decodedChunkCount window=${index + 1} " +
                            "melMs=$melElapsedMs inferenceMs=$inferenceElapsedMs"
                    )
                }
                onProgress("LiteRT executou ${inference.signature} com input_features 1x80x3000.")
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
        Log.i(
            LOG_TAG,
            "LiteRT transcription finished for file=$filePath segments=${segments.size} windows=$speechWindowCount speechSeconds=$speechSeconds"
        )
        return BatchTranscriptionResult(
            segments = segments,
            speechWindowCount = speechWindowCount,
            speechSeconds = speechSeconds,
            totalSeconds = ((if (totalDurationMs == Long.MAX_VALUE) processedEndMs else totalDurationMs) / 1000f),
            engineId = engineId,
        )
    }

    private suspend fun ensureInterpreter(onProgress: suspend (String) -> Unit): Interpreter {
        interpreter?.let { return it }

        onProgress("Inicializando LiteRT Interpreter para modelo DocWolle...")
        val failures = mutableListOf<String>()
        modelManager.transcriptionModelCandidates()
            .filter { it.extension.equals("tflite", ignoreCase = true) }
            .forEach { modelFile ->
                val loaded = runCatching {
                    onProgress("Carregando ${modelFile.name} com Interpreter.runSignature...")
                    Interpreter(modelFile, Interpreter.Options().apply {
                        setNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(2, 6))
                    })
                }.onFailure { error ->
                    val message = error.message ?: error::class.java.simpleName
                    failures += "${modelFile.name}: $message"
                    Log.w(LOG_TAG, "LiteRT Interpreter load failed for model=${modelFile.name}", error)
                }.getOrNull()
                if (loaded != null) {
                    interpreter = loaded
                    interpreterModelFile = modelFile
                    Log.i(
                        LOG_TAG,
                        "LiteRT Interpreter loaded model=${modelFile.name} signatures=${loaded.getSignatureKeys().joinToString()}"
                    )
                    return loaded
                }
            }
        error("LiteRT nao conseguiu carregar nenhum modelo DocWolle .tflite. ${failures.joinToString(" | ")}")
    }

    private fun runWhisperSignature(
        model: Interpreter,
        inputFeatureValues: FloatArray,
    ): LiteRtInferenceResult {
        val failures = mutableListOf<String>()
        val availableSignatures = runCatching { model.getSignatureKeys().toSet() }.getOrDefault(emptySet())
        Log.d(LOG_TAG, "DocWolle signatures available=${availableSignatures.joinToString()}")
        val signaturesToTry = when {
            SIGNATURE_TRANSCRIBE in availableSignatures -> listOf(SIGNATURE_TRANSCRIBE)
            SIGNATURE_DEFAULT in availableSignatures -> listOf(SIGNATURE_DEFAULT)
            else -> WHISPER_SIGNATURES
        }
        for (signature in signaturesToTry) {
            if (signature !in availableSignatures) {
                failures += "$signature: assinatura ausente em ${availableSignatures.joinToString()}"
                continue
            }
            val result = runCatching {
                val output = createSignatureOutput(model, signature)
                model.runSignature(
                    mapOf(INPUT_FEATURES to inputFeatureTensor(inputFeatureValues)),
                    mapOf(OUTPUT_SEQUENCES to output),
                    signature,
                )
                Log.i(LOG_TAG, "LiteRT successfully ran signature=$signature")
                LiteRtInferenceResult(signature = signature, output = output)
            }.onFailure { error ->
                val message = error.message ?: error::class.java.simpleName
                failures += "$signature: $message"
                Log.w(LOG_TAG, "LiteRT signature failed: $signature", error)
                if (signature == SIGNATURE_TRANSCRIBE && isFatalWhisperGenerateError(message)) {
                    error(
                        "Modelo DocWolle falhou dentro de serving_transcribe ($message). " +
                            "Nao tentei serving_default/translate porque isso costuma repetir o WHILE do generate " +
                            "e travar ate o timeout."
                    )
                }
            }.getOrNull()

            if (result != null) return result
        }

        error(
            "Modelo DocWolle LiteRT carregado, mas nenhuma assinatura de transcricao funcionou. " +
                failures.joinToString(" | ")
        )
    }

    private fun inputFeatureTensor(values: FloatArray): Array<Array<FloatArray>> {
        require(values.size == 80 * 3000) {
            "input_features precisa ter 80x3000 floats, mas veio ${values.size}"
        }
        var offset = 0
        return Array(1) {
            Array(80) {
                FloatArray(3000) { values[offset++] }
            }
        }
    }

    private fun isFatalWhisperGenerateError(message: String): Boolean {
        return message.contains("gather index out of bounds", ignoreCase = true) ||
            message.contains("WHILE", ignoreCase = true)
    }

    private fun createSignatureOutput(model: Interpreter, signature: String): Any {
        val tensor = model.getOutputTensorFromSignature(OUTPUT_SEQUENCES, signature)
        val shape = tensor.shape().map { if (it <= 0) MAX_OUTPUT_TOKENS else it }
        val batch = shape.getOrNull(0)?.coerceAtLeast(1) ?: 1
        val tokens = shape.getOrNull(1)?.coerceAtLeast(1) ?: MAX_OUTPUT_TOKENS
        Log.d(
            LOG_TAG,
            "DocWolle output tensor signature=$signature name=${tensor.name()} type=${tensor.dataType()} " +
                "shape=${tensor.shape().joinToString()} shapeSignature=${tensor.shapeSignature().joinToString()}"
        )
        return when (tensor.dataType()) {
            DataType.INT64 -> Array(batch) { LongArray(tokens) }
            DataType.INT32 -> Array(batch) { IntArray(tokens) }
            DataType.FLOAT32 -> Array(batch) { FloatArray(tokens) }
            else -> ByteBuffer.allocateDirect(tensor.numBytes().coerceAtLeast(batch * tokens * Int.SIZE_BYTES))
                .order(ByteOrder.nativeOrder())
        }
    }

    private fun ensureTokenDecoder(context: Context): LiteRtTokenDecoder {
        tokenDecoder?.let { return it }
        return LiteRtTokenDecoder(
            context = context,
            filesDir = modelManager.quickTranscriptionModel.parentFile ?: context.filesDir,
        ).also { tokenDecoder = it }
    }

    fun close() {
        interpreter?.close()
        interpreter = null
        interpreterModelFile = null
        gemmaAudioTranscriber?.close()
        gemmaAudioTranscriber = null
    }

    companion object {
        private const val LOG_TAG = "WhisperLiteRt"
        private const val SIGNATURE_TRANSCRIBE = "serving_transcribe"
        private const val SIGNATURE_DEFAULT = "serving_default"
        private const val SIGNATURE_TRANSLATE = "serving_translate"
        private const val INPUT_FEATURES = "input_features"
        private const val OUTPUT_SEQUENCES = "sequences"
        private const val DECODE_CHUNK_MS = 30_000L
        private const val MAX_OUTPUT_TOKENS = 512
        private val WHISPER_SIGNATURES = listOf(
            SIGNATURE_TRANSCRIBE,
            SIGNATURE_DEFAULT,
            SIGNATURE_TRANSLATE,
        )
    }
}

private data class LiteRtInferenceResult(
    val signature: String,
    val output: Any,
)

private data class LiteRtMelFilters(
    val nMel: Int,
    val nFft: Int,
    val rows: Array<FloatArray>,
)

private fun loadDocWolleMelFilters(context: Context): LiteRtMelFilters? {
    return runCatching {
        val bytes = context.assets.open("models/filters_vocab_multilingual.bin").use { it.readBytes() }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val magic = buffer.int
        if (magic != 0x5553454e) return@runCatching null
        val nMel = buffer.int
        val nFft = buffer.int
        require(nMel > 0 && nFft > 0)
        val rows = Array(nMel) {
            FloatArray(nFft) {
                buffer.float
            }
        }
        Log.i("WhisperLiteRt", "Loaded DocWolle mel filters nMel=$nMel nFft=$nFft")
        LiteRtMelFilters(nMel = nMel, nFft = nFft, rows = rows)
    }.onFailure { error ->
        Log.w("WhisperLiteRt", "Failed to load DocWolle mel filters, falling back to generated filters", error)
    }.getOrNull()
}

private object WhisperLogMel {
    private const val SAMPLE_RATE = AudioDecodeUtil.WHISPER_SAMPLE_RATE
    private const val FFT_SIZE = 400
    private const val HOP_LENGTH = 160
    private const val MEL_MIN_HZ = 0f
    private const val MEL_MAX_HZ = 8000f

    fun compute(samples: FloatArray, filters: LiteRtMelFilters?, frames: Int): FloatArray {
        val padded = FloatArray(30 * SAMPLE_RATE) { index -> samples.getOrElse(index) { 0f } }
        val window = FloatArray(FFT_SIZE) { index ->
            (0.5 - 0.5 * cos((2.0 * PI * index) / FFT_SIZE)).toFloat()
        }
        val filterRows = filters?.rows ?: melFilters(80)
        val melBins = filterRows.size
        val raw = FloatArray(melBins * frames)
        val power = FloatArray((FFT_SIZE / 2) + 1)
        val fftIn = FloatArray(FFT_SIZE)
        val fftOut = FloatArray(FFT_SIZE * 2)

        for (frame in 0 until frames) {
            val offset = frame * HOP_LENGTH
            powerSpectrum(padded, offset, window, fftIn, fftOut, power)
            for (mel in 0 until melBins) {
                var energy = 0f
                val filter = filterRows[mel]
                val bins = minOf(power.size, filter.size)
                for (bin in 0 until bins) {
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

    private fun powerSpectrum(
        samples: FloatArray,
        offset: Int,
        window: FloatArray,
        fftIn: FloatArray,
        fftOut: FloatArray,
        out: FloatArray,
    ) {
        for (i in 0 until FFT_SIZE) {
            // Evita a alocação da lambda do getOrElse, tornando o loop mais limpo
            val sample = if (offset + i < samples.size) samples[offset + i] else 0f
            fftIn[i] = sample * window[i]
        }

        // Executa a nova FFT in-place com ZERO alocações
        fft(fftIn, 0, 1, fftOut, 0, FFT_SIZE)

        for (bin in out.indices) {
            val real = fftOut[2 * bin]
            val imag = fftOut[(2 * bin) + 1]
            out[bin] = (real * real) + (imag * imag)
        }
        for (bin in 1 until FFT_SIZE / 2) {
            out[bin] *= 2f
        }
    }

    private fun fft(
        input: FloatArray, inOffset: Int, step: Int,
        output: FloatArray, outOffset: Int, size: Int
    ) {
        if (size == 1) {
            output[outOffset] = input[inOffset]
            output[outOffset + 1] = 0f
            return
        }
        if (size % 2 == 1) {
            dft(input, inOffset, step, output, outOffset, size)
            return
        }

        val half = size / 2

        // As metades par/ímpar compartilham a matriz final para evitar arrays intermediários
        fft(input, inOffset, step * 2, output, outOffset, half)
        fft(input, inOffset + step, step * 2, output, outOffset + size, half)

        for (k in 0 until half) {
            val theta = (2.0 * PI * k / size).toFloat()
            val twiddleReal = cos(theta).toFloat()
            val twiddleImag = -kotlin.math.sin(theta).toFloat()

            val evenReal = output[outOffset + 2 * k]
            val evenImag = output[outOffset + 2 * k + 1]

            val oddReal = output[outOffset + size + 2 * k]
            val oddImag = output[outOffset + size + 2 * k + 1]

            val rotatedReal = twiddleReal * oddReal - twiddleImag * oddImag
            val rotatedImag = twiddleReal * oddImag + twiddleImag * oddReal

            output[outOffset + 2 * k] = evenReal + rotatedReal
            output[outOffset + 2 * k + 1] = evenImag + rotatedImag

            output[outOffset + size + 2 * k] = evenReal - rotatedReal
            output[outOffset + size + 2 * k + 1] = evenImag - rotatedImag
        }
    }

    private fun dft(
        input: FloatArray, inOffset: Int, step: Int,
        output: FloatArray, outOffset: Int, size: Int
    ) {
        for (k in 0 until size) {
            var real = 0f
            var imag = 0f
            for (n in 0 until size) {
                val angle = (2.0 * PI * k * n / size).toFloat()
                val v = input[inOffset + n * step]
                real += v * cos(angle).toFloat()
                imag -= v * kotlin.math.sin(angle).toFloat()
            }
            output[outOffset + 2 * k] = real
            output[outOffset + 2 * k + 1] = imag
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
    context: Context,
    filesDir: File,
) {
    private val idToToken: Map<Int, String> = loadVocabulary(context, filesDir)
    private val byteDecoder: Map<Char, Int> = whisperByteDecoder()

    fun decode(signature: String, outputBuffers: List<Any>): String {
        outputBuffers.forEach { output ->
            readUtf8(output)?.let { return it }
            readIntTokens(output)?.let { return it }
            readLongTokens(output)?.let { return it }
            readFloatTokens(output)?.let { return it }
        }
        error(
            "LiteRT executou $signature, mas a saida nao e texto nem sequencia de tokens Whisper decodificavel. " +
                "Este modelo provavelmente retorna logits/estados intermediarios e precisa de loop autoregressivo " +
                "decoder, que ainda nao esta implementado neste caminho."
        )
    }

    private fun readUtf8(output: Any): String? {
        val bytes = when (output) {
            is ByteArray -> output
            is ByteBuffer -> {
                val duplicate = output.asReadOnlyBuffer()
                duplicate.rewind()
                ByteArray(duplicate.remaining()).also(duplicate::get)
            }
            else -> return null
        }
        val text = bytes.takeWhile { it != 0.toByte() }.toByteArray().toString(Charsets.UTF_8).trim()
        return text.takeIf { it.isNotBlank() && it.any(Char::isLetterOrDigit) }
    }

    private fun readIntTokens(output: Any): String? {
        val values = when (output) {
            is IntArray -> output
            is Array<*> -> output.filterIsInstance<IntArray>().flatMap { it.asIterable() }.toIntArray()
            else -> return null
        }
        return decodeIds(values.asIterable())
    }

    private fun readLongTokens(output: Any): String? {
        val values = when (output) {
            is LongArray -> output
            is Array<*> -> output.filterIsInstance<LongArray>().flatMap { it.asIterable() }.toLongArray()
            else -> return null
        }
        return decodeIds(values.map { it.toInt() })
    }

    private fun readFloatTokens(output: Any): String? {
        val values = when (output) {
            is FloatArray -> output
            is Array<*> -> output.filterIsInstance<FloatArray>().flatMap { it.asIterable() }.toFloatArray()
            else -> return null
        }
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
            .takeWhile { it !in WHISPER_EOT_TOKENS }
            .filter { it in 0 until WHISPER_ENGLISH_EOT }
            .mapNotNull { idToToken[it] }
            .filterNot(::isSpecialToken)
            .joinToString("")
        val text = decodeWhisperTokenText(tokenText).trim()
        return text.takeIf { it.isNotBlank() }
    }

    private fun loadVocabulary(context: Context, filesDir: File): Map<Int, String> {
        val vocabFiles = listOf(
            "models/filters_vocab_multilingual.bin",
            "models/filters_vocab_en.bin"
        )
        for (vocabPath in vocabFiles) {
            parseFiltersVocabularyBin(context, vocabPath)?.let { vocabulary ->
                Log.i(DECODER_LOG_TAG, "Loaded DocWolle/whisper.tflite vocabulary path=$vocabPath size=${vocabulary.size}")
                return vocabulary
            }
        }

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

    private fun parseFiltersVocabularyBin(context: Context, vocabPath: String): Map<Int, String>? {
        return runCatching {
            val bytes = context.assets.open(vocabPath).use { it.readBytes() }
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val magic = buffer.int
            if (magic != FILTERS_VOCAB_MAGIC) {
                Log.w(DECODER_LOG_TAG, "Ignoring vocab path=$vocabPath with unexpected magic=${magic.toString(16)}")
                return@runCatching null
            }

            val nMel = buffer.int
            val nFft = buffer.int
            val filterBytes = nMel * nFft * Float.SIZE_BYTES
            require(nMel > 0 && nFft > 0 && buffer.remaining() > filterBytes) {
                "Invalid filters header nMel=$nMel nFft=$nFft remaining=${buffer.remaining()}"
            }
            buffer.position(buffer.position() + filterBytes)

            val baseVocabularySize = buffer.int
            require(baseVocabularySize in 1..WHISPER_MULTILINGUAL_VOCAB_SIZE) {
                "Invalid vocabulary size=$baseVocabularySize"
            }
            val vocabulary = LinkedHashMap<Int, String>(WHISPER_MULTILINGUAL_VOCAB_SIZE)

            // NOVO: Mapeamento reverso para preservar bytes crus em representação BPE
            val byteToChar = whisperByteDecoder().entries.associate { it.value to it.key }

            repeat(baseVocabularySize) { id ->
                val length = buffer.int
                require(length >= 0 && length <= buffer.remaining()) {
                    "Invalid token length=$length id=$id remaining=${buffer.remaining()}"
                }
                val tokenBytes = ByteArray(length)
                buffer.get(tokenBytes)

                // CORREÇÃO: Ao invés de usar tokenBytes.toString(Charsets.UTF_8),
                // usamos a ponte GPT-2 BPE pra não quebrar a decodificação:
                val sb = java.lang.StringBuilder(length)
                for (b in tokenBytes) {
                    val unsignedByte = b.toInt() and 0xFF
                    sb.append(byteToChar[unsignedByte] ?: unsignedByte.toChar())
                }
                vocabulary[id] = sb.toString()
            }

            val multilingual = vocabPath.contains("multilingual", ignoreCase = true)
            val targetVocabularySize = if (multilingual) {
                WHISPER_MULTILINGUAL_VOCAB_SIZE
            } else {
                WHISPER_ENGLISH_VOCAB_SIZE
            }
            var tokenEot = WHISPER_ENGLISH_EOT
            var tokenSot = WHISPER_ENGLISH_SOT
            var tokenPrev = WHISPER_ENGLISH_PREV
            var tokenNot = WHISPER_ENGLISH_NO_TIMESTAMPS
            var tokenBeg = WHISPER_ENGLISH_TIMESTAMP_BEGIN
            if (multilingual) {
                tokenEot += 1
                tokenSot += 1
                tokenPrev += 1
                tokenNot += 1
                tokenBeg += 1
            }

            for (id in baseVocabularySize until targetVocabularySize) {
                vocabulary[id] = when {
                    id > tokenBeg -> "[_TT_${id - tokenBeg}]"
                    id == tokenEot -> "[_EOT_]"
                    id == tokenSot -> "[_SOT_]"
                    id == tokenPrev -> "[_PREV_]"
                    id == tokenNot -> "[_NOT_]"
                    id == tokenBeg -> "[_BEG_]"
                    else -> "[_extra_token_$id]"
                }
            }
            vocabulary
        }.onFailure { error ->
            Log.w(DECODER_LOG_TAG, "Failed to load DocWolle/whisper.tflite vocabulary path=$vocabPath", error)
        }.getOrNull()
    }

    private fun isSpecialToken(token: String): Boolean {
        return (token.startsWith("<|") && token.endsWith("|>")) ||
            (token.startsWith("[_") && token.endsWith("]"))
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

    companion object {
        private const val DECODER_LOG_TAG = "WhisperLiteRt"
        private const val FILTERS_VOCAB_MAGIC = 0x5553454e
        private const val WHISPER_ENGLISH_EOT = 50256
        private const val WHISPER_ENGLISH_SOT = 50257
        private const val WHISPER_ENGLISH_PREV = 50360
        private const val WHISPER_ENGLISH_NO_TIMESTAMPS = 50362
        private const val WHISPER_ENGLISH_TIMESTAMP_BEGIN = 50363
        private const val WHISPER_ENGLISH_VOCAB_SIZE = 51864
        private const val WHISPER_MULTILINGUAL_VOCAB_SIZE = 51865
        private val WHISPER_EOT_TOKENS = setOf(50256, 50257)
    }
}
