package com.example.transcritorsemantico.transcription

import com.whispercpp.whisper.WhisperSegment

data class BatchTranscriptionResult(
    val segments: List<WhisperSegment>,
    val speechWindowCount: Int,
    val speechSeconds: Float,
    val totalSeconds: Float,
    val engineId: String,
)

interface BatchTranscriber {
    val engineId: String

    suspend fun transcribeFile(
        filePath: String,
        language: String = "auto",
        onProgress: suspend (String) -> Unit,
    ): BatchTranscriptionResult
}
