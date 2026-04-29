package com.example.transcritorsemantico.audio

import android.content.Context
import android.content.Intent
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class RecorderStartResult(
    val audioPath: String,
    val startedAt: Long,
    val transcriptionActive: Boolean,
)

interface RecorderCallbacks {
    fun onPartialText(text: String)
    fun onFinalText(text: String, timestampMs: Long)
    fun onLevelChanged(level: Float)
    fun onError(message: String)
}

class RecorderEngine(
    private val context: Context,
) {
    private val mainHandler = Handler(Looper.getMainLooper())

    private var mediaRecorder: MediaRecorder? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private var callbacks: RecorderCallbacks? = null
    private var currentLanguageTag: String = Locale.getDefault().toLanguageTag()
    private var startedAt: Long = 0L
    private var isRecording = false
    private var lastFinal = ""

    fun start(
        languageTag: String,
        engine: TranscriptionEngine,
        callbacks: RecorderCallbacks,
    ): RecorderStartResult {
        stop()

        this.callbacks = callbacks
        this.currentLanguageTag = languageTag
        this.startedAt = System.currentTimeMillis()
        this.lastFinal = ""
        this.isRecording = true

        val audioFile = createOutputFile()
        val recorder = MediaRecorder(context).apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioEncodingBitRate(128_000)
            setAudioSamplingRate(44_100)
            setOutputFile(audioFile.absolutePath)
            prepare()
            start()
        }
        mediaRecorder = recorder

        if (!engine.transcriptionEnabled) {
            callbacks.onError("Gravando somente audio. Esta sessao nao sera pesquisavel ate ser transcrita.")
            return RecorderStartResult(
                audioPath = audioFile.absolutePath,
                startedAt = startedAt,
                transcriptionActive = false,
            )
        }

        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            callbacks.onError("Reconhecimento de voz indisponivel neste dispositivo.")
            return RecorderStartResult(
                audioPath = audioFile.absolutePath,
                startedAt = startedAt,
                transcriptionActive = false,
            )
        }

        mainHandler.post {
            val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
            recognizer.setRecognitionListener(listener)
            speechRecognizer = recognizer
            beginListening(engine.preferOffline)
        }

        return RecorderStartResult(
            audioPath = audioFile.absolutePath,
            startedAt = startedAt,
            transcriptionActive = true,
        )
    }

    fun stop() {
        isRecording = false
        mainHandler.removeCallbacksAndMessages(null)

        runCatching {
            speechRecognizer?.stopListening()
        }
        runCatching {
            speechRecognizer?.cancel()
        }
        runCatching {
            speechRecognizer?.destroy()
        }
        speechRecognizer = null

        runCatching {
            mediaRecorder?.stop()
        }
        runCatching {
            mediaRecorder?.reset()
        }
        runCatching {
            mediaRecorder?.release()
        }
        mediaRecorder = null

        callbacks?.onPartialText("")
        callbacks?.onLevelChanged(0f)
        callbacks = null
        lastFinal = ""
    }

    fun currentLevel(): Float {
        val amplitude = runCatching { mediaRecorder?.maxAmplitude ?: 0 }.getOrDefault(0)
        return (amplitude / 32767f).coerceIn(0f, 1f)
    }

    private fun beginListening(preferOffline: Boolean) {
        if (!isRecording) return
        val recognizer = speechRecognizer ?: return
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, currentLanguageTag)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, preferOffline)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 900L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1200L)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        recognizer.startListening(intent)
    }

    private fun restartListening(delayMs: Long = 180L, preferOffline: Boolean = false) {
        if (!isRecording) return
        mainHandler.postDelayed({
            if (isRecording) beginListening(preferOffline)
        }, delayMs)
    }

    private fun createOutputFile(): File {
        val recordingsDir = File(context.filesDir, "memorywave/recordings")
        if (!recordingsDir.exists()) recordingsDir.mkdirs()
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return File(recordingsDir, "session_$stamp.m4a")
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) {
            val normalized = ((rmsdB + 10f) / 20f).coerceIn(0f, 1f)
            callbacks?.onLevelChanged(normalized)
        }
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit

        override fun onError(error: Int) {
            if (!isRecording) return
            if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                callbacks?.onError("Nenhuma fala reconhecida ainda. Continue falando ou troque o motor.")
                restartListening()
                return
            }
            callbacks?.onError("Reconhecimento reiniciado (codigo $error).")
            restartListening(320L)
        }

        override fun onResults(results: Bundle?) {
            val text = results.bestText()
            if (text.isNotBlank() && text != lastFinal) {
                lastFinal = text
                val timestamp = System.currentTimeMillis() - startedAt
                callbacks?.onFinalText(text, timestamp)
            }
            callbacks?.onPartialText("")
            restartListening()
        }

        override fun onPartialResults(partialResults: Bundle?) {
            callbacks?.onPartialText(partialResults.bestText())
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }
}

private fun Bundle?.bestText(): String {
    val list = this?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
    return list?.firstOrNull()?.trim().orEmpty()
}
