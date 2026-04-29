package com.example.transcritorsemantico.whisper

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.sqrt

data class WhisperSpeechWindow(
    val startSample: Int,
    val endSample: Int,
) {
    val lengthSamples: Int
        get() = endSample - startSample
}

object WhisperVad {
    private const val SAMPLE_RATE = 16_000
    private const val FRAME_MS = 30
    private const val MIN_SPEECH_MS = 240
    private const val MIN_SILENCE_MS = 320
    private const val SPEECH_PAD_MS = 180
    private const val MAX_WINDOW_MS = 30_000
    private const val MERGE_GAP_MS = 220
    private const val MIN_ABS_THRESHOLD = 0.010f

    fun detectSpeechWindows(samples: FloatArray): List<WhisperSpeechWindow> {
        if (samples.isEmpty()) return emptyList()

        val frameSize = (SAMPLE_RATE * FRAME_MS) / 1000
        val minSpeechFrames = ceil(MIN_SPEECH_MS / FRAME_MS.toDouble()).toInt().coerceAtLeast(1)
        val minSilenceFrames = ceil(MIN_SILENCE_MS / FRAME_MS.toDouble()).toInt().coerceAtLeast(1)
        val padFrames = ceil(SPEECH_PAD_MS / FRAME_MS.toDouble()).toInt().coerceAtLeast(0)
        val maxWindowSamples = (SAMPLE_RATE * MAX_WINDOW_MS) / 1000
        val mergeGapSamples = (SAMPLE_RATE * MERGE_GAP_MS) / 1000

        val frameEnergies = buildList {
            var offset = 0
            while (offset < samples.size) {
                val end = minOf(samples.size, offset + frameSize)
                add(frameRms(samples, offset, end))
                offset = end
            }
        }
        if (frameEnergies.isEmpty()) return emptyList()

        val sorted = frameEnergies.sorted()
        val noiseFloor = percentile(sorted, 0.20f)
        val strongSpeech = percentile(sorted, 0.92f)
        val threshold = max(
            MIN_ABS_THRESHOLD,
            max(noiseFloor * 2.4f, noiseFloor + ((strongSpeech - noiseFloor) * 0.20f)),
        )

        val rawWindows = mutableListOf<WhisperSpeechWindow>()
        var speechRun = 0
        var silenceRun = 0
        var activeStartFrame = -1
        var lastSpeechFrame = -1

        frameEnergies.forEachIndexed { frameIndex, energy ->
            val isSpeech = energy >= threshold
            if (isSpeech) {
                speechRun += 1
                silenceRun = 0
                lastSpeechFrame = frameIndex
                if (activeStartFrame == -1 && speechRun >= minSpeechFrames) {
                    activeStartFrame = (frameIndex - speechRun + 1).coerceAtLeast(0)
                }
            } else {
                if (activeStartFrame != -1) {
                    silenceRun += 1
                    if (silenceRun >= minSilenceFrames) {
                        rawWindows += frameRangeToWindow(
                            startFrame = activeStartFrame,
                            endFrame = lastSpeechFrame,
                            frameSize = frameSize,
                            padFrames = padFrames,
                            totalSamples = samples.size,
                        )
                        activeStartFrame = -1
                        speechRun = 0
                        silenceRun = 0
                        lastSpeechFrame = -1
                    }
                } else {
                    speechRun = 0
                }
            }
        }

        if (activeStartFrame != -1 && lastSpeechFrame >= activeStartFrame) {
            rawWindows += frameRangeToWindow(
                startFrame = activeStartFrame,
                endFrame = lastSpeechFrame,
                frameSize = frameSize,
                padFrames = padFrames,
                totalSamples = samples.size,
            )
        }

        val normalized = if (rawWindows.isEmpty()) {
            val peak = frameEnergies.maxOrNull() ?: 0f
            if (peak >= MIN_ABS_THRESHOLD) listOf(WhisperSpeechWindow(0, samples.size)) else emptyList()
        } else {
            rawWindows
        }

        return splitLongWindows(mergeAdjacent(normalized, mergeGapSamples), maxWindowSamples)
    }

    private fun frameRms(samples: FloatArray, start: Int, end: Int): Float {
        if (end <= start) return 0f
        var sum = 0f
        for (index in start until end) {
            val value = samples[index]
            sum += value * value
        }
        return sqrt(sum / (end - start))
    }

    private fun percentile(sortedValues: List<Float>, fraction: Float): Float {
        if (sortedValues.isEmpty()) return 0f
        val index = ((sortedValues.lastIndex) * fraction).toInt().coerceIn(0, sortedValues.lastIndex)
        return sortedValues[index]
    }

    private fun frameRangeToWindow(
        startFrame: Int,
        endFrame: Int,
        frameSize: Int,
        padFrames: Int,
        totalSamples: Int,
    ): WhisperSpeechWindow {
        val start = ((startFrame - padFrames).coerceAtLeast(0) * frameSize).coerceAtLeast(0)
        val endExclusive = (((endFrame + padFrames + 1) * frameSize).coerceAtMost(totalSamples)).coerceAtLeast(start + 1)
        return WhisperSpeechWindow(start, endExclusive)
    }

    private fun mergeAdjacent(
        windows: List<WhisperSpeechWindow>,
        mergeGapSamples: Int,
    ): List<WhisperSpeechWindow> {
        if (windows.isEmpty()) return windows
        val merged = mutableListOf<WhisperSpeechWindow>()
        var current = windows.first()
        for (next in windows.drop(1)) {
            if (next.startSample - current.endSample <= mergeGapSamples) {
                current = WhisperSpeechWindow(current.startSample, max(current.endSample, next.endSample))
            } else {
                merged += current
                current = next
            }
        }
        merged += current
        return merged
    }

    private fun splitLongWindows(
        windows: List<WhisperSpeechWindow>,
        maxWindowSamples: Int,
    ): List<WhisperSpeechWindow> {
        val split = mutableListOf<WhisperSpeechWindow>()
        windows.forEach { window ->
            if (window.lengthSamples <= maxWindowSamples) {
                split += window
            } else {
                var start = window.startSample
                while (start < window.endSample) {
                    val end = minOf(window.endSample, start + maxWindowSamples)
                    split += WhisperSpeechWindow(start, end)
                    start = end
                }
            }
        }
        return split
    }
}
