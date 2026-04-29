package com.example.transcritorsemantico.data

data class TranscriptChunk(
    val id: String,
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val packedEmbedding: String,
)

data class TranscriptVariant(
    val engineId: String,
    val label: String,
    val createdAt: Long,
    val speechWindowCount: Int = 0,
    val speechSeconds: Float = 0f,
    val totalSeconds: Float = 0f,
    val text: String = "",
    val chunks: List<TranscriptChunk> = emptyList(),
)

data class AudioSession(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val durationMs: Long,
    val audioPath: String? = null,
    val sourceType: String = "recording",
    val status: String = "indexed",
    val transcriptionEngine: String = "android_system",
    val note: String? = null,
    val chunks: List<TranscriptChunk> = emptyList(),
    val liteRtTranscript: TranscriptVariant? = null,
    val legacyTurboTranscript: TranscriptVariant? = null,
)

data class SearchHit(
    val sessionId: String,
    val chunkId: String,
    val score: Float,
    val text: String,
    val timestampMs: Long,
)
