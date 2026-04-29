package com.example.transcritorsemantico.semantic

import android.util.Base64
import com.example.transcritorsemantico.data.AudioSession
import com.example.transcritorsemantico.data.SearchHit
import com.example.transcritorsemantico.data.TranscriptChunk
import java.util.Locale
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sqrt
import kotlin.random.Random

private data class Codebook(
    val thresholds: FloatArray,
    val levels: FloatArray,
)

class TurboMseQuantizer(
    private val bits: Int = 4,
    val dimension: Int = 96,
    seed: Int = 17,
) {
    private val sigma = 1f / sqrt(dimension.toFloat())
    private val codebook = buildCodebook(1 shl bits, sigma)
    private val rotation = buildRotationMatrix(dimension, seed)

    fun quantize(vector: FloatArray): String {
        val rotated = rotate(vector)
        val codes = IntArray(dimension)
        for (index in 0 until dimension) {
            codes[index] = locateBin(rotated[index], codebook.thresholds)
        }
        return Base64.encodeToString(packNibbles(codes), Base64.NO_WRAP)
    }

    fun rotate(vector: FloatArray): FloatArray {
        val normalized = normalize(vector)
        val out = FloatArray(dimension)
        for (row in 0 until dimension) {
            var sum = 0f
            val rowVector = rotation[row]
            for (column in 0 until dimension) {
                sum += rowVector[column] * normalized[column]
            }
            out[row] = sum
        }
        return out
    }

    fun scoreRotatedQuery(rotatedQuery: FloatArray, packedBase64: String): Float {
        val packed = Base64.decode(packedBase64, Base64.DEFAULT)
        val codes = unpackNibbles(packed, dimension)
        var score = 0f
        for (index in 0 until dimension) {
            score += codebook.levels[codes[index]] * rotatedQuery[index]
        }
        return score
    }

    private fun buildCodebook(levelCount: Int, sourceSigma: Float, iterations: Int = 120): Codebook {
        val levels = FloatArray(levelCount) { index ->
            val start = -3f * sourceSigma
            val step = (6f * sourceSigma) / max(levelCount - 1, 1)
            start + (index * step)
        }
        val bounds = FloatArray(levelCount + 1)

        repeat(iterations) {
            bounds[0] = Float.NEGATIVE_INFINITY
            bounds[levelCount] = Float.POSITIVE_INFINITY
            for (index in 1 until levelCount) {
                bounds[index] = (levels[index - 1] + levels[index]) / 2f
            }

            var changed = 0f
            for (index in 0 until levelCount) {
                val updated = conditionalMean(bounds[index], bounds[index + 1], sourceSigma)
                changed = max(changed, kotlin.math.abs(updated - levels[index]))
                levels[index] = updated
            }
            if (changed < 1e-6f) {
                return@repeat
            }
        }

        return Codebook(
            thresholds = bounds.copyOfRange(1, levelCount),
            levels = levels,
        )
    }

    private fun gaussianPdf(x: Float, sourceSigma: Float): Float {
        val variance = sourceSigma * sourceSigma
        val coeff = 1f / (sourceSigma * sqrt((2f * PI).toFloat()))
        return coeff * exp(-((x * x) / (2f * variance)))
    }

    private fun gaussianCdf(x: Float, sourceSigma: Float): Float {
        if (x == Float.POSITIVE_INFINITY) return 1f
        if (x == Float.NEGATIVE_INFINITY) return 0f
        val scaled = x / (sourceSigma * sqrt(2f))
        return 0.5f * (1f + erfApprox(scaled))
    }

    private fun conditionalMean(a: Float, b: Float, sourceSigma: Float): Float {
        if (a == Float.NEGATIVE_INFINITY && b == Float.POSITIVE_INFINITY) return 0f
        val pa = gaussianCdf(a, sourceSigma)
        val pb = gaussianCdf(b, sourceSigma)
        val mass = pb - pa
        if (mass < 1e-6f) {
            return when {
                a.isFinite() && b.isFinite() -> (a + b) / 2f
                a == Float.NEGATIVE_INFINITY -> b - sourceSigma
                else -> a + sourceSigma
            }
        }
        val fa = if (a == Float.NEGATIVE_INFINITY) 0f else gaussianPdf(a, sourceSigma)
        val fb = if (b == Float.POSITIVE_INFINITY) 0f else gaussianPdf(b, sourceSigma)
        return ((sourceSigma * sourceSigma) * (fa - fb)) / mass
    }

    private fun erfApprox(value: Float): Float {
        val polarity = sign(value)
        val x = kotlin.math.abs(value)
        val a1 = 0.2548296f
        val a2 = -0.28449672f
        val a3 = 1.4214138f
        val a4 = -1.4531521f
        val a5 = 1.0614054f
        val p = 0.3275911f
        val t = 1f / (1f + p * x)
        val y = 1f - (((((a5 * t + a4) * t) + a3) * t + a2) * t + a1) * t * exp(-x * x)
        return polarity * y
    }

    private fun buildRotationMatrix(size: Int, seed: Int): Array<FloatArray> {
        val rng = Random(seed)
        val matrix = Array(size) {
            FloatArray(size) { rng.nextGaussianFloat() }
        }

        for (row in 0 until size) {
            for (previous in 0 until row) {
                val dot = dot(matrix[row], matrix[previous])
                for (column in 0 until size) {
                    matrix[row][column] -= dot * matrix[previous][column]
                }
            }
            val norm = sqrt(dot(matrix[row], matrix[row])).coerceAtLeast(1e-6f)
            for (column in 0 until size) {
                matrix[row][column] /= norm
            }
        }

        return matrix
    }

    private fun dot(left: FloatArray, right: FloatArray): Float {
        var score = 0f
        for (index in left.indices) {
            score += left[index] * right[index]
        }
        return score
    }

    private fun normalize(values: FloatArray): FloatArray {
        var sum = 0f
        for (value in values) {
            sum += value * value
        }
        if (sum <= 1e-6f) {
            return values.copyOf()
        }
        val scale = 1f / sqrt(sum)
        return FloatArray(values.size) { index -> values[index] * scale }
    }

    private fun locateBin(value: Float, thresholds: FloatArray): Int {
        var low = 0
        var high = thresholds.lastIndex
        var result = thresholds.size
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (value <= thresholds[mid]) {
                result = mid
                high = mid - 1
            } else {
                low = mid + 1
            }
        }
        return result
    }

    private fun packNibbles(codes: IntArray): ByteArray {
        val out = ByteArray((codes.size + 1) / 2)
        var outIndex = 0
        var inputIndex = 0
        while (inputIndex < codes.size) {
            val low = codes[inputIndex] and 0x0F
            val high = if (inputIndex + 1 < codes.size) {
                (codes[inputIndex + 1] and 0x0F) shl 4
            } else {
                0
            }
            out[outIndex] = (low or high).toByte()
            outIndex += 1
            inputIndex += 2
        }
        return out
    }

    private fun unpackNibbles(bytes: ByteArray, targetSize: Int): IntArray {
        val codes = IntArray(targetSize)
        var cursor = 0
        for (byteValue in bytes) {
            if (cursor < targetSize) {
                codes[cursor] = byteValue.toInt() and 0x0F
                cursor += 1
            }
            if (cursor < targetSize) {
                codes[cursor] = (byteValue.toInt() ushr 4) and 0x0F
                cursor += 1
            }
        }
        return codes
    }
}

private fun Random.nextGaussianFloat(): Float {
    val u1 = nextFloat().coerceIn(1e-6f, 0.999999f)
    val u2 = nextFloat().coerceIn(1e-6f, 0.999999f)
    val radius = sqrt(-2f * kotlin.math.ln(u1))
    val theta = (2f * PI.toFloat()) * u2
    return radius * kotlin.math.cos(theta)
}

class LocalSemanticEngine(
    private val quantizer: TurboMseQuantizer = TurboMseQuantizer(),
) {
    fun chunkFromText(text: String, startMs: Long, endMs: Long): TranscriptChunk {
        val embedding = encode(text)
        return TranscriptChunk(
            id = "chunk_${startMs}_${text.hashCode()}",
            startMs = startMs,
            endMs = endMs,
            text = text.trim(),
            packedEmbedding = quantizer.quantize(embedding),
        )
    }

    fun search(query: String, sessions: List<AudioSession>, limit: Int = 12): List<SearchHit> {
        if (query.isBlank()) return emptyList()
        val queryEmbedding = encode(query)
        val rotatedQuery = quantizer.rotate(queryEmbedding)
        val normalizedQuery = normalizeText(query)

        return sessions.asSequence()
            .flatMap { session ->
                session.chunks.asSequence().map { chunk ->
                    val semanticScore = quantizer.scoreRotatedQuery(rotatedQuery, chunk.packedEmbedding)
                    val lexicalScore = lexicalScore(normalizedQuery, normalizeText(chunk.text))
                    val exactBoost = exactBoost(normalizedQuery, normalizeText(chunk.text))
                    SearchHit(
                        sessionId = session.id,
                        chunkId = chunk.id,
                        score = maxOf(exactBoost, (semanticScore * 0.72f) + (lexicalScore * 0.28f)),
                        text = chunk.text,
                        timestampMs = chunk.startMs,
                    )
                }
            }
            .sortedByDescending { it.score }
            .filter { it.score > 0.02f }
            .take(limit)
            .toList()
    }

    fun importChunksFromPlainText(text: String): List<TranscriptChunk> {
        val lines = text.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }

        return lines.mapIndexed { index, line ->
            val startMs = index * 12_000L
            chunkFromText(line, startMs, startMs + 12_000L)
        }
    }

    fun suggestTitle(chunks: List<TranscriptChunk>): String {
        val preview = chunks.firstOrNull()?.text.orEmpty()
        if (preview.isBlank()) return "Sessao sem titulo"
        return preview.split(" ")
            .take(6)
            .joinToString(" ")
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
    }

    private fun encode(text: String): FloatArray {
        val vector = FloatArray(quantizer.dimension)
        val normalized = normalizeText(text)
        if (normalized.isBlank()) return vector

        val tokens = normalized.split(" ").filter { it.isNotBlank() }
        for (token in tokens) {
            val weight = (1f + min(token.length, 10) / 10f)
            injectFeature(vector, token, weight)

            if (token.length >= 3) {
                for (index in 0..token.length - 3) {
                    injectFeature(vector, token.substring(index, index + 3), 0.55f)
                }
            }
        }

        for (index in 0 until max(tokens.size - 1, 0)) {
            injectFeature(vector, tokens[index] + "_" + tokens[index + 1], 0.9f)
        }

        return normalize(vector)
    }

    private fun injectFeature(vector: FloatArray, feature: String, weight: Float) {
        val hashA = stableHash(feature)
        val hashB = stableHash(feature.reversed())
        val indexA = kotlin.math.abs(hashA) % vector.size
        val indexB = kotlin.math.abs(hashB) % vector.size
        vector[indexA] += if (hashA >= 0) weight else -weight
        vector[indexB] += if (hashB >= 0) weight * 0.7f else -weight * 0.7f
    }

    private fun stableHash(value: String): Int {
        var hash = 216613626
        for (char in value) {
            hash = hash xor char.code
            hash *= 16777619
        }
        return hash
    }

    private fun normalize(vector: FloatArray): FloatArray {
        var energy = 0f
        for (value in vector) {
            energy += value * value
        }
        if (energy <= 1e-6f) return vector
        val factor = 1f / sqrt(energy)
        return FloatArray(vector.size) { index -> vector[index] * factor }
    }

    private fun normalizeText(text: String): String {
        return text.lowercase(Locale.getDefault())
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim()
    }

    private fun lexicalScore(query: String, text: String): Float {
        if (query.isBlank() || text.isBlank()) return 0f
        if (text.contains(query)) return 1f
        val queryTokens = query.split(" ").filter { it.isNotBlank() }.toSet()
        val textTokens = text.split(" ").filter { it.isNotBlank() }.toSet()
        if (queryTokens.isEmpty()) return 0f
        val overlap = queryTokens.intersect(textTokens).size.toFloat()
        return overlap / queryTokens.size.toFloat()
    }

    private fun exactBoost(query: String, text: String): Float {
        if (query.isBlank() || text.isBlank()) return 0f
        return when {
            text == query -> 1.2f
            text.contains(query) -> 1.0f
            else -> 0f
        }
    }
}
