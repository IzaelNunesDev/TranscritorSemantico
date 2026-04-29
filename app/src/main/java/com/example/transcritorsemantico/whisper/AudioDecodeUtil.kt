package com.example.transcritorsemantico.whisper

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

object AudioDecodeUtil {
    const val WHISPER_SAMPLE_RATE = 16_000

    data class Chunk(
        val startMs: Long,
        val endMs: Long,
        val samples: FloatArray,
    )

    fun decodeToWhisperInput(filePath: String): FloatArray {
        return if (filePath.lowercase().endsWith(".wav")) {
            decodeWav(File(filePath))
        } else {
            decodeMedia(filePath, startMs = 0L, endMs = null)
        }
    }

    fun decodeToWhisperChunks(
        filePath: String,
        chunkMs: Long = 30_000L,
        onChunk: (Chunk) -> Unit,
    ): Long {
        val durationMs = durationMs(filePath)
        if (durationMs <= 0L || filePath.lowercase().endsWith(".wav")) {
            val samples = decodeToWhisperInput(filePath)
            val inferredDurationMs = ((samples.size * 1000L) / WHISPER_SAMPLE_RATE).coerceAtLeast(1L)
            onChunk(Chunk(startMs = 0L, endMs = inferredDurationMs, samples = samples))
            return inferredDurationMs
        }

        var startMs = 0L
        while (startMs < durationMs) {
            val endMs = minOf(startMs + chunkMs, durationMs)
            val samples = decodeMedia(filePath, startMs = startMs, endMs = endMs)
            if (samples.isNotEmpty()) {
                onChunk(Chunk(startMs = startMs, endMs = endMs, samples = samples))
            }
            startMs = endMs
        }
        return durationMs
    }

    fun decodeToWhisperChunk(filePath: String, startMs: Long, endMs: Long): Chunk {
        val samples = if (filePath.lowercase().endsWith(".wav")) {
            val allSamples = decodeWav(File(filePath))
            val startSample = ((startMs * WHISPER_SAMPLE_RATE) / 1000L).toInt().coerceIn(0, allSamples.size)
            val endSample = ((endMs * WHISPER_SAMPLE_RATE) / 1000L).toInt().coerceIn(startSample, allSamples.size)
            allSamples.copyOfRange(startSample, endSample)
        } else {
            decodeMedia(filePath, startMs = startMs, endMs = endMs)
        }
        return Chunk(startMs = startMs, endMs = endMs, samples = samples)
    }

    fun durationMs(filePath: String): Long {
        return runCatching {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(filePath)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            retriever.release()
            duration?.toLongOrNull() ?: 0L
        }.getOrDefault(0L)
    }

    private fun decodeMedia(filePath: String, startMs: Long, endMs: Long?): FloatArray {
        val extractor = MediaExtractor()
        extractor.setDataSource(filePath)

        val trackIndex = (0 until extractor.trackCount).firstOrNull { index ->
            extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: error("Nenhuma trilha de áudio encontrada no arquivo.")

        extractor.selectTrack(trackIndex)
        if (startMs > 0L) {
            extractor.seekTo(startMs * 1000L, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        }
        val trackFormat = extractor.getTrackFormat(trackIndex)
        val mime = trackFormat.getString(MediaFormat.KEY_MIME) ?: error("Formato de áudio inválido.")
        val decoder = MediaCodec.createDecoderByType(mime)
        decoder.configure(trackFormat, null, null, 0)
        decoder.start()

        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        var sampleRate = trackFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = trackFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT
        val endUs = endMs?.times(1000L)
        val out = ArrayList<Float>((sampleRate * channels * 35).coerceAtMost(3_000_000))

        try {
            while (!outputDone) {
                if (!inputDone) {
                    val inputBufferIndex = decoder.dequeueInputBuffer(10_000)
                    if (inputBufferIndex >= 0) {
                        val inputBuffer = decoder.getInputBuffer(inputBufferIndex) ?: error("Buffer de entrada indisponível.")
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        val sampleTimeUs = extractor.sampleTime
                        if (sampleSize < 0 || (endUs != null && sampleTimeUs >= endUs)) {
                            decoder.queueInputBuffer(
                                inputBufferIndex,
                                0,
                                0,
                                0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(
                                inputBufferIndex,
                                0,
                                sampleSize,
                                extractor.sampleTime,
                                0,
                            )
                            extractor.advance()
                        }
                    }
                }

                val outputBufferIndex = decoder.dequeueOutputBuffer(info, 10_000)
                when {
                    outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val format = decoder.outputFormat
                        sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        pcmEncoding = if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                            format.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        } else {
                            AudioFormat.ENCODING_PCM_16BIT
                        }
                    }

                    outputBufferIndex >= 0 -> {
                        val outputBuffer = decoder.getOutputBuffer(outputBufferIndex)
                        if (outputBuffer != null && info.size > 0) {
                            outputBuffer.position(info.offset)
                            outputBuffer.limit(info.offset + info.size)
                            appendBufferAsFloats(outputBuffer, pcmEncoding, channels, out)
                        }
                        decoder.releaseOutputBuffer(outputBufferIndex, false)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            outputDone = true
                        }
                    }
                }
            }
        } finally {
            extractor.release()
            decoder.stop()
            decoder.release()
        }

        val interleaved = out.toFloatArray()
        return resampleTo16kMono(interleaved, sampleRate, channels)
    }

    private fun appendBufferAsFloats(
        buffer: ByteBuffer,
        pcmEncoding: Int,
        channels: Int,
        out: MutableList<Float>,
    ) {
        val ordered = buffer.order(ByteOrder.LITTLE_ENDIAN)
        when (pcmEncoding) {
            AudioFormat.ENCODING_PCM_FLOAT -> {
                while (ordered.remaining() >= 4) {
                    out.add(ordered.float)
                }
            }

            else -> {
                while (ordered.remaining() >= 2) {
                    out.add((ordered.short / 32768f).coerceIn(-1f, 1f))
                }
            }
        }
    }

    private fun resampleTo16kMono(
        interleaved: FloatArray,
        sampleRate: Int,
        channels: Int,
    ): FloatArray {
        if (interleaved.isEmpty()) return interleaved
        val mono = if (channels <= 1) {
            interleaved
        } else {
            val frames = interleaved.size / channels
            FloatArray(frames) { frame ->
                var sum = 0f
                for (channel in 0 until channels) {
                    sum += interleaved[(frame * channels) + channel]
                }
                sum / channels.toFloat()
            }
        }

        if (sampleRate == 16_000) {
            return mono
        }

        val ratio = WHISPER_SAMPLE_RATE.toDouble() / sampleRate.toDouble()
        val outputSize = (mono.size * ratio).roundToInt().coerceAtLeast(1)
        return FloatArray(outputSize) { index ->
            val sourceIndex = index / ratio
            val left = sourceIndex.toInt().coerceIn(0, mono.lastIndex)
            val right = (left + 1).coerceAtMost(mono.lastIndex)
            val frac = (sourceIndex - left).toFloat()
            mono[left] * (1f - frac) + mono[right] * frac
        }
    }

    private fun decodeWav(file: File): FloatArray {
        val bytes = ByteArrayOutputStream().also { baos ->
            file.inputStream().use { input -> input.copyTo(baos) }
        }.toByteArray()
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val channels = buffer.getShort(22).toInt()
        val sampleRate = buffer.getInt(24)
        buffer.position(44)
        val shortBuffer = buffer.asShortBuffer()
        val audio = ShortArray(shortBuffer.limit())
        shortBuffer.get(audio)
        val floats = FloatArray(audio.size) { index -> (audio[index] / 32768f).coerceIn(-1f, 1f) }
        return resampleTo16kMono(floats, sampleRate, channels)
    }
}
